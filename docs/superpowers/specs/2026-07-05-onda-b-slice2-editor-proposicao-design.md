# Spec — Track FE · Onda B · Slice 2 · `editor-proposicao` (criar/editar)

> **Fatia** do `docs/13-plano-track-fe.md` (Onda B — "o fluxo diário do servidor"), segunda vertical,
> rumo ao **Marco MFE-2**. Primeira borda de ESCRITA do módulo `legislativo`; primeiro formulário/mutação
> real do frontend inteiro (Slice 1 e a A2 só tinham forms de busca/filtro).
>
> Fonte de design: `produto/design-system/o-plenario/telas/editor-proposicao.html` — **ficha técnica +
> ilha-papel são portadas; o rail `.copiloto` e o `.demo-strip` NÃO são** (Track IA satélite, `docs/13` §8).
>
> **Data:** 2026-07-05 · **Branch:** `fe-8-editor-proposicao` (off `main`) · **Escopo aprovado:** criar + editar.

---

## 1. Objetivo e escopo

Dar ao servidor o próximo passo do dia depois de ver a lista (Slice 1): **protocolar uma proposição nova**
e **corrigir uma existente** (metadados e/ou texto), sem nenhuma peça de IA.

**No escopo:**
1. `POST /legislativo/proposicoes` — protocola (numera oficial na hora), com texto inicial opcional.
2. `GET /legislativo/proposicoes/:id` — detalhe (proposição inteira + texto vigente), para a tela de edição.
3. `PATCH /legislativo/proposicoes/:id` — edita metadados e/ou promove uma nova versão de texto.
4. Duas páginas internas: `/editor-proposicao` (criar) e `/editor-proposicao/[id]` (editar).
5. Habilitar os dois pontos de entrada que a Slice 1 deixou desabilitados: "Nova proposição" (header) e
   "Editar" por linha em `/proposicoes`.
6. Corrigir o carry de navegação (perda de `?token=` dev em `<Link>`), que a partir desta fatia passa a
   doer de verdade (primeiro loop lista→editor→lista).

**Fora do escopo, com decisão explícita (não "em breve" — genuinamente não existe):**
- Copiloto de IA (pinos, sugestões, técnica legislativa, normas relacionadas) — Track IA é satélite.
- Editor estruturado por artigo/inciso/parágrafo como blocos distintos — o corpo é **um textarea markdown**,
  com a convenção leve `## Art. Nº` (`arquitetura/22-4-dados-legislativo.md` eixo B) e **helpers de inserção**
  (botões que inserem `Art. Nº`/`§`/`I —` no cursor — snippet, não parsing).
- `tema` e `regime de tramitação`/urgência do mockup — sem representação no domínio hoje, não inventar.
- Autosave client-side ("Salvo há Xs") — carry, não MVP.
- Autoria de vereador-sobre-si-mesmo como authz fina — gate continua só o papel `"secretario"` (mesmo
  contrato de toda leitura/escrita interna hoje).
- Overflow de texto para `objeto_store` (>32KB) — `decidir-armazenamento` existe no domínio, mas esta fatia
  só aceita `:inline`; texto maior que 32KB é rejeitado (400) como carry explícito (nenhum bill real chega
  perto disso; a S3-write path fica para quando aparecer).

## 2. Decisão de fundo: por que "criar" = protocolar! imediato

`legislativo.proposicoes.estado` nasce sempre `'protocolada'` (DEFAULT da migration `20260620000013`); o
`protocolar!` (db) numera oficial (sequencial gapless + URN/LexML) **atomicamente**, no ato — não existe,
em lugar nenhum da SSOT, um estado de rascunho pré-protocolo para a proposição em si (só o TEXTO tem
`rascunho`/`vigente`, eixo B). Isso reflete o modelo legal real: uma proposição *passa a existir* quando é
protocolada; uma proposição retirada depois **mantém seu número** (a numeração é gapless, Inv.10 sem DELETE
— 042 retirada não libera 042, 043 é a próxima). Logo:

- **Tela de criar → uma ação primária: "Protocolar."** Não existe "Salvar rascunho" batendo no backend.
- **Tela de editar → "Salvar alterações"** (metadados via `editar!` novo +, se o texto mudou, uma nova
  versão promovida via `nova-versao!`/`promover-versao!`, ambos já existentes desde F3.2).
- O texto inicial na criação é **opcional** (`texto-vigente-versao-id` já é nullable) — o servidor pode
  protocolar só com metadados e escrever o texto depois, via edição.

## 3. Backend — contratos novos

### 3.1 Vocabulário (extensão do que já existe, `logic.clj`)

- `autor-tipos` — `#{"vereador" "mesa" "comissao" "executivo" "cidadao"}` (espelha o CHECK `autor_tipo` da
  migration `20260620000013`, hoje sem vocabulário em código — gap achado pela exploração).
- `estados-proposicao-terminais` — `#{"publicada" "arquivada"}` (espelha o trigger
  `trg_proposicoes_imut_estado`; guarda o `editar!` novo).
- `origens-versao` ganha um valor novo: **`"edicao"`** — usado quando `editar!` também promove uma nova
  versão de texto pós-protocolo (nenhum valor do enum atual — `protocolo|substitutivo|aplicacao_emenda|
  redacao_final|promulgacao|importacao_legado` — descreve uma correção simples pelo servidor; `substitutivo`
  tem processo legislativo formal próprio, eixo D, e usá-lo aqui estaria semanticamente errado). Requer
  migration nova (ALTER da CHECK constraint — `origem_versao` é CHECK, não tipo enum do Postgres; a
  constraint do `PARTITION BY HASH` é herdada automaticamente por todas as partições ao alterar o pai).

### 3.2 Resolver uf/município (novo, `cadastros`)

`protocolar!` precisa de `uf`+`municipio-nome` para computar a URN (eixo H) — hoje só suprido por teste,
nenhum caller de produção existe. `cadastros.ente` só tem `municipio_ibge` (FK); `uf`/`nome` vivem em
`cadastros.municipios` (mesmo schema — join dentro de `cadastros`, sem violar §22.10). Nova função
`cadastros.db.estrutura/uf-e-municipio` (join simples) + método novo no `RepoCadastros`
(`uf-e-municipio [this ente-id]`) + injeção pelo host em `rotas.clj` como `resolver-municipio`, **mesmo
padrão de inversão de dependência já usado para `consultar-sessao`/`membros-da-casa`/`info-ente`**.

### 3.3 `db/proposicao.clj` — `editar!` (novo)

CAS por `lock-version` com `SELECT ... FOR UPDATE` antes (mesmo padrão de `db/documento.clj/
editar-rascunho!`), guard "não terminal" (não `publicada`/`arquivada` — ao contrário de `documento`, que
guarda "só rascunho"; a proposição não tem fase rascunho, mutação é livre enquanto não-terminal, §22.4.3
disc.4). PATCH parcial: só os campos presentes mudam.

### 3.4 `components/repositorio.clj` — composição em UMA tx

- **`protocolar!` estendido** (não um método novo): aceita uma chave opcional `:texto` no mapa de entrada;
  se presente, dentro da MESMA tx do protocolo, cria uma `proposicao_texto_versao` (`origem-versao
  "protocolo"`) e a promove a vigente. Reaproveita o método existente em vez de duplicar composição —
  mesmo racional de `aprovar-emenda!`/`transicionar!` (Repo compõe múltiplos `db/` numa tx).
- **`editar-proposicao!` (novo método)** — compõe `proposicao/editar!` + (se `:texto` presente) uma nova
  versão (`origem-versao "edicao"`) promovida a vigente, na MESMA tx.
- **`buscar-proposicao-detalhe` (novo método)** — `proposicao/buscar` + `texto/vigente`, uma leitura.

### 3.5 Rotas HTTP (papel `"secretario"`, mesmo gate grosso de toda leitura/escrita interna hoje)

| Rota | Ação | Authz |
|---|---|---|
| `POST /legislativo/proposicoes` | protocolar (+ texto opcional) | `exige-papel "secretario"` |
| `GET /legislativo/proposicoes/:id` | detalhe (proposição+texto vigente) | `exige-papel "secretario"` |
| `PATCH /legislativo/proposicoes/:id` | editar metadados/texto | `exige-papel "secretario"` |

Sem policy fina nova (mesmo contrato de `listar-proposicoes`/`relatores-pendentes`) — RLS + papel é a régua
já estabelecida para todo o módulo. `resolver-municipio` falhando (ente sem perfil cadastrado em `cadastros`)
é condição operacional, não erro de cliente — deixa propagar como 500 (não mapear para 400/404): câmara sem
perfil cadastrado é bug de provisionamento, não algo o usuário corrige preenchendo o formulário de novo.

## 4. Frontend — contratos novos

- **Rotas:** `(interno)/editor-proposicao/page.tsx` (criar) e `(interno)/editor-proposicao/[id]/page.tsx`
  (editar), compartilhando `<FormularioProposicao>`.
- **Hooks:** `use-proposicao-detalhe.ts` (GET, mesmo idioma de `useProposicoes`);
  `use-criar-proposicao.ts`/`use-editar-proposicao.ts` (**primeiro par de hooks de mutação do app** — inventa
  o padrão loading/erro/sucesso+redirect a partir da forma do hook de leitura, guard `vivo` contra unmount).
- **Contrato:** `wire/in/proposicao.clj` deixa de ser stub; `contrato-legislativo.gen.ts` regerado via
  `oplenario.codegen.malli-ts` (manifesto em `gerar_legislativo.clj` ganha `ProposicaoDetalheOut`).
- **Fix do carry de navegação:** helper `comToken(href, token)` (novo, `src/lib/nav.ts`) usado por
  `TopoInterno`/`DESTINOS_NAV` e por qualquer redirect pós-submit — preserva `?token=` dev entre páginas.
- **Pontos de entrada:** botão "Nova proposição" (header de `/proposicoes`) e ação "Editar" por linha —
  ambos habilitados agora (eram os dois desabilitados de propósito na Slice 1).

## 5. Testes

- **Backend TDD red→green:** migration (CHECK aceita `"edicao"`); `cadastros/db/uf-e-municipio` (integração,
  join correto, RLS isola por tenant); `db/proposicao/editar!` (CAS, guard terminal, PATCH parcial);
  `wire/http/in` das 3 rotas novas (201/200/400/403/404, authz grossa, corpo inválido, texto >32KB → 400).
- **Frontend:** hooks de mutação (sucesso/erro/loading, guard de unmount); `FormularioProposicao` (campos
  condicionais por tipo, helpers de inserção); render das 2 páginas (estados carregando/erro/pronto).
- **Review `ecc`:** clojure+database (backend), react+security (frontend) — mesmo gate de toda fatia anterior.
- **E2E manual em Docker:** protocolar do zero (form→número oficial aparece) e editar uma existente
  (ementa+texto→nova versão vigente); AA nos 2 temas; navegação lista→editor→lista com `?token=` explícito.

## 6. Carries (não bloqueiam esta fatia)

- Autosave client-side (localStorage) do texto em digitação.
- Overflow de texto para `objeto_store` (>32KB inline).
- Autoria de vereador-sobre-si-mesmo (authz fina ligando `identidade`→`cadastros.vereador`).
- Editor estruturado por artigo/inciso (upgrade de UI sobre o markdown já convencionado).
- `tema`/regime de urgência do mockup (sem representação de domínio).
