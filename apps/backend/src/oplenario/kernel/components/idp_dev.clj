(ns oplenario.kernel.components.idp-dev
  "Impl DEV/TESTE do IdentityProvider (§22.5): `verificar-token` decodifica o token como JSON de claims —
  CONFIANCA TOTAL, SEM verificacao de assinatura (so dev/teste). A impl Keycloak real (JWKS + realm-por-tenant
  + IdP do operador fisicamente separado) e' carry F1.4 (infra-gated). Provisionamento lanca aqui (indisponivel
  em dev), salvo no `idp-do-modo-dev`, que o pula de forma explicita. NUNCA usar em producao — o boot de prod deve
  injetar a impl Keycloak."
  (:require [jsonista.core :as json]
            [oplenario.kernel.components.idp :as idp]))

(set! *warn-on-reflection* true)

(defn- ->uuid [s] (when s (java.util.UUID/fromString s)))

(def ^:private pulado {:provisionamento :pulado-em-dev})

(defn- recusar [msg dados] (throw (ex-info msg dados)))

;; `pular-provisionamento?`: o modo dev da stack (APP_ENV=dev, sem Keycloak) — o host liga por `idp-do-modo-dev`, na
;; MESMA decisao que liga o login por token de dev (`sistema/idp-para`). Ai' provisionar realm, criar usuario, convidar e
;; corrigir e-mail sao PULADOS de forma explicita: nada e' criado (nem conta, nem senha, nem e-mail) e a resposta diz
;; isso (`:provisionamento :pulado-em-dev`, sem `:keycloak-user-id`). O `admin_ente` concede acesso em /administracao e
;; recebe o mesmo 201 de producao; a pessoa entra pelo token de dev, como todo mundo em dev. Sem a flag (o construtor
;; cru, o dos testes que so' precisam do token), cada operacao de provisionamento LANCA, como sempre.
(defrecord IdpDev [pular-provisionamento?]
  idp/IdentityProvider
  (verificar-token [_ token]
    ;; token = JSON de claims (ex.: {"sub":"...","identidade-id":"<uuid>","ente-id":"<uuid>"}). uuid chega
    ;; como string no JSON -> normaliza. Token malformado -> nil (fail-closed, igual contrato do port).
    (try
      (let [c (json/read-value token json/keyword-keys-object-mapper)]
        (when (map? c)   ; JSON nao-objeto (ex.: "x", 42) = token invalido -> nil (contrato do port)
          (cond-> c
            (:identidade-id c) (update :identidade-id ->uuid)
            (:ente-id c)       (update :ente-id ->uuid))))
      (catch Exception _ nil)))
  (provisionar-realm! [_ _]
    (if pular-provisionamento? pulado (recusar "idp-dev: provisionamento indisponivel (carry Keycloak)" {})))
  (provisionar-realm! [_ _ _]
    (if pular-provisionamento? pulado (recusar "idp-dev: provisionamento indisponivel (carry Keycloak)" {})))
  (criar-usuario! [_ _ _]
    (if pular-provisionamento?
      (assoc pulado :existia? false)   ; nunca ha' conta previa: o e-mail informado conta como "novo"
      (recusar "idp-dev: criar-usuario indisponivel (carry Keycloak)" {})))
  (convidar! [_ _ente-id _identidade-id]
    (if pular-provisionamento?
      pulado
      (recusar "idp-dev nao envia convite (use o KeycloakIdp)" {:tipo :idp/nao-suportado})))
  (corrigir-email-do-convite! [_ _ _ _]
    (if pular-provisionamento?
      pulado
      (recusar "idp-dev nao guarda e-mail (use o KeycloakIdp)" {:tipo :idp/nao-suportado})))
  (resetar-mfa! [_ _ _] (recusar "idp-dev: reset-mfa indisponivel (carry Keycloak)" {}))
  ;; dev nao provisiona realm nenhum -> nao ha' o que apagar: o realm "ja' inexistente" do contrato. Nunca roda em
  ;; producao (idp-para so' liga o idp-dev em dev/test).
  (apagar-realm! [_ _] {:realm nil :existia? false}))

(defn idp-dev
  "Component IdP de teste (sem estado/Lifecycle): so' verifica o token de dev; o provisionamento LANCA. Prod injeta a
  impl Keycloak."
  []
  (->IdpDev false))

(defn idp-do-modo-dev
  "O IdP que o HOST liga em dev/test (`sistema/idp-para`): o mesmo token de dev, e o provisionamento no Keycloak pulado
  de forma explicita (ver o record). NUNCA fora de dev/test."
  []
  (->IdpDev true))
