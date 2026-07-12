(ns oplenario.sistema-test
  "Integracao: o sistema Component sobe e expoe a infra do kernel ja conectada (§22.10: host =
  merge dos sub-systems + infra do kernel). F0.1 fia o minimo: datasource. F0.2+ adicionam outbox-relay etc."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.sistema :as sistema]
            [next.jdbc :as jdbc]))

;; `oplenario.sistema` requer `keycloak-idp`/`idp-dev` (transitivamente carrega as classes KeycloakIdp/IdpDev
;; usadas em `instance?` abaixo) — sem alias direto aqui p/ nao acumular unused-namespace no clj-kondo.

;; Config fake de :keycloak — so' a FORMA (chaves) exigida por `keycloak-idp/keycloak-idp`; nenhum destes
;; testes faz I/O (o Component nasce NAO-iniciado, component/start nao e' chamado aqui).
(def ^:private keycloak-config-fake
  {:base-url "http://x" :realm-prefixo "ente-" :audiencia "a"
   :admin-usuario "u" :admin-senha "p" :jwks-cache-ttl-s 600})

(deftest sistema-boota-e-expoe-datasource-conectado
  (let [sys (component/start (sistema/novo-sistema (config/carregar)))]
    (try
      (is (= {:um 1} (jdbc/execute-one! (-> sys :datasource :ds) ["SELECT 1 AS um"]))
          "o sistema bootado expoe um datasource conectado ao Postgres")
      (finally (component/stop sys)))))

(deftest backplane-de-tempo-real-invalido-lanca
  ;; review sec-MINOR-1: um typo em TEMPO_REAL_BACKPLANE (ex.: :Valkey) cairia em :memoria em silencio — cada
  ;; replica de prod com store isolado. novo-sistema deve LANCAR no boot (antes de qualquer IO).
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"backplane de tempo real invalido"
        (sistema/novo-sistema (assoc-in (config/carregar) [:tempo-real :backplane] :bogus)))
      "backplane desconhecido bloqueia o boot (fail-closed)"))

;; `idp-para` e' privada (defn- em sistema.clj) — acessada via `#'sistema/idp-para`, convencao do proprio
;; ns p/ testar a selecao de impl sem expor a fn no API publica do host.
(deftest idp-para-producao-usa-keycloak
  ;; Onda D Slice 1: producao deixa de LANCAR (guard fail-closed do carry F1.4) e passa a injetar o
  ;; KeycloakIdp real (Component NAO-iniciado — sem I/O aqui).
  (is (instance? oplenario.kernel.components.keycloak_idp.KeycloakIdp
                 (#'sistema/idp-para {:env "production" :keycloak keycloak-config-fake}))
      "production usa o KeycloakIdp real"))

(deftest idp-para-staging-usa-keycloak
  (is (instance? oplenario.kernel.components.keycloak_idp.KeycloakIdp
                 (#'sistema/idp-para {:env "staging" :keycloak keycloak-config-fake}))
      "staging usa o KeycloakIdp real"))

(deftest idp-para-dev-usa-idp-dev
  (is (instance? oplenario.kernel.components.idp_dev.IdpDev (#'sistema/idp-para {:env "dev"}))
      "dev segue no idp-dev (confia claims sem verificar assinatura — nunca fora de dev/test)"))

(deftest idp-para-env-nao-reconhecido-usa-keycloak
  ;; Achado da revisao final de branco (Onda D Slice 1): o predicado antigo so' usava o KeycloakIdp
  ;; real p/ "production"/"staging" literais e caia em idp-dev p/ QUALQUER outro valor de :env, incl.
  ;; um typo ("producton") ou um :env ausente (default e' "dev" em config.edn) — auth-bypass silencioso
  ;; se esse caminho fosse alcancavel fora de dev/test. O predicado invertido (dev/test = whitelist p/
  ;; idp-dev) fecha isso: um :env nao-reconhecido cai no KeycloakIdp real (fail-safe — falha tentando
  ;; falar com um Keycloak de verdade, nunca aceita claims forjadas sem assinatura). Esta prova falharia
  ;; contra o predicado antigo (que so' testava a whitelist de "production"/"staging").
  (is (instance? oplenario.kernel.components.keycloak_idp.KeycloakIdp
                 (#'sistema/idp-para {:env "producton" :keycloak keycloak-config-fake}))
      "um :env nao-reconhecido/typo usa o KeycloakIdp real, nunca o idp-dev (fail-safe)"))
