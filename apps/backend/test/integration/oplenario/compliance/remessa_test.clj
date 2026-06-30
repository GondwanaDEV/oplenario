(ns oplenario.compliance.remessa-test
  "INTEGRACAO (PG real) — F5.3a: persistencia do artefato de remessa (compliance.remessa_gerada, §22.7.8)
  + a COSTURA `remessa_enviada`. O artefato e' imutavel por VERSAO (re-emissao = nova versao); o `estado`
  evolui no ciclo (rascunho -> validada -> submetida -> {aceita | rejeitada}) via CAS. Costura: so a linha
  'aceita' satisfaz a relacao `remessa_enviada`. Inclui o MARCO M6 (a costura fecha T1). Sob a tx do tenant."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.db.remessa :as db-rem]
            [oplenario.compliance.models.remessa :as mod-rem]
            [oplenario.compliance.relacoes :as rel]
            [oplenario.config :as config]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.repositorio :as rm]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.motor.templates :as tpl]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(defn- nova-remessa [ente competencia versao]
  {:id (random-uuid) :ente-id ente :template-chave "remessa_mensal_sim" :sistema "SIM"
   :competencia competencia :versao versao :spec-layout-versao "fixture-sim-v0"
   :registry-versao-ref "registry-v1@2026-06-20" :hash "sha256:abc" :objeto-store-ref "remessas/x.bin"})

;; ---------- inserir! materializa rascunho; bate o model ----------

(deftest inserir-cria-rascunho
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (let [r (db-rem/inserir! tx (nova-remessa ente "2026-05" 1))]
          (is (= "rascunho" (:estado r)) "nasce em rascunho")
          (is (= 1 (:versao r)) "versao 1")
          (is (m/validate mod-rem/Remessa r) "bate o model de remessa")
          (is (m/validate mod-rem/Remessa (db-rem/buscar tx ente (:id r))) "model bate na releitura"))))))

;; ---------- proxima-versao: 1 quando nao ha; max+1 apos ----------

(deftest proxima-versao-incrementa
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (is (= 1 (db-rem/proxima-versao tx ente "remessa_mensal_sim" "2026-04")) "sem remessa -> versao 1")
        (db-rem/inserir! tx (nova-remessa ente "2026-04" 1))
        (is (= 2 (db-rem/proxima-versao tx ente "remessa_mensal_sim" "2026-04")) "ja' ha v1 -> proxima e' 2")))))

;; ---------- transicionar-estado!: CAS pelo estado atual; carimba submetida_em/resposta_em ----------

(deftest transicionar-segue-o-ciclo-com-cas
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (let [r (db-rem/inserir! tx (nova-remessa ente "2026-03" 1))
              id (:id r)]
          (is (some? (db-rem/transicionar-estado! tx ente id "rascunho" "validada" {})) "rascunho->validada")
          (is (nil? (db-rem/transicionar-estado! tx ente id "rascunho" "validada" {}))
              "CAS: a 2a tentativa do MESMO de->para falha (estado ja' mudou)")
          (db-rem/transicionar-estado! tx ente id "validada" "submetida" {:submetida-em [:now]})
          (let [sub (db-rem/buscar tx ente id)]
            (is (= "submetida" (:estado sub)) "validada->submetida")
            (is (some? (:submetida-em sub)) "submetida_em carimbada"))
          (db-rem/transicionar-estado! tx ente id "submetida" "aceita" {:resposta-em [:now]})
          (let [ac (db-rem/buscar tx ente id)]
            (is (= "aceita" (:estado ac)) "submetida->aceita")
            (is (some? (:resposta-em ac)) "resposta_em carimbada")))))))

;; ---------- guard: salto ilegal no grafo do ciclo LANCA (2a linha de defesa, review M2) ----------

(deftest transicionar-salto-ilegal-lanca
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (let [r (db-rem/inserir! tx (nova-remessa ente "2026-06" 1))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"transicao de estado de remessa invalida"
                (db-rem/transicionar-estado! tx ente (:id r) "rascunho" "aceita" {}))
              "rascunho->aceita pula o ciclo -> LANCA (independente do CAS)"))))))

;; ---------- costura: a relacao remessa_enviada (so 'aceita' cumpre; competencia = {:ano :mes}) ----------

(deftest remessa-enviada-e-a-costura
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)
        compet {:ano 2026 :mes 2}]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (is (false? (rel/remessa-enviada? tx "SIM" compet)) "sem remessa aceita -> falso")
        (let [r (db-rem/inserir! tx (nova-remessa ente "2026-02" 1))]
          (db-rem/transicionar-estado! tx ente (:id r) "rascunho" "validada" {})
          (db-rem/transicionar-estado! tx ente (:id r) "validada" "submetida" {:submetida-em [:now]})
          (is (false? (rel/remessa-enviada? tx "SIM" compet)) "submetida ainda NAO satisfaz a costura")
          (db-rem/transicionar-estado! tx ente (:id r) "submetida" "aceita" {:resposta-em [:now]})
          (is (true? (rel/remessa-enviada? tx "SIM" compet)) "aceita satisfaz a costura remessa_enviada")
          (is (false? (rel/remessa-enviada? tx "SIM" {:ano 2099 :mes 1})) "outra competencia -> falso"))))))

;; ---------- MARCO M6: a costura fecha T1 (remessa_mensal_sim) ponta-a-ponta ----------
;; A relacao `remessa_enviada` (registrada pelo compliance/relacoes, F5.3a) liga o motor ao artefato:
;; T1 nao-conforme (sem remessa aceita) -> obrigacao PENDENTE; remessa ACEITA -> T1 conforme -> CUMPRIDA.

(defn- seed-prazo! [ds repo-motor chave-periodo data-limite]
  (jdbc/execute-one! ds ["DELETE FROM motor.prazo_dominio_vigente WHERE chave_periodo = ?" chave-periodo])
  (rm/criar-prazo! repo-motor {:id (random-uuid) :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
                               :tipo-prazo "SIM_mensal" :chave-periodo chave-periodo
                               :data-limite data-limite :vigente true :fonte "IN 04/2019"}))

(deftest costura-remessa-cumpre-T1
  (let [{:keys [datasource repo-motor repo-compliance registro-fatos]} *sys*
        ds (:ds datasource) ente (random-uuid) objeto (random-uuid)]
    (seed-prazo! ds repo-motor "2099-07" (LocalDate/of 2099 7 31))
    (let [avaliar (fn [agora]
                    (repo-compliance/avaliar-obrigacao! repo-compliance ente registro-fatos repo-motor
                      {:regra (nuc/carregar-envelope tpl/T1) :reg-ver "registry-v1@2026-06-20"
                       :objeto-tipo "competencia" :objeto-id objeto
                       :amb {"competencia" {:ano 2099 :mes 7}} :agora agora :origem "evento"}))
          r1 (avaliar (LocalDate/of 2026 6 19))]
      (is (= "nao_conforme" (:veredito (:avaliacao r1))) "sem remessa aceita -> T1 nao-conforme")
      (is (= "pendente" (:estado (:obrigacao r1))) "obrigacao materializada PENDENTE")
      ;; o ente gera e o TCE aceita a remessa do SIM da competencia
      (tenancy/com-tenant* ds ente
        (fn [tx]
          (let [remessa-row (db-rem/inserir! tx (nova-remessa ente "2099-07" 1))]
            (db-rem/transicionar-estado! tx ente (:id remessa-row) "rascunho" "validada" {})
            (db-rem/transicionar-estado! tx ente (:id remessa-row) "validada" "submetida" {:submetida-em [:now]})
            (db-rem/transicionar-estado! tx ente (:id remessa-row) "submetida" "aceita" {:resposta-em [:now]}))))
      (let [r2 (avaliar (LocalDate/of 2026 6 20))]
        (is (= "conforme" (:veredito (:avaliacao r2))) "remessa ACEITA -> remessa_enviada verdadeiro -> T1 conforme")
        (is (= (:id (:obrigacao r1)) (:id (:obrigacao r2))) "MESMA obrigacao (idempotencia da chave)")
        (is (= "cumprida" (:estado (:obrigacao r2))) "T1 cumprida pela remessa aceita (M6)")))))
