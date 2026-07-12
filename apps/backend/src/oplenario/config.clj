(ns oplenario.config
  "Carga de config 12-factor: base declarativa em resources/config.edn; o ambiente sobrepoe os
  pontos de deploy (DATABASE_URL/DB_USER/DB_PASSWORD/VALKEY_URI/HTTP_PORT). Permite a stack coexistir
  com outras stacks locais sem hardcode (§22.9: localizacao/credencial = deploy-config, nao arquitetura)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- base []
  (if-let [r (io/resource "config.edn")]
    (edn/read-string (slurp r))
    (throw (ex-info "config.edn ausente do classpath" {}))))

(defn carregar
  "Le o config.edn e aplica overrides do ambiente. `env` default = System/getenv (java.util.Map);
  os testes injetam um mapa. Sobrepoe credenciais alem da URL — senao o pool ignoraria a URL de
  producao e tentaria o user/password default do EDN."
  ([] (carregar (System/getenv)))
  ([env]
   (cond-> (base)
     (get env "APP_ENV")      (assoc :env (get env "APP_ENV"))
     (get env "DATABASE_URL") (assoc-in [:db :jdbc-url] (get env "DATABASE_URL"))
     (get env "DB_USER")      (assoc-in [:db :user]     (get env "DB_USER"))
     (get env "DB_PASSWORD")  (assoc-in [:db :password] (get env "DB_PASSWORD"))
     (get env "VALKEY_URI")       (assoc-in [:valkey :uri]            (get env "VALKEY_URI"))
     (get env "TEMPO_REAL_BACKPLANE") (assoc-in [:tempo-real :backplane] (keyword (get env "TEMPO_REAL_BACKPLANE")))
     (get env "MINIO_ENDPOINT")   (assoc-in [:objeto-store :endpoint]   (get env "MINIO_ENDPOINT"))
     (get env "MINIO_ACCESS_KEY") (assoc-in [:objeto-store :access-key] (get env "MINIO_ACCESS_KEY"))
     (get env "MINIO_SECRET_KEY") (assoc-in [:objeto-store :secret-key] (get env "MINIO_SECRET_KEY"))
     (get env "MINIO_BUCKET")     (assoc-in [:objeto-store :bucket]     (get env "MINIO_BUCKET"))
     (get env "HTTP_PORT")        (assoc-in [:http :port]             (Integer/parseInt (get env "HTTP_PORT")))
     (get env "KEYCLOAK_BASE_URL")        (assoc-in [:keycloak :base-url]        (get env "KEYCLOAK_BASE_URL"))
     (get env "KEYCLOAK_REALM_PREFIXO")   (assoc-in [:keycloak :realm-prefixo]   (get env "KEYCLOAK_REALM_PREFIXO"))
     (get env "KEYCLOAK_AUDIENCIA")       (assoc-in [:keycloak :audiencia]       (get env "KEYCLOAK_AUDIENCIA"))
     (get env "KEYCLOAK_ADMIN_USUARIO")   (assoc-in [:keycloak :admin-usuario]   (get env "KEYCLOAK_ADMIN_USUARIO"))
     (get env "KEYCLOAK_ADMIN_SENHA")     (assoc-in [:keycloak :admin-senha]     (get env "KEYCLOAK_ADMIN_SENHA"))
     (get env "KEYCLOAK_JWKS_CACHE_TTL_S") (assoc-in [:keycloak :jwks-cache-ttl-s]
                                                      (Integer/parseInt (get env "KEYCLOAK_JWKS_CACHE_TTL_S")))
     (get env "KEYCLOAK_WEB_CLIENT_ID")    (assoc-in [:keycloak :web-client-id]    (get env "KEYCLOAK_WEB_CLIENT_ID"))
     (get env "KEYCLOAK_BASE_URL_PUBLICO") (assoc-in [:keycloak :base-url-publico] (get env "KEYCLOAK_BASE_URL_PUBLICO")))))
