(ns oplenario.legislativo.documento-db-test
  "INTEGRACAO (PG real): F3.9b — Expediente / GERACAO DE DOCUMENTOS por modelo (§16.3, feature 3.22). Prova:
  documento_modelo (template configuravel) + documento (gerado por MERGE do dominio no template). State
  machine rascunho -> emitido (emitido congela = artefato). Vinculo opcional ao Protocolo Geral (F3.9a).
  Merge fail-closed: campo nao-preenchido lanca (documento legal nao sai com lacuna)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.documento :as documento]
            [oplenario.legislativo.db.documento-modelo :as modelo]
            [oplenario.legislativo.db.protocolo-geral :as protocolo]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.models.documento :as mod-doc]
            [oplenario.legislativo.models.documento-modelo :as mod-mod]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- criar-modelo! [tx ente extra]
  (modelo/criar! tx (merge {:id (random-uuid) :ente-id ente :chave (str "oficio_" (random-uuid))
                            :nome "Oficio padrao" :tipo-documento "oficio"
                            :corpo-template "Ao {{destinatario}}, ref. {{assunto}}. Atenciosamente, a Camara."}
                           extra)))

(defn- gerar! [tx ente mid extra]
  (documento/gerar! tx (merge {:id (random-uuid) :ente-id ente :modelo-id mid :tipo-documento "oficio"
                               :assunto "Convite" :corpo-template "Ao {{destinatario}}."
                               :dados {"destinatario" "Prefeito"}} extra)))

;; ---------- logica pura: merge ----------

(deftest renderiza-merge-e-falha-em-campo-vazio
  (is (= "Ao Prefeito, ref. Convite."
         (logic/renderizar-documento "Ao {{destinatario}}, ref. {{ assunto }}."
                                     {"destinatario" "Prefeito" "assunto" "Convite"}))
      "substitui placeholders (com/sem espaco)")
  (is (thrown? Exception (logic/renderizar-documento "Ao {{destinatario}}." {}))
      "campo nao-preenchido lanca (fail-closed)"))

;; ---------- modelo: criar + chave unica ----------

(deftest modelo-criar-e-chave-unica
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{mid :id} (criar-modelo! tx ente {:chave "oficio_padrao"})]
          (is (m/validate mod-mod/DocumentoModelo (modelo/buscar tx ente mid)) "modelo bate o model")
          (is (= mid (:id (modelo/buscar-por-chave tx ente "oficio_padrao"))))
          (is (thrown? Exception (criar-modelo! tx ente {:chave "oficio_padrao"}))
              "chave duplicada no ente barra (UNIQUE)"))))))

;; ---------- modelo: atualizar (nome/corpo/ativo) + CAS ----------

(deftest modelo-atualizar-edita-desativa-e-cas
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{mid :id} (criar-modelo! tx ente {})]
          (modelo/atualizar! tx {:id mid :ente-id ente :nome "Nome revisado" :updated-by nil :lock-version 0})
          (let [r (modelo/buscar tx ente mid)]
            (is (= "Nome revisado" (:nome r)) "nome editavel")
            (is (= 1 (:lock-version r)) "CAS incrementa"))
          ;; desativa (soft: sem DELETE, so' `ativo` baixa)
          (modelo/atualizar! tx {:id mid :ente-id ente :ativo false :updated-by nil :lock-version 1})
          (is (false? (:ativo (modelo/buscar tx ente mid))))
          (is (not (some #{mid} (map :id (modelo/listar-ativos tx ente)))) "desativado some da listagem de ativos")
          ;; CAS: lock-version desatualizado -> :validacao/invalido (mesmo contrato de documento/editar-rascunho!)
          (is (thrown-with-msg? Exception #"conflito"
                (modelo/atualizar! tx {:id mid :ente-id ente :nome "X" :updated-by nil :lock-version 1}))
              "lock-version ja consumido (agora e' 2) barra"))))))

;; ---------- gerar: merge aplicado + rascunho ----------

(deftest gera-documento-com-merge
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{mid :id} (criar-modelo! tx ente {})
              {did :id corpo :corpo} (gerar! tx ente mid {:corpo-template "Prezado {{nome}}, sessao em {{data}}."
                                                          :dados {"nome" "Joao" "data" "10/07"}})]
          (is (= "Prezado Joao, sessao em 10/07." corpo) "corpo renderizado com o merge")
          (let [r (documento/buscar tx ente did)]
            (is (= "rascunho" (:estado r)) "nasce rascunho")
            (is (= corpo (:corpo r)))
            (is (m/validate mod-doc/Documento r) "documento bate o model")))))))

;; ---------- state machine: editar rascunho, emitir, congelar ----------

(deftest edita-rascunho-emite-e-congela
  (let [ente (random-uuid) did (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{mid :id} (criar-modelo! tx ente {})
              {id :id} (gerar! tx ente mid {})]
          (reset! did id)
          ;; edita enquanto rascunho
          (documento/editar-rascunho! tx {:id id :ente-id ente :corpo "Corpo revisado." :updated-by nil :lock-version 0})
          (is (= "Corpo revisado." (:corpo (documento/buscar tx ente id))) "rascunho editavel")
          ;; emite (emitido_por obrigatorio: autoria do artefato legal)
          (documento/emitir! tx {:id id :ente-id ente :emitido-por (random-uuid) :updated-by nil :lock-version 1})
          (let [r (documento/buscar tx ente id)]
            (is (= "emitido" (:estado r)))
            (is (some? (:emitido-em r)) "carimba emitido_em")))))
    ;; emitido e' terminal: nao edita, nao re-emite, nem UPDATE direto
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (documento/editar-rascunho! tx {:id @did :ente-id ente :corpo "X" :updated-by nil :lock-version 2}))))
        "documento emitido nao edita (guard rascunho)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.documento SET corpo = 'hack' WHERE id = ?" @did]))))
        "emitido congela o conteudo (trigger trava terminal)")))

;; ---------- vinculo ao Protocolo Geral (FK same-tenant) ----------

(deftest documento-vincula-protocolo-geral
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{mid :id} (criar-modelo! tx ente {})
              {pg :id} (protocolo/protocolar! tx {:id (random-uuid) :ente-id ente :ano 2026
                                                  :objeto-tipo "documento" :sentido "expedido"
                                                  :assunto "Oficio expedido"})
              {did :id} (gerar! tx ente mid {:protocolo-geral-id pg})]
          (is (= pg (:protocolo-geral-id (documento/buscar tx ente did))) "documento aponta a entrada do PG"))))
    ;; FK same-tenant: protocolo de outro ente nao cola
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx]
                     (let [{mid :id} (criar-modelo! tx ente {})]
                       (gerar! tx ente mid {:protocolo-geral-id (random-uuid)})))))
        "protocolo_geral_id inexistente barra (FK)")))

;; ---------- emissao exige autoria ----------

(deftest emitir-exige-autor
  (let [ente (random-uuid)]
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx]
                     (let [{mid :id} (criar-modelo! tx ente {}) {id :id} (gerar! tx ente mid {})]
                       ;; emitido_por NULL barra no CHECK documento_emitido_tem_marca (autoria obrigatoria)
                       (documento/emitir! tx {:id id :ente-id ente :emitido-por nil :updated-by nil :lock-version 0})))))
        "emitir sem emitido_por barra (CHECK de autoria do artefato)")))

;; ---------- vocabularios ----------

(deftest vocabularios-invalidos-barram
  (let [ente (random-uuid)]
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente (fn [tx] (criar-modelo! tx ente {:tipo-documento "telegrama"}))))
        "tipo_documento invalido barra (CHECK)")))
