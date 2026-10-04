(ns oplenario.config-test
  "Carga de config 12-factor: base em resources/config.edn, env sobrepoe (DATABASE_URL etc.).
  Permite a stack coexistir com outras locais sem hardcode de porta (§22.9 deploy-config)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.config :as config]))

(deftest sem-env-usa-default-do-edn
  (let [c (config/carregar {})]
    (is (= "jdbc:postgresql://localhost:5432/oplenario" (get-in c [:db :jdbc-url]))
        "sem env, jdbc-url vem do config.edn")
    (is (= 8888 (get-in c [:http :port])) "porta default do edn")))

(deftest sem-app-env-o-env-fica-ausente
  ;; O config NAO inventa um ambiente. O default era "dev", e "dev" liga o idp-dev (JWT nao-assinado) em
  ;; `sistema/idp-para` — logo um deploy que esquecesse APP_ENV nascia com a auth aberta. O fail-safe do
  ;; idp-para so' cobre valor DESCONHECIDO; a AUSENCIA nunca chegava la' (chegava "dev"). Sem default, a
  ;; ausencia flui ate' o idp-para como nil e cai no KeycloakIdp real. Modo dev = opt-in explicito.
  (let [c (config/carregar {})]
    (is (nil? (:env c)) "sem APP_ENV o :env fica nil — o config nao inventa 'dev'")))

(deftest app-env-sobrepoe-o-env
  (is (= "dev" (:env (config/carregar {"APP_ENV" "dev"}))) "APP_ENV explicito e' a UNICA fonte do :env")
  (is (= "production" (:env (config/carregar {"APP_ENV" "production"})))))

(deftest env-sobrepoe-jdbc-url
  (let [c (config/carregar {"DATABASE_URL" "jdbc:postgresql://localhost:5544/oplenario"})]
    (is (= "jdbc:postgresql://localhost:5544/oplenario" (get-in c [:db :jdbc-url]))
        "DATABASE_URL sobrepoe o default")))

(deftest env-http-port-vira-inteiro
  (let [c (config/carregar {"HTTP_PORT" "9999"})]
    (is (= 9999 (get-in c [:http :port])) "HTTP_PORT do env e' parseado p/ inteiro")))

(deftest env-sobrepoe-credenciais-do-banco
  ;; sem isto, jdbc-url de producao seria ignorado nas credenciais e o pool tentaria oplenario/dev.
  (let [c (config/carregar {"DB_USER" "produser" "DB_PASSWORD" "s3cr3t"})]
    (is (= "produser" (get-in c [:db :user])) "DB_USER sobrepoe o user default")
    (is (= "s3cr3t" (get-in c [:db :password])) "DB_PASSWORD sobrepoe o password default")))

(deftest keycloak-overrides-do-ambiente
  (let [c (config/carregar {"KEYCLOAK_BASE_URL" "http://localhost:9090"
                             "KEYCLOAK_REALM_PREFIXO" "casa-"
                             "KEYCLOAK_AUDIENCIA" "oplenario-teste"
                             "KEYCLOAK_ADMIN_USUARIO" "root"
                             "KEYCLOAK_ADMIN_SENHA" "segredo"
                             "KEYCLOAK_JWKS_CACHE_TTL_S" "120"
                             "KEYCLOAK_WEB_CLIENT_ID" "web-teste"
                             "KEYCLOAK_BASE_URL_PUBLICO" "http://localhost:9091"})]
    (is (= "http://localhost:9090" (get-in c [:keycloak :base-url])))
    (is (= "casa-" (get-in c [:keycloak :realm-prefixo])))
    (is (= "oplenario-teste" (get-in c [:keycloak :audiencia])))
    (is (= "root" (get-in c [:keycloak :admin-usuario])))
    (is (= "segredo" (get-in c [:keycloak :admin-senha])))
    (is (= 120 (get-in c [:keycloak :jwks-cache-ttl-s])))
    (is (= "web-teste" (get-in c [:keycloak :web-client-id])) "KEYCLOAK_WEB_CLIENT_ID sobrepoe o client id publico")
    (is (= "http://localhost:9091" (get-in c [:keycloak :base-url-publico]))
        "KEYCLOAK_BASE_URL_PUBLICO sobrepoe a URL browser-facing")))

(deftest keycloak-defaults-sem-override
  (let [c (config/carregar {})]
    (is (= "ente-" (get-in c [:keycloak :realm-prefixo])))
    (is (= "oplenario-backend" (get-in c [:keycloak :audiencia])))
    (is (pos? (get-in c [:keycloak :jwks-cache-ttl-s])))
    (is (= "oplenario-web" (get-in c [:keycloak :web-client-id])) "client id publico default do edn")
    (is (= "http://localhost:8090" (get-in c [:keycloak :base-url-publico])) "base-url publico default do edn")))

(deftest a-trilha-so-bloqueia-a-escrita-com-opt-in-explicito
  ;; ADR-0017 (adendo): o padrao e' NAO bloquear; so' o valor exato "true" liga o modo que recusa
  (is (false? (get-in (config/carregar {}) [:auditoria :exigir-tentativa])))
  (is (true? (get-in (config/carregar {"AUDITORIA_EXIGIR_TENTATIVA" "true"}) [:auditoria :exigir-tentativa])))
  (doseq [v ["" "false" "1" "TRUE" "sim"]]
    (is (false? (get-in (config/carregar {"AUDITORIA_EXIGIR_TENTATIVA" v}) [:auditoria :exigir-tentativa])) (pr-str v))))

(deftest govbr-so-existe-com-ambiente
  ;; ADR-0015: sem GOVBR_AMBIENTE o realm nao ganha o broker (fail-closed)
  (is (nil? (get-in (config/carregar {}) [:keycloak :govbr])))
  (is (= {:ambiente "producao" :client-id "cid" :client-secret "seg"}
         (get-in (config/carregar {"GOVBR_AMBIENTE" "producao" "GOVBR_CLIENT_ID" "cid" "GOVBR_CLIENT_SECRET" "seg"})
                 [:keycloak :govbr])))
  (is (= "oplenario-broker" (get-in (config/carregar {"GOVBR_AMBIENTE" "simulado"}) [:keycloak :govbr :client-id]))
      "o simulado tem client fixo")
  (is (nil? (get-in (config/carregar {"GOVBR_AMBIENTE" "producao"}) [:keycloak :govbr :client-id]))
      "producao nunca herda o client do simulado"))

(deftest operacao-herda-o-keycloak-em-dev-e-separa-em-producao
  ;; ADR-0016: sem OPERACAO_KC_* o console usa o mesmo Keycloak (dev/CI); com eles, o Keycloak separado.
  (let [dev (config/carregar {})]
    (is (= "operacao" (get-in dev [:operacao :realm])))
    (is (= (get-in dev [:keycloak :base-url]) (get-in dev [:operacao :base-url])))
    (is (= (get-in dev [:keycloak :smtp]) (get-in dev [:operacao :smtp]))))
  (let [prod (config/carregar {"KEYCLOAK_BASE_URL" "https://kc-casas" "OPERACAO_KC_BASE_URL" "https://kc-operacao"
                               "OPERACAO_KC_ADMIN_SENHA" "outra" "OPERACAO_REDIRECT_URIS" "https://app/api/operacao/callback"
                               "OPERACAO_AAGUIDS" "a, b"})]
    (is (= "https://kc-operacao" (get-in prod [:operacao :base-url])))
    (is (= "outra" (get-in prod [:operacao :admin-senha])))
    (is (= ["https://app/api/operacao/callback"] (get-in prod [:operacao :redirect-uris])))
    (is (= ["a" "b"] (get-in prod [:operacao :aaguids])))
    (is (= "direct" (get-in prod [:operacao :atestacao]))))
  (is (= "none" (get-in (config/carregar {"OPERACAO_ATESTACAO" "none"}) [:operacao :atestacao])))
  (is (thrown? clojure.lang.ExceptionInfo (config/carregar {"OPERACAO_ATESTACAO" "talvez"}))))

(deftest valkey-senha-so-vem-do-ambiente
  (is (nil? (get-in (config/carregar {}) [:valkey :password])) "o config.edn nao traz senha padrao do Valkey")
  (is (nil? (get-in (config/carregar {}) [:valkey :username])))
  (let [c (config/carregar {"VALKEY_URI" "rediss://cache.interno:6380" "VALKEY_USERNAME" "oplenario"
                            "VALKEY_PASSWORD" "s3nha"})]
    (is (= {:uri "rediss://cache.interno:6380" :username "oplenario" :password "s3nha"} (:valkey c)))))

(deftest valkey-exigir-senha-so-liga-com-true
  (is (nil? (get-in (config/carregar {}) [:valkey :exigir-senha])) "ausente por padrao: o boot so' avisa")
  (is (true? (get-in (config/carregar {"VALKEY_EXIGIR_SENHA" "true"}) [:valkey :exigir-senha])))
  (is (false? (get-in (config/carregar {"VALKEY_EXIGIR_SENHA" "1"}) [:valkey :exigir-senha]))
      "so' o literal true liga"))
