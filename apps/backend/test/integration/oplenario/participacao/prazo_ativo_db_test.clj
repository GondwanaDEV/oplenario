(ns oplenario.participacao.prazo-ativo-db-test
  "INTEGRACAO (PG real): a query agregada `esic-cumprimento` (read-model barato, FE Onda A1 §16.11) — 'o que
  a Casa entregou': dos pedidos e-SIC JA ENCERRADOS (cumprida|vencida — pendente ainda esta ABERTO, nao entra
  no historico), quantos foram cumpridos DENTRO do prazo (cumprida_em <= vence_em). So' objeto_tipo=
  'pedido_esic' conta (a metrica e' especifica do e-SIC/LAI); outro objeto_tipo (ex.: recurso_esic) NAO entra
  na contagem. Testa direto contra `db/prazo-ativo` (sem Repo-Component — mesmo estilo de presenca_db_test),
  sob FORCE RLS (`tenancy/com-tenant*` abre a tx do tenant)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.db.prazo-ativo :as db])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

;; ---------- cenario principal do brief: no-prazo vs. fora-do-prazo vs. outro objeto-tipo ----------

(deftest esic-cumprimento-conta-no-prazo-vs-total
  (let [ente (random-uuid)
        obj-no-prazo   (random-uuid)
        obj-fora-prazo (random-uuid)
        obj-recurso    (random-uuid)
        obj-pendente   (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; 1o pedido: cumprida NO PRAZO (cumprida_em <= vence_em)
        (db/inserir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "pedido_esic" :objeto-id obj-no-prazo
                          :vence-em (LocalDate/of 2026 7 20) :estado "pendente"})
        (db/cumprir! tx {:ente-id ente :objeto-tipo "pedido_esic" :objeto-id obj-no-prazo
                          :cumprida-em (LocalDate/of 2026 7 18)})
        ;; 2o pedido: cumprida FORA do prazo
        (db/inserir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "pedido_esic" :objeto-id obj-fora-prazo
                          :vence-em (LocalDate/of 2026 7 10) :estado "pendente"})
        (db/cumprir! tx {:ente-id ente :objeto-tipo "pedido_esic" :objeto-id obj-fora-prazo
                          :cumprida-em (LocalDate/of 2026 7 15)})
        ;; 3o: outro objeto-tipo (recurso_esic), tambem cumprido no prazo — NAO deve contar (metrica e' so' pedido_esic)
        (db/inserir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "recurso_esic" :objeto-id obj-recurso
                          :vence-em (LocalDate/of 2026 7 20) :estado "pendente"})
        (db/cumprir! tx {:ente-id ente :objeto-tipo "recurso_esic" :objeto-id obj-recurso
                          :cumprida-em (LocalDate/of 2026 7 18)})
        ;; 4o: um pedido_esic AINDA pendente (aberto) — nao deve entrar no total-encerrados
        (db/inserir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "pedido_esic" :objeto-id obj-pendente
                          :vence-em (LocalDate/of 2026 8 1) :estado "pendente"})
        (let [r (db/esic-cumprimento tx ente)]
          (is (= 2 (:total-encerrados r))
              "so' os 2 pedido_esic cumpridos contam (o recurso_esic e o pendente ficam fora)")
          (is (= 1 (:cumpridos-no-prazo r)) "so' 1 dos 2 foi cumprido DENTRO do prazo"))))))

;; ---------- vencida conta no total-encerrados, mas NUNCA no-prazo (por definicao) ----------

(deftest esic-cumprimento-vencida-conta-no-total-mas-nao-no-prazo
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{prazo-id :id} (db/inserir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "pedido_esic"
                                              :objeto-id (random-uuid) :vence-em (LocalDate/of 2026 7 10)
                                              :estado "pendente"})]
          (db/vencer-se-pendente! tx {:ente-id ente :id prazo-id})
          (let [r (db/esic-cumprimento tx ente)]
            (is (= 1 (:total-encerrados r)) "vencida conta como ENCERRADA")
            (is (= 0 (:cumpridos-no-prazo r)) "vencida NUNCA conta no-prazo, por definicao")))))))

;; ---------- ente sem nenhum prazo encerrado: 0/0 (indefinido, nao erro) ----------

(deftest esic-cumprimento-sem-encerrados-e-zero-zero
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [r (db/esic-cumprimento tx ente)]
          (is (= 0 (:total-encerrados r)))
          (is (= 0 (:cumpridos-no-prazo r))))))))
