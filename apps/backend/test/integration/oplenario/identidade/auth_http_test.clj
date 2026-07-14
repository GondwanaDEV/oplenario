(ns oplenario.identidade.auth-http-test
  "Onda D Slice 2 — borda HTTP de identidade. Task 3: GET /auth/descoberta/:ente (descoberta PRE-login, ver
  docstring original abaixo). Task 4 (`[REVISAO OPUS]`, SECURITY-CRITICAL): POST /auth/sessoes (mint) — o
  BFF, apos completar o PKCE contra o Keycloak, POSTa o access token aqui; o backend RE-VERIFICA (nunca
  confia no BFF) e a cadeia INEGOCIAVEL e' verificar-token -> resolver-sessao -> criar-sessao!. Os 5 casos:
  token invalido -> 401 (criar-sessao! JAMAIS chamado); sem vinculo ativo -> 401; falha de INFRA do idp
  (LANCA) -> 500 (NUNCA 401 — mascarar degradacao como token ruim seria incorreto); sucesso -> 200 +
  {:sessao <segredo>}; e o teste-INVARIANTE — corpo com `:ente-id`/`:identidade-id` FORJADOS e' ignorado, a
  sessao minted usa SO o `ente-id`/`identidade-id` do claims VERIFICADO (o issuer), nunca o corpo HTTP.

  GET /auth/descoberta/:ente: prova a silhueta de borda end-to-end (`info-ente` seam injetada ->
  realm/base-url/client-id + nome PUBLICO do ente da config `:keycloak`), o 404 de ente inexistente
  (existencia = `(some? (info-ente id))`) e o 400 de UUID invalido. Rota PUBLICA (sem auth — e' descoberta
  PRE-login, o FE ainda nao tem token pra comecar o PKCE). DB-free: `info-ente`/`idp`/`repo-identidade` FAKE
  injetados via `montar` (mesmo racional/precedente de info-ente-http-in-test/mesa-http-in-test — seam
  cross-modulo por inversao de dependencia, §22.10)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(defn- service-fn
  "`info-ente` default permissivo (so as rotas de descoberta usam) — devolve um ente-map fixture (nunca nil,
  p/ os testes que nao exercitam o 404 passarem sem stub); `idp` default idp-dev (confianca-total, so' as
  rotas de mint fazem o fake substituir); `repo-identidade` default nil (as rotas de descoberta nao o
  tocam)."
  [{:keys [info-ente idp repo-identidade]
    :or   {info-ente (constantly {:nome-oficial "Câmara de Teste" :nome-curto "Câmara"})
           idp       (idp-dev/idp-dev)}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp idp
                                   :repo-identidade repo-identidade
                                   :info-ente info-ente})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ========================= GET /auth/descoberta/:ente (Task 3) =========================

(deftest descoberta-200-resolve-realm-base-url-client-id
  (let [ente (random-uuid)
        chamou-com (atom nil)
        info-ente (fn [eid] (reset! chamou-com eid) {:nome-oficial "X" :nome-curto "X"})
        r (pt/response-for (service-fn {:info-ente info-ente}) :get (str "/auth/descoberta/" ente))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /auth/descoberta/:ente (sem auth — descoberta pre-login) -> 200")
    (is (= ente @chamou-com) "info-ente foi chamada com o ente-id resolvido do path")
    (is (= (str ente) (:ente-id body)))
    (is (= (str "ente-" ente) (:realm body)) "realm = realm-prefixo da config + ente-id")
    (is (= "http://localhost:8090" (:base-url body)) "base-url PUBLICO da config (nao o :base-url interno)")
    (is (= "oplenario-web" (:client-id body)) "client-id PUBLICO da config")))

(deftest descoberta-200-inclui-nome-publico-do-ente
  (let [info-ente (constantly {:nome-oficial "Câmara Municipal de Fortaleza" :nome-curto "Câmara de Fortaleza"})
        r (pt/response-for (service-fn {:info-ente info-ente}) :get (str "/auth/descoberta/" (random-uuid)))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "Câmara Municipal de Fortaleza" (:nome-oficial body))
        "nome-oficial do info-ente stubado aparece na resposta de descoberta")
    (is (= "Câmara de Fortaleza" (:nome-curto body))
        "nome-curto do info-ente stubado aparece na resposta de descoberta")))

(deftest descoberta-404-quando-ente-nao-existe
  (let [r (pt/response-for (service-fn {:info-ente (constantly nil)}) :get (str "/auth/descoberta/" (random-uuid)))
        body (ler-json r)]
    (is (= 404 (:status r)) "ente inexistente -> 404 fail-closed (nunca vaza realm de tenant que nao existe)")
    (is (= "ente nao encontrado" (:erro body)))))

(deftest descoberta-400-quando-ente-nao-e-uuid
  (let [r (pt/response-for (service-fn {}) :get "/auth/descoberta/nao-e-um-uuid")]
    (is (= 400 (:status r)) "path-param que nao coage a UUID -> 400 fail-closed (mesmo seam das demais rotas publicas)")))

(deftest descoberta-nao-exige-auth
  ;; e' descoberta PRE-login (o FE ainda nao tem token) — sem header Authorization, nunca 401.
  (let [r (pt/response-for (service-fn {}) :get (str "/auth/descoberta/" (random-uuid)))]
    (is (not= 401 (:status r)))))

;; ========================= POST /auth/sessoes (Task 4 — mint) =========================

(defn- fake-idp
  "IdentityProvider fake — SO `verificar-token` importa aqui (as demais operacoes de provisionamento nao
  sao exercitadas por esta borda)."
  [verificar-token-fn]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify idp/IdentityProvider
    (verificar-token [_ token] (verificar-token-fn token))))

(defn- fake-repo-identidade
  "RepoIdentidade fake — SO `snapshot-ator` (usado por `resolver-sessao`), `criar-sessao!` e `apagar-sessao!`
  (Task 5 — logout) importam aqui. A AUSENCIA proposital de uma chave faz a chamada fora-de-ordem estourar
  (NPE/CCE) em vez de passar silenciosamente — sinaliza regressao de ordem na cadeia de confianca (mesmo
  racional de expediente-http-in-test/fake-repo-legislativo)."
  [{:keys [snapshot-ator criar-sessao! apagar-sessao!]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ ente-id identidade-id] (snapshot-ator ente-id identidade-id))
    (criar-sessao! [_ sessao] (criar-sessao! sessao))
    (apagar-sessao! [_ segredo] (apagar-sessao! segredo))))

(defn- vinculo-ativo-fixture [] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{}})

(deftest token-invalido-401
  (let [chamou-criar (atom false)
        idp (fake-idp (constantly nil))
        repo (fake-repo-identidade {:criar-sessao! (fn [_] (reset! chamou-criar true) "nunca-devolvido")})
        r (pt/response-for (service-fn {:idp idp :repo-identidade repo})
                           :post "/auth/sessoes"
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-value-as-string {:token "qualquer"}))]
    (is (= 401 (:status r)) "verificar-token nil -> 401")
    (is (= "token invalido" (:erro (ler-json r))))
    (is (false? @chamou-criar) "criar-sessao! NUNCA chamado quando o token e' invalido")))

(deftest sem-vinculo-401
  (let [ente (random-uuid) iid (random-uuid)
        chamou-criar (atom false)
        idp (fake-idp (constantly {:identidade-id iid :ente-id ente}))
        repo (fake-repo-identidade {:snapshot-ator (fn [_ente _iid] nil)
                                    :criar-sessao! (fn [_] (reset! chamou-criar true) "nunca-devolvido")})
        r (pt/response-for (service-fn {:idp idp :repo-identidade repo})
                           :post "/auth/sessoes"
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-value-as-string {:token "tok-verificavel"}))]
    (is (= 401 (:status r)) "claims verificadas, mas sem vinculo ATIVO -> 401")
    (is (= "sem vinculo ativo" (:erro (ler-json r))))
    (is (false? @chamou-criar) "criar-sessao! NUNCA chamado sem vinculo ativo")))

(deftest infra-propaga-500
  (let [idp (fake-idp (fn [_] (throw (ex-info "JWKS indisponivel" {}))))
        repo (fake-repo-identidade {})
        r (pt/response-for (service-fn {:idp idp :repo-identidade repo})
                           :post "/auth/sessoes"
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-value-as-string {:token "tok-verificavel"}))]
    (is (= 500 (:status r))
        "falha de INFRA do idp (rede/JWKS) PROPAGA (nao e' catch->401) -- o interceptor global `erro` mapeia p/ 500")
    (is (not= 401 (:status r)) "NUNCA mascara falha de infra como token invalido")))

(deftest happy-cria-sessao
  (let [ente (random-uuid) iid (random-uuid)
        recebido (atom nil)
        idp (fake-idp (constantly {:identidade-id iid :ente-id ente}))
        repo (fake-repo-identidade
              {:snapshot-ator (fn [_ente _iid] (vinculo-ativo-fixture))
               :criar-sessao! (fn [m] (reset! recebido m) "segredo-cru-fixture")})
        r (pt/response-for (service-fn {:idp idp :repo-identidade repo})
                           :post "/auth/sessoes"
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-value-as-string {:token "tok-verificavel"}))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "segredo-cru-fixture" (:sessao body)) "o corpo devolve o SEGREDO CRU (o BFF poe no cookie httpOnly)")
    (is (= iid (:identidade-id @recebido)) "criar-sessao! recebeu identidade-id do claims VERIFICADO")
    (is (= ente (:ente-id @recebido)) "criar-sessao! recebeu ente-id do claims VERIFICADO")
    (is (some? (:expira-em @recebido)) "teto absoluto computado")
    (is (some? (:ocioso-ate @recebido)) "janela de ociosidade inicial computada")
    (is (.isAfter (:expira-em @recebido) (:ocioso-ate @recebido))
        "o teto absoluto (12h default) e' mais distante que a janela de ociosidade inicial (30min default)")))

(deftest ente-do-issuer-nao-do-corpo
  ;; O TESTE-INVARIANTE (Task 4): o corpo tenta forjar :ente-id/:identidade-id — DEVEM ser ignorados. A
  ;; sessao minted usa SO os valores do claims VERIFICADO (o issuer), nunca o que o cliente mandou no corpo.
  (let [ente-real (random-uuid) iid-real (random-uuid)
        ente-forjado (random-uuid) iid-forjado (random-uuid)
        recebido (atom nil)
        idp (fake-idp (constantly {:identidade-id iid-real :ente-id ente-real}))
        repo (fake-repo-identidade
              {:snapshot-ator (fn [_ente _iid] (vinculo-ativo-fixture))
               :criar-sessao! (fn [m] (reset! recebido m) "segredo-cru-fixture")})
        r (pt/response-for (service-fn {:idp idp :repo-identidade repo})
                           :post "/auth/sessoes"
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-value-as-string
                                   {:token "tok-verificavel"
                                    :ente-id (str ente-forjado)
                                    :identidade-id (str iid-forjado)}))]
    (is (= 200 (:status r)) "corpo com campos extras/forjados nao quebra a requisicao (allowlist descarta)")
    (is (= iid-real (:identidade-id @recebido)) "identidade-id da sessao = claims VERIFICADO, nao o corpo")
    (is (= ente-real (:ente-id @recebido)) "ente-id da sessao = ISSUER verificado, nao o corpo")
    (is (not= iid-forjado (:identidade-id @recebido)) "o identidade-id FORJADO no corpo nunca alcancou a sessao")
    (is (not= ente-forjado (:ente-id @recebido)) "o ente-id FORJADO no corpo nunca alcancou a sessao")))

;; ========================= DELETE /auth/sessoes (Task 5 — logout) =========================
;; Rota PUBLICA (decisao desta sessao: T6/interceptor de cookie ainda nao existe, ver docstring do ns) — o
;; handler le o cookie `sessao` DIRETO via `it/cookie-sessao` e chama `apagar-sessao!` incondicional.
;; Idempotente por desenho: 204 sempre, com ou sem cookie — logout de uma sessao inexistente e' sucesso.

(deftest logout-com-cookie-apaga-e-204
  (let [segredo "segredo-cru-do-cookie"
        chamou-com (atom nil)
        repo (fake-repo-identidade {:apagar-sessao! (fn [s] (reset! chamou-com s))})
        r (pt/response-for (service-fn {:repo-identidade repo})
                           :delete "/auth/sessoes"
                           :headers {"cookie" (str "sessao=" segredo)})]
    (is (= 204 (:status r)) "cookie presente -> apaga e devolve 204")
    (is (= segredo @chamou-com) "apagar-sessao! foi chamado com o SEGREDO CRU exato do cookie")))

(deftest logout-sem-cookie-204-idempotente
  (let [chamou (atom false)
        repo (fake-repo-identidade {:apagar-sessao! (fn [_] (reset! chamou true))})
        r (pt/response-for (service-fn {:repo-identidade repo})
                           :delete "/auth/sessoes")]
    (is (= 204 (:status r)) "sem cookie -> 204 tambem (idempotente; NUNCA 401 -- deslogar de nada e' sucesso)")
    (is (not= 401 (:status r)))
    (is (false? @chamou) "apagar-sessao! NUNCA chamado quando nao ha cookie -- nada pra apagar")))

(deftest logout-cookie-entre-outros-apaga-so-o-certo
  ;; Prova end-to-end que a rota reusa o parsing multi-cookie de `it/cookie-sessao` (nao um regex ad-hoc
  ;; local) -- mesmo cenario da unit de baixo, mas exercitado pela borda HTTP inteira.
  (let [segredo "abc123"
        chamou-com (atom nil)
        repo (fake-repo-identidade {:apagar-sessao! (fn [s] (reset! chamou-com s))})
        r (pt/response-for (service-fn {:repo-identidade repo})
                           :delete "/auth/sessoes"
                           :headers {"cookie" (str "outra=xyz; sessao=" segredo "; terceira=qqq")})]
    (is (= 204 (:status r)))
    (is (= segredo @chamou-com) "so o par sessao=... foi extraido, mesmo cercado de outros cookies")))

;; ========================= `it/cookie-sessao` (helper puro, unit) =========================
;; Vive em `oplenario.interceptors` (home cross-cutting do host — ver docstring la'), reusavel por esta rota
;; (Task 5) e pelo futuro interceptor de cookie (Task 6). Testado aqui porque e' o consumidor atual; a T6
;; podera' escrever units proprias tambem quando o interceptor existir.

(deftest cookie-sessao-extrai-de-header-com-um-cookie-so
  (is (= "abc" (it/cookie-sessao {:headers {"cookie" "sessao=abc"}}))))

(deftest cookie-sessao-extrai-de-header-multi-cookie-sessao-primeiro
  (is (= "abc" (it/cookie-sessao {:headers {"cookie" "sessao=abc; outra=xyz"}}))))

(deftest cookie-sessao-extrai-de-header-multi-cookie-sessao-por-ultimo
  (is (= "abc" (it/cookie-sessao {:headers {"cookie" "outra=xyz; sessao=abc"}}))))

(deftest cookie-sessao-ignora-espacos-entre-pares
  (is (= "abc" (it/cookie-sessao {:headers {"cookie" "outra=xyz;   sessao=abc  ;   terceira=qqq"}}))))

(deftest cookie-sessao-nil-quando-sessao-ausente
  (is (nil? (it/cookie-sessao {:headers {"cookie" "outra=xyz; terceira=qqq"}}))))

(deftest cookie-sessao-nil-quando-header-ausente
  (is (nil? (it/cookie-sessao {:headers {}}))))

(deftest cookie-sessao-nil-quando-valor-vazio
  (is (nil? (it/cookie-sessao {:headers {"cookie" "sessao="}}))))

(deftest cookie-sessao-nao-confunde-cookie-de-nome-parecido
  ;; "outra-sessao=xyz" NAO deve ser lido como o par "sessao=" (prefixo estrito, nao substring).
  (is (nil? (it/cookie-sessao {:headers {"cookie" "outra-sessao=xyz"}}))))
