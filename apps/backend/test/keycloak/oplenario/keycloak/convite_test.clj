(ns oplenario.keycloak.convite-test
  "Prova que o CONVITE de 1o acesso sai de verdade (§22.5.2 eixo F). Quem envia e' o Keycloak; nos so'
  pedimos. O Mailpit captura o e-mail e expoe a caixa por HTTP (nada sai p/ o mundo)."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as keycloak-idp]))

(defn- mailpit-url [] (or (System/getenv "MAILPIT_URL") "http://mailpit:8025"))

(defn- limpar-caixa! []
  (-> (java.net.http.HttpClient/newHttpClient)
      (.send (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str (mailpit-url) "/api/v1/messages")))
                 (.DELETE) (.build))
             (java.net.http.HttpResponse$BodyHandlers/ofString))))

(defn- mensagens []
  (-> (java.net.http.HttpClient/newHttpClient)
      (.send (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str (mailpit-url) "/api/v1/messages")))
                 (.GET) (.build))
             (java.net.http.HttpResponse$BodyHandlers/ofString))
      (.body)
      (json/read-value json/keyword-keys-object-mapper)))

(deftest convite-chega-na-caixa
  (limpar-caixa!)
  (let [ente (random-uuid) ident (random-uuid)
        idp (component/start (keycloak-idp/keycloak-idp (:keycloak (config/carregar))))]
    (try
      (idp/provisionar-realm! idp ente)
      (idp/criar-usuario! idp ente {:identidade-id ident :nome "Helena Matos"
                                    :email "helena@camara.local"})
      (is (true? (idp/convidar! idp ente ident)) "convidar! devolve true no envio")
      (let [msgs (:messages (mensagens))]
        (is (= 1 (count msgs)) "exatamente 1 e-mail na caixa")
        (is (= "helena@camara.local" (-> msgs first :To first :Address))
            "foi p/ o e-mail institucional do usuario — que mora SO' no Keycloak, nao em tabela nossa"))
      (finally (component/stop idp)))))

(deftest convidar-usuario-inexistente-lanca
  (let [ente (random-uuid)
        idp (component/start (keycloak-idp/keycloak-idp (:keycloak (config/carregar))))]
    (try
      (idp/provisionar-realm! idp ente)
      (is (thrown? clojure.lang.ExceptionInfo (idp/convidar! idp ente (random-uuid)))
          "sem usuario no realm nao ha' convite — lanca (fail-closed), nunca 'true' mentiroso")
      (finally (component/stop idp)))))
