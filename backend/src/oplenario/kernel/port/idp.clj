(ns oplenario.kernel.port.idp
  "Protocolo do IdP (Keycloak, §22.9 Eixo 6 / §22.5). PORT de saida (kernel) — a impl concreta e' um
  Component no kernel/components/idp que fala com o Keycloak. Duas familias de operacao:
   - PROVISIONAMENTO (admin): realm-por-tenant + IdP do operador FISICAMENTE separado (§22.5.1).
   - VERIFICACAO DE TOKEN: claims de um access token ja emitido (assinatura+expiracao+issuer).
  A resolucao de SESSAO (claims -> ator com vinculo+papeis) NAO vive aqui — e' do modulo identidade
  (dono do vinculo); este port so entrega claims crus + provisiona. Seam estavel; impl real = carry F1.4.")

(defprotocol IdentityProvider
  (verificar-token [idp token]
    "Verifica assinatura/expiracao/issuer de um access token e devolve as CLAIMS cruas (mapa) ou nil
    se invalido. Fail-closed: token malformado/expirado/assinatura ruim -> nil, nunca claims parciais.")
  (provisionar-realm! [idp ente-id]
    "Provisiona o realm do tenant `ente-id` (realm-por-tenant). Idempotente. Carry: impl Keycloak.")
  (criar-usuario! [idp ente-id usuario]
    "Cria o usuario no realm do tenant (enrollment); MFA obrigatorio na 1a sessao (§22.5.2 eixo F).")
  (resetar-mfa! [idp ente-id identidade-id]
    "Reset de fator (ato auditado, nunca autoatendido p/ servidor/vereador — §22.5.2 eixo F)."))

;; CLAIMS minimas que a resolucao de sessao espera do token verificado (o resto e' opcional/provider).
;; sub = subject do IdP; ente-id presente p/ tenant, AUSENTE p/ operador supratenant (§22.5.2 eixo E).
(def Claims
  [:map
   [:sub :string]
   [:identidade-id {:optional true} [:maybe :uuid]]
   [:ente-id {:optional true} [:maybe :uuid]]
   [:exp {:optional true} [:maybe :int]]])
