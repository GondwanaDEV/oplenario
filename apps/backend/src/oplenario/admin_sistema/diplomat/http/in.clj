(ns oplenario.admin-sistema.diplomat.http.in
  "Rotas do CONSOLE DO OPERADOR (ADR-0016) — SUPRATENANT: o ator nao tem Casa, e o interceptor
  `it/autenticacao-operador` recusa qualquer credencial de Casa (cross-esfera = 401, sem fail-open). O prefixo
  `/operacao` separa as duas esferas tambem na URL.

  O login espelha o da Casa (`identidade/diplomat/http/auth_in`): descoberta publica -> PKCE no BFF -> o token
  e' RE-VERIFICADO aqui (nunca se confia no BFF) -> sessao opaca em `admin_sistema.sessao_operador`."
  (:require [clojure.string :as str]
            [oplenario.admin-sistema.autenticacao :as auten]
            [oplenario.admin-sistema.components.idp-admin :as idp-op]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Duration Instant)))

(defn- descoberta-handler
  "GET /operacao/descoberta — o que o BFF precisa para comecar o PKCE no realm do operador (URL publica)."
  [{:keys [realm base-url base-url-publico client-id]}]
  (fn [_]
    (http/json-resposta 200 {:realm realm :base-url (if (str/blank? base-url-publico) base-url base-url-publico)
                             :client-id client-id})))

(defn- corpo->token [json-params]
  (let [token (when (map? json-params) (get json-params "token"))]
    (when-not (and (string? token) (not (str/blank? token)) (<= (count token) 16384))
      (throw (ex-info "token ausente ou invalido" {:tipo :validacao/invalido :campo :token})))
    token))

(defn- mint-handler
  "POST /operacao/sessoes — token do realm do operador -> operador ATIVO -> sessao opaca. A entrada fica na
  atuacao (retencao maxima, 12.5)."
  [idp repo-op relogio {:keys [absoluta-h ociosa-min]}]
  (fn [req]
    (let [token (corpo->token (:json-params req))]
      (if-let [claims (idp-op/verificar-token-operador idp token)]
        (if-let [ator (auten/ator-do-operador repo-op (:operador-id claims))]
          (let [^Instant agora (tempo/agora relogio)
                seg (repo/criar-sessao-operador! repo-op {:operador-id (:operador-id ator)
                                                          :expira-em (.plus agora (Duration/ofHours absoluta-h))
                                                          :ocioso-ate (.plus agora (Duration/ofMinutes ociosa-min))})]
            (repo/registrar-atuacao! repo-op {:operador-id (:operador-id ator) :acao "entrou-no-console"})
            (http/json-resposta 200 {:sessao seg}))
          (http/json-resposta 401 {:erro "operador inativo"}))
        (http/json-resposta 401 {:erro "token invalido"})))))

(defn- logout-handler [repo-op]
  (fn [req]
    (when-let [seg (it/cookie req it/nome-cookie-operacao)]
      (repo/apagar-sessao-operador! repo-op seg))
    {:status 204 :headers {} :body nil}))

(defn- eu-handler [req]
  (let [{:keys [operador-id nome email papeis]} (:ator req)]
    (http/json-resposta 200 {:operador {:id (str operador-id) :nome nome :email email :papeis (vec (sort papeis))}})))

(defn rotas
  "`operacao` = o mapa `:operacao` da config, ja' resolvido pelo host."
  [{:keys [idp-operacao repo-admin-sistema relogio operacao]}]
  (let [auth (it/autenticacao-operador idp-operacao repo-admin-sistema)]
    #{["/operacao/descoberta" :get [(descoberta-handler operacao)] :route-name :admin-sistema/descoberta]
      ["/operacao/sessoes" :post [it/corpo-json (mint-handler idp-operacao repo-admin-sistema relogio (:sessao operacao))]
       :route-name :admin-sistema/mint-sessao]
      ["/operacao/sessoes" :delete [(logout-handler repo-admin-sistema)] :route-name :admin-sistema/logout-sessao]
      ["/operacao/eu" :get [auth eu-handler] :route-name :admin-sistema/eu]}))
