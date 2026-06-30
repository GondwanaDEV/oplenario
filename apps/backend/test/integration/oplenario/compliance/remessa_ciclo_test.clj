(ns oplenario.compliance.remessa-ciclo-test
  "INTEGRACAO (PG real) — F5.5b: a CAMADA DE CONTROLLER do ciclo da remessa (validar/submeter/registrar-
  resposta) sobre o Repo real, sob FORCE RLS (mig 0009). Prova: (1) o happy-path do ciclo persiste as
  transicoes; (2) a DESAMBIGUACAO do nil do Repo — CAS perdido por estado incompativel devolve `:conflito/
  remessa` (handler -> 409), remessa inexistente devolve nil (handler -> 404) — via `buscar-remessa` real."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.controllers :as controllers]
            [oplenario.compliance.gerador-remessa :as ger]
            [oplenario.compliance.components.fontes :as fontes]
            [oplenario.compliance.components.serializador-remessa :as ser]
            [oplenario.config :as config]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema]))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(defn- resolver-relacao-ok [_tx nome]
  (get {"nome_ente" "Camara Municipal de Fortaleza"} nome))

(defn- gerar-rascunho!
  "Materializa uma remessa rascunho real (reusa o gerador da F5.3b) e devolve a linha."
  [repo ente]
  (repo-compliance/gerar-remessa! repo ente
    {:descritor ger/descritor-sim-fixture
     :template-chave "remessa_mensal_sim" :sistema "SIM" :competencia "2099-07"
     :contexto {"competencia" "2099-07" "sistema" "SIM"}
     :resolver-relacao resolver-relacao-ok
     :fontes (fontes/fontes-fixture {"despesas" [{"data" "2099-07-05" "valor" "1000,00"}]})
     :serializador (ser/serializador-sim)
     :objeto-store (:objeto-store *sys*)
     :registry-versao-ref "registry-v1@2026-06-20"}))

(defn- ator [ente] {:ente-id ente :identidade-id (random-uuid)})

;; ---------- happy-path: o controller percorre o ciclo persistido ----------

(deftest controller-percorre-o-ciclo
  (let [repo (:repo-compliance *sys*) ente (random-uuid) a (ator ente)
        id (:id (gerar-rascunho! repo ente))]
    (is (= "validada" (:estado (controllers/validar-remessa repo a id))) "rascunho->validada")
    (is (= "submetida" (:estado (controllers/submeter-remessa repo a id))) "validada->submetida")
    (let [resp (controllers/registrar-resposta-remessa repo a id "aceita")]
      (is (= "aceita" (:estado resp)) "submetida->aceita")
      (is (some? (:resposta-em resp)) "carimba resposta_em"))))

;; ---------- happy-path 'rejeitada' persiste no PG real (simetrico a 'aceita'; review clj M5) ----------

(deftest controller-registra-resposta-rejeitada
  (let [repo (:repo-compliance *sys*) ente (random-uuid) a (ator ente)
        id (:id (gerar-rascunho! repo ente))]
    (controllers/validar-remessa repo a id)
    (controllers/submeter-remessa repo a id)
    (let [resp (controllers/registrar-resposta-remessa repo a id "rejeitada")]
      (is (= "rejeitada" (:estado resp)) "submetida->rejeitada persiste no PG (NAO cumpre; reenvio = nova versao)")
      (is (some? (:resposta-em resp)) "carimba resposta_em tambem na rejeicao"))))

;; ---------- desambiguacao: CAS perdido (estado incompativel) -> conflito (409) ----------

(deftest transicao-em-estado-incompativel-sinaliza-conflito
  (let [repo (:repo-compliance *sys*) ente (random-uuid) a (ator ente)
        id (:id (gerar-rascunho! repo ente))]
    ;; a remessa esta em rascunho; submeter (exige 'validada') nao casa o CAS, mas a remessa EXISTE.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"conflito"
          (controllers/submeter-remessa repo a id))
        "submeter rascunho -> :conflito/remessa (handler -> 409), nao nil silencioso")
    (let [e (try (controllers/submeter-remessa repo a id) (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :conflito/remessa (:tipo (ex-data e))) "marca :conflito/remessa p/ o 409 da borda"))))

;; ---------- desambiguacao: remessa inexistente -> nil (404) ----------

(deftest transicao-de-remessa-inexistente-devolve-nil
  (let [repo (:repo-compliance *sys*) ente (random-uuid) a (ator ente)]
    (is (nil? (controllers/validar-remessa repo a (random-uuid)))
        "id inexistente no tenant -> nil (handler -> 404), nao conflito")))

;; ---------- isolamento de tenant: remessa de OUTRO ente nao e' alcancavel ----------

(deftest transicao-respeita-o-tenant
  (let [repo (:repo-compliance *sys*) ente-a (random-uuid) ente-b (random-uuid)
        id (:id (gerar-rascunho! repo ente-a))]
    ;; o ator do ente-b nao enxerga a remessa do ente-a (RLS) -> nil (404), nunca conflito/sucesso.
    (is (nil? (controllers/validar-remessa repo (ator ente-b) id))
        "remessa de outro tenant e' invisivel -> nil (RLS isola; sem vazamento cross-tenant)")))
