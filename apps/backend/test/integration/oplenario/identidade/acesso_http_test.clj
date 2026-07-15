(ns oplenario.identidade.acesso-http-test
  "Onda D Slice 5 Task 8 — a superficie ADMINISTRATIVA HTTP de identidade (gated `admin_ente`): POST
  /identidade/identidades (criar identidade), POST /identidade/acessos (conceder acesso — o passo que
  ABRE A PORTA, 'acesso por ultimo') e POST /identidade/acessos/:identidade-id/convite (reenviar).
  DB-free (padrao da casa): repo fake por `reify`, ator/papel por token JSON do `idp-dev`.

  DESVIOS do brief original (Step 1), achados na corrida RED->GREEN:
  (1) o brief chamava `(idp/criar-usuario! idp-comp ente-id {... :nome (:nome ator) ...})` — mas `ator`
  (autenticacao/resolver-sessao) NUNCA tem `:nome` (so' :identidade-id/:ente-id/:tipo-vinculo/
  :vinculo-ativo-id/:papeis) e o wire ConcederAcesso tambem nao tem `:nome`. Em producao isso mandaria
  `nome=nil` pro Keycloak, que NPEs em `nome->first-last` (`str/trim` de nil). O nome CERTO e' o da
  identidade sendo provisionada (ja gravado por criar-identidade!, Task 6) — buscado aqui via
  `repo/identidade-por-id`. Por isso `fake-repo-identidade` abaixo implementa `identidade-por-id`
  (ausente no brief).
  (2) `com-bearer` do brief mandava `\"content-type\"` MINUSCULO — o mock de `io.pedestal.test`
  (`getContentType`) le' a chave EXATA `Content-Type` capitalizada (mesmo gotcha ja' documentado em
  vereador_http_in_test.clj/proposicao-escrita-http-in-test); sem isso `it/corpo-json` nunca via' o corpo
  como JSON e todo POST-com-corpo 400ava.
  (3) `fake-idp`'s `verificar-token` do brief devolvia `:ente-id`/`:identidade-id` como STRING (json cru);
  toda impl real (idp-dev, keycloak-idp) normaliza p/ `java.util.UUID` (contrato `idp/Claims`). Sem essa
  normalizacao aqui, `ator` carregava `:ente-id` STRING e `reenviar-convite-200` comparava contra um
  `random-uuid` — nunca bateria."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.rotas :as rotas]))

(def ^:private cpf-valido "52998224725")

(defn- fake-repo-identidade [papeis capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _e _i] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})
    (criar-identidade! [_ m] (swap! capturado conj [:criar-identidade m]) (:id m))
    (identidade-por-id [_ id] {:id id :cpf cpf-valido :nome "Helena Matos"})
    (conceder-acesso! [_ e v p] (swap! capturado conj [:conceder-acesso e v p]) {:vinculo-id (random-uuid)})))

(defn- ->uuid [s] (when s (java.util.UUID/fromString s)))

(defn- fake-idp [capturado & {:keys [convidar-lanca?]}]
  (reify idp/IdentityProvider
    (verificar-token [_ t]
      ;; mesma normalizacao de idp-dev/keycloak-idp: ente-id/identidade-id chegam como UUID, nunca string
      ;; (contrato idp/Claims) — ver DESVIO (3) na docstring do ns.
      (let [c (json/read-value t json/keyword-keys-object-mapper)]
        (cond-> c
          (:identidade-id c) (update :identidade-id ->uuid)
          (:ente-id c)       (update :ente-id ->uuid))))
    (provisionar-realm! [_ e] (swap! capturado conj [:provisionar-realm e]) true)
    (criar-usuario! [_ e u] (swap! capturado conj [:criar-usuario e u]) {:keycloak-user-id "kc-1"})
    (convidar! [_ e i]
      (when convidar-lanca? (throw (ex-info "keycloak fora do ar" {:tipo :infra})))
      (swap! capturado conj [:convidar e i]) true)
    (resetar-mfa! [_ _e _i] true)))

(defn- service-fn [papeis capturado & {:keys [convidar-lanca?]}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (fake-idp capturado :convidar-lanca? convidar-lanca?)
                                   :repo-identidade (fake-repo-identidade papeis capturado)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
;; "Content-Type" CAPITALIZADO de proposito — o mock de `io.pedestal.test` (getContentType) le' a chave
;; EXATA `Content-Type`, case-sensitive (mesmo precedente de vereador_http_in_test/com-bearer). Ver DESVIO
;; (2) na docstring do ns.
(defn- com-bearer [t] {"authorization" (str "Bearer " t) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest criar-identidade-201
  (let [cap (atom []) ente (random-uuid)
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/identidades"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:cpf cpf-valido :nome "Helena Matos"}))]
    (is (= 201 (:status r)))
    (is (uuid? (parse-uuid (:identidade-id (ler-json r)))) "devolve o id canonico")))

(deftest criar-identidade-sem-admin-ente-403
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"secretario"} cap)
                           :post "/identidade/identidades"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:cpf cpf-valido :nome "X"}))]
    (is (= 403 (:status r))
        "secretario cria vereador mas NAO concede acesso — senao cria 'vereador' com o proprio e-mail e vota")
    (is (empty? @cap) "nada foi tocado")))

(deftest criar-identidade-cpf-invalido-400
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/identidades"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:cpf "11111111111" :nome "X"}))]
    (is (= 400 (:status r)) "digito verificador errado -> 400")
    (is (not (re-find #"11111111111" (:body r))) "o CPF NUNCA volta no corpo do erro")))

(deftest conceder-acesso-201-e-a-ordem-importa
  (let [cap (atom []) ente (random-uuid) ident (random-uuid)
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/acessos"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                  {:identidade-id (str ident) :tipo "vereador"
                                   :papeis ["vereador"] :email "helena@camara.local"}))]
    (is (= 201 (:status r)))
    (is (= [:conceder-acesso :provisionar-realm :criar-usuario :convidar] (mapv first @cap))
        "DB ANTES do Keycloak: se o KC cair, sobra vinculo sem credencial = ninguem entra (fail-closed)")))

(deftest conceder-acesso-keycloak-fora-do-ar-500
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"admin_ente"} cap :convidar-lanca? true)
                           :post "/identidade/acessos"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string
                                  {:identidade-id (str (random-uuid)) :tipo "vereador"
                                   :papeis ["vereador"] :email "h@c.local"}))]
    (is (= 500 (:status r))
        "infra fora do ar -> 500, NUNCA 401 — mascarar degradacao como credencial ruim vira incidente mudo")))

(deftest reenviar-convite-200
  (let [cap (atom []) ente (random-uuid) ident (random-uuid)
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post (str "/identidade/acessos/" ident "/convite")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [[:convidar ente ident]] @cap) "so' reenvia — nao recria vinculo nem usuario")))

(deftest reenviar-convite-id-malformado-404
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/acessos/nao-e-uuid/convite"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "id que nao parseia -> 404, nunca 500")))
