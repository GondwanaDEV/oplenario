(ns oplenario.identidade.autenticacao
  "Resolucao de SESSAO (§22.5 eixo D): de claims de token VERIFICADO -> o `ator` que a camada de
  autorizacao (kernel/autorizacao) consome. Carrega o vinculo ATIVO + o snapshot de papeis do tenant
  numa UNICA tx via o RepoIdentidade (§3-bis: passa pelo Repo-Component, NUNCA pelo db/ direto).
  Fail-closed: sem vinculo ativo -> nil (sem sessao). O `ator` bate a forma de kernel/autorizacao
  ({:identidade-id :ente-id :papeis ...}).

  A VERIFICACAO do token (assinatura/exp/issuer) e' do IdP port (kernel/components/idp); aqui as claims ja
  vem verificadas. Os fluxos VIVOS — login passkey, broker gov.br, provisionamento de realm-por-tenant,
  e os interceptors Pedestal que chamam isto — sao infra-gated (Keycloak vivo + credencial gov.br +
  rotas F3) -> carry F1.4. Este e' o seam estavel, testavel contra o DB real."
  (:require [oplenario.identidade.components.repositorio :as repo]))

(set! *warn-on-reflection* true)

(defn resolver-sessao
  "claims VERIFICADAS {:identidade-id :ente-id} + o RepoIdentidade -> ator {:identidade-id :ente-id
  :tipo-vinculo :vinculo-ativo-id :papeis} ou nil (fail-closed) se nao ha vinculo ATIVO da identidade
  no ente. Um vinculo SUSPENSO/ENCERRADO nao da sessao (§22.5 eixo G). O operador supratenant (sem
  ente-id) e' caso a parte (admin_sistema), nao tratado aqui. A selecao do vinculo ativo e' deterministica
  (ORDER BY no repo) — multi-vinculo nao escolhe ao acaso."
  [repo-identidade {:keys [identidade-id ente-id]}]
  (when (and identidade-id ente-id)
    (when-let [{:keys [vinculo-ativo papeis]} (repo/snapshot-ator repo-identidade ente-id identidade-id)]
      {:identidade-id    identidade-id
       :ente-id          ente-id
       :tipo-vinculo     (:tipo vinculo-ativo)
       :vinculo-ativo-id (:id vinculo-ativo)
       :papeis           papeis})))
