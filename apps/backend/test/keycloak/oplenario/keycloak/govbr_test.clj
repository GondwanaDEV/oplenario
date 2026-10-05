(ns oplenario.keycloak.govbr-test
  "INTEGRACAO gated (PG + Keycloak reais) — ADR-0015: o login do cidadao pelo gov.br de ponta a ponta, contra o
  gov.br SIMULADO (`demo/govbr_simulado.clj`). Faz o que o navegador faz: autoriza no realm da Casa com
  `kc_idp_hint=govbr`, cai na tela de login do gov.br, entra com CPF e senha, volta pelo broker (o 1o login cria o
  usuario `govbr-<cpf>`) e troca o code por token (PKCE). O token tem de dizer `idp=govbr` + o CPF + o nome, e o
  mint tem de fazer dele um cidadao."
  (:require [oplenario.suporte-cpf :refer [cpf-valido]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [govbr-simulado :as simulado]
            [jsonista.core :as json]
            [keycloak-admin :as kc-admin]
            [oplenario.config :as config]
            [oplenario.identidade.autenticacao :as auth]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as kc]
            [oplenario.migracao :as migracao])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(def ^:private kc-cfg
  (-> (:keycloak (config/carregar))
      (assoc :base-url-publico (:base-url (:keycloak (config/carregar))))   ; o "navegador" do teste fala com a mesma URL
      (assoc :govbr {:ambiente "simulado" :client-id config/govbr-simulado-client-id
                     :client-secret config/govbr-simulado-client-secret})))

(def ^:private redirect-uri (first (:redirect-uris kc-cfg)))

(defn- b64url [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn- enc [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- navegador
  "Cliente HTTP + pote de cookies proprio: o Keycloak 26 marca os cookies de sessao `Secure` e o CookieManager do
  Java nao os devolve em http:// — um navegador de dev em localhost devolve. Respeita o `Path` como o navegador: os
  dois realms (a Casa e o gov.br simulado) usam o MESMO nome de cookie, separados pelo caminho."
  []
  {:http (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) (.build))
   :pote (atom {})})

(defn- guardar-cookies! [pote headers]
  (doseq [c (.allValues headers "set-cookie")]
    (let [[par & attrs] (str/split c #";\s*")
          [k v] (str/split par #"=" 2)
          caminho (or (some #(second (re-matches #"(?i)path=(.*)" %)) attrs) "/")
          apagar? (some #(re-matches #"(?i)max-age=0" %) attrs)]
      (if (or apagar? (str/blank? v)) (swap! pote dissoc [k caminho]) (swap! pote assoc [k caminho] v)))))

(defn- cookies-para [pote url]
  (let [caminho (.getPath (URI/create url))]
    (->> @pote
         (filter (fn [[[_ c] _]] (str/starts-with? caminho c)))
         (sort-by (fn [[[_ c] _]] (- (count c))))
         (map (fn [[[k _] v]] (str k "=" v)))
         (str/join "; "))))

(defn- ir! [{:keys [^HttpClient http pote]} url & [form]]
  (let [b (-> (HttpRequest/newBuilder) (.uri (URI/create url)))
        cs (cookies-para pote url)
        b (if (str/blank? cs) b (.header b "Cookie" cs))
        b (if form
            (-> b (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (str/join "&" (map (fn [[k v]] (str (enc k) "=" (enc v))) form)))))
            (.GET b))
        r (.send http (.build b) (HttpResponse$BodyHandlers/ofString))]
    (guardar-cookies! pote (.headers r))
    {:status (.statusCode r) :corpo (.body r)
     :location (let [l (.firstValue (.headers r) "location")] (when (.isPresent l) (.get l)))}))

(defn- seguir!
  "Segue redirects ate' uma pagina (200) ou ate' chegar no redirect_uri do BFF (onde o code aparece)."
  [nav resp]
  (loop [r resp n 0]
    (cond
      (> n 15) (throw (ex-info "redirects demais" {:ultimo r}))
      (and (:location r) (str/starts-with? (:location r) redirect-uri)) r
      (:location r) (recur (ir! nav (str (.resolve (URI/create "http://x/") (URI/create (:location r))))) (inc n))
      :else r)))

(defn- acao-do-form [html]
  (some-> (re-find #"<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"" html) second
          (str/replace "&amp;" "&")))

(defn- entrar-pelo-govbr!
  "O caminho do navegador; devolve o access token do realm da Casa."
  [ente cpf senha]
  (let [nav (navegador)
        verifier (b64url (let [b (byte-array 32)] (.nextBytes (SecureRandom.) b) b))
        challenge (b64url (.digest (MessageDigest/getInstance "SHA-256") (.getBytes verifier "US-ASCII")))
        realm-url (str (:base-url kc-cfg) "/realms/ente-" ente "/protocol/openid-connect")
        inicio (ir! nav (str realm-url "/auth?client_id=" (:web-client-id kc-cfg) "&response_type=code&scope=openid"
                             "&redirect_uri=" (enc redirect-uri) "&state=s1&code_challenge=" challenge
                             "&code_challenge_method=S256&kc_idp_hint=govbr"))
        tela-govbr (seguir! nav inicio)
        acao (acao-do-form (:corpo tela-govbr))
        _ (is (some? acao) (str "caiu na tela de login do gov.br (simulado); parou em " (:status tela-govbr) " "
                                (:location tela-govbr) " "
                                (or (second (re-find #"(?s)kc-error-message.*?<p[^>]*>(.*?)</p>" (str (:corpo tela-govbr))))
                                    (subs (str (:corpo tela-govbr)) 0 (min 300 (count (str (:corpo tela-govbr))))))))
        volta (seguir! nav (ir! nav acao {"username" cpf "password" senha}))
        code (second (re-find #"[?&]code=([^&]+)" (or (:location volta) "")))
        _ (is (some? code) (str "voltou ao BFF com o code; ultima resposta: " (:status volta) " "
                                (subs (str (:corpo volta)) 0 (min 400 (count (str (:corpo volta)))))))
        tok (ir! nav (str realm-url "/token")
                 {"grant_type" "authorization_code" "client_id" (:web-client-id kc-cfg) "code" code
                  "redirect_uri" redirect-uri "code_verifier" verifier})]
    (:access_token (json/read-value (:corpo tok) json/keyword-keys-object-mapper))))

(defn- apagar-realm! [ente]
  (let [http (HttpClient/newHttpClient)
        t (kc-admin/admin-token! http (:base-url kc-cfg) (:admin-usuario kc-cfg) (:admin-senha kc-cfg))]
    (.send http (-> (HttpRequest/newBuilder) (.uri (URI/create (str (:base-url kc-cfg) "/admin/realms/ente-" ente)))
                    (.header "Authorization" (str "Bearer " t)) (.DELETE) (.build))
           (HttpResponse$BodyHandlers/ofString))))

(deftest cidadao-entra-pelo-govbr-e-vira-cidadao-da-casa
  (let [ente (random-uuid) cpf (cpf-valido) senha "Senha-simulada-1"
        ip (component/start (kc/keycloak-idp kc-cfg))]
    (simulado/garantir! kc-cfg)
    (simulado/garantir-cidadao! kc-cfg {:cpf cpf :nome "Maria das Dores Silva" :senha senha})
    (idp/provisionar-realm! ip ente)
    (idp/provisionar-realm! ip ente)   ; re-provisionar converge sem quebrar (idempotente)
    (try
      (let [token (entrar-pelo-govbr! ente cpf senha)
            claims (idp/verificar-token ip token)]
        (testing "o token do realm da Casa diz que a pessoa entrou pelo gov.br, com o CPF e o nome verificados"
          (is (= "govbr" (:idp claims)))
          (is (= cpf (:govbr-sub claims)))
          (is (= "Maria das Dores Silva" (:nome claims)))
          (is (= ente (:ente-id claims))))
        (testing "o mint faz dela uma cidada da Casa, sem papel nenhum"
          (let [repo (assoc (repo-id/repositorio) :datasource {:ds *ds*})
                iid (auth/garantir-cidadao! repo claims)
                ator (auth/resolver-claims repo claims)]
            (is (= iid (:identidade-id ator)))
            (is (= "cidadao" (:tipo-vinculo ator)))
            (is (= #{} (:papeis ator)))))
        (testing "o 2o login (usuario ja' criado) tambem passa"
          (is (= cpf (:govbr-sub (idp/verificar-token ip (entrar-pelo-govbr! ente cpf senha))))))
        (testing "a descoberta do botao depende da config"
          (is (some? (kc/govbr-endpoints (merge (:govbr kc-cfg) (select-keys kc-cfg [:base-url])))))))
      (finally
        (component/stop ip)
        (apagar-realm! ente)))))
