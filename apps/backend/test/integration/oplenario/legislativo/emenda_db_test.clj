(ns oplenario.legislativo.emenda-db-test
  "INTEGRACAO (PG real): eixo D — emendas (§22.4). Prova: numeracao LOCAL por proposicao-mae (ordinal,
  UNIQUE), conformidade de model, vocabularios (tipo_emenda/momento_apresentacao) barrados por CHECK,
  ciclo de vida em ENUM SIMPLES (nao motor de templates — ciclo universal) com travamento em estado
  terminal (imutabilidade nivel b, helper compartilhado), e a APLICACAO ao texto-mae: emenda aprovada
  cria uma proposicao_texto_versao 'rascunho' (origem 'aplicacao_emenda') e fecha o ciclo bidirecional
  via versao_texto_resultante_id — tudo na MESMA tx."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.emenda :as em]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.texto-versao :as txt]
            [oplenario.legislativo.models.emenda :as mod]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- emendar! [tx ente mae extra]
  (em/criar! tx (merge {:id (random-uuid) :ente-id ente :proposicao-mae-id mae
                        :tipo-emenda "modificativa" :momento-apresentacao "no_prazo"
                        :escopo-textual "Art. 1o, caput" :texto-inline "Onde se le X, leia-se Y"} extra)))

(deftest criar-numera-local-por-mae-e-conforma
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [m1 (protocolar! tx ente)
              m2 (protocolar! tx ente)
              e1 (emendar! tx ente m1 {})
              e2 (emendar! tx ente m1 {:tipo-emenda "supressiva"})
              o1 (emendar! tx ente m2 {})]
          (is (= 1 (:numero-local e1)) "primeira emenda da mae 1 = 1")
          (is (= 2 (:numero-local e2)) "segunda emenda da MESMA mae = 2 (ordinal local)")
          (is (= 1 (:numero-local o1)) "primeira emenda de OUTRA mae reinicia em 1 (escopo por mae)")
          (let [r (em/buscar tx ente (:id e1))]
            (is (= "apresentada" (:estado r)) "emenda nasce 'apresentada'")
            (is (nil? (:versao-texto-resultante-id r)) "sem versao resultante antes da aprovacao")
            (is (m/validate mod/Emenda r) "emenda bate o model interno")))))))

(deftest tipo-e-momento-invalidos-barram
  ;; cada violacao de CHECK aborta a tx -> uma com-tenant* por caso.
  (let [ente (random-uuid) mae (atom nil)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (reset! mae (protocolar! tx ente))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente (fn [tx] (emendar! tx ente @mae {:tipo-emenda "inventada"}))))
        "tipo_emenda fora do vocabulario e barrado pelo CHECK")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente (fn [tx] (emendar! tx ente @mae {:momento-apresentacao "ontem"}))))
        "momento_apresentacao fora do vocabulario e barrado pelo CHECK")))

(deftest aprovar-cria-rascunho-e-fecha-ciclo-bidirecional
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [mae (protocolar! tx ente)
              e   (emendar! tx ente mae {})
              r   (em/aprovar! tx {:ente-id ente :emenda-id (:id e) :updated-by nil
                                   :versao {:id (random-uuid) :texto-inline "## Texto consolidado pelo redator"
                                            :created-by nil}})
              vid (:versao-resultante-id r)]
          (is (= "aprovada" (:estado r)) "aprovar! leva a emenda a 'aprovada'")
          (is (uuid? vid) "aprovar! devolve a versao-resultante")
          (let [emr (em/buscar tx ente (:id e))]
            (is (= "aprovada" (:estado emr)) "estado persistido = aprovada")
            (is (= vid (:versao-texto-resultante-id emr)) "ciclo bidirecional: emenda aponta a versao"))
          (let [ver (txt/buscar tx ente vid)]
            (is (= "rascunho" (:estado-versao ver)) "a versao nasce rascunho (redator consolida)")
            (is (= "aplicacao_emenda" (:origem-versao ver)) "proveniencia = aplicacao_emenda")
            (is (= (:id e) (:origem-ref ver)) "ciclo bidirecional: versao aponta a emenda de origem")))))))

(deftest terminal-trava-mutacao
  (let [ente (random-uuid) eid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [mae (protocolar! tx ente)
              e   (emendar! tx ente mae {})]
          (reset! eid (:id e))
          ;; leva a um estado terminal por aprovacao
          (em/aprovar! tx {:ente-id ente :emenda-id (:id e) :updated-by nil
                           :versao {:id (random-uuid) :texto-inline "## consolidado" :created-by nil}}))))
    ;; em estado terminal ('aprovada') o trigger compartilhado trava qualquer mutacao (sem correcao auditada)
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (em/mudar-estado! tx {:ente-id ente :id @eid :estado "retirada"
                                                  :updated-by nil :lock-version 1}))))
        "emenda em estado terminal nao muda sem correcao auditada (imutabilidade nivel b)")))

(deftest mudar-estado-nao-faz-aprovacao
  ;; review F3.4 DB-M2: 'aprovada' exige a criacao ATOMICA da versao (aprovar!); mudar-estado! recusa o
  ;; atalho que deixaria a emenda 'aprovada' sem versao_texto_resultante_id (a CHECK do banco tambem barra).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [mae (protocolar! tx ente)
              e   (emendar! tx ente mae {})]
          (is (thrown? Exception
                       (em/mudar-estado! tx {:ente-id ente :id (:id e) :estado "aprovada"
                                             :updated-by nil :lock-version 0}))
              "mudar-estado! recusa 'aprovada' (deve usar aprovar!)"))))))

(deftest aprovar-fixa-proveniencia-ignora-override-do-caller
  ;; review F3.4 clojure-MAJOR: a proveniencia da versao (origem 'aplicacao_emenda', origem_ref=emenda) e'
  ;; cravada por aprovar!; o seed do caller NAO pode sobrescreve-la (texto_versao e' append-only).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [mae (protocolar! tx ente)
              e   (emendar! tx ente mae {})
              r   (em/aprovar! tx {:ente-id ente :emenda-id (:id e) :updated-by nil
                                   :versao {:id (random-uuid) :texto-inline "## consolidado"
                                            :origem-versao "importacao_legado" :origem-ref (random-uuid)
                                            :origem-tipo "hack" :created-by nil}})
              ver (txt/buscar tx ente (:versao-resultante-id r))]
          (is (= "aplicacao_emenda" (:origem-versao ver)) "origem cravada por aprovar! (override ignorado)")
          (is (= (:id e) (:origem-ref ver)) "origem_ref aponta a emenda (override ignorado)"))))))

(deftest mudar-estado-cas-e-ciclo
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [mae (protocolar! tx ente)
              e   (emendar! tx ente mae {})]
          (em/mudar-estado! tx {:ente-id ente :id (:id e) :estado "admitida" :updated-by nil :lock-version 0})
          (is (= "admitida" (:estado (em/buscar tx ente (:id e)))) "apresentada -> admitida (nao-terminal)")
          ;; CAS: lock-version desatualizado lanca
          (is (thrown? Exception
                       (em/mudar-estado! tx {:ente-id ente :id (:id e) :estado "rejeitada"
                                             :updated-by nil :lock-version 0}))
              "lock_version desatualizado e rejeitado (CAS honesto)"))))))
