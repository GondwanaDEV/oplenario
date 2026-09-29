(ns oplenario.admin-sistema.diplomat.http.in
  "Rotas do CONSOLE DO OPERADOR (ADR-0016) — SUPRATENANT: o ator nao tem Casa, e o interceptor
  `it/autenticacao-operador` recusa qualquer credencial de Casa (cross-esfera = 401, sem fail-open). O prefixo
  `/operacao` separa as duas esferas tambem na URL.

  O login espelha o da Casa (`identidade/diplomat/http/auth_in`): descoberta publica -> PKCE no BFF -> o token
  e' RE-VERIFICADO aqui (nunca se confia no BFF) -> sessao opaca em `admin_sistema.sessao_operador`."
  (:require [clojure.string :as str]
            [oplenario.admin-sistema.adapters.in.ente :as in-ente]
            [oplenario.admin-sistema.adapters.out.ente :as out-ente]
            [oplenario.admin-sistema.adapters.out.ia :as out-ia]
            [oplenario.admin-sistema.autenticacao :as auten]
            [oplenario.admin-sistema.components.idp-admin :as idp-op]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.controllers :as controllers]
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

;; ---- registro de Casas (12.1) ----

(defn- com-erros
  "Os erros do registro que viram resposta: Casa inexistente -> 404; estado que nao permite -> 409 (com o porque)."
  [f]
  (fn [req]
    (try (f req)
         (catch clojure.lang.ExceptionInfo e
           (case (:tipo (ex-data e))
             :admin-sistema/nao-encontrada (http/json-resposta 404 {:erro "Casa nao encontrada"})
             :admin-sistema/conflito (http/json-resposta 409 {:erro (ex-message e)})
             (throw e))))))

(defn- ente-do-path [req] (in-ente/ente-param->uuid (get-in req [:path-params :ente])))

(defn- listar-handler [repo-op]
  (fn [_] (http/json-resposta 200 (out-ente/lista->wire (repo/listar-casas repo-op)))))

(defn- provisionar-handler [repo-op deps]
  (fn [req]
    (let [casa (in-ente/provisionar->dominio (:json-params req))]
      (http/json-resposta 201 (out-ente/provisionada->wire
                               (controllers/provisionar-casa! repo-op deps (:ator req) casa))))))

(defn- ficha-handler [repo-op deps]
  (com-erros (fn [req] (http/json-resposta 200 (out-ente/ficha->wire
                                                (controllers/ficha-da-casa repo-op deps (ente-do-path req)))))))

(defn- reenviar-convite-handler [repo-op deps]
  (com-erros (fn [req] (http/json-resposta 200 (out-ente/casa->wire
                                                (controllers/reenviar-convite! repo-op deps (:ator req)
                                                                               (ente-do-path req)))))))

(defn- reprovisionar-realm-handler [repo-op deps]
  (com-erros (fn [req] (http/json-resposta 200 (out-ente/casa->wire
                                                (controllers/reprovisionar-realm! repo-op deps (:ator req)
                                                                                  (ente-do-path req)))))))

;; ---- observabilidade da IA (Onda E, §22.8) ----

(def ^:private janelas-ia
  "As janelas que a tela oferece (o satelite aceita 1-168; o console so' pede estas)."
  #{24 168})

(defn- horas-param [s]
  (let [h (if (str/blank? s) 24 (parse-long s))]
    (when-not (contains? janelas-ia h)
      (throw (ex-info "horas deve ser 24 ou 168" {:tipo :validacao/invalido :campo :horas})))
    h))

(defn- observabilidade-ia-handler
  "GET /operacao/ia?horas=24|168 — a saude da IA em todas as Casas. IA fora (ou nao ligada) nao e' 500: a resposta diz
  `disponivel: false` e a tela avisa (R-IA-1)."
  [observabilidade-ia]
  (fn [req]
    (let [horas (horas-param (get-in req [:query-params :horas]))
          o (when observabilidade-ia
              (try (observabilidade-ia horas)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :ia/indisponivel (:tipo (ex-data e))) nil (throw e)))))]
      (http/json-resposta 200 (out-ia/observabilidade->wire horas o)))))

(defn rotas
  "`operacao` = o mapa `:operacao` da config, ja' resolvido pelo host. `deps-registro` = os seams que o host injeta
  para o provisionamento cruzar cadastros/identidade/IdP das Casas sem import (§22.10). `observabilidade-ia` =
  (horas -> mapa do satelite; lanca `:ia/indisponivel`), o seam do host para a IA (admin_sistema nao importa
  integracao_ia)."
  [{:keys [idp-operacao repo-admin-sistema relogio operacao deps-registro observabilidade-ia]}]
  (let [auth (it/autenticacao-operador idp-operacao repo-admin-sistema)
        papel (it/exige-papel "operador")]
    #{["/operacao/descoberta" :get [(descoberta-handler operacao)] :route-name :admin-sistema/descoberta]
      ["/operacao/sessoes" :post [it/corpo-json (mint-handler idp-operacao repo-admin-sistema relogio (:sessao operacao))]
       :route-name :admin-sistema/mint-sessao]
      ["/operacao/sessoes" :delete [(logout-handler repo-admin-sistema)] :route-name :admin-sistema/logout-sessao]
      ["/operacao/eu" :get [auth eu-handler] :route-name :admin-sistema/eu]
      ["/operacao/casas" :get [auth papel (listar-handler repo-admin-sistema)] :route-name :admin-sistema/listar-casas]
      ["/operacao/casas" :post [auth papel it/corpo-json (provisionar-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/provisionar-casa]
      ["/operacao/casas/:ente" :get [auth papel (ficha-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/ficha-da-casa]
      ["/operacao/casas/:ente/convite" :post [auth papel (reenviar-convite-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/reenviar-convite]
      ["/operacao/casas/:ente/realm" :post [auth papel (reprovisionar-realm-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/reprovisionar-realm]
      ["/operacao/ia" :get [auth papel (observabilidade-ia-handler observabilidade-ia)]
       :route-name :admin-sistema/observabilidade-ia]}))
