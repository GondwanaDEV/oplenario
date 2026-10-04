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

(def govbr-simulado-client-id
  "O client do broker no realm `govbr-simulado` (dev/demo/CI, ADR-0015). Fixo: o simulado nao guarda segredo real."
  "oplenario-broker")
(def govbr-simulado-client-secret "govbr-simulado-dev")

(defn- herdar-operacao
  "O que o `:operacao` nao declara vem do `:keycloak` (URL, credencial admin, cache de JWKS, SMTP) — em dev/CI e' o
  mesmo container. Em producao o deploy declara o Keycloak separado e nada e' herdado."
  [config]
  (update config :operacao
          #(merge (select-keys (:keycloak config) [:base-url :base-url-publico :admin-usuario :admin-senha
                                                  :jwks-cache-ttl-s :smtp])
                  %)))

(defn carregar
  "Le o config.edn e aplica overrides do ambiente. `env` default = System/getenv (java.util.Map);
  os testes injetam um mapa. Sobrepoe credenciais alem da URL — senao o pool ignoraria a URL de
  producao e tentaria o user/password default do EDN."
  ([] (carregar (System/getenv)))
  ([env]
   (herdar-operacao
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
     ;; ADR-0008: segredo de servico core<->satelite de IA (vem do cofre). Ausente = rotas /integracao/ia desligadas.
     (get env "OPLENARIO_IA_SEGREDO") (assoc-in [:integracao-ia :segredo] (get env "OPLENARIO_IA_SEGREDO"))
     (get env "OPLENARIO_IA_URL")     (assoc-in [:integracao-ia :url]     (get env "OPLENARIO_IA_URL"))
     ;; ADR-0017 (adendo): so' o valor exato "true" liga o modo que RECUSA a escrita quando a tentativa da trilha nao
     ;; grava. Ausente ou qualquer outro valor = nao bloqueia (a trilha fora nao para a Casa).
     (= "true" (get env "AUDITORIA_EXIGIR_TENTATIVA")) (assoc-in [:auditoria :exigir-tentativa] true)
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
     (get env "SESSAO_OCIOSA_MIN")  (assoc-in [:sessao :ociosa-min] (Integer/parseInt (get env "SESSAO_OCIOSA_MIN")))
     ;; ADR-0015: o broker gov.br do cidadao. Sem GOVBR_AMBIENTE = sem gov.br (o realm nao ganha o IdP e o portal
     ;; nao mostra o botao). producao|homologacao pedem o client credenciado no gov.br (cofre); o simulado (realm
     ;; `govbr-simulado` no proprio Keycloak, dev/demo/CI) tem client fixo semeado por `demo/govbr_simulado.clj`.
     (get env "GOVBR_AMBIENTE")
     (assoc-in [:keycloak :govbr]
               (let [simulado? (= "simulado" (get env "GOVBR_AMBIENTE"))]
                 {:ambiente      (get env "GOVBR_AMBIENTE")
                  :client-id     (or (get env "GOVBR_CLIENT_ID") (when simulado? govbr-simulado-client-id))
                  :client-secret (or (get env "GOVBR_CLIENT_SECRET") (when simulado? govbr-simulado-client-secret))}))
     ;; ADR-0016: o IdP do operador. Em producao aponta para o Keycloak SEPARADO; sem isto herda o das Casas (dev/CI).
     (get env "OPERACAO_KC_BASE_URL")         (assoc-in [:operacao :base-url]         (get env "OPERACAO_KC_BASE_URL"))
     (get env "OPERACAO_KC_BASE_URL_PUBLICO") (assoc-in [:operacao :base-url-publico] (get env "OPERACAO_KC_BASE_URL_PUBLICO"))
     (get env "OPERACAO_KC_ADMIN_USUARIO")    (assoc-in [:operacao :admin-usuario]    (get env "OPERACAO_KC_ADMIN_USUARIO"))
     (get env "OPERACAO_KC_ADMIN_SENHA")      (assoc-in [:operacao :admin-senha]      (get env "OPERACAO_KC_ADMIN_SENHA"))
     (get env "OPERACAO_REDIRECT_URIS") (assoc-in [:operacao :redirect-uris] (lista-csv (get env "OPERACAO_REDIRECT_URIS")))
     (get env "OPERACAO_WEB_ORIGINS")   (assoc-in [:operacao :web-origins]   (lista-csv (get env "OPERACAO_WEB_ORIGINS")))
     ;; modelos de chave fisica aceitos (AAGUID, CSV). Vazio = qualquer chave de seguranca (cross-platform).
     (get env "OPERACAO_AAGUIDS")       (assoc-in [:operacao :aaguids]       (lista-csv (get env "OPERACAO_AAGUIDS")))
     (get env "OPERACAO_ATESTACAO")
     (assoc-in [:operacao :atestacao]
               (let [v (get env "OPERACAO_ATESTACAO")]
                 (if (#{"none" "indirect" "direct"} v)
                   v
                   (throw (ex-info "OPERACAO_ATESTACAO invalida — use none|indirect|direct" {:valor v})))))))))
