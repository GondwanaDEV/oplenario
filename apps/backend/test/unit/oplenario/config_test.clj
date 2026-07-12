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
                             "KEYCLOAK_JWKS_CACHE_TTL_S" "120"})]
    (is (= "http://localhost:9090" (get-in c [:keycloak :base-url])))
    (is (= "casa-" (get-in c [:keycloak :realm-prefixo])))
    (is (= "oplenario-teste" (get-in c [:keycloak :audiencia])))
    (is (= "root" (get-in c [:keycloak :admin-usuario])))
    (is (= "segredo" (get-in c [:keycloak :admin-senha])))
    (is (= 120 (get-in c [:keycloak :jwks-cache-ttl-s])))))

(deftest keycloak-defaults-sem-override
  (let [c (config/carregar {})]
    (is (= "ente-" (get-in c [:keycloak :realm-prefixo])))
    (is (= "oplenario-backend" (get-in c [:keycloak :audiencia])))
    (is (pos? (get-in c [:keycloak :jwks-cache-ttl-s])))))
