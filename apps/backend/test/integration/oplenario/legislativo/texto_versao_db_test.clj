(ns oplenario.legislativo.texto-versao-db-test
  "INTEGRACAO (PG real): eixo B — versionamento de texto. Prova: numeracao ordinal local, conformidade de
  model, conteudo APPEND-ONLY (trigger congela; so estado_versao muda), promocao atomica (supersede +
  reaponta o pointer da proposicao) com one-vigente, XOR inline/uri + threshold 32KB, RLS e CAS."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.texto-versao :as txt]
            [oplenario.legislativo.models.texto-versao :as mod]
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

(defn- nova! [tx ente pid extra]
  (txt/nova-versao! tx (merge {:id (random-uuid) :ente-id ente :proposicao-id pid
                               :origem-versao "protocolo" :texto-inline "## Art. 1o ..."} extra)))

(deftest nova-versao-numera-e-conforma
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              v1  (nova! tx ente pid {})
              v2  (nova! tx ente pid {:origem-versao "substitutivo" :texto-inline "## Art. 1o (subst)"})]
          (is (= 1 (:numero-versao v1)) "primeira versao = 1")
          (is (= 2 (:numero-versao v2)) "segunda versao = 2 (ordinal local)")
          (let [r (txt/buscar tx ente (:id v1))]
            (is (= "rascunho" (:estado-versao r)) "nasce rascunho")
            (is (m/validate mod/TextoVersao r) "versao bate o model interno")))))))

(deftest conteudo-append-only-estado-muta
  ;; cada violacao de trigger aborta a tx -> uma com-tenant* por caso (senao o comando seguinte na mesma
  ;; tx ja-abortada falha com 'current transaction is aborted').
  (let [ente (random-uuid) vid (atom nil)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (reset! vid (:id (nova! tx ente (protocolar! tx ente) {})))))
    ;; mudar o CONTEUDO e' bloqueado pelo trigger (tx isolada)
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.proposicao_texto_versao SET texto_inline = 'hack' WHERE id = ?" @vid]))))
        "conteudo da versao e' append-only (trigger congela)")
    ;; mudar so o estado_versao e' permitido (a mutacao controlada) — tx separada
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.proposicao_texto_versao SET estado_versao = 'arquivada' WHERE id = ?" @vid])))
    (is (= "arquivada" (:estado-versao (tenancy/com-tenant* *ds* ente (fn [tx] (txt/buscar tx ente @vid)))))
        "estado_versao muta (a mutacao controlada)")))

(deftest promocao-supersede-e-reaponta-pointer
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) v1 (nova! tx ente pid {}) v2 (nova! tx ente pid {:origem-versao "substitutivo"})]
          (txt/promover! tx {:ente-id ente :proposicao-id pid :versao-id (:id v1) :lock-version 0})
          (is (= "vigente" (:estado-versao (txt/buscar tx ente (:id v1)))) "v1 vigente")
          (is (= (:id v1) (:texto-vigente-versao-id (prop/buscar tx ente pid))) "pointer da proposicao -> v1")
          ;; promover v2: v1 vira superada, v2 vigente, pointer -> v2
          (txt/promover! tx {:ente-id ente :proposicao-id pid :versao-id (:id v2) :lock-version 0})
          (is (= "superada" (:estado-versao (txt/buscar tx ente (:id v1)))) "v1 superada")
          (is (= "vigente" (:estado-versao (txt/buscar tx ente (:id v2)))) "v2 vigente")
          (is (= (:id v2) (:texto-vigente-versao-id (prop/buscar tx ente pid))) "pointer -> v2")
          (is (= 1 (count (filter #(= "vigente" (:estado-versao %)) (txt/versoes-da-proposicao tx ente pid))))
              "no maximo uma vigente"))))))

(deftest promocao-cas-detecta-conflito
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) v (nova! tx ente pid {})]
          (is (thrown? Exception
                       (txt/promover! tx {:ente-id ente :proposicao-id pid :versao-id (:id v) :lock-version 99}))
              "lock_version errada = conflito"))))))

(deftest xor-conteudo-e-threshold-32kb
  ;; cada violacao de CHECK aborta a tx -> uma com-tenant* por caso
  (let [ente (random-uuid)]
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (nova! tx ente (protocolar! tx ente) {:conteudo-uri "s3://x/y"}))))
        "inline E uri juntos viola o XOR")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (nova! tx ente (protocolar! tx ente) {:texto-inline nil}))))
        "nenhum dos dois viola o XOR")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (nova! tx ente (protocolar! tx ente) {:texto-inline (apply str (repeat 32769 \a))}))))
        "inline acima de 32KB viola o CHECK (deveria ir p/ objeto_store)")))

(deftest rls-isola-versao-cross-tenant
  (let [a (random-uuid) b (random-uuid) vid (atom nil)]
    (tenancy/com-tenant* *ds* a (fn [tx] (reset! vid (:id (nova! tx a (protocolar! tx a) {})))))
    (is (some? (tenancy/com-tenant* *ds* a (fn [tx] (txt/buscar tx a @vid)))) "ente A ve a propria versao")
    (is (nil? (tenancy/com-tenant* *ds* b (fn [tx] (txt/buscar tx b @vid)))) "ente B NAO ve a versao de A (RLS)")))
