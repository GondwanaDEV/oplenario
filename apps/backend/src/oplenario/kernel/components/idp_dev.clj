(ns oplenario.kernel.components.idp-dev
  "Impl DEV/TESTE do IdentityProvider (§22.5): `verificar-token` decodifica o token como JSON de claims —
  CONFIANCA TOTAL, SEM verificacao de assinatura (so dev/teste). A impl Keycloak real (JWKS + realm-por-tenant
  + IdP do operador fisicamente separado) e' carry F1.4 (infra-gated). Provisionamento lanca aqui (indisponivel
  em dev). NUNCA usar em producao — o boot de prod deve injetar a impl Keycloak."
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
  (provisionar-realm! [_ _] (throw (ex-info "idp-dev: provisionamento indisponivel (carry Keycloak)" {})))
  (criar-usuario! [_ _ _] (throw (ex-info "idp-dev: criar-usuario indisponivel (carry Keycloak)" {})))
  (convidar! [_ _ente-id _identidade-id]
    (throw (ex-info "idp-dev nao envia convite (use o KeycloakIdp)" {:tipo :idp/nao-suportado})))
  (resetar-mfa! [_ _ _] (throw (ex-info "idp-dev: reset-mfa indisponivel (carry Keycloak)" {}))))

(defn idp-dev
  "Component IdP de dev/teste (sem estado/Lifecycle). Prod injeta a impl Keycloak (carry F1.4)."
  []
  (->IdpDev))
