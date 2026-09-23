# 18 — Personas da demo: credenciais + roteiro de exploração

Mapa das **5 personas nomeadas da Casa** (mais o **cidadão anônimo**, sem login) sobre a Casa única da
demo (`ente-id` fixo `10000000-0000-0000-0000-000000000001`, Câmara Municipal de Fortaleza), semeada
pelas 4 sementes narrativas (`casa` → `acervo` → `sessoes` → `participacao`, `./demo/semear-tudo.sh`) e
credenciada pela 5ª semente (`./demo/semear-credenciais.sh`, `apps/backend/demo/personas.clj`).

A 5ª persona nomeada (**Apresentação — acesso total**) entrou depois das outras 4, especificamente para
demo comercial de visita única (pedido do Rigoni — sócio comercial, apresentação para presidente de
câmara): empilha `vereador`+`secretario`+`admin_ente` no MESMO vínculo, então 1 login alcança tudo que
as 3 outras personas de trabalho alcançam juntas, sem trocar de sessão. Ver a seção própria dela abaixo
— ela NÃO passou pela mesma varredura de verificação ao vivo (§ "Verificado AO VIVO") que as 4
originais, que é anterior a ela.

O roteiro de cada persona abaixo está **ancorado no dado que as 4 sementes narrativas de fato criaram**
(lido da fonte em `apps/backend/demo/*.clj`) e na **superfície HTTP real que cada rota expõe** (lida em
`apps/backend/src/oplenario/*/diplomat/http/in.clj`) — nunca em cenário inventado.

## Como rodar

```
./demo/semear-tudo.sh          # a Casa + o acervo + as 3 sessões + a participação (sem Keycloak)
OPLENARIO_APP_ENV=production docker compose --profile auth up -d   # Keycloak + Mailpit (~1min p/ bootar)
./demo/semear-credenciais.sh   # provisiona o realm + os 4 usuários Keycloak, imprime o cartão
```

As credenciais também ficam gravadas em `e2e/.artifacts/credenciais.edn` (mesmo scratch de
`demo-ids.edn` — corrigido em `0a97fe0`: a 1ª versão do script montava `apps/backend` `:ro` mas não a
outra metade do par, o scratch gravável, e o provisionamento inteiro corria antes de estourar
`FileNotFoundException ... Read-only file system` na gravação; defeito só alcançável contra um Keycloak
de verdade). **Senha única das 4 personas: `Plenario@2026`** — credencial de **demonstração local**,
nunca de produção; este repositório não tem `git remote` configurado (`git remote -v` vazio), então não
há canal de vazamento por push — é fixture de dev, não segredo.

## O defeito consertado antes deste mapa existir

Antes desta frente, `casa/criar-identidades!` criava a identidade da cidadã (Roberta Costa Aguiar) mas
**nunca lhe dava vínculo** — só secretaria/presidente/vereador ganhavam `identidade.vinculo`.
`autenticacao/resolver-sessao` é fail-closed (sem vínculo ATIVO → `nil`, sem sessão): na prática, **a
superfície do cidadão autenticado nunca teve ator na demo**, e por isso nunca foi explorada. A cidadã
agora tem vínculo `tipo "cidadao"`, **sem papel nenhum** (quem trabalha na Casa tem papel —
secretario/vereador/admin_ente; quem só consulta ou peticiona, não).

## As 6 personas — cartão resumo

| Persona | Nome | Vínculo · papéis | Username (Keycloak) | Senha | URL de entrada |
|---|---|---|---|---|---|
| **Secretária** | Marina Alencar Freire | `servidor` · `secretario` | `identidade-id` da secretaria (ver `credenciais.edn`) | `Plenario@2026` | `http://localhost:3000/entrar/10000000-0000-0000-0000-000000000001` |
| **Presidente da Mesa** | Antônio Carlos Ferreira | `vereador` · `vereador`, `admin_ente` | idem | `Plenario@2026` | idem |
| **Vereadora** | Fernanda Rocha Pinto | `vereador` · `vereador` | idem | `Plenario@2026` | idem |
| **Cidadã** | Roberta Costa Aguiar | `cidadao` · *(sem papel)* | idem | `Plenario@2026` | idem |
| **Apresentação (acesso total)** | Patrícia Nogueira Santos | `vereador` · `vereador`, `secretario`, `admin_ente` | idem | `Plenario@2026` | idem |
| **Cidadão anônimo** | — | — (sem login) | — | — | `http://localhost:3000/portal/casa/10000000-0000-0000-0000-000000000001` |

As 5 personas nomeadas entram pela **mesma URL** (`/entrar/<ente-id>`) — um único realm-por-tenant
(§22.5.1); o app roteia cada uma pelo **papel do token** depois do login. Os `identidade-id` reais
(usados como `username` no Keycloak) saem impressos por `semear-credenciais.sh` e gravados em
`credenciais.edn` — não são repetidos aqui porque são UUIDs gerados a partir do CPF fixo, estáveis
entre execuções mas não literais fáceis de citar num doc estático.

## Verificado AO VIVO — matriz de autorização (12/09/2026)

> Esta varredura é **anterior** à persona "Apresentação (acesso total)" — cobre só as 4 originais. A
> combinação de papéis dela (`vereador`+`secretario`+`admin_ente` no mesmo vínculo) usa a MESMA camada
> de autorização (`(:papeis ator)`, `oplenario.kernel.autorizacao/tem-papel?`) que já está provada linha
> por linha abaixo para a presidente (`vereador`+`admin_ente`) — empilhar um 3º papel no mesmo vínculo
> não é mecanismo novo, só mais um elemento no mesmo conjunto.

Daouda verificou as 4 credenciais originais contra um Keycloak real (`docker compose --profile auth up -d`, app
religado com `OPLENARIO_APP_ENV=production` para usar o `KeycloakIdp` de verdade, não o `idp-dev`).
**Método: fluxo PKCE completo, não password grant** — o client `oplenario-web` nasce
`directAccessGrantsEnabled: false` (`keycloak_idp.clj/provisionar-realm-impl`), então não existe atalho
de trocar usuário+senha direto por token; a prova percorreu página de login → POST da credencial →
`code` → troca por `token` → chamada à rota do backend com `Authorization: Bearer` — o mesmo caminho que
o navegador percorre, não um substituto mais fácil de simular.

| Persona | Rota | Esperado | Resultado |
|---|---|---|---|
| Secretária | `GET /paineis/pendencias` | 200 | **200** |
| Presidente da Mesa | `GET /meu/painel` | 200 | **200** |
| Vereadora | `GET /meu/painel` | 200 | **200** |
| Cidadã | `GET /portal/acompanhamentos` | 200 | **200** |
| Vereadora | `GET /paineis/pendencias` | **403** | **403** |
| Cidadã | `GET /paineis/pendencias` | **403** | **403** |
| Cidadã | `GET /meu/painel` | **403** | **403** |

Os 3 negativos pesam mais que os 4 positivos: provam que o **papel** morde de verdade no ambiente real
(não só nos testes unitários da política) — uma vereadora ou uma cidadã autenticada, com token válido,
**não** entra numa tela que exige `secretario`/`vereador` sem ter esse papel.

---

## Secretária da Mesa — Marina Alencar Freire (`secretario`)

O papel operacional da Casa: quem opera o balcão interno, convoca sessão, modera participação e
responde e-SIC/LGPD/ouvidoria.

**O que o dado sustenta:**
- `/cadastros/vereadores` — os 17 vereadores (16 vigentes + 1 licenciado, Thiago Moraes Bezerra, com
  selo de licença), a Mesa Diretora (4 cargos) e as 3 comissões permanentes, cada uma com presidente e
  membros reais.
- `/proposicoes` e `/ficha-materia/:id` — as 24 proposições nos 6 estados do rito real (`protocolada` ×4,
  `em_comissoes` ×4, `aguardando_pauta` ×4, `em_pauta` ×3, `aprovada` ×6, `arquivada` ×3), cada uma com
  texto próprio (nunca lorem ipsum) e autoria distribuída pelos 17 vereadores.
- `/parecer/:id` — os 3 pareceres (rascunho · aguardando assinatura do relator · emitido/aprovado e
  assinado), cada um numa comissão permanente real.
- `/pos-aprovacao` / `/pos-aprovacao/:id` — os 2 autógrafos visíveis (1 aguardando o Executivo, 1
  sancionado) + as 4 normas já promulgadas e publicadas.
- `/pauta-convocacao` — convocar/montar pauta (a sessão AGENDADA já tem pauta montada com as 4
  proposições `aguardando_pauta` + 1 item de expediente).
- `/paineis/mesa` (papel `secretario`) — o painel de compliance e pendências da Mesa.
- Balcão de participação (**backend-only, sem tela — ver a nota de honestidade abaixo**):
  `POST /esic/pedidos/:id/resposta`, `POST /esic/recursos/:id/decisao`, `POST /lgpd/solicitacoes/:id/
  resposta`, `POST /ouvidoria/manifestacoes/:id/resposta`, `GET /moderacao/comentarios` (a fila tem
  **2 comentários pendentes**, de propósito nunca vazia) + `POST /comentarios/:id/moderar`. Já
  respondeu, na semente: 1 dos 3 pedidos e-SIC, 1 das 2 solicitações LGPD, 1 das 2 manifestações de
  ouvidoria — os outros ficam abertos para ela agir ao vivo.
- `/calendario` — sessões + prazos de compliance.
- `/status` — pública, sem exigir login.

**O que NÃO dá para explorar:** o balcão de e-SIC/LGPD/ouvidoria/comentários é **API pura** — não há
`page.tsx` nenhuma sob `apps/frontend/src/app` que chame essas rotas (confirmado por busca no código do
FE). Para ver a secretária respondendo um pedido "ao vivo" é preciso chamar a API direto (curl/Postman
com o token dela), não clicar em nada no navegador.

---

## Presidente da Mesa — Antônio Carlos Ferreira (`vereador` + `admin_ente`)

Vereador comum **mais** o papel administrativo do ente. É também, na semente, **quem discursa** na
tribuna das sessões ENCERRADA e ABERTA (`semear-tribuna!` usa o 1º presente da lista, que é ele —
ordem alfabética do roster).

**O que o dado sustenta:**
- Tudo que qualquer vereador vê (ficha própria, proposições, pareceres, portal público) — ver a seção
  da Vereadora abaixo, que se aplica igual.
- `/cadastros/vereadores` → "Conceder acesso" → `POST /identidade/acessos` (exige `admin_ente`) — os 3
  vereadores criados pelo `slice5` de `seed_demo.clj` (Ana Ribeiro/Bruno Sales/Carla Nunes, **em outro
  ente**, não o desta Casa) são o cenário pronto para essa concessão; **dentro da própria Casa da
  demo**, os 17 vereadores já têm identidade vinculada (exceto o padrão de `slice5`), então para provar
  esta jornada com um alvo real desta Casa é preciso primeiro desvincular um cadastro ou usar o cenário
  do `slice5` — decisão de roteiro de apresentação, não bug.
- `/sessoes/:id/plenario` (a sessão ABERTA, `id-aberta`) — ele é o orador com a fala **em curso**
  (cronômetro correndo, sem encerrar) e está entre os 12 presentes (presença via `painel_eletronico`).
  Como tem papel `vereador`, também pode `POST /sessoes/:id/votacoes/:votacao-id/meu-voto` na votação
  nominal **aberta** dessa sessão (a 3ª das matérias `em_pauta`, ninguém votou ainda — é exatamente a
  jornada "o vereador vota ao vivo").
- Na sessão ENCERRADA (`id-encerrada`), ele já está registrado como presente e como o orador que
  encerrou a fala; já tem voto registrado (auto-semeado) nas 2 votações apuradas — dá para conferir o
  placar em `/ficha-materia/:id`, não para revotar (encerrada).

**O que NÃO dá para explorar:** `/paineis/mesa` exige papel `secretario` no backend — ele **não** tem
esse papel (só `vereador` + `admin_ente`), então essa tela específica devolve 403 para ele mesmo
estando logado (o guard real é sempre server-side; a rota abre no navegador, o conteúdo não vem).

---

## Vereadora — Fernanda Rocha Pinto (`vereador`)

O vereador comum, sem cargo na Mesa nem em comissão de liderança — mas com um papel concreto na
semente: é a **relatora do parecer B** (`aguardando_assinatura`), a jornada de assinatura de parecer.

**O que o dado sustenta:**
- `/meu/painel` (papel `vereador`) → lista os pareceres dela — o parecer B (sobre a proposição
  `aguardando-pauta-1`, na comissão de Finanças e Orçamento) está **aguardando a assinatura dela**.
- `/meu/pareceres/:id` → `/parecer/:id/assinar` (rota do vereador, distinta da do servidor) →
  `POST /meu/pareceres/:id/emissao` — assina o parecer, a jornada J3 que a semente existe para provar
  (ledger #12, `docs/16`: antes desta correção, a relatora do parecer B nunca era ligada a uma
  identidade real e a tela dava 404).
- `/sessoes/:id/plenario` (sessão ABERTA) — ela está entre os 12 presentes (presença via
  `painel_eletronico`, roster ordenado por nome — ela é a 6ª alfabeticamente, dentro dos 12 primeiros) e
  pode votar na votação nominal aberta (`POST .../meu-voto`, papel `vereador`), igual ao presidente.
- Na sessão ENCERRADA, também está entre os 14 presentes e já tem voto registrado (auto-semeado) nas 2
  votações apuradas.
- `/cadastros/vereadores/:id` (a própria ficha) — mandato vigente, partido PSB, sem cargo na Mesa nem
  comissão de liderança (é membro simples da CCJ).
- `(publico)/portal/casa/:ente/vereadores/:id` — o perfil público dela é o mesmo perfil que qualquer
  visitante anônimo vê (ver seção do cidadão anônimo).

**O que NÃO dá para explorar:** `/identidade/acessos` (exige `admin_ente`, ela não tem); `/paineis/mesa`
(exige `secretario`, ela não tem).

---

## Cidadã — Roberta Costa Aguiar (`cidadao`, sem papel)

A persona que **este trabalho desbloqueou**: antes da Parte A, ela não tinha vínculo nenhum — qualquer
rota gated só por `auth` (sem papel) devolvia 401 (sem sessão nenhuma resolvida) para ela sempre.

**O que o dado sustenta — e é dela na semente** (`participacao/semear!` usa `cidadao-id` como
solicitante/autora em tudo abaixo):
- 3 pedidos e-SIC como solicitante: 1 `protocolado` (aberto no prazo), 1 `respondido`, 1 `respondido`
  com um **recurso já interposto e ainda não decidido**. `GET /portal/esic/pedidos/:id` (auth, sem
  papel) mostra qualquer um dos 3, pelo `id`; `POST /portal/esic/pedidos/:id/recursos` deixa interpor
  recurso em outro pedido respondido.
- 2 solicitações LGPD como titular: 1 `protocolada` ("acessar"), 1 `respondida` ("corrigir" — e-mail
  atualizado). `GET /portal/lgpd/solicitacoes/:id`.
- 2 manifestações de ouvidoria como manifestante (não-anônimas): 1 `protocolada` (reclamação), 1
  `respondida` (elogio). `GET /portal/ouvidoria/manifestacoes/:id`.
- 5 comentários dela em proposições reais do acervo — 3 já `aprovado`, 2 ainda `pendente` (a fila que a
  secretária modera). `POST /portal/materias/:id/comentarios` para comentar mais; `POST
  /portal/comentarios/:id/denunciar` para denunciar comentário alheio.
- **3 acompanhamentos ativos** — `GET /portal/acompanhamentos` devolve `:acompanhamentos-total 3`
  (achado da verificação ao vivo de 12/09: a rota respondia 200, mas a lista vinha **vazia**; nenhuma das
  4 sementes narrativas criava acompanhamento nenhum. Conserto em `participacao/semear-acompanhamentos!`,
  escrevendo via `RepoTransparenciaPg/seguir!`, nunca `INSERT` cru — a coluna do dono é
  `seguidor_identidade_id`, não `identidade_id`). As 3 matérias seguidas (o primeiro item que
  `listar-e-contar-proposicoes` devolve para cada filtro de estado, na ordenação real
  `atualizado_em DESC`): **"Altera a Lei Orgânica do Município quanto à composição da Mesa Diretora"**
  (`em_pauta`, NÃO-terminal), **"Manifesta congratulações à comunidade escolar..."** (`em_comissoes`,
  NÃO-terminal) e **"Dispõe sobre a acessibilidade em prédios públicos municipais"** (`aprovada`,
  TERMINAL — de propósito, para mostrar o contraste). `POST /portal/materias/:id/acompanhar` segue mais.
- `GET /meu/notificacoes` (auth, sem papel) — **ainda começa vazia**, mesmo com os 3 acompanhamentos
  acima: nada nas 4 sementes emite `notificacao.requisitada` a partir de uma resposta de e-SIC/LGPD/
  ouvidoria, e o único produtor desse evento (`transparencia.diplomat.consumers/tipos-fan-out`) reage SÓ
  a `proposicao.transicionou` — populam essa caixa só depois que um servidor avançar o rito de uma das 2
  matérias NÃO-terminais que ela agora segue (a `em_pauta` ou a `em_comissoes` acima).

**O que NÃO dá para explorar — e é a descoberta mais importante desta persona:** o **balcão inteiro é
API pura, sem tela nenhuma no frontend.** Busca no `apps/frontend/src/app` não encontra nenhuma página
que chame `/portal/esic/*`, `/portal/lgpd/*`, `/portal/ouvidoria/*`, `/portal/comentarios/*`,
`/portal/materias/:id/acompanhar` ou `/portal/acompanhamentos`. O único jeito de exercitar essa
superfície hoje é por chamada HTTP direta (curl/Postman) com o token dela — **não clicando em nada no
navegador**. Pior ainda para `/meu/notificacoes`: o backend não exige papel nenhum, mas a **única**
página do frontend que chama essa rota vive sob o grupo `(vereador)`, cujo `layout.tsx` **bloqueia o
render inteiro se o token não tiver o papel `vereador`** (é um guard de UX, não o authz real — mas
significa que ela nunca vê essa tela mesmo tendo uma sessão válida). Ou seja: a Parte A deste trabalho
lhe deu um **ator** (o backend agora resolve sessão para ela); os acompanhamentos desta revisão lhe deram
**móveis no quarto** (3 acompanhamentos + 5 comentários + 3 e-SIC reais, em vez de listas vazias); mas
nenhuma das duas coisas abriu uma **porta** nova no frontend — o ganho inteiro, hoje, só se alcança por
API direta.

---

## Apresentação (acesso total) — Patrícia Nogueira Santos (`vereador` + `secretario` + `admin_ente`)

**Por que existe:** demo comercial de visita única — o Rigoni (sócio comercial) apresentando a
plataforma para um presidente de câmara não pode gastar tempo trocando de login/perfil no meio da
conversa. Esta persona empilha os 3 papéis de trabalho (`vereador`, `secretario`, `admin_ente`) no
MESMO vínculo — a mesma mecânica que já prova a Presidente da Mesa (`vereador`+`admin_ente`), só com
`secretario` a mais em cima. **1 login alcança tudo que as personas Secretária + Vereadora + Presidente
da Mesa alcançam juntas.**

**O que o dado sustenta:** o mesmo assento de vereador (idx 13 do roster, sem cargo na Mesa nem
presidência de comissão) que dá cadastro real (mandato vigente, partido CIDADANIA) — necessário porque
`resolver-vereador` (`sessoes/controllers.clj`) exige um `cadastros.vereador` vinculado por
`identidade-id` para confirmar presença/votar; o papel `vereador` sozinho, sem esse cadastro, 404 em
qualquer ação de vereador (mesmo contrato que vale para a Presidente e a Vereadora comum). Por carregar
os 3 papéis, ela soma TUDO que as seções "Secretária", "Presidente da Mesa" e "Vereadora" acima
descrevem — inclusive o Comando da Mesa (`/sessoes/:id/conduzir`, exige `secretario`) e conceder acesso
(`/identidade/acessos`, exige `admin_ente`).

**Ressalva deliberada, não bug:** empilhar `admin_ente` no mesmo login que `secretario` vai CONTRA a
segregação de responsabilidade documentada em `docs/adr/0005` (§4 do `docs/21`: "quem mantém o cadastro
não pode ligar uma identidade a ele e sair votando") — em produção real, esses dois papéis nunca
deveriam estar na mesma pessoa. Esta persona é uma fixture de demonstração pontual, não um padrão a
replicar para tenants reais.

---

## Cidadão anônimo — sem login

A porta pública, sem vínculo nenhum e sem token — o único fluxo do cidadão que já existia coberto na
demo antes desta frente.

**O que o dado sustenta:**
- `http://localhost:3000/portal/casa/<ente-id>` → `/materias` — as proposições **públicas** do acervo
  (o read-model de transparência, materializado pelo relay a partir do mesmo acervo de 24 proposições).
- `/portal/casa/<ente-id>/vereadores` e `/vereadores/:id` — o perfil público de cada um dos 17
  vereadores (mandato, partido, e as matérias de autoria dele).
- `/portal/casa/<ente-id>/esic/acompanhar/:protocolo` — acompanhar um pedido e-SIC pelo protocolo
  (público por lei, LAI); idem `/ouvidoria/acompanhar/:protocolo` (Lei 13.460).
- `/portal/casa/<ente-id>/encarregado` — o contato do Encarregado/DPO (LGPD art. 41 §1º, público por
  lei).
- `/portal/casa/<ente-id>/materias/:id/comentarios` (GET, sem auth) — lê os comentários **aprovados**
  de uma matéria (os 2 pendentes de moderação não aparecem aqui).
- `/status` — status público da plataforma.

Nenhuma escrita: comentar, seguir matéria, protocolar e-SIC/LGPD/ouvidoria — tudo isso exige `auth`
(mesmo sem papel), ou seja, precisa da cidadã logada acima, não do anônimo.

---

## Duas honestidades que este mapa registra de propósito

**(a) `admin_sistema` (console do operador) não tem persona nesta semente.** O IdP do operador é um
stub de 3 linhas e `admin_sistema/diplomat/http/in.clj` não tem rota nenhuma — não há realm, não há
usuário, não há tela para provisionar. Isso não é omissão desta frente: é o estado real do código
(`CLAUDE.md` §3, item 2), e criar uma persona aqui seria fingir uma superfície que não existe.

**(b) O login da cidadã aqui é Keycloak, não gov.br — e isso NÃO é o fluxo de produção previsto.** O
broker gov.br (`identidade.identidade_externa`, provedor `gov_br`) está na migration e no `db/` de
identidade, mas **zero linhas de integração real** existem (`CLAUDE.md` §3, item 2). A cidadã desta
demo entra pelo **mesmo realm-por-tenant** que secretaria/presidente/vereador usam — o que serve
perfeitamente para **provar a superfície autenticada do cidadão** (o objetivo desta frente), mas é uma
substituição deliberada de infraestrutura ausente, não uma antecipação do fluxo real. Em produção, a
V1 prevê o cidadão entrando por gov.br, com um `sub` OIDC vinculado à identidade via
`identidade_externa` — carry aberto, sem relação com este trabalho.
