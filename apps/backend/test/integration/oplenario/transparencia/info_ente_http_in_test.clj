(ns oplenario.transparencia.info-ente-http-in-test
  "F.A2 fast-follow (borda HTTP do portal) — GET /portal/casa/:ente: prova a silhueta de borda end-to-end
  (info-ente injetada -> adapters/out -> wire/out), o 404 de ente inexistente e o 400 de UUID invalido.
  DB-free: `info-ente` FAKE injetada via `montar` (mesmo racional/precedente de mesa_http_in_test — os 4
  cards cross-modulo do dashboard da Mesa)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(defn- service-fn [info-ente]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade nil
                                   :info-ente info-ente})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest info-ente-200-projeta-nome-oficial-e-curto
  (let [ente (random-uuid)
        chamou-com (atom nil)
        info-ente (fn [eid] (reset! chamou-com eid)
                    {:ente-id eid :nome-oficial "Câmara Municipal de Fortaleza"
                     :nome-curto "Câmara de Fortaleza"})
        r (pt/response-for (service-fn info-ente) :get (str "/portal/casa/" ente))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /portal/casa/:ente (sem auth — portal publico) -> 200")
    (is (= ente @chamou-com) "info-ente foi chamada com o ente-id resolvido do path")
    (is (= "Câmara Municipal de Fortaleza" (:nome-oficial body)))
    (is (= "Câmara de Fortaleza" (:nome-curto body)))
    (is (not (contains? body :ente-id)) "ente-id (interno) nao vaza no wire")))

(deftest info-ente-404-quando-ente-sem-perfil
  (let [r (pt/response-for (service-fn (constantly nil)) :get (str "/portal/casa/" (random-uuid)))]
    (is (= 404 (:status r)) "sem perfil cadastrado -> 404 (nunca devolve o UUID como se fosse nome)")))

(deftest info-ente-400-quando-ente-nao-e-uuid
  (let [r (pt/response-for (service-fn (constantly nil)) :get "/portal/casa/nao-e-um-uuid")]
    (is (= 400 (:status r)) "path-param que nao coage a UUID -> 400 fail-closed (mesmo seam das demais rotas publicas)")))

(deftest info-ente-nao-exige-auth
  ;; review seg/arquitetura: e' rota do PORTAL PUBLICO (mesmo perfil de listar-materias/ficha-materia) — sem
  ;; header Authorization, nunca 401.
  (let [r (pt/response-for (service-fn (constantly {:nome-oficial "Câmara X"})) :get (str "/portal/casa/" (random-uuid)))]
    (is (not= 401 (:status r)))))
