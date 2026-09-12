(ns keycloak-admin
  "Admin-API do Keycloak — helpers COMPARTILHADOS entre as sementes que falam DIRETO com o Keycloak por
  fora do protocolo `oplenario.kernel.components.idp` (que ja' cobre `provisionar-realm!`/`criar-usuario!`,
  mas nao seta senha nem limpa required-actions — esses dois passos so' fazem sentido p/ quem FURA o
  bootstrap de passkey de proposito: `demo/personas.clj` e o `slice5` de `demo/seed_demo.clj`).

  ORIGEM: replicados verbatim (so' generalizando base-url/credenciais em vez de hardcode), mesmo
  racional ja documentado em `test/keycloak/oplenario/keycloak/ponta_a_ponta_test.clj:85-108` p/
  `setar-senha-teste!`/`limpar-required-actions-teste!` — `demo/` nao pode requerer `test/` (nem o
  inverso). Antes desta ns, `seed_demo.clj` tinha a SUA PROPRIA copia `^:private`; esta ns vira a FONTE
  UNICA e `seed_demo.clj` passa a consumi-la em vez de manter a 2a copia.

  A ARMADILHA DO TOKEN DE 56s (ja' paga neste projeto, ver `oplenario-subir-com-login` na memoria de
  sessao): o token admin do Keycloak 26 (client `admin-cli`, ROPC no realm master) vive so' ~56s por
  default. Um provisionamento com mais de um punhado de chamadas HTTP sequenciais (realm + N usuarios ×
  senha+required-actions) expira NO MEIO — e falha em etapa DIFERENTE a cada tentativa, o que parece
  flakiness mas e' relogio. `estender-lifespan-admin-token!` mata isso alongando o lifespan do realm
  MASTER p/ 3600s ANTES de qualquer outra chamada admin. E' configuracao de RUNTIME do Keycloak (nao
  sobrevive a um container do KC recriado do zero) — por isso quem provisiona faz isso sempre, nunca o
  operador manualmente."
  (:require [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)))

(defn admin-token!
  "Token admin via ROPC no realm master, client publico builtin `admin-cli` (KEYCLOAK_ADMIN/_PASSWORD
  do docker-compose dev). Lanca em erro de infra (nunca devolve token nil silenciosamente)."
  [^HttpClient http base-url admin-usuario admin-senha]
  (let [corpo (str "grant_type=password&client_id=admin-cli"
                    "&username=" admin-usuario "&password=" admin-senha)
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str base-url "/realms/master/protocol/openid-connect/token")))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString corpo))
                (.build))
        resp (.send http req (HttpResponse$BodyHandlers/ofString))]
    (if (= 200 (.statusCode resp))
      (get (json/read-value (.body resp) json/keyword-keys-object-mapper) :access_token)
      (throw (ex-info "keycloak-admin: falha ao obter token admin (infra)"
                       {:status (.statusCode resp) :corpo (.body resp)})))))

(defn estender-lifespan-admin-token!
  "PUT PARCIAL em /admin/realms/master (so' :accessTokenLifespan no corpo — mesmo padrao ja' validado
  contra o Keycloak 26 vivo por `keycloak_idp.clj/configurar-smtp!`: PUT parcial na raiz do realm nao
  zera os demais campos omitidos) — 3600s mata a janela de 56s. `token` precisa ser um token admin
  RECEM-obtido (a chamada em si ainda roda sob o lifespan ANTIGO; so' os tokens obtidos DEPOIS desta
  chamada ganham a janela nova). Falha alto — nunca segue o provisionamento com o realm master
  parcialmente configurado."
  [^HttpClient http base-url token]
  (let [resp (.send http (-> (HttpRequest/newBuilder)
                              (.uri (URI/create (str base-url "/admin/realms/master")))
                              (.header "Authorization" (str "Bearer " token))
                              (.header "Content-Type" "application/json")
                              (.PUT (HttpRequest$BodyPublishers/ofString "{\"accessTokenLifespan\":3600}"))
                              (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 204 (.statusCode resp))
      (throw (ex-info "keycloak-admin: falha ao alongar o lifespan do token admin (infra)"
                       {:status (.statusCode resp) :corpo (.body resp)})))))

(defn setar-senha!
  "Reset de senha NAO-temporaria via admin-API. Falha alto (a copia original em `seed_demo.clj` nao
  checava o status — corrigido aqui: um 4xx silencioso deixaria a persona sem senha utilizavel e o
  operador so' descobriria no login, longe da causa)."
  [^HttpClient http base-url token realm kc-user-id senha]
  (let [resp (.send http (-> (HttpRequest/newBuilder)
                              (.uri (URI/create (str base-url "/admin/realms/" realm "/users/" kc-user-id "/reset-password")))
                              (.header "Authorization" (str "Bearer " token))
                              (.header "Content-Type" "application/json")
                              (.PUT (HttpRequest$BodyPublishers/ofString
                                     (str "{\"type\":\"password\",\"value\":\"" senha "\",\"temporary\":false}")))
                              (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 204 (.statusCode resp))
      (throw (ex-info "keycloak-admin: falha ao setar senha (infra)"
                       {:status (.statusCode resp) :corpo (.body resp)})))))

(defn limpar-required-actions!
  "Zera as required-actions do usuario (ex.: `webauthn-register-passwordless`, que `criar-usuario!` do
  IdentityProvider crava sempre) — sem isto o usuario nao consegue logar so' com senha."
  [^HttpClient http base-url token realm kc-user-id]
  (let [resp (.send http (-> (HttpRequest/newBuilder)
                              (.uri (URI/create (str base-url "/admin/realms/" realm "/users/" kc-user-id)))
                              (.header "Authorization" (str "Bearer " token))
                              (.header "Content-Type" "application/json")
                              (.PUT (HttpRequest$BodyPublishers/ofString "{\"requiredActions\":[]}"))
                              (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 204 (.statusCode resp))
      (throw (ex-info "keycloak-admin: falha ao limpar required-actions (infra)"
                       {:status (.statusCode resp) :corpo (.body resp)})))))
