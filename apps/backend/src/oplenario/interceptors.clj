(ns oplenario.interceptors
  "Interceptors do host (§22.5 — a borda de autorizacao). AUTENTICACAO: bearer token -> idp/verificar-token ->
  identidade/resolver-sessao -> `ator` em (:request :ator), fail-closed (401). AUTORIZACAO GROSSA: exige-papel
  (papel estatico do snapshot) -> 403 via kernel/autorizacao (que lanca negado?). ERRO: mapeia a negacao de
  autorizacao -> 403; o resto -> 500. A camada FINA (policy.check in-domain) roda nos controllers (W3+), com o
  recurso carregado. (§22.5: authz avaliada na borda; a tenancy [GUC app.ente_id] e' por-tx no Repo.)"
  (:require [clojure.string :as str]
            [io.pedestal.interceptor.chain :as chain]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.idp :as idp]))

(set! *warn-on-reflection* true)

(defn- bearer [req]
  (let [h (get-in req [:headers "authorization"])]
    (when (and h (str/starts-with? h "Bearer ")) (subs h 7))))

(defn- nega! [ctx status razao]
  (chain/terminate (assoc ctx :response (http/json-resposta status {:erro razao}))))

(defn autenticacao
  "Interceptor de AUTENTICACAO (§22.5 eixo D). Sem token / token invalido / sem vinculo ativo -> 401 + termina
  (fail-closed). Sucesso -> `ator` em (:request :ator) p/ os interceptors/handlers seguintes."
  [idp repo-identidade]
  {:name  ::autenticacao
   :enter (fn [ctx]
            (if-let [tok (bearer (:request ctx))]
              (if-let [claims (idp/verificar-token idp tok)]
                (if-let [ator (auten/resolver-sessao repo-identidade claims)]
                  (assoc-in ctx [:request :ator] ator)
                  (nega! ctx 401 "sem vinculo ativo"))
                (nega! ctx 401 "token invalido"))
              (nega! ctx 401 "token ausente")))})

(defn exige-papel
  "Interceptor de AUTORIZACAO GROSSA: exige o `papel` estatico (STRING — os papeis do snapshot sao strings) no
  ator. Falta -> authz lanca negado? -> o interceptor `erro` mapeia p/ 403. Pressupoe `autenticacao` antes."
  [papel]
  {:name  (keyword "oplenario.interceptors" (str "exige-papel--" papel))
   :enter (fn [ctx]
            (authz/exige-papel! (get-in ctx [:request :ator]) papel)
            ctx)})

(defn- raiz
  "Desembrulha a excecao que o Pedestal repassa ao :error: o original que a interceptor-chain pegou vem como
  CAUSE do wrapper (Pedestal 0.7); algumas versoes expoem em (:exception ex-data). Checa ambos + o proprio ex."
  [ex]
  (or (:exception (ex-data ex)) (ex-cause ex) ex))

(def erro
  "Interceptor de ERRO (GLOBAL/outermost via http/servico): negacao de autorizacao -> 403; resto -> 500. Nao
  vaza detalhe de erro interno no corpo."
  {:name  ::erro
   :error (fn [ctx ex]
            (if (or (authz/negado? ex) (authz/negado? (raiz ex)))
              (assoc ctx :response (http/json-resposta 403 {:erro "autorizacao negada"}))
              (assoc ctx :response (http/json-resposta 500 {:erro "erro interno"}))))})

(def cabecalhos-seguranca
  "Interceptor de cabecalhos de seguranca (review W2): no-store (respostas de auth nao cacheiam em proxy/browser)
  + nosniff + DENY de frame. HSTS/CSP ficam no reverse-proxy (API JSON)."
  {:name  ::cabecalhos-seguranca
   :leave (fn [ctx]
            (cond-> ctx
              (:response ctx) (update-in [:response :headers] merge
                                         {"X-Content-Type-Options" "nosniff"
                                          "X-Frame-Options"        "DENY"
                                          "Cache-Control"          "no-store"})))})

(def globais
  "Interceptors OUTERMOST de TODA rota (prepend em http/servico): cabecalhos (leave por ultimo, cobre ate erros)
  + erro (envolve toda a cadeia). W3: rota nova herda isto automaticamente — nao depende de lembrar por rota."
  [cabecalhos-seguranca erro])
