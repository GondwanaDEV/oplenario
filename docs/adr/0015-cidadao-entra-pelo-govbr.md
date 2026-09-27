# ADR-0015 — O cidadão entra pelo gov.br: broker no realm da Casa, sessão só de cidadão

- **Status:** aceita (27/09/2026)
- **Contexto de decisão:** §22.5.1 (cidadão só via gov.br, bronze/prata/ouro iguais na V1), §22.5.2 eixo D
  (`identidade_externa`, anti-takeover, consentimento na 1ª vinculação), §22.9 Eixo 6 (gov.br como IdP externo do
  Keycloak; realm por ente, v1.45)
- **Relacionadas:** ADR-0005 (acesso é área do `admin_ente`), ADR-0010 (identidade delegada)

## Contexto

As rotas de escrita do cidadão já existem: pedido e recurso de e-SIC, solicitação LGPD, manifestação de ouvidoria,
comentário e acompanhamento de matéria. Todas tiram a Casa e a pessoa do `ator`. Faltava o cidadão ter um `ator`, e o
portal mostrava "Entrar com gov.br — chega numa fatia futura". A tabela `identidade_externa` e o guard contra
reciclagem de `sub` já estavam prontos desde a F1; ninguém os chamava.

## Decisão

1. **O gov.br é um IdP do realm de cada Casa** (`ente-<id>`), como o §22.9 manda. O provisionamento do realm o
   configura quando `:govbr` existe na config (sem config → sem botão, fail-closed). O fluxo é OIDC com PKCE S256 e
   `client_secret_basic`. O `sub` do gov.br **é o CPF**. Bronze, prata e ouro valem igual; nível mínimo por fluxo
   continua fora da V1.
2. **Do gov.br entram só nome e CPF**, como a tela promete. Não se importa e-mail. Isso também tira a colisão com a
   conta institucional de quem é servidor e cidadão ao mesmo tempo. O usuário criado no Keycloak se chama
   `govbr-<cpf>` e guarda o CPF no atributo `govbr-sub`, declarado só para admin: a pessoa não o edita. O primeiro
   login usa o fluxo `govbr-primeiro-login`, que só **cria** o usuário. Não existe caminho de vincular a uma conta
   existente, então um gov.br nunca "assume" a conta de passkey de um vereador.
3. **O token diz por onde a pessoa entrou.** Os clients emitem `govbr-sub` e `idp`, a nota de sessão
   `identity_provider` do Keycloak. Com `idp = govbr`, o backend **ignora `identidade-id`** e trata a sessão como de
   cidadão, venha o token de quem vier.
4. **No mint (`POST /auth/sessoes`), o primeiro login do cidadão cria o que falta**:
   - a identidade pelo CPF (idempotente; se o CPF já é de um servidor ou vereador, é a mesma pessoa, âncora
     §22.5.3 disc.1);
   - o vínculo `identidade_externa(gov_br, cpf)`, com o anti-takeover que já existia;
   - o vínculo `cidadao` na Casa;
   - o consentimento da 1ª vinculação (`participacao_cidada`, versão do termo da tela).

   Um vínculo de cidadão suspenso não abre sessão. Fora do mint (Bearer direto), a resolução é só leitura.
5. **A sessão de cidadão só é de cidadão.** A sessão opaca ganha `vinculo_tipo` (mig 0099). Quando ele é `cidadao`,
   o ator é o vínculo de cidadão **com zero papéis**, mesmo que a mesma identidade seja vereadora nessa Casa. Os
   poderes institucionais continuam exigindo o login institucional (passkey), nunca o nível bronze do gov.br.
6. **O gov.br simulado** (`govbr-simulado`, um realm no mesmo Keycloak, com CPF como claim) serve dev, demo e CI.
   Mesma porta, outra configuração. O fake não espera o credenciamento.

## Consequências

- Cada Casa nova é uma URL de retorno a mais no cliente gov.br
  (`…/realms/ente-<id>/broker/govbr/endpoint`), cadastrada no gov.br. É custo operacional do realm por ente. Se o
  cadastro por Casa pesar, a alternativa é um realm único de cidadão (cidadão não tem poder de tenant, então a razão
  do realm por ente pesa menos para ele), mas isso reabre o §22.9 Eixo 6 e é decisão do Daouda.
- `[GAP]` externo: o credenciamento no gov.br (client_id/secret de homologação e produção, URLs de retorno) e o texto
  final da política de privacidade da tela.
- Quem é servidor e cidadão tem duas contas no realm (institucional e `govbr-<cpf>`) sobre **uma** identidade. A
  trilha por `identidade_id` atravessa as duas.
