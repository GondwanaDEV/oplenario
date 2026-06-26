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
     (get env "DATABASE_URL") (assoc-in [:db :jdbc-url] (get env "DATABASE_URL"))
     (get env "DB_USER")      (assoc-in [:db :user]     (get env "DB_USER"))
     (get env "DB_PASSWORD")  (assoc-in [:db :password] (get env "DB_PASSWORD"))
     (get env "VALKEY_URI")   (assoc-in [:valkey :uri]  (get env "VALKEY_URI"))
     (get env "HTTP_PORT")    (assoc-in [:http :port]   (Integer/parseInt (get env "HTTP_PORT"))))))
