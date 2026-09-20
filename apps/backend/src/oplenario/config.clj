(ns oplenario.config
  "Carga de config 12-factor: base declarativa em resources/config.edn; o ambiente sobrepoe os
  pontos de deploy (DATABASE_URL/DB_USER/DB_PASSWORD/VALKEY_URI/HTTP_PORT). Permite a stack coexistir
  com outras stacks locais sem hardcode (§22.9: localizacao/credencial = deploy-config, nao arquitetura)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- lista-csv
  "Divide uma env var CSV em vetor de itens aparados, descartando vazios. Usada para os campos do
  Keycloak que sao LISTA (redirect-uris/web-origins): o EDN default so serve p/ dev, e prod precisa
  sobrepor com a(s) origem(ns) reais — sem isto o client PKCE nasce com redirect de localhost e todo
  login autenticado quebra em prod (achado do teste da homolog, metodo docs/20)."
  [s]
  (->> (str/split s #",") (map str/trim) (remove str/blank?) vec))

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
     (get env "KEYCLOAK_BASE_URL_PUBLICO") (assoc-in [:keycloak :base-url-publico] (get env "KEYCLOAK_BASE_URL_PUBLICO"))
     ;; Listas (CSV): a(s) URL(s) de redirect do BFF e a(s) origem(ns) web do client PKCE. Prod DEVE
     ;; sobrepor o default de localhost do config.edn, senao o `oplenario-web` provisionado so aceita
     ;; redirect de http://localhost:3000 e o login em prod falha com "Invalid redirect_uri".
     (get env "KEYCLOAK_REDIRECT_URIS")    (assoc-in [:keycloak :redirect-uris]   (lista-csv (get env "KEYCLOAK_REDIRECT_URIS")))
     (get env "KEYCLOAK_WEB_ORIGINS")      (assoc-in [:keycloak :web-origins]     (lista-csv (get env "KEYCLOAK_WEB_ORIGINS")))
     (get env "KEYCLOAK_SMTP_HOST")      (assoc-in [:keycloak :smtp :host]     (get env "KEYCLOAK_SMTP_HOST"))
     (get env "KEYCLOAK_SMTP_PORT")      (assoc-in [:keycloak :smtp :port]     (Integer/parseInt (get env "KEYCLOAK_SMTP_PORT")))
     (get env "KEYCLOAK_SMTP_FROM")      (assoc-in [:keycloak :smtp :from]     (get env "KEYCLOAK_SMTP_FROM"))
     (get env "KEYCLOAK_SMTP_SSL")       (assoc-in [:keycloak :smtp :ssl]      (= "true" (get env "KEYCLOAK_SMTP_SSL")))
     (get env "KEYCLOAK_SMTP_STARTTLS")  (assoc-in [:keycloak :smtp :starttls] (= "true" (get env "KEYCLOAK_SMTP_STARTTLS")))
     (get env "KEYCLOAK_SMTP_AUTH")      (assoc-in [:keycloak :smtp :auth]     (= "true" (get env "KEYCLOAK_SMTP_AUTH")))
     (get env "KEYCLOAK_SMTP_USUARIO")   (assoc-in [:keycloak :smtp :usuario]  (get env "KEYCLOAK_SMTP_USUARIO"))
     (get env "KEYCLOAK_SMTP_SENHA")     (assoc-in [:keycloak :smtp :senha]    (get env "KEYCLOAK_SMTP_SENHA"))
     (get env "SESSAO_ABSOLUTA_H")  (assoc-in [:sessao :absoluta-h] (Integer/parseInt (get env "SESSAO_ABSOLUTA_H")))
     (get env "SESSAO_OCIOSA_MIN")  (assoc-in [:sessao :ociosa-min] (Integer/parseInt (get env "SESSAO_OCIOSA_MIN"))))))
