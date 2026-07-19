# Inbox interno de notificações — design (Onda E, fatia 1)

**Data:** 2026-07-19 · **Track:** FE Onda E · **Status:** aprovado

## 1. Problema

A Onda E foi descrita no `docs/13` como "cauda" — telas de design prontas esperando serem portadas.
Ao auditar, isso se mostrou falso: **nenhuma das 16 telas da Onda E tem rota de backend pronta**.
Ao contrário das Ondas A–D, onde o backend vinha primeiro e o FE consumia, aqui toda tela é zero-a-um.

`notificacoes` é a de menor fan-out: a máquina cara já foi construída na F7 E2 (fan-out durável,
evento `notificacao.requisitada`, ledger de entrega com dedup). Falta a borda de leitura — e, como se
descobriu ao desenhar, falta também o conceito de "lida" e um produtor cujo destinatário seja interno.

## 2. O que existe hoje (medido, não suposto)

- **Produtor único:** `transparencia/components/repositorio.clj` faz fan-out de `proposicao.transicionou`
  para **cidadãos que seguem a matéria** (`transparencia.acompanhamento`, opt-in explícito).
- **Evento:** `notificacao.requisitada` (`transparencia/events/notificacao.clj`), payload Malli `:closed`
  com `destinatario-identidade-id`, `canal`, `consent-base`, `idempotency-key` (determinística),
  `assunto`, `corpo`, `objeto-tipo`, `objeto-id`.
- **Consumidor:** `paineis` projeta em `paineis.notificacao_entrega` — **ledger de tentativa de entrega
  por canal** (`pendente → enviada | falha`), canal fixo `"email"`, destinatário = UUID de identidade.
  Sem PII: o UUID é pseudônimo, assunto/corpo derivam de informação já pública no portal.
- **Não existe:** rota HTTP de leitura, estado "lida", contagem de não lidas, preferência de canal.
- **Carries de infra da F7 (intocados aqui):** entrega SMTP/push real (hoje `NotificadorLog`),
  resolução UUID→contato, agendador do worker sob leader-election.

## 3. Decisões

### D1 — Público: interno (servidor/vereador), não cidadão

A tela desenhada (`produto/design-system/o-plenario/telas/notificacoes.html`) é interna. O caso do
cidadão tem dado fluindo hoje, mas o cidadão ainda não tem login real (broker gov.br é frente aberta),
e portar a tela interna para o público errado não fecha nada.

**Consequência assumida:** como nenhuma notificação interna existe hoje, a fatia **não é** "uma rota de
leitura". É **inbox = borda de leitura + conceito de lida + um produtor interno real**. Entregar só a
rota devolveria lista vazia para 100% dos usuários internos — verde e inútil.

### D2 — Destinatário derivado por relação com o objeto

Internamente não há inscrição; o destinatário precisa ser derivado. Escolhido: **por relação com o
objeto**, reusando o resolvedor de fatos que foi o KEYSTONE da F2, em vez de inventar mecânica nova.
A notificação passa a ter dono nominal — que é o que torna um inbox útil.

Descartado por ora: **por papel** (todos com papel X na Casa) — legítimo para eventos genuinamente
institucionais, adotar quando um segundo produtor pedir; **inscrição interna explícita** — mais caro e
nasce com a inbox vazia, a armadilha que D1 evita.

### D3 — Primeiro produtor: `norma.publicada`, NÃO `parecer.transicionou`

`parecer.transicionou → autor da proposição` foi a primeira escolha e **está errada**: colide com a
**ciência do vereador** (`legislativo.ciencia_vereador`, Onda C1), que já é "parecer publicado sobre
matéria sua", já aparece em `/meu/painel` como pendência, e cuja docstring diz "ciência DERIVADA, sem
pipeline de notificação". Duplicar isso viola a regra de dedup que a própria tela documenta (§5.1):
**o que exige ação mora em pendências; a inbox é acompanhamento.**

`norma.publicada` (`legislativo/events/norma.clj`) é o produtor certo: payload já carrega
`proposicao-id`, `tipo-norma`, `numero`, `ano`, `urn`, `ementa`, `publicado-em`; é pura ciência-de-fato,
não exige ação, não duplica pendência. Semanticamente: **"a sua proposição virou lei"**.

### D4 — Duas projeções do mesmo evento, não um ledger com mais colunas

`paineis.notificacao_entrega` é um ledger de **entrega por canal**. Um inbox é a **mensagem + estado de
leitura do destinatário**. Enfiar `lida_em` naquela tabela mistura os dois, e `pendente/enviada` não
significa nada para in-app.

A inbox vira um **segundo projetor** de `notificacao.requisitada`, com tabela própria. Nenhuma tabela
existente é alterada, nenhum join entre elas, cada read-model com a forma do seu consumidor — o padrão
do projeto (o evento é a verdade, projeções são derivadas).

Descartado: reestruturar em mensagem-pai + tentativas-filhas por canal. É o modelo mais correto no
abstrato, mas mexe em código F7 que funciona para ganhar uma normalização que ninguém precisa enquanto
houver dois canais.

### D5 — `falha` é categoria da mensagem, não estado de entrega

O filtro "Falhas" da tela mostra falha **de domínio** ("transcrição interrompida", "TCE-CE rejeitou
remessa"), não falha de envio de e-mail. Logo `categoria` é campo da mensagem.

## 4. Arquitetura

### 4.1 Produtor (`legislativo`)

`legislativo` passa a ser **segundo consumidor do próprio `norma.publicada`** — mesma disciplina de
dedup independente por `(consumidor, key)` que `transparencia` já usa. Roda dentro da tx do relay e
**nunca lança** (o relay é compartilhado por todos os módulos; um throw ali trava a fila de todo mundo).

Fluxo: `proposicao_id → autor_id` (same-schema, `legislativo.proposicoes`) → `vereador → identidade`
por **resolvedor injetado pelo host** (inverso do `resolver-vereador` de `/meu/painel`, §22.5.3 —
`legislativo` nunca importa `cadastros`) → emite `notificacao.requisitada`.

**Sem destinatário resolvível** (autor não é vereador, ou vereador sem identidade vinculada):
não notifica, `log/debug`, segue. Silêncio honesto, não erro.

### 4.2 Contrato do evento — duas mudanças compatíveis

`transparencia/events/notificacao.clj` (`:closed true`):
- `canal` passa a admitir `"in_app"` além de `"email"` (o schema já é `:string`, sem mudança de tipo).
- **campo novo** `categoria` `{:optional true}` — opcional para não quebrar o produtor do cidadão.
- `consent-base` para notificação interna: `"vinculo"` (a pessoa é agente da Casa; não é consentimento
  de marketing, é comunicação institucional do sistema que ela opera).

### 4.3 Roteamento por canal

Cada projetor trata **apenas o seu canal**. Isso exige uma guarda de duas linhas no projetor de e-mail
existente (`paineis` → `registrar-intent!`): ignorar `canal ≠ "email"`. Sem ela, o worker
`entregar-pendentes!` tentaria enviar e-mail de uma notificação in-app.

### 4.4 Armazenamento — `paineis.notificacao_caixa`

| coluna | tipo | nota |
|---|---|---|
| `id` | uuid PK | |
| `ente_id` | uuid NOT NULL | RLS |
| `destinatario_identidade_id` | uuid NOT NULL | a quem endereça |
| `categoria` | text NOT NULL | `norma_publicada` nesta fatia; `falha`/`prazo`/`sessao`/`sistema` depois |
| `assunto` | text NOT NULL | |
| `corpo` | text NOT NULL | |
| `objeto_tipo` | text NOT NULL | `"proposicao"` |
| `objeto_id` | uuid NOT NULL | destino do clique |
| `idempotency_key` | text NOT NULL | do payload |
| `criado_em` | timestamptz NOT NULL DEFAULT now() | |
| `lida_em` | timestamptz NULL | NULL = não lida |

- `UNIQUE (ente_id, idempotency_key)` → `ON CONFLICT DO NOTHING` (redrive/backfill é no-op).
- Índice `(ente_id, destinatario_identidade_id, criado_em DESC)` — o acesso da borda de leitura.
- Índice parcial `(ente_id, destinatario_identidade_id) WHERE lida_em IS NULL` — a contagem.
- RLS `ENABLE` + `FORCE`, policy `tenant_isolation` com `USING` **e** `WITH CHECK`; grants a
  `oplenario_app`. Padrão copiado do exemplar do projeto.
- **Sem PII:** destinatário é UUID pseudônimo; assunto/corpo derivam de dado público (a norma publicada
  é ato público por natureza).
- **Inv.10:** `lida_em` é estado atual mutável, não histórico. Aceito e registrado — se um dia houver
  exigência formal de trilha de leitura, entra um ledger companheiro append-only.

### 4.5 Bordas HTTP

**`GET /meu/notificacoes`** — gate `auth` **apenas, sem papel**: notificação é endereçada a uma
identidade, não a um cargo. Devolve as do próprio ator (`WHERE ente_id = … AND
destinatario_identidade_id = (:identidade-id ator)`), ordenadas por `criado_em DESC`, com teto de 50,
mais a contagem de não lidas. A identidade vem sempre de `(:ator req)`, nunca de path/query/corpo.

**Sem paginação nesta fatia**, de propósito: o teto de 50 é rígido e a resposta declara a contagem total
de não lidas, então a UI nunca mente sobre o que existe. Paginação entra quando um usuário real passar do
teto — antes disso é complexidade sem demanda. O teto é aplicado no SQL (`LIMIT`), nunca em Clojure depois
do fetch: a armadilha de descartar os itens mais recentes já mordeu este projeto na F3.

**`POST /meu/notificacoes/:id/lida`** — idempotente (`SET lida_em = now() WHERE lida_em IS NULL`),
filtra por destinatário no **mesmo `WHERE`** do tenant (anti-confused-deputy: marcar a notificação de
outra pessoa não pode ser possível nem por id adivinhado). Id inexistente ou de outro destinatário → 404,
nunca 200 silencioso.

Silhueta ADR-0001 completa: `diplomat/http/in` · `wire/out` · `adapters/out` (gate Malli de saída) ·
`controllers` · `db`. Wire exposto no codegen Malli→TS — **`paineis` ainda não tem manifesto de codegen**
(existem `gerar.clj`, `gerar_legislativo.clj`, `gerar_cadastros.clj`, `gerar_portal.clj`); esta fatia cria
`gerar_paineis.clj`, seguindo a forma dos existentes.

### 4.6 Frontend

Rota nova no shell do vereador (é quem recebe hoje: autor da proposição). Porta a
`notificacoes.html` com escopo reduzido:

- Hook `use-minhas-notificacoes` (leitura) e `use-marcar-lida` (mutação), espelhando `use-meu-painel`
  e o par de mutação da Onda B Slice 2.
- View-model puro `notificacoes-vista.ts`: agrupamento temporal (Hoje / Esta semana / Antes) e
  derivação do estado visual. Puro = testável sem DOM, padrão de todo view-model do projeto.
- Não-lida marcada por **ponto + negrito + tinta de fundo**, nunca só cor (regra do
  `GUIDELINES-CHECKLIST.md`).
- Contraste AA medido em pixel composto, um tema por chamada com flush, nos dois temas.

## 5. Fora de escopo (deliberado)

- **"Marcar todas como lidas"** — ação em massa; sem valor com o volume desta fatia.
- **Filtros/abas por tipo** — com uma só categoria existindo, aba de filtro é teatro.
- **Contador no sino do topo** — a própria tela documenta que o sino conta *pendências*, não
  notificações. Fazer isso quebraria a dedup §5.1.
- **Preferência de canal** por usuário.
- **Entrega real de e-mail e resolução UUID→contato** — carries de infra da F7, intocados.
- **Segundo produtor interno** (prazo, sessão, falha de remessa) — a forma fica provada aqui; o próximo
  entra quando alguém pedir.

## 6. Critérios de aceitação

1. Publicar uma norma cujo autor é vereador com identidade vinculada faz aparecer **uma** notificação na
   inbox dele — e re-executar o mesmo evento não cria uma segunda (idempotência provada, não afirmada).
2. Autor sem identidade vinculada não gera notificação **e não gera erro** — o relay segue drenando.
3. `GET /meu/notificacoes` de um ator sem notificação devolve 200 com lista vazia e contagem 0.
4. Ator A não consegue ler nem marcar como lida notificação de ator B, nem com o id em mãos.
5. Isolamento de tenant provado: notificação de uma Casa nunca aparece na outra.
6. Marcar lida é idempotente: chamar duas vezes não muda `lida_em` nem devolve erro.
7. Uma notificação com `canal "in_app"` **não** entra no ledger de entrega de e-mail.
8. A suíte inteira verde (backend e frontend), lint 0, e a tela verificada ao vivo nos dois temas.

## 7. Verificação

TDD por fatia (red → green). Revisão `ecc` antes do merge: `clojure-reviewer`, `database-reviewer` e
`security-reviewer` no backend; `react-reviewer` no frontend. Prova ao vivo com seed real:
proposição de autor vereador → publicar como norma → ver a notificação na inbox dele.
