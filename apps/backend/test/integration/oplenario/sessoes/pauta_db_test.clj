(ns oplenario.sessoes.pauta-db-test
  "INTEGRACAO (PG real): F4.2a — §22.6 eixo B, camada VIVA da pauta. Prova: pauta_sessao 1:1 com sessao;
  pauta_item com FASE (atributo) + tipo de item com FK DECLARATIVA por tipo (proposicao_id XOR texto_descricao,
  descartado polimorfismo); ordem numerada; remocao intra-sessao = ativo=false (NUNCA DELETE, Inv.10);
  cada mutacao (inclusao/exclusao/inversao/retirada) grava pauta_alteracao APPEND-ONLY. proposicao_id e'
  forward-ref (uuid, sem FK cross-schema p/ legislativo, §22.10)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.pauta :as pauta]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.pauta :as mod]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- nova-pauta!
  "Cria sessao + pauta 1:1, devolve {:sessao-id :pauta-id}."
  [tx ente]
  (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                       :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"})
        {pid :id} (pauta/criar-pauta! tx {:id (random-uuid) :ente-id ente :sessao-id sid})]
    {:sessao-id sid :pauta-id pid}))

(defn- add! [tx ente pid extra]
  (pauta/adicionar-item! tx (merge {:id (random-uuid) :ente-id ente :pauta-sessao-id pid
                                    :fase "ordem_do_dia" :tipo-item "proposicao"
                                    :proposicao-id (random-uuid)} extra)))

;; ---------- logica pura: vocabularios ----------

(deftest vocabularios-pauta
  (is (contains? logic/fases-pauta "ordem_do_dia"))
  (is (contains? logic/tipos-item-pauta "proposicao"))
  (is (contains? logic/tipos-alteracao-pauta "inversao"))
  (is (true? (logic/item-requer-proposicao? "proposicao")))
  (is (false? (logic/item-requer-proposicao? "leitura")))
  (is (thrown? Exception (logic/validar-fase "almoco")))
  (is (thrown? Exception (logic/validar-tipo-item "musica"))))

;; ---------- pauta 1:1 com sessao ----------

(deftest pauta-unica-por-sessao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [sessao-id pauta-id]} (nova-pauta! tx ente)]
          (is (some? pauta-id))
          (is (= pauta-id (:id (pauta/buscar-pauta-por-sessao tx ente sessao-id))) "acha a pauta pela sessao")
          (is (thrown? Exception
                       (pauta/criar-pauta! tx {:id (random-uuid) :ente-id ente :sessao-id sessao-id}))
              "segunda pauta na mesma sessao barra (UNIQUE 1:1)"))))))

(deftest garantir-pauta-get-or-create
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{sid :id} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                             :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"})
              ;; 1a chamada CRIA a pauta (sessao sem container ainda)
              p1 (pauta/garantir-pauta! tx {:ente-id ente :sessao-id sid})
              ;; 2a chamada RE-LE a mesma pauta (idempotente, ON CONFLICT na UNIQUE 1:1)
              p2 (pauta/garantir-pauta! tx {:ente-id ente :sessao-id sid})]
          (is (some? (:id p1)) "1a chamada cria e devolve a pauta")
          (is (= (:id p1) (:id p2)) "2a chamada converge na MESMA pauta (nunca duplica)")
          (is (= (:id p1) (:id (pauta/buscar-pauta-por-sessao tx ente sid))) "a pauta criada e' a da sessao"))))))

(deftest garantir-pauta-reusa-existente
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [sessao-id pauta-id]} (nova-pauta! tx ente)]
          (is (= pauta-id (:id (pauta/garantir-pauta! tx {:ente-id ente :sessao-id sessao-id})))
              "pauta ja criada por criar-pauta! e' reusada (nao recria)"))))))

;; ---------- itens: FK declarativa por tipo + ordem ----------

(deftest adiciona-itens-numera-ordem-e-loga
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)
              a (add! tx ente pauta-id {:fase "expediente"})
              b (add! tx ente pauta-id {:tipo-item "leitura" :proposicao-id nil
                                        :texto-descricao "Leitura do oficio 12/2026"})]
          (is (= [1 2] [(:ordem a) (:ordem b)]) "ordem numerada por pauta")
          (let [itens (pauta/listar-itens tx ente pauta-id)]
            (is (= 2 (count itens)) "dois itens ativos")
            (is (= ["expediente" "ordem_do_dia"] (map :fase itens)) "fase como atributo do item")
            (is (m/validate mod/PautaItem (first itens)) "item bate o model"))
          ;; cada inclusao gerou um registro de alteracao
          (let [alts (pauta/listar-alteracoes tx ente pauta-id)]
            (is (= 2 (count alts)))
            (is (every? #(= "inclusao" (:tipo %)) alts))))))))

(deftest fk-declarativa-por-tipo
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)]
          (is (thrown? Exception
                       (add! tx ente pauta-id {:tipo-item "proposicao" :proposicao-id nil
                                               :texto-descricao "x"}))
              "proposicao SEM proposicao_id barra")
          (is (thrown? Exception
                       (add! tx ente pauta-id {:tipo-item "leitura"
                                               :proposicao-id (random-uuid) :texto-descricao nil}))
              "leitura COM proposicao_id (e sem descricao) barra")
          (is (thrown? Exception
                       (add! tx ente pauta-id {:tipo-item "homenagem" :proposicao-id nil
                                               :texto-descricao "   "}))
              "descricao vazia barra"))))))

;; ---------- reordenar (inversao) ----------

(deftest reordena-item-e-loga-inversao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)
              a (add! tx ente pauta-id {})
              b (add! tx ente pauta-id {})]
          ;; move b para frente de a
          (pauta/reordenar-item! tx {:ente-id ente :id (:id b) :nova-ordem 0
                                     :updated-by nil :lock-version 0})
          (is (= [(:id b) (:id a)] (map :id (pauta/listar-itens tx ente pauta-id))) "b agora vem antes de a")
          (let [alts (filter #(= "inversao" (:tipo %)) (pauta/listar-alteracoes tx ente pauta-id))]
            (is (= 1 (count alts)) "inversao logada")))))))

;; ---------- remocao = soft (ativo=false), nunca DELETE ----------

(deftest remove-item-soft-e-loga
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)
              a (add! tx ente pauta-id {})]
          (pauta/remover-item! tx {:ente-id ente :id (:id a) :tipo "retirada_pedido_autor"
                                   :justificativa "autor pediu retirada" :updated-by nil :lock-version 0})
          (is (empty? (pauta/listar-itens tx ente pauta-id)) "item some da lista ATIVA")
          (is (false? (:ativo (pauta/buscar-item tx ente (:id a)))) "mas persiste com ativo=false (Inv.10)")
          (let [alts (filter #(= "retirada_pedido_autor" (:tipo %)) (pauta/listar-alteracoes tx ente pauta-id))]
            (is (= 1 (count alts)))
            (is (= "autor pediu retirada" (:justificativa (first alts))))))))))

(deftest remocao-tipo-invalido-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)
              a (add! tx ente pauta-id {})]
          (is (thrown? Exception
                       (pauta/remover-item! tx {:ente-id ente :id (:id a) :tipo "inclusao"
                                                :updated-by nil :lock-version 0}))
              "remocao so aceita exclusao|retirada_pedido_autor"))))))

(deftest mutar-item-removido-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)
              a (add! tx ente pauta-id {})]
          (pauta/remover-item! tx {:ente-id ente :id (:id a) :tipo "exclusao" :updated-by nil :lock-version 0})
          (is (thrown? Exception
                       (pauta/remover-item! tx {:ente-id ente :id (:id a) :tipo "exclusao" :updated-by nil :lock-version 1}))
              "remover item ja removido barra (nao corrompe o log append-only)")
          (is (thrown? Exception
                       (pauta/reordenar-item! tx {:ente-id ente :id (:id a) :nova-ordem 0 :updated-by nil :lock-version 1}))
              "reordenar item removido barra"))))))

;; ---------- alteracao e' append-only puro ----------

(deftest alteracao-append-only
  (let [ente (random-uuid) aid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)]
          (add! tx ente pauta-id {})
          (reset! aid (:id (first (pauta/listar-alteracoes tx ente pauta-id)))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE sessoes.pauta_alteracao SET tipo = 'exclusao' WHERE id = ?" @aid]))))
        "pauta_alteracao e' append-only (trigger barra UPDATE)")))

;; ================= F4.2b — pauta_sessao_versao (snapshots canonicos) =================
;; A camada viva (item/alteracao) muta; a VERSAO congela a pauta num instante = o que o portal cita / a prova.

(deftest vocabularios-versao
  (is (contains? logic/tipos-versao-pauta "publicacao_inicial"))
  (is (contains? logic/tipos-versao-pauta "republicacao"))
  (is (contains? logic/tipos-versao-pauta "execucao_final"))
  (is (thrown? Exception (logic/validar-tipo-versao "rascunho"))))

(deftest publica-versao-numera-e-snapshota
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)]
          (add! tx ente pauta-id {})
          (add! tx ente pauta-id {:tipo-item "leitura" :proposicao-id nil :texto-descricao "Oficio 1"})
          (let [v1 (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                               :tipo-versao "publicacao_inicial" :publica true})]
            (is (= 1 (:numero-versao v1)) "primeira versao = numero 1")
            (let [lido (pauta/buscar-versao tx ente (:id v1))]
              (is (= "publicacao_inicial" (:tipo-versao lido)))
              (is (true? (:publica lido)))
              (is (= 2 (count (:snapshot lido))) "snapshot congela os 2 itens ativos em ordem")
              (is (= [1 2] (mapv :ordem (:snapshot lido))) "snapshot mantem a ordem")
              (is (m/validate mod/PautaVersao lido) "versao bate o model"))))))))

(deftest versao-congela-mesmo-apos-mutar-pauta
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)
              a (add! tx ente pauta-id {})]
          (add! tx ente pauta-id {})
          (let [v1 (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                               :tipo-versao "publicacao_inicial" :publica true})]
            ;; muta a pauta DEPOIS de publicar
            (pauta/remover-item! tx {:ente-id ente :id (:id a) :tipo "exclusao" :updated-by nil :lock-version 0})
            (is (= 1 (count (pauta/listar-itens tx ente pauta-id))) "pauta viva agora tem 1 item")
            (is (= 2 (count (:snapshot (pauta/buscar-versao tx ente (:id v1)))))
                "mas o snapshot da v1 segue com 2 (congelado)")))))))

(deftest versao-publica-corrente-segue-maior-numero-publico
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)]
          (add! tx ente pauta-id {})
          (let [v1 (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                               :tipo-versao "publicacao_inicial" :publica true})
                v2 (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                               :tipo-versao "execucao_final" :publica false})]
            (is (= 2 (:numero-versao v2)) "numero local incrementa por pauta")
            (is (= (:id v1) (:id (pauta/versao-publica-corrente tx ente pauta-id)))
                "corrente = maior numero com publica=true (v2 nao e' publica)")
            (is (= 2 (count (pauta/listar-versoes tx ente pauta-id))) "as duas versoes listadas")))))))

(deftest versao-tipo-invalido-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)]
          (add! tx ente pauta-id {})
          (is (thrown? Exception
                       (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                                   :tipo-versao "rascunho" :publica true}))
              "tipo_versao fora do enum barra (fail-closed)"))))))

(deftest republicacao-exige-justificativa
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)]
          (add! tx ente pauta-id {})
          (is (thrown? Exception
                       (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                                   :tipo-versao "republicacao" :publica true}))
              "republicacao sem justificativa barra (fail-closed)")
          (let [v (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                              :tipo-versao "republicacao" :publica true
                                              :justificativa "incluido requerimento urgente"})]
            (is (some? (:id v)) "republicacao COM justificativa passa")))))))

(deftest versao-append-only
  (let [ente (random-uuid) vid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [pauta-id]} (nova-pauta! tx ente)]
          (add! tx ente pauta-id {})
          (reset! vid (:id (pauta/publicar-versao! tx {:ente-id ente :pauta-sessao-id pauta-id
                                                       :tipo-versao "publicacao_inicial" :publica true}))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE sessoes.pauta_sessao_versao SET publica = false WHERE id = ?" @vid]))))
        "pauta_sessao_versao e' append-only (trigger barra UPDATE)")))
