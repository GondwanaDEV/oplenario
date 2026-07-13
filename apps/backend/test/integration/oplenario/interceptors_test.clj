(ns oplenario.interceptors-test
  "Onda D Slice 2 — Task 6 (`[REVISAO OPUS]`, SECURITY-CRITICAL): o interceptor `autenticacao` aceita sessao
  de COOKIE (login real) OU bearer token (dev-token/servico, mantido vivo). Testa `:enter` DIRETO com fakes
  (DB-free) — sem subir servidor HTTP: a precedencia cookie->bearer, o fail-closed em toda falha, e a prova
  de que um cookie invalido NUNCA cai silenciosamente pro bearer (mesmo com um Authorization presente)."
  (:require [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp :as idp]))

(defn- fake-idp
  "IdentityProvider fake. `verificar-token-fn` opcional — se omitida, CHAMAR estoura (prova que o path
  bearer nao foi consultado quando a precedencia de cookie deveria ter resolvido/negado primeiro)."
  ([] (fake-idp (fn [_] (throw (ex-info "verificar-token NAO deveria ter sido chamado (cookie tem precedencia)" {})))))
  ([verificar-token-fn]
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify idp/IdentityProvider
     (verificar-token [_ token] (verificar-token-fn token)))))

(defn- fake-repo
  "RepoIdentidade fake — so `resolver-sessao-por-segredo` e `snapshot-ator` importam ao interceptor de
  auth. Ausencia proposital de chave faz a chamada fora-de-ordem estourar (NPE), nao passar silenciosa."
  [{:keys [resolver-sessao-por-segredo snapshot-ator]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (resolver-sessao-por-segredo [_ segredo] (resolver-sessao-por-segredo segredo))
    (snapshot-ator [_ ente-id identidade-id] (snapshot-ator ente-id identidade-id))))

(defn- vinculo-ativo-fixture [] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{}})

(defn- enter! [idp repo request]
  ((:enter (it/autenticacao idp repo)) {:request request}))

(defn- erro-de [ctx]
  (:erro (json/read-value (get-in ctx [:response :body]) json/keyword-keys-object-mapper)))

(deftest cookie-valido-resolve-ator
  (let [ente (random-uuid) iid (random-uuid)
        repo (fake-repo {:resolver-sessao-por-segredo (fn [seg] (when (= "seg-bom" seg) {:identidade-id iid :ente-id ente}))
                          :snapshot-ator (fn [_ente _iid] (vinculo-ativo-fixture))})
        ;; idp SEM verificar-token-fn -- chamar estoura, prova que o path bearer nao foi consultado.
        ctx (enter! (fake-idp) repo {:headers {"cookie" (str "sessao=seg-bom")
                                                "authorization" "Bearer nunca-usado"}})]
    (is (some? (get-in ctx [:request :ator])) "cookie valido -> ator presente")
    (is (= iid (get-in ctx [:request :ator :identidade-id])))
    (is (= ente (get-in ctx [:request :ator :ente-id])))
    (is (nil? (:response ctx)) "sucesso -> sem resposta de negacao")))

(deftest cookie-invalido-401-nao-cai-pro-bearer
  (let [repo (fake-repo {:resolver-sessao-por-segredo (constantly nil)})
        ;; idp SEM verificar-token-fn -- se o fallback bearer fosse consultado, estouraria aqui.
        ctx (enter! (fake-idp) repo {:headers {"cookie" "sessao=seg-ruim"
                                                "authorization" "Bearer presente-mas-ignorado"}})]
    (is (= 401 (get-in ctx [:response :status])))
    (is (= "sessao invalida" (erro-de ctx))
        "cookie invalido nega direto -- nao cai silenciosamente pro bearer mesmo com Authorization presente")))

(deftest cookie-valido-vinculo-revogado-401
  (let [ente (random-uuid) iid (random-uuid)
        repo (fake-repo {:resolver-sessao-por-segredo (constantly {:identidade-id iid :ente-id ente})
                          :snapshot-ator (fn [_ente _iid] nil)})
        ctx (enter! (fake-idp) repo {:headers {"cookie" "sessao=seg-bom"}})]
    (is (= 401 (get-in ctx [:response :status])))
    (is (= "sem vinculo ativo" (erro-de ctx))
        "cookie resolve ids mas vinculo revogado (authz viva) -> 401")))

(deftest sem-cookie-bearer-valido-resolve-ator
  (let [ente (random-uuid) iid (random-uuid)
        idp (fake-idp (constantly {:identidade-id iid :ente-id ente}))
        repo (fake-repo {:snapshot-ator (fn [_ente _iid] (vinculo-ativo-fixture))})
        ctx (enter! idp repo {:headers {"authorization" "Bearer tok-bom"}})]
    (is (some? (get-in ctx [:request :ator])) "sem cookie, bearer valido -> ator (dev-token segue vivo)")
    (is (= iid (get-in ctx [:request :ator :identidade-id])))))

(deftest sem-cookie-sem-bearer-401
  (let [ctx (enter! (fake-idp) (fake-repo {}) {:headers {}})]
    (is (= 401 (get-in ctx [:response :status])))
    (is (= "sem credencial" (erro-de ctx)))))

(deftest bearer-token-invalido-401
  (let [idp (fake-idp (constantly nil))
        ctx (enter! idp (fake-repo {}) {:headers {"authorization" "Bearer tok-ruim"}})]
    (is (= 401 (get-in ctx [:response :status])))
    (is (= "token invalido" (erro-de ctx)))))

(deftest bearer-sem-vinculo-401
  (let [ente (random-uuid) iid (random-uuid)
        idp (fake-idp (constantly {:identidade-id iid :ente-id ente}))
        repo (fake-repo {:snapshot-ator (fn [_ente _iid] nil)})
        ctx (enter! idp repo {:headers {"authorization" "Bearer tok-bom"}})]
    (is (= 401 (get-in ctx [:response :status])))
    (is (= "sem vinculo ativo" (erro-de ctx)))))
