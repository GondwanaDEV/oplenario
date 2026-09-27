# ADR-0016 — O operador da plataforma: realm próprio com chave física, e o registro de Casas por handoff

- **Status:** aceita (27/09/2026)
- **Contexto de decisão:** §22.10 (3ª categoria de módulo, supratenant: `admin_sistema`), §22.5.1 (admin interno:
  IdP fisicamente separado, hardware key obrigatória), §22.5.2 eixo E (`ente_id` ausente = supratenant, só em
  `admin_sistema`; cross-esfera → negado), §22.9 Eixo 6, `produto/13` §16.12 (12.1, 12.4, 12.5, 12.8 — "pré-requisito
  barato e inadiável")
- **Relacionadas:** ADR-0005 (o 1º `admin_ente` só podia vir do `admin_sistema` — P1), ADR-0015 (re-provisionar os
  realms quando o gov.br é ligado)

## Contexto

O `admin_sistema` era 16 arquivos de comentário. Nenhum código criava uma Casa fora das sementes de demo, e o 1º
administrador de uma Casa era inserido direto no banco. O login inteiro pressupunha uma Casa: a sessão exige
`ente_id`, a allowlist de issuer só aceita realms `ente-<uuid>`, o BFF só conhece esse formato de realm.

## Decisão

1. **O operador tem realm próprio (`operacao`).** Em produção ele fica num Keycloak **fisicamente separado**
   (`OPERACAO_KC_*`). Em dev e CI o mesmo container serve os dois, por herança de config.
   - O token do realm carrega `operador-id` e nenhuma Casa.
   - A verificação reusa o núcleo do kernel (`keycloak-idp/verificar-jwt`: allowlist de issuer antes da chave,
     RS256, audiência, fail-closed), com outra allowlist e outra audiência (`oplenario-operacao`).
2. **O login do operador exige senha E chave física.**
   - O fluxo do navegador `operacao-navegador` tem o subfluxo `senha + webauthn-authenticator`, ambos REQUIRED.
   - A política WebAuthn usa `cross-platform`, ou seja, a chave de segurança e não a biometria do aparelho.
   - `:aaguids` restringe os modelos aceitos quando a Operação definir a frota.
   - Não há grant direto de senha, nem cadastro aberto, e há proteção contra força bruta.
   - Sessão do realm: 8 h absoluta, 15 min ociosa.
   - **A atestação é `direct` por padrão.** O Keycloak confere a cadeia de certificados da chave contra o próprio
     truststore, que precisa ter as raízes FIDO dos modelos aceitos. Sem elas nenhuma chave registra, e o erro é
     "invalid cert path" (falha fechada, achado no teste com autenticador virtual). Dev, demo e CI usam `none`
     (`OPERACAO_ATESTACAO`).
3. **O operador é principal supratenant, com RBAC disjunto.**
   - Tabela `admin_sistema.operador`, com papel `operador`, estado `ativo|desligado`.
   - Sessão opaca em `admin_sistema.sessao_operador`, cookie **`sessao_operacao`**.
   - Um role de banco próprio, `oplenario_operacao`, que o pool herda e que o domínio (`oplenario_app`) não
     enxerga.
   - O primeiro operador entra por linha de comando (`oplenario.main operador-convidar <email> <nome>`) e sai por
     `operador-desligar`, que derruba as sessões no console e no realm.
4. **Separação de esferas (12.8, 2ª dimensão do teste de vazamento).**
   - As rotas `/operacao/*` usam um interceptor próprio (`autenticacao-operador`). A checagem `:supratenant` do
     kernel roda nele.
   - Sessão de Casa, token de Casa e credencial de agente recebem 401 no console. Cookie e token do console
     recebem 401 em qualquer rota de Casa.
   - No banco, o domínio de uma Casa leva `permission denied` nas tabelas do operador.
   - No catálogo, toda rota `:admin-sistema/*` é da categoria nova `:supratenant`: o agente é de uma Casa e o
     console fica fora do alcance dele (o lint cobra).
5. **A atuação do operador (12.5) é append-only com selo encadeado.**
   - `admin_sistema.atuacao`: cada registro sela o anterior (sha256), serializado por advisory lock.
   - O role só insere e lê.
   - `verificar-corrente` aponta o primeiro registro adulterado.
6. **O registro de Casas (12.1) é handoff, não controle.**
   - `POST /operacao/casas` faz, em ordem:
     1. emite o `ente_id`;
     2. entrega o perfil ao `cadastros`, com o município de referência se faltar;
     3. cria o 1º administrador pelo CPF (vínculo `servidor` + `admin_ente`), o que resolve o P1 do ADR-0005;
     4. convida pelo Keycloak da Casa.
   - Nada cruza módulo por import: o host injeta os seams, como no resto do §22.10.
   - A Casa fica `provisionar` até o administrador **entrar**. O mint marca o 1º acesso do vínculo e emite
     `identidade.vinculo.primeiro_acesso`. O consumidor do `admin_sistema` ativa a Casa e sela "casa-ativada" sem
     operador: foi a Casa.
   - Se o Keycloak falhar no convite, a Casa fica registrada e o console diz que o convite não saiu. "Reenviar
     convite" retoma de forma idempotente.
   - "Reaplicar configuração de login" re-provisiona o realm da Casa, que era o carry do ADR-0015.
   - O CPF do administrador não fica no registro nem sai no console.

## Consequências

- O console mostra só metadado: estado, 1º administrador e a atuação da Operação. Os dados da Casa ficam fora até
  existir o **acesso de suporte (12.7)**, que depende de consentimento/LGPD, um `[GAP]` jurídico. A tela diz isso em
  vez de mostrar um botão morto.
- **Ficam para fatias seguintes:** suspender/encerrar (com o portal da LAI no ar), billing (12.2, build-vs-buy
  parqueado), flags por Casa (12.3) e observabilidade cross-tenant (12.6).
- **Área do `admin_ente`.** Com o bootstrap existindo, o P1 do ADR-0005 cai: o próximo passo é a área própria do
  `admin_ente`. Hoje quem só tem esse papel cai na tela inicial da cidadã depois do login.
- **Obrigações de deploy em produção:**
  - `OPERACAO_KC_BASE_URL` e credenciais admin do Keycloak separado;
  - `OPERACAO_REDIRECT_URIS`;
  - as raízes FIDO no truststore do Keycloak da Operação;
  - no BFF: `OPERACAO_KEYCLOAK_INTERNAL_URL`, se o frontend falar com ele pela rede interna.
- Demo: `demo/semear-credenciais.sh` semeia a operadora `operacao@oplenario.dev`. A chave de segurança é cadastrada
  no primeiro login; num navegador de demo, use o autenticador virtual do DevTools.
