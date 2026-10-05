(ns oplenario.kernel.components.idp
  "Protocolo do IdP (Keycloak, §22.9 Eixo 6 / §22.5). PORT de saida (kernel) — a impl concreta e' um
  Component no kernel/components/idp que fala com o Keycloak. Duas familias de operacao:
   - PROVISIONAMENTO (admin): realm-por-tenant + IdP do operador FISICAMENTE separado (§22.5.1).
   - VERIFICACAO DE TOKEN: claims de um access token ja emitido (assinatura+expiracao+issuer).
  A resolucao de SESSAO (claims -> ator com vinculo+papeis) NAO vive aqui — e' do modulo identidade
  (dono do vinculo); este port so entrega claims crus + provisiona. Seam estavel; impl real = carry F1.4.")

(defprotocol IdentityProvider
  (verificar-token [idp token]
    "Verifica assinatura/expiracao/issuer de um access token e devolve as CLAIMS cruas (mapa) ou nil
    se invalido. Fail-closed: token malformado/expirado/assinatura ruim -> nil, nunca claims parciais.
    CONTRATO (review W2): erro de INFRA (rede/JWKS indisponivel) deve LANCAR (a borda responde 500), NUNCA
    devolver nil — nil = 'token invalido' (401), e mascarar degradacao como token ruim e' incorreto.")
  (provisionar-realm! [idp ente-id]
    "Provisiona o realm do tenant `ente-id` (realm-por-tenant). Idempotente. Carry: impl Keycloak.")
  (criar-usuario! [idp ente-id usuario]
    "Cria o usuario no realm do tenant (enrollment); MFA obrigatorio na 1a sessao (§22.5.2 eixo F).")
  (convidar! [idp ente-id identidade-id]
    "Dispara o BOOTSTRAP de 1o acesso: o IdP envia codigo de uso unico ao e-mail institucional, que abre
    a sessao e OBRIGA o cadastro de passkey antes de qualquer acao (§22.5.2 eixo F). Operacao PROPRIA (nao
    dobrada em criar-usuario!) porque 'reenviar convite' e' acao de produto separada. Idempotente: reenviar
    invalida o codigo anterior. O e-mail sai do IdP, NAO da aplicacao — nao confundir com o carry F6
    (e-mail transacional da app). Erro de infra LANCA (borda -> 500), nunca devolve false.")
  (corrigir-email-do-convite! [idp ente-id identidade-id email]
    "Troca o e-mail do usuario no realm da Casa SO' se ele ainda nao tem credencial nenhuma (nunca concluiu o convite):
    sem credencial nao ha' conta a tomar, e o proximo convite vai para o e-mail certo. Com credencial lanca
    `:idp/conta-ja-ativa`; e-mail ja' usado por outra pessoa no realm lanca `:idp/email-em-uso`; usuario ausente lanca
    `:idp/usuario-inexistente`. Depois do primeiro acesso, quem troca o e-mail e' a propria pessoa, na conta dela.
    Erro de infra LANCA.")
  (resetar-mfa! [idp ente-id identidade-id]
    "Reset de fator (ato auditado, nunca autoatendido p/ servidor/vereador — §22.5.2 eixo F).")
  (apagar-realm! [idp ente-id]
    "O INVERSO de `provisionar-realm!` (ADR-0018, Eixo 4.5): apaga o realm da Casa encerrada — usuarios, credenciais,
    clients e o IdP gov.br vao junto. Idempotente: realm ja' inexistente = ok. Devolve {:realm :existia?}. Erro de
    infra LANCA (o apagamento da Casa marca o passo como pendente e e' retomado), nunca devolve sucesso falso."))

;; CLAIMS minimas que a resolucao de sessao espera do token verificado (o resto e' opcional/provider).
;; sub = subject do IdP; ente-id presente p/ tenant, AUSENTE p/ operador supratenant (§22.5.2 eixo E).
(def Claims
  [:map
   [:sub :string]
   [:identidade-id {:optional true} [:maybe :uuid]]
   [:ente-id {:optional true} [:maybe :uuid]]
   [:exp {:optional true} [:maybe :int]]])

;; Record/Component (impl Keycloak: verificar-token via JWKS + provisionar realm-por-tenant +
;; IdP do operador FISICAMENTE separado, §22.5.1) = carry F1.4 (infra-gated; rotas Pedestal na F3).
