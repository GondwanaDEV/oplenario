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

;; ---------- o interceptor de ERRO: teto de capacidade -> 422 com o numero MEDIDO ----------
;; Etapa 6 fatia 1 (revisao adversarial). `:limite/datas-excedido` era lancado fail-closed pelo repo e
;; caia no ramo `:else` do interceptor: 500 opaco, corpo `{"erro":"erro interno"}`. O I7 do brief da
;; Etapa 6 exige o oposto — "estourou teto -> 422 com o numero medido, nunca uma pagina parcial parecendo
;; total" — e o operador que pediu um periodo grande demais nao tem como corrigir o pedido sem saber
;; quanto pediu e qual e' o limite.

(defn- corpo-de [ctx]
  (json/read-value (get-in ctx [:response :body]) json/keyword-keys-object-mapper))

(defn- erro! [ex] ((:error it/erro) {} ex))

(deftest limite-excedido-vira-422-com-medido-e-teto
  (let [ctx (erro! (ex-info "datas demais" {:tipo :limite/datas-excedido :medido 367 :teto 366}))]
    (is (= 422 (get-in ctx [:response :status]))
        "teto de capacidade nao e' 400 (o pedido esta' bem formado) nem 500 (nao e' bug): e' 422")
    (is (= {:erro "limite excedido" :medido 367 :teto 366} (corpo-de ctx))
        "o numero MEDIDO e o TETO saem no corpo — sem eles o operador so' tem tentativa e erro")))

(deftest limite-com-medicao-de-piso-marca-que-e-piso
  ;; `:limite/linhas-excedido` mede via `:max-rows` = teto+1: sabemos que passou, nao por quanto. O corpo
  ;; nao pode apresentar teto+1 como se fosse a contagem exata.
  (let [ctx (erro! (ex-info "linhas demais" {:tipo :limite/linhas-excedido
                                             :medido-ao-menos 54901 :teto 54900 :datas 366}))]
    (is (= 422 (get-in ctx [:response :status])))
    (is (= {:erro "limite excedido" :medido 54901 :teto 54900 :medido-e-piso true} (corpo-de ctx)))
    (is (nil? (:datas (corpo-de ctx)))
        "so' :medido/:teto atravessam — o resto da ex-data fica no log, como nos demais ramos")))

(deftest limite-embrulhado-pelo-pedestal-tambem-vira-422
  ;; O Pedestal entrega ao :error um WRAPPER cuja causa e' a excecao original (mesma razao de existir do
  ;; `raiz`). Sem olhar a raiz, o mapeamento so' funcionaria em teste e nunca em producao.
  (let [dentro (ex-info "datas demais" {:tipo :limite/datas-excedido :medido 500 :teto 366})
        ctx (erro! (ex-info "wrapper do pedestal" {:exception dentro} dentro))]
    (is (= 422 (get-in ctx [:response :status])))
    (is (= 500 (:medido (corpo-de ctx))))))

(deftest tipo-fora-do-namespace-limite-continua-500
  ;; O reconhecimento e' por NAMESPACE `limite`; nada mais pode escorregar para o 422 (que carrega numeros
  ;; no corpo) so' por ter `:medido`/`:teto` na ex-data.
  (let [ctx (erro! (ex-info "bug" {:tipo :conflito/qualquer :medido 9 :teto 1}))]
    (is (= 500 (get-in ctx [:response :status])))
    (is (= {:erro "erro interno"} (corpo-de ctx)) "nenhum numero vaza pelo ramo de 500"))
  (let [ctx (erro! (ex-info "sem tipo" {}))]
    (is (= 500 (get-in ctx [:response :status])) "ex-data sem :tipo nao estoura no `namespace`")))

;; ---------- o interceptor de ERRO: config do TENANT -> 409 NOMEADO ----------
;; Fatia 1 da borda de tramitacao (decisao 1-A). `db/proposicao/resolver-rito!` recusa quando a Casa tem
;; dois ritos ativos para a MESMA especie — e a recusa caia no `:else`: 500 opaco em `POST
;; /legislativo/proposicoes`, indistinguivel de um bug do servidor. O operador da Casa e' quem pode
;; consertar (a config e' dado DELE, Inv.4), e nao tinha como saber disso nem qual especie olhar.

(deftest config-do-tenant-vira-409-nomeado-com-a-especie
  (let [ctx (erro! (ex-info "dois ritos" {:tipo :config/rito-ambiguo-na-especie
                                          :especie "projeto_lei" :ente-id (random-uuid)}))]
    (is (= 409 (get-in ctx [:response :status]))
        "config da Casa nao e' 400 (o corpo estava certo), nem 422 (nao e' teto), nem 500 (o operador conserta)")
    (is (= {:erro "a configuracao desta Casa impede esta operacao"
            :causa "rito-ambiguo-na-especie" :especie "projeto_lei"}
           (corpo-de ctx))
        "o corpo NOMEIA a causa + a especie acionavel — sem elas resta auditar a tabela de ritos inteira")))

(deftest config-sem-especie-omite-a-chave-em-vez-de-inventar
  ;; `:config/rito-generico-ambiguo` nao tem especie (os ritos duplicados sao justamente os que NAO
  ;; declaram uma). Preencher com placeholder seria afirmar um fato falso sobre qual especie olhar.
  (let [ctx (erro! (ex-info "dois genericos" {:tipo :config/rito-generico-ambiguo :ente-id (random-uuid)}))]
    (is (= 409 (get-in ctx [:response :status])))
    (is (= {:erro "a configuracao desta Casa impede esta operacao" :causa "rito-generico-ambiguo"}
           (corpo-de ctx)))
    (is (not (contains? (corpo-de ctx) :especie)) "chave ausente, nao nil nem placeholder")))

(deftest config-embrulhada-pelo-pedestal-tambem-vira-409
  ;; Mesma razao de existir do `raiz`: em producao o Pedestal entrega um WRAPPER. Sem olhar a causa, o
  ;; mapeamento so' funcionaria no teste.
  (let [dentro (ex-info "dois ritos" {:tipo :config/rito-ambiguo-na-especie :especie "mocao"})
        ctx (erro! (ex-info "wrapper do pedestal" {:exception dentro} dentro))]
    (is (= 409 (get-in ctx [:response :status])))
    (is (= "mocao" (:especie (corpo-de ctx))))))

(deftest a-mensagem-da-excecao-nao-vaza-no-409
  ;; O ramo 409 e' NOMEADO, nao transparente: `:causa` e `:especie` atravessam, o resto (mensagem, ente-id,
  ;; contexto interno) fica no log como em todos os outros ramos.
  (let [ctx (erro! (ex-info "SEGREDO INTERNO DO SERVIDOR"
                            {:tipo :config/rito-ambiguo-na-especie :especie "mocao"
                             :ente-id "ente-secreto" :sql "select ..."}))
        corpo (corpo-de ctx)]
    ;; o 409 PRIMEIRO: sem esta assercao o teste passaria VACUO se o ramo sumisse (o 500 opaco tambem nao
    ;; vaza nada) — assercao que nao pode reprovar nao e' cobertura.
    (is (= 409 (get-in ctx [:response :status])))
    (is (not (re-find #"SEGREDO" (get-in ctx [:response :body]))))
    (is (nil? (:ente-id corpo)))
    (is (nil? (:sql corpo)))))

(deftest tipo-fora-do-namespace-config-continua-500
  ;; O reconhecimento e' por NAMESPACE `config`; `:conflito/*` (que os diplomats traduzem por conta) NAO
  ;; escorrega para ca — mudar isso alteraria em silencio o codigo de 7 bordas que ja' existem.
  (doseq [t [:conflito/transicao :conflito/sem-rito :validacao/outra-coisa]]
    (let [ctx (erro! (ex-info "x" {:tipo t :especie "projeto_lei"}))]
      (is (not= 409 (get-in ctx [:response :status])) (str "nao pode virar 409 por acidente: " t)))))
