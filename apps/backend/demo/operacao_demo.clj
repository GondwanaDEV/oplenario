(ns operacao-demo
  "A OPERADORA de demo (ADR-0016): o realm do operador provisionado e uma operadora com senha conhecida. A chave de
  seguranca NAO e' semeada — nao existe chave de mentira: no 1o login o Keycloak pede para cadastrar a chave (no
  navegador de demo, o autenticador virtual do DevTools/Playwright faz esse papel). Idempotente; falha alto.
  NAO e' codigo de producao (demo/). Em producao o operador entra por `oplenario.main operador-convidar`."
  (:require [com.stuartsierra.component :as component]
            [keycloak-admin :as kc-admin]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource])
  (:import (java.net.http HttpClient)))

(def email "operacao@oplenario.dev")
(def nome "Rafaela Moura")
(def senha "Plenario@Operacao2026")

(defn semear-operador!
  "Ponto de entrada do `-X` (`clojure -X:seed operacao-demo/semear-operador!`)."
  [_]
  (let [cfg (config/carregar)
        op-cfg (:operacao cfg)
        ds (component/start (datasource/datasource cfg))
        idp (component/start (idp-admin/keycloak-operacao op-cfg))
        r (assoc (repo/repositorio) :datasource ds)]
    (try
      (idp-admin/provisionar-realm-operacao! idp)
      (let [o (repo/criar-operador! r {:id (random-uuid) :email email :nome nome})
            {:keys [keycloak-user-id]} (idp-admin/criar-operador-no-idp! idp {:operador-id (:id o) :email email :nome nome})
            http (HttpClient/newHttpClient)
            {:keys [base-url admin-usuario admin-senha realm]} op-cfg
            tok (kc-admin/admin-token! http base-url admin-usuario admin-senha)]
        ;; re-semear: a politica do realm recusa repetir as ultimas 5 senhas — a de demo ja' esta' la', segue
        (try (kc-admin/setar-senha! http base-url tok realm keycloak-user-id senha)
             (catch clojure.lang.ExceptionInfo e
               (when-not (re-find #"invalidPasswordHistory" (str (:corpo (ex-data e)))) (throw e))))
        ;; sem UPDATE_PASSWORD (a senha e' a de demo); a chave o fluxo do navegador exige de qualquer forma
        (kc-admin/limpar-required-actions! http base-url tok realm keycloak-user-id)
        (println "operacao-demo: operadora" email "senha" senha "— a chave de seguranca e' cadastrada no 1o login")
        o)
      (finally (component/stop idp) (component/stop ds)))))
