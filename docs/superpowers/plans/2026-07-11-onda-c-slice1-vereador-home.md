# Onda C · Slice C1 — Vereador: home (fora de sessão) · Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: implementar via ultracode Workflow (padrão provado das fatias
> B5–B7: impl TDD + 4 revisores `ecc` adversariais + corretor; o orquestrador verifica independente), OU
> superpowers:subagent-driven-development. Steps usam checkbox (`- [ ]`) para tracking.

**Goal:** Entregar a home do vereador (estado *fora de sessão* da `vereador-app.html`) como rota web
responsiva no app Next existente, ligada a uma borda de backend nova e escopada ao ator vereador.

**Architecture:** Uma borda fina `GET /meu/painel` no módulo `legislativo` devolve o que é do vereador
(minhas proposições por `autor_id`, meus pareceres por `relator_id`, ciências pendentes). O `vereador-id`
**não** vem do corpo nem da query — o **host resolve** `identidade→vereador` por inversão de dependência
(padrão `membros-da-casa`, `cadastros/db/vereador.clj/por-identidade`), injetando a fn no controller. A
autorização grossa é `exige-papel "vereador"`; a fina confirma que o ator É aquele vereador (o resolvido).
No FE, um grupo de rotas `(vereador)` mobile-first sobre o `chassi`, view-models puros testados, consumindo
`/meu/painel` + a próxima sessão (read do `/sessoes` existente, composto no cliente).

**Tech Stack:** Backend Clojure (Pedestal · Malli · next.jdbc/HoneySQL · Component · Migratus · kaocha).
Frontend Next.js 16 (App Router, TS, Tailwind 4, React 19, vitest). Postgres real via docker.

## Global Constraints

- **§22.10** — `legislativo` NUNCA importa `cadastros`/`identidade`/`sessoes`; nada de JOIN cross-schema. A
  resolução `identidade→vereador-id` chega **injetada pelo host** (fn), não por import. Comunicação inter-módulo
  só HTTP/eventos ou inversão de dependência no host (exceção nomeada §22.5.3).
- **Inv. 1** — `ente_id` em toda tabela e em todo WHERE; índice composto começa por `ente_id`.
- **Inv. 10** — a ciência é **append-only** (a prova "você foi notificada, com data e hora"); nunca UPDATE/DELETE.
- **Anti-forja** — o `vereador-id` alvo do read é sempre o do ator resolvido; um cliente não escolhe "de quem"
  são as proposições. `identidade-id` + `ente-id` vêm do ator (`resolver-sessao`), nunca do request.
- **ADR-0001 / silhueta** — `wire/in`·`wire/out`, `adapters/in`·`adapters/out`, controller fino, db puro; sem
  `port/`, sem ORM. Recurso via Component. Estende o módulo `legislativo` existente.
- **Auth de produção = carry Onda D** — o ator com papel `vereador` chega via dev-token nesta fatia (Keycloak
  vivo é F1.4-carry). Não construir login real aqui.
- **Verificação por fatia** — `vitest` (view-models puros) + `tsc`/`eslint`/`next build` limpos; backend
  `clj -M:test` (kaocha) verde + `clj-kondo` 0/0; revisão `ecc` **clojure + database + security + react**;
  paridade visual lado-a-lado com `vereador-app.html` (estado fora-de-sessão) nos 2 temas
  (`GUIDELINES-CHECKLIST.md`, pixel composto).

## Decisões assumidas (lean; reversíveis)

1. **Presença do próprio aparelho → C3.** É ação em-sessão e esbarra no mesmo muro `exige-papel "secretario"`
   do voto. A C1 mostra "próxima sessão" como **read-only** (data/pauta), sem escrita.
2. **Ciência derivada, sem pipeline de notificação.** Item de ciência = parecer **publicado** sobre uma
   proposição de minha autoria que eu ainda não acusei. Fonte = dado que o `legislativo` já é dono; ack = tabela
   append-only `legislativo.ciencia_vereador`. **Nada de push/Web Push** (fora de escopo da Onda C enxuta, §11.2).
3. **Uma borda composta `/meu/painel`**, não três endpoints — os três blocos (proposições/pareceres/ciências)
   são todos dados do `legislativo`, lidos numa única tx do tenant. A próxima sessão o FE compõe do `/sessoes`.
4. **Branch:** `fe-14-vereador-home` (segue a numeração `fe-N`; última foi `fe-13-pos-aprovacao`).

---

## File Structure

**Backend (`apps/backend/`) — estende o módulo `legislativo`:**
- `resources/migrations/*__legislativo-ciencia-vereador.up.sql` (+`.down.sql`) — tabela append-only.
- `src/oplenario/legislativo/db/meu_painel.clj` — queries escopadas (proposições por autor, pareceres por
  relator, ciências pendentes) + insert de ciência.
- `src/oplenario/legislativo/controllers.clj` (modificar) — `meu-painel` + `acusar-ciencia`.
- `src/oplenario/legislativo/components/repositorio.clj` (modificar) — protocolo + impl das novas fns.
- `src/oplenario/legislativo/wire/out/meu_painel.clj` — schema Malli de saída (o painel do vereador).
- `src/oplenario/legislativo/wire/in/ciencia.clj` — schema de entrada de acusar ciência.
- `src/oplenario/legislativo/adapters/out/meu_painel.clj` — domínio→wire (kebab, projeta+valida).
- `src/oplenario/legislativo/adapters/in/ciencia.clj` — request→domínio.
- `src/oplenario/legislativo/diplomat/http/in.clj` (modificar) — 2 rotas gated `exige-papel "vereador"`.
- `src/oplenario/sistema.clj` (modificar) — injeta `resolver-vereador` (host wiring, `por-identidade`).
- `src/oplenario/interceptors.clj` — confirmar que `exige-papel` aceita "vereador" (só uso, sem mudança).
- Testes: `test/.../legislativo/meu_painel_test.clj` (unit db+controller) e
  `test/integration/.../legislativo/meu_painel_http_in_test.clj` (borda + authz + anti-forja).

**Frontend (`apps/frontend/`):**
- `src/app/(vereador)/layout.tsx` — shell mobile-first do vereador (topo + tema + auth-guard papel vereador).
- `src/app/(vereador)/vereador/page.tsx` — a home (estado fora-de-sessão).
- `src/lib/meu-painel-vista.ts` (+`.test.ts`) — view-model puro (agrupa/ordena; deriva "próxima sessão").
- `src/lib/use-meu-painel.ts` — hook de fetch tipado de `/meu/painel`.
- `src/lib/use-acusar-ciencia.ts` — mutation de acusar ciência (otimista, revalida).
- `src/lib/contrato-legislativo.gen.ts` (modificar/estender) — tipos do painel do vereador.
- `src/lib/auth.tsx` (modificar) — papel `vereador` no contexto (dev-token).

---

## Interfaces (contrato entre tasks)

- **`por-identidade`** (já existe, `cadastros/db/vereador.clj`): `(tx ente-id identidade-id) -> {:id :ente_id
  :identidade_id :nome :nome_parlamentar} | nil`. O host embrulha como
  `resolver-vereador :: ente-id identidade-id -> vereador-id(uuid) | nil`.
- **`repo/meu-painel`**: `(repo ente-id vereador-id) -> {:proposicoes [...] :pareceres [...] :ciencias [...]}`.
- **`repo/acusar-ciencia!`**: `(repo ente-id vereador-id {:evento-ref :tipo}) -> {:id :ciente-em}` (append-only;
  idempotente por `(ente_id, vereador_id, evento_ref)`).
- **`MeuPainelOut`** (wire/out, `:closed true`): `{:proposicoes [PropResumo] :pareceres [ParecerResumo]
  :ciencias [CienciaPendente]}`; datas como string ISO; ids como string.
- **`GET /meu/painel`** → 200 `MeuPainelOut` (ator vereador) · 403 (papel ≠ vereador) · 200 vazio (papel
  vereador sem vínculo de cadastro → `resolver-vereador` nil ⇒ painel vazio, **não** 500).
- **`POST /meu/ciencias`** corpo `{:evento-ref :string :tipo :string}` → 201 `{:id :ciente-em}` · 403 (não-vereador).

---

## Tasks

### Task 1: Host resolve `identidade→vereador-id` (inversão de dependência)

**Files:**
- Modify: `apps/backend/src/oplenario/sistema.clj` (wire a `resolver-vereador` no componente do `legislativo`)
- Test: `apps/backend/test/.../legislativo/meu_painel_test.clj`

**Interfaces:**
- Consumes: `cadastros/db/vereador.clj/por-identidade`.
- Produces: `resolver-vereador :: ente-id identidade-id -> vereador-id | nil` injetada no repo/controller do
  `legislativo` (o `legislativo` NUNCA importa `cadastros`; recebe a fn).

- [ ] **Step 1: Write failing test** — dado um vereador de `cadastros` com `identidade_id = I` no ente `E`,
  `resolver-vereador E I` devolve o `:id` do vereador; para `identidade_id` sem cadastro, devolve `nil`.
- [ ] **Step 2: Run → FAIL** (`resolver-vereador` não existe / não injetado).
- [ ] **Step 3: Implement** — no host, `(fn [ente-id identidade-id] (:id (cadastros-db/por-identidade tx ente-id
  identidade-id)))`, injetada na construção do componente `legislativo`. Documentar como a exceção §22.5.3
  (mesma justificativa de `membros-da-casa`).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(be): host resolve identidade→vereador-id p/ a borda /meu (Onda C1)`.

### Task 2: `meu-painel` read (minhas proposições + meus pareceres)

**Files:**
- Create: `apps/backend/src/oplenario/legislativo/db/meu_painel.clj`
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj`, `.../controllers.clj`
- Test: `apps/backend/test/.../legislativo/meu_painel_test.clj`

**Interfaces:**
- Produces: `repo/meu-painel :: ente-id vereador-id -> {:proposicoes [...] :pareceres [...] :ciencias [...]}`.

- [ ] **Step 1: Write failing tests** — (a) proposições WHERE `ente_id=E AND autor_tipo='vereador' AND
  autor_id=V`, ordenadas mais-recente-primeiro; (b) pareceres WHERE `ente_id=E AND relator_id=V`; (c) o read
  NUNCA devolve proposição de outro autor nem de outro ente (isolamento). Semente: 2 vereadores, 2 entes.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** as queries em `db/meu_painel.clj` (HoneySQL, sempre `ente_id` no WHERE), o método
  no Repo-Component, e `controllers/meu-painel` (recebe `ator` + `resolver-vereador`; se `nil` ⇒ painel vazio).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(be): read /meu/painel escopado por autor/relator (Onda C1)`.

### Task 3: Ciência append-only (derivar pendentes + acusar)

**Files:**
- Create: migration `…__legislativo-ciencia-vereador.up.sql`/`.down.sql`
- Modify: `db/meu_painel.clj`, `components/repositorio.clj`, `controllers.clj`
- Test: `meu_painel_test.clj`

**Interfaces:**
- Produces: `repo/acusar-ciencia! :: ente-id vereador-id {:evento-ref :tipo} -> {:id :ciente-em}` (idempotente);
  `:ciencias` do painel = pareceres **publicados** sobre proposição de autoria `V` **menos** os já acusados.

- [ ] **Step 1: Write failing tests** — (a) tabela `legislativo.ciencia_vereador` (`id, ente_id, vereador_id,
  evento_ref, tipo, ciente_em`) com UNIQUE `(ente_id, vereador_id, evento_ref)`; (b) `:ciencias` lista o parecer
  publicado sobre minha proposição enquanto não acusado; (c) `acusar-ciencia!` insere e remove o item do
  pendente; (d) acusar 2× é idempotente (não duplica, `ciente_em` do 1º preserva). RLS/`ente_id` isola.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** migration (append-only, índice `(ente_id, vereador_id)`), a query de pendentes
  (LEFT JOIN anti-join contra ciência, dentro do schema `legislativo`), `acusar-ciencia!` (`ON CONFLICT DO
  NOTHING` + retorno do existente).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(be): ciência append-only do vereador — derivar + acusar (Onda C1)`.

### Task 4: Borda HTTP — 2 rotas gated `vereador` (+ anti-forja)

**Files:**
- Create: `wire/out/meu_painel.clj`, `wire/in/ciencia.clj`, `adapters/out/meu_painel.clj`, `adapters/in/ciencia.clj`
- Modify: `legislativo/diplomat/http/in.clj`
- Test: `test/integration/.../legislativo/meu_painel_http_in_test.clj`

**Interfaces:**
- Consumes: `controllers/meu-painel`, `controllers/acusar-ciencia`, `resolver-vereador`.
- Produces: `GET /meu/painel`, `POST /meu/ciencias` (ambas `[auth (exige-papel "vereador") …]`).

- [ ] **Step 1: Write failing integration tests** — (a) ator vereador → 200 com só o que é dele; (b) ator com
  papel `secretario`/`cidadao` → 403; (c) **anti-forja**: nenhum campo do corpo/query muda de quem é o painel
  (mesmo mandando `vereador-id` no corpo, é ignorado); (d) ator vereador sem cadastro (`resolver-vereador` nil)
  → 200 vazio, não 500; (e) `POST /meu/ciencias` 201 e reflete no próximo GET.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** wire/out (`:closed true`, projeta+valida), adapters, e as 2 rotas gated. O
  `vereador-id` é injetado do ator no controller; o corpo de ciência carrega só `evento-ref`+`tipo`.
- [ ] **Step 4: Run → PASS** + `clj-kondo` 0/0 + suíte inteira verde.
- [ ] **Step 5: Commit** `feat(be): borda /meu/painel + /meu/ciencias gated papel vereador (Onda C1)`.

### Task 5: FE — shell `(vereador)` + papel no AuthContext

**Files:**
- Create: `apps/frontend/src/app/(vereador)/layout.tsx`
- Modify: `apps/frontend/src/lib/auth.tsx`
- Test: `apps/frontend/src/lib/auth.test.tsx` (estende)

**Interfaces:**
- Produces: layout mobile-first (topo do `chassi` + toggle de tema + guard: sem papel `vereador` ⇒ bloqueia),
  `useAuth()` expõe `papeis` incluindo `vereador` a partir do dev-token.

- [ ] **Step 1: Write failing test** — `useAuth` com dev-token de papel `vereador` expõe `papeis` contendo
  `"vereador"`; o guard do layout nega render sem esse papel.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** o grupo de rotas `(vereador)` + guard + extensão do contexto. Reusa `chassi.css`
  (não inventar componente novo antes de portar 1:1).
- [ ] **Step 4: Run → PASS** (`vitest`).
- [ ] **Step 5: Commit** `feat(fe): shell (vereador) mobile + papel no AuthContext (Onda C1)`.

### Task 6: FE — view-model puro `meu-painel-vista`

**Files:**
- Create: `apps/frontend/src/lib/meu-painel-vista.ts`, `apps/frontend/src/lib/meu-painel-vista.test.ts`

**Interfaces:**
- Consumes: `MeuPainelOut` (contrato gerado) + a lista de sessões do `/sessoes`.
- Produces: `derivarHome(painel, sessoes) -> { minhasProposicoes, meusPareceres, ciencias, proximaSessao }`
  — funções PURAS (ordenação, agrupamento, "próxima sessão" = a mais próxima no futuro), 100% testável sem rede.

- [ ] **Step 1: Write failing tests** — ordena proposições recente-primeiro; separa pareceres por estado
  (aguardando vs concluído); ciências não-vazias marcam o "para sua ciência"; `proximaSessao` escolhe a de menor
  data futura, `null` se não há; entrada vazia ⇒ estrutura vazia coerente (sem throw).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** as funções puras.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(fe): view-model puro da home do vereador (Onda C1)`.

### Task 7: FE — a home (estado fora-de-sessão) + acusar ciência

**Files:**
- Create: `apps/frontend/src/app/(vereador)/vereador/page.tsx`, `src/lib/use-meu-painel.ts`,
  `src/lib/use-acusar-ciencia.ts`
- Modify: `src/lib/contrato-legislativo.gen.ts` (tipos do painel)
- Test: paridade visual + e2e leve (Playwright) do read + acusar

**Interfaces:**
- Consumes: `derivarHome`, `/meu/painel`, `POST /meu/ciencias`, `/sessoes`.

- [ ] **Step 1:** Portar o estado *fora de sessão* da `vereador-app.html` (suas proposições · para sua ciência
  com "Dar ciência" · próxima sessão read-only). Sem estado *em sessão* (é a C3).
- [ ] **Step 2:** `use-meu-painel` (fetch tipado, dev-token) + `use-acusar-ciencia` (otimista → revalida).
- [ ] **Step 3:** Rodar contra o backend real (docker) com um dev-token de vereador; conferir que só aparece o
  que é dele; acusar ciência some da lista e persiste no reload.
- [ ] **Step 4:** `tsc`/`eslint`/`next build` limpos; paridade visual nos 2 temas (`GUIDELINES-CHECKLIST.md`);
  contraste em pixel composto.
- [ ] **Step 5: Commit** `feat(fe): home do vereador fora-de-sessão + acusar ciência (Onda C1)`.

---

## Self-Review (cobertura vs §11.2/§11.3)

- ✅ "minhas proposições" (Task 2/6/7) · ✅ "meus pareceres" — **so' o READ (Task 2) + o view-model (Task 6,
  `derivarHome().meusPareceres`, testado); a UI (Task 7) NAO renderiza secao propria** (review MEDIUM react,
  achado pos-merge — `vereador-app.html` tambem nao mostra essa secao no estado fora-de-sessao; registrado
  aqui como CARRY explicito, nao "coberto") · ✅ "ciências a acusar via inbox in-app"
  (Task 3/7, append-only = prova) · ✅ "próxima sessão" read-only (Task 6/7) · ✅ papel vereador via dev-token
  (Task 1/5) · ✅ §22.10 preservado (host injeta resolver; nada importa `cadastros`) · ✅ anti-forja (Task 4).
- ⤳ **Movido p/ C3** (decisão 1): confirmar presença do próprio aparelho (write em-sessão).
- ⤳ **Fora da Onda C enxuta** (§11.2): manifest/SW/Web Push, Flutter, WebAuthn real.
- **Risco quente:** isolamento por `ente_id`/autor + anti-forja — coberto por Task 2 (semente 2 entes/2
  vereadores) e Task 4 (403 + corpo ignorado). Revisor `security` obrigatório.

## Execução

Padrão provado das fatias (B5–B7): **ultracode Workflow** — impl TDD + 4 revisores `ecc` adversariais
(`clojure` · `database` · `security` · `react`) + corretor; o orquestrador verifica independente antes do merge.
Alternativa: subagent-driven (um subagente fresco por task + review entre tasks). Merge `fe-14-vereador-home`→
`main` quando aprovado (fecha 1/4 da Onda C; Marco MFE-3 fecha na C3).
