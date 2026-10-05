(ns oplenario.kernel.components.idp-dev
  "Impl DEV/TESTE do IdentityProvider (§22.5): `verificar-token` decodifica o token como JSON de claims —
  CONFIANCA TOTAL, SEM verificacao de assinatura (so dev/teste). A impl Keycloak real (JWKS + realm-por-tenant
  + IdP do operador fisicamente separado) e' carry F1.4 (infra-gated). O provisionamento e' um NO-OP aqui: dev nao
  tem realm, usuario nem e-mail, e quem concede acesso (`identidade/diplomat/http/in.clj`) grava o vinculo ANTES de
  chamar o IdP — lancar aqui deixava o ato gravado com resposta 500. Quem precisa do IdP de verdade usa o
  KeycloakIdp (a falha dele LANCA e continua visivel; so' o fake de dev e' mudo). NUNCA usar em producao — o boot de
  prod deve injetar a impl Keycloak."
  (:require [jsonista.core :as json]
            [oplenario.kernel.components.idp :as idp]))

(set! *warn-on-reflection* true)

(defn- ->uuid [s] (when s (java.util.UUID/fromString s)))

(defrecord IdpDev []
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
  ;; Provisionamento: no-op coerente com um IdP que so' finge sessao por token. Nao ha' realm a criar nem e-mail a enviar,
  ;; e o que o handler de conceder acesso precisa saber (`:existia?`) e' "nao" — a pessoa nunca teve conta no IdP.
  (provisionar-realm! [_ _] true)
  (provisionar-realm! [_ _ _] true)
  (criar-usuario! [_ _ _] {:existia? false})
  (convidar! [_ _ente-id _identidade-id] true)
  (corrigir-email-do-convite! [_ _ _ _] true)
  (resetar-mfa! [_ _ _] (throw (ex-info "idp-dev: reset-mfa indisponivel (carry Keycloak)" {})))
  ;; dev nao provisiona realm nenhum (provisionar-realm! e' no-op) -> nao ha' o que apagar: o realm "ja' inexistente"
  ;; do contrato. Nunca roda em producao (idp-para so' liga o idp-dev em dev/test).
  (apagar-realm! [_ _] {:realm nil :existia? false}))

(defn idp-dev
  "Component IdP de dev/teste (sem estado/Lifecycle). Prod injeta a impl Keycloak (carry F1.4)."
  []
  (->IdpDev))
