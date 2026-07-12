(ns oplenario.identidade.auth-http-test
  "Onda D Slice 2 Task 3 (borda HTTP de identidade) — GET /auth/descoberta/:ente: prova a silhueta de borda
  end-to-end (`ente-existe?` seam injetada -> realm/base-url/client-id da config `:keycloak`), o 404 de ente
  inexistente e o 400 de UUID invalido. Rota PUBLICA (sem auth — e' descoberta PRE-login, o FE ainda nao tem
  token pra comecar o PKCE). DB-free: `ente-existe?` FAKE injetada via `montar` (mesmo racional/precedente de
  info-ente-http-in-test/mesa-http-in-test — seam cross-modulo por inversao de dependencia, §22.10)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(defn- service-fn [ente-existe?]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade nil
                                   :ente-existe? ente-existe?})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest descoberta-200-resolve-realm-base-url-client-id
  (let [ente (random-uuid)
        chamou-com (atom nil)
        ente-existe? (fn [eid] (reset! chamou-com eid) true)
        r (pt/response-for (service-fn ente-existe?) :get (str "/auth/descoberta/" ente))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /auth/descoberta/:ente (sem auth — descoberta pre-login) -> 200")
    (is (= ente @chamou-com) "ente-existe? foi chamada com o ente-id resolvido do path")
    (is (= (str ente) (:ente-id body)))
    (is (= (str "ente-" ente) (:realm body)) "realm = realm-prefixo da config + ente-id")
    (is (= "http://localhost:8090" (:base-url body)) "base-url PUBLICO da config (nao o :base-url interno)")
    (is (= "oplenario-web" (:client-id body)) "client-id PUBLICO da config")))

(deftest descoberta-404-quando-ente-nao-existe
  (let [r (pt/response-for (service-fn (constantly false)) :get (str "/auth/descoberta/" (random-uuid)))
        body (ler-json r)]
    (is (= 404 (:status r)) "ente inexistente -> 404 fail-closed (nunca vaza realm de tenant que nao existe)")
    (is (= "ente nao encontrado" (:erro body)))))

(deftest descoberta-400-quando-ente-nao-e-uuid
  (let [r (pt/response-for (service-fn (constantly true)) :get "/auth/descoberta/nao-e-um-uuid")]
    (is (= 400 (:status r)) "path-param que nao coage a UUID -> 400 fail-closed (mesmo seam das demais rotas publicas)")))

(deftest descoberta-nao-exige-auth
  ;; e' descoberta PRE-login (o FE ainda nao tem token) — sem header Authorization, nunca 401.
  (let [r (pt/response-for (service-fn (constantly true)) :get (str "/auth/descoberta/" (random-uuid)))]
    (is (not= 401 (:status r)))))
