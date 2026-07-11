# Onda C Slice C3 — cockpit ao vivo do vereador (voto + presença pelo celular) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the vereador confirms presence and votes Sim/Não/Abstenção from their own phone during a live session, closing Marco MFE-3.

**Architecture:** Backend (Clojure/Pedestal, `apps/backend`) adds two new self-service routes (`sessoes` module: `POST /sessoes/:id/presenca/confirmar`; `legislativo` module: `POST /sessoes/:id/votacoes/:votacao-id/meu-voto`) that reuse existing write paths (`repo/registrar-presenca!`, `repo/registrar-voto!`) behind a NEW anti-forja identity resolution (mirrors `/meu/ciencias`) and — for `meu-voto` — the **first live production use of `motor/politica-dsl`** for a fine-grained authorization check. A tiny new read (`GET /meu/sessao-atual`, `paineis` module) lets the vereador's phone discover which session is currently live, reusing the existing `paineis/sli-sessoes` read-model. Frontend (Next.js, `apps/frontend`) adds a `(vereador)/votar` route that reuses the EXISTING `use-plenario`/`plenario-reducer`/`placar-vista` (same SSE the Mesa's live session screen already consumes) plus two new mutation hooks and a pure view-model.

**Tech Stack:** Clojure (Pedestal, Malli, HoneySQL, next.jdbc, Migratus), TypeScript (Next.js 16 App Router, vitest).

## Global Constraints

- **Anti-forja (§22.5):** `vereador-id` is NEVER read from a request body anywhere in this plan — always resolved server-side from the authenticated `ator` via `resolver-vereador`, mirroring `legislativo/controllers.clj:319-335` (`acusar-ciencia`).
- **Fail-closed authorization:** any policy failure throws (`authz/negar!`/`authz/check!`) and the existing global error interceptor (`apps/backend/src/oplenario/interceptors.clj:107-114`) maps it to a generic `403 {:erro "autorizacao negada"}` — never a 200 with a partial/wrong result, never a detailed reason in the body.
- **DSL identifiers have no hyphen** (`apps/backend/src/oplenario/motor/nucleo.clj` tokenizer: only letters/digits/`_`). Any map handed to `motor/politica-dsl` as `ator`/`recurso` MUST be built with underscore-separated keys for any field the DSL expression dot-accesses — do not hand it a raw kebab-case domain map and expect field access to work.
- **`hoje()` and `agora()` share ONE `:agora` context slot** in the motor runtime (`apps/backend/src/oplenario/motor/runtime.clj:162`: `("hoje" "agora") (:agora ctx)`). A single DSL expression that calls both would receive the same value typed for only one of them. Where a policy needs both a `Data` (LocalDate) fact and an `Instante` fact, run TWO separate `motor/politica-dsl` calls (one per builtin), each constructed with its own correctly-typed `:agora`, and compose the booleans in plain Clojure — do not put both builtins in one expression string.
- **Migratus never re-runs an applied migration id.** All schema changes in this plan are NEW migration files; the existing `20260620000029-sessoes-presenca.up.sql` is never edited.
- **Every backend task ends with `clojure -M:test` (or the project's equivalent alias — check `deps.edn`/`tests.edn` for the exact invocation used by recent slices) green**, and Clojure changes get an `ecc` clojure-reviewer + security-reviewer (+ database-reviewer for the migration task) pass before merge, per this project's standing protocol (CLAUDE.md §4).
- **Every frontend task ends with `vitest run`, `tsc --noEmit`, `eslint`, and `next build` green** inside the project's Docker container (`oplenario-nfr-pendencias`/CLAUDE.md memory: never run npm/node directly on the host — always in the container), plus an `ecc` react-reviewer + security-reviewer pass.
- **One task = one commit.** Branch: `fe-17-cockpit-votacao` off `main`.

---

### Task 1: Motor catalog — `esta_presente_em` fact + `VereadorId` type

**Files:**
- Modify: `apps/backend/src/oplenario/motor/tipos.clj`
- Modify: `apps/backend/src/oplenario/motor/catalogo.clj`
- Modify: `apps/backend/src/oplenario/sessoes/relacoes/presenca.clj`
- Test: `apps/backend/test/unit/oplenario/motor/registro_fatos_esta_presente_em_test.clj` (new)

**Interfaces:**
- Consumes: `sessoes.relacoes.presenca/esta-presente-em?` (existing, `apps/backend/src/oplenario/sessoes/relacoes/presenca.clj:29-42`, signature `[tx sessao-id vereador-id instante]`, unchanged).
- Produces: the fact is now resolvable by name `"esta_presente_em"` through `oplenario.motor.components.registro-fatos/resolver-para` when the host's merged `relacoes` map includes it (already true today — `oplenario.sistema` already merges `rel-sessoes/relacoes`, see `apps/backend/src/oplenario/sistema.clj:28,111`; no host wiring change needed here, only the fact map itself).

- [ ] **Step 1: Add the `VereadorId` opaque type**

In `apps/backend/src/oplenario/motor/tipos.clj`, add after the existing `SESSAO-ID` def (line 44):

```clojure
(def VEREADOR-ID   {:kind :opaco :nome "VereadorId"})    ; C3: esta_presente_em(SessaoId,VereadorId,Instante)
```

- [ ] **Step 2: Register the type name + the fact signature in the catalog**

In `apps/backend/src/oplenario/motor/catalogo.clj`:

1. Add `"VereadorId" t/VEREADOR-ID` to the `tipos-nomeados` map (line 32-36).
2. Add this line right after `(r "presentes_remoto" [t/SESSAO-ID t/INSTANTE] t/INTEIRO "Sessoes")` (line 90):

```clojure
         (r "esta_presente_em" [t/SESSAO-ID t/VEREADOR-ID t/INSTANTE] t/BOOLEANO "Sessoes")
```

3. Bump `CATALOGO-VERSAO` (line 10) to:

```clojure
(def CATALOGO-VERSAO "registry-v1@2026-07-11")  ; C3: +esta_presente_em(SessaoId,VereadorId,Instante)->Booleano
```

- [ ] **Step 3: Expose the fn under its canonical DSL name**

In `apps/backend/src/oplenario/sessoes/relacoes/presenca.clj`, change the `relacoes` map at the bottom of the file (lines 75-77) to:

```clojure
(def relacoes
  {"presentes_plenario" presentes-plenario
   "presentes_remoto"   presentes-remoto
   "esta_presente_em"   esta-presente-em?})
```

Update the docstring comment above it (line 72-74) to drop the "apenas os agregadores entram no DSL" claim, since `esta-presente-em?` now also does — e.g.: `;; nome canonico (= assinatura no catalogo) -> fn de relacao. C3: esta_presente_em passou a expor a relacao de` `;; LEITURA tambem a DSL (nao so os agregadores) — a policy fina do meu-voto (legislativo) resolve por ela.`

- [ ] **Step 4: Write the costura test (proves the aridade/signature match, and that it stays wired if someone edits either side later)**

Create `apps/backend/test/unit/oplenario/motor/registro_fatos_esta_presente_em_test.clj`:

```clojure
(ns oplenario.motor.registro-fatos-esta-presente-em-test
  "C3: prova que `esta_presente_em` (sessoes/relacoes/presenca) casa a assinatura do catalogo — a mesma
  rede de costura que já protege presentes_plenario/remoto (§1, registro_fatos.clj)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.sessoes.relacoes.presenca :as rel-sessoes]))

(deftest costura-esta-presente-em-ok
  (let [r (rf/verificar-costura rel-sessoes/relacoes)]
    (is (:ok r) (str "esperava costura ok, erros: " (:erros r)))))
```

- [ ] **Step 5: Run it**

Run: `cd apps/backend && clojure -M:test -n oplenario.motor.registro-fatos-esta-presente-em-test`
Expected: PASS (1 test, 1 assertion).

- [ ] **Step 6: Run the FULL suite to confirm nothing else's costura broke (any module that starts `RegistroFatos` at boot will now validate `esta_presente_em` too)**

Run: `cd apps/backend && clojure -M:test`
Expected: all green, same count as before + 1.

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/oplenario/motor/tipos.clj apps/backend/src/oplenario/motor/catalogo.clj \
        apps/backend/src/oplenario/sessoes/relacoes/presenca.clj \
        apps/backend/test/unit/oplenario/motor/registro_fatos_esta_presente_em_test.clj
git commit -m "feat(be): expõe esta_presente_em ao registry do motor (Onda C3)

Novo tipo VereadorId + assinatura no catálogo (registry-v1@2026-07-11);
esta-presente-em? passa a ser resolvível por nome pela DSL — pré-requisito
da policy fina do meu-voto (1ª produção real de motor/politica-dsl)."
```

---

### Task 2: Migration — nova fonte de presença `autoatendimento`

**Files:**
- Create: `apps/backend/resources/migrations/20260620000056-sessoes-presenca-fonte-autoatendimento.up.sql`
- Create: `apps/backend/resources/migrations/20260620000056-sessoes-presenca-fonte-autoatendimento.down.sql`
- Modify: `apps/backend/src/oplenario/sessoes/logic.clj`
- Test: `apps/backend/test/integration/oplenario/sessoes/presenca_db_test.clj` (add cases — read the existing file first to match its fixture style)

**Interfaces:**
- Consumes: `sessoes.presenca_evento` table (migration `20260620000029`), `logic/fontes-presenca` set, `logic/precedencia-fonte` map (both in `apps/backend/src/oplenario/sessoes/logic.clj:154-163`).
- Produces: `"autoatendimento"` becomes a valid `:fonte` value (validated automatically by `apps/backend/src/oplenario/sessoes/models/presenca.clj:19`, which reads `logic/fontes-presenca` — no separate model edit needed) with precedence BELOW `manual_secretaria`/`painel_eletronico` and ABOVE the two `inferida_*` values.

- [ ] **Step 1: Confirm the live constraint name before writing DDL**

The existing `fonte` CHECK in migration `20260620000029` is unnamed — Postgres auto-names it `{table}_{column}_check`, which SHOULD be `presenca_evento_fonte_check`, but confirm against the actual dev database rather than assume:

Run: `cd apps/backend && docker compose exec -T postgres psql -U oplenario -d oplenario -c "\d sessoes.presenca_evento"` (adjust container/db/user names to match this repo's `docker-compose.yml` / `.env` — check `apps/backend/oplenario-rodar-local` memory / `docker-compose.yml` if these don't match).
Expected: output lists a `CHECK` constraint on `fonte` — note its exact name for Step 2.

- [ ] **Step 2: Write the up migration**

Create `apps/backend/resources/migrations/20260620000056-sessoes-presenca-fonte-autoatendimento.up.sql` (substitute the confirmed constraint name from Step 1 if it differs from `presenca_evento_fonte_check`):

```sql
-- Onda C3: nova fonte de presenca 'autoatendimento' (o vereador confirma a propria presenca pelo celular,
-- POST /sessoes/:id/presenca/confirmar). Precedencia (desempate de MESMO instante, logic/precedencia-fonte):
-- manual_secretaria > painel_eletronico > autoatendimento > inferida_* — a Mesa (manual ou painel fisico)
-- sempre pode sobrepor um autoatendimento do proprio vereador; autoatendimento vale mais que uma INFERENCIA
-- (voto/tribuna sem check-in). Precisa DROP+ADD da coluna gerada (Postgres nao altera a expressao de uma
-- GENERATED ALWAYS AS em ALTER COLUMN) — o indice que a usa precisa ser derrubado antes e recriado depois.

DROP INDEX IF EXISTS sessoes.idx_presenca_evento_corrente;
--;;
ALTER TABLE sessoes.presenca_evento DROP CONSTRAINT IF EXISTS presenca_evento_fonte_check;
--;;
ALTER TABLE sessoes.presenca_evento ADD CONSTRAINT presenca_evento_fonte_check
  CHECK (fonte IN ('painel_eletronico', 'manual_secretaria', 'autoatendimento', 'inferida_por_voto', 'inferida_por_tribuna'));
--;;
ALTER TABLE sessoes.presenca_evento DROP COLUMN fonte_precedencia;
--;;
ALTER TABLE sessoes.presenca_evento ADD COLUMN fonte_precedencia integer NOT NULL GENERATED ALWAYS AS (
  CASE fonte
    WHEN 'manual_secretaria' THEN 4
    WHEN 'painel_eletronico' THEN 3
    WHEN 'autoatendimento'   THEN 2
    ELSE 1
  END) STORED;
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_evento_corrente
  ON sessoes.presenca_evento (ente_id, sessao_id, vereador_id, ocorrido_em DESC, fonte_precedencia DESC, id DESC)
  INCLUDE (tipo, modalidade);
```

- [ ] **Step 3: Write the down migration**

Create `apps/backend/resources/migrations/20260620000056-sessoes-presenca-fonte-autoatendimento.down.sql`:

```sql
DROP INDEX IF EXISTS sessoes.idx_presenca_evento_corrente;
--;;
ALTER TABLE sessoes.presenca_evento DROP CONSTRAINT IF EXISTS presenca_evento_fonte_check;
--;;
-- reverte a CHECK: qualquer linha 'autoatendimento' ja gravada bloquearia este DOWN (esperado — down nao e
-- p/ rodar com dado incompativel presente; mesma disciplina das demais migrations deste projeto).
ALTER TABLE sessoes.presenca_evento ADD CONSTRAINT presenca_evento_fonte_check
  CHECK (fonte IN ('painel_eletronico', 'manual_secretaria', 'inferida_por_voto', 'inferida_por_tribuna'));
--;;
ALTER TABLE sessoes.presenca_evento DROP COLUMN fonte_precedencia;
--;;
ALTER TABLE sessoes.presenca_evento ADD COLUMN fonte_precedencia integer NOT NULL GENERATED ALWAYS AS (
  CASE fonte WHEN 'manual_secretaria' THEN 3 WHEN 'painel_eletronico' THEN 2 ELSE 1 END) STORED;
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_evento_corrente
  ON sessoes.presenca_evento (ente_id, sessao_id, vereador_id, ocorrido_em DESC, fonte_precedencia DESC, id DESC)
  INCLUDE (tipo, modalidade);
```

- [ ] **Step 4: Update `logic.clj`'s app-side mirror of the enum/precedence**

In `apps/backend/src/oplenario/sessoes/logic.clj`, change (lines 154-163):

```clojure
(def fontes-presenca
  "Fonte de captura do evento. As inferencias (vereador vota/usa tribuna sem check-in) viram evento concreto.
  'autoatendimento' (Onda C3) = o proprio vereador confirma a propria presenca pelo celular."
  #{"painel_eletronico" "manual_secretaria" "autoatendimento" "inferida_por_voto" "inferida_por_tribuna"})

(def tipos-presenca-positiva
  "Tipos cujo ULTIMO evento mantem o vereador PRESENTE; 'saida' e' o unico que tira."
  #{"entrada" "retorno" "mudanca_modalidade"})

(def precedencia-fonte
  "Precedencia em conflito de MESMO instante (§22.6 eixo C): manual_secretaria > painel_eletronico >
  autoatendimento > inferida_* (Onda C3). Usada como desempate ao escolher o ultimo evento por vereador (a
  consulta replica esta ordem em SQL, fonte_precedencia — migration 20260620000056)."
  {"manual_secretaria" 4 "painel_eletronico" 3 "autoatendimento" 2 "inferida_por_voto" 1 "inferida_por_tribuna" 1})
```

- [ ] **Step 5: Read the existing DB test file to match its exact fixture/helper style, then add one test**

Read `apps/backend/test/integration/oplenario/sessoes/presenca_db_test.clj` in full first (it already has the tenant/seed fixture pattern this test must reuse — do not invent a new one). Add a test asserting: inserting a `presenca_evento` row with `fonte "autoatendimento"` succeeds, and `fonte_precedencia` reads back as `2`. Mirror the exact assertion style already used in that file for `fonte_precedencia` (search the file for `fonte_precedencia` — it likely already has a similar case for `manual_secretaria`/`painel_eletronico` to copy the shape from).

- [ ] **Step 6: Run migrations + the test**

Run: `cd apps/backend && clojure -M:test -n oplenario.sessoes.presenca-db-test` (adjust the test-runner invocation to match whatever `deps.edn`/`tests.edn` alias this project actually uses — check a recent commit for the exact command; migrations run automatically via the test fixture, same as every other integration test in this suite).
Expected: PASS, including the new case.

- [ ] **Step 7: Commit**

```bash
git add apps/backend/resources/migrations/20260620000056-sessoes-presenca-fonte-autoatendimento.up.sql \
        apps/backend/resources/migrations/20260620000056-sessoes-presenca-fonte-autoatendimento.down.sql \
        apps/backend/src/oplenario/sessoes/logic.clj \
        apps/backend/test/integration/oplenario/sessoes/presenca_db_test.clj
git commit -m "feat(be): nova fonte de presença 'autoatendimento' (Onda C3)

Migration 0056 (fonte + fonte_precedencia recalculada); precedência:
manual_secretaria > painel_eletronico > autoatendimento > inferida_*."
```

**Review checkpoint:** dispatch `ecc` **database-reviewer** on this task specifically before moving on — DROP/ADD COLUMN on a column embedded in a hot-path index is exactly the kind of change that reviewer exists to catch (lock behavior under concurrent writes, whether the DROP COLUMN needs a `CASCADE`, whether the constraint name assumption from Step 1 held).

---

### Task 3: Backend — `POST /sessoes/:id/presenca/confirmar`

**Files:**
- Modify: `apps/backend/src/oplenario/sessoes/controllers.clj`
- Modify: `apps/backend/src/oplenario/sessoes/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj`
- Test: `apps/backend/test/integration/oplenario/sessoes/presenca_http_in_test.clj` (add cases; read it first to mirror its exact fake-repo/harness style — same family as `votacao_http_in_test.clj` shown in the codebase already)

**Interfaces:**
- Consumes: `oplenario.sessoes.components.repositorio/RepoSessoes` protocol methods `buscar-sessao`, `registrar-presenca!` (both already exist, unchanged signatures). `oplenario.kernel.tempo/agora` (existing, `[r] -> Instant`).
- Produces: `controllers/confirmar-minha-presenca [repo-sessoes resolver-vereador ator sessao-id instante] -> {:id uuid} | nil`. Route `:sessoes/confirmar-minha-presenca`.

- [ ] **Step 1: Add the controller**

In `apps/backend/src/oplenario/sessoes/controllers.clj`, add after `registrar-presenca` (after line 59):

```clojure
(defn confirmar-minha-presenca
  "Onda C3 — autoatendimento: o vereador confirma a PROPRIA presenca pelo celular. `vereador-id` NUNCA vem
  do corpo (resolvido do ator via `resolver-vereador`, injetado pelo host — mesmo contrato anti-forja de
  `legislativo/controllers.clj/acusar-ciencia`). `fonte` e' SEMPRE 'autoatendimento' (nunca do cliente,
  mesma disciplina de `registrar-presenca` forcando 'manual_secretaria'). `tipo` e' SEMPRE 'entrada':
  reconfirmar nao corrompe nada (append-only; so' o ULTIMO evento por vereador conta, `esta-presente-em?`),
  entao um evento extra e' inofensivo — nao ha necessidade de checar 'ja presente' antes de inserir.
  `modalidade` fixa 'plenario' (V1 = Nivel 1, presenca remota e' so' manual pela Mesa, §22.6). Ator sem
  cadastro vinculado (`resolver-vereador` nil) -> nil (-> 404, mesmo contrato de /meu/ciencias). Sessao
  inexistente no tenant -> nil (-> 404). `instante` vem do RELOGIO do servidor (borda), nunca do cliente."
  [repo-sessoes resolver-vereador ator sessao-id instante]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
      (authz/check! ator :sessao/confirmar-presenca sessao logic/pode-ver-sessao?)
      (let [id (random-uuid)]
        (repo/registrar-presenca! repo-sessoes (:ente-id ator)
          {:id id :sessao-id sessao-id :vereador-id vereador-id :tipo "entrada" :modalidade "plenario"
           :fonte "autoatendimento" :ocorrido-em instante :created-by (:identidade-id ator)})
        {:id id}))))
```

- [ ] **Step 2: Add the handler + wire it into `rotas`**

In `apps/backend/src/oplenario/sessoes/diplomat/http/in.clj`:

1. Add `[oplenario.kernel.tempo :as tempo]` to the `:require` block (it's not currently imported in this namespace).
2. Add after `registrar-presenca-handler` (after line 84):

```clojure
(defn- confirmar-presenca-handler
  "POST /sessoes/:id/presenca/confirmar (Onda C3, papel 'vereador'). Sem corpo — `vereador-id` resolvido do
  ator (anti-forja), `fonte`/`tipo`/`modalidade` fixos no controller, `ocorrido-em` = o relogio do servidor
  (nunca do cliente). Reusa o MESMO wire/out de recibo que a rota da Mesa (`recibo-presenca->wire`, so
  {:id}). nil (sessao inexistente OU ator sem cadastro de vereador) -> 404."
  [repo-sessoes resolver-vereador relogio]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          instante (tempo/agora relogio)]
      (if-let [recibo (controllers/confirmar-minha-presenca repo-sessoes resolver-vereador ator sid instante)]
        (http/json-resposta 201 (adapters-out-presenca/recibo-presenca->wire recibo))
        (http/json-resposta 404 {:erro "sessao nao encontrada, ou vereador sem cadastro vinculado neste ente"})))))
```

3. In the `rotas` fn (line 329-334), add `resolver-vereador` and `relogio` to the destructured arg map (`[{:keys [auth repo-sessoes objeto-store resolver-vereador relogio]}]`) and add a new route entry to the route set, using the SAME `papel-vereador` binding style as `legislativo-http/rotas` (`it/exige-papel "vereador"` — define it locally in this fn, e.g. `(let [papel-vereador (it/exige-papel "vereador")] ...)`):

```clojure
    ["/sessoes/:id/presenca/confirmar" :post
     [auth papel-vereador (confirmar-presenca-handler repo-sessoes resolver-vereador relogio)]
     :route-name :sessoes/confirmar-minha-presenca]
```

Update the `rotas` docstring to mention the new dep (mirrors how `legislativo-http/rotas`'s docstring documents each injected dependency).

- [ ] **Step 3: Wire the host (`apps/backend/src/oplenario/rotas.clj`)**

Change line 100 from:

```clojure
        (into (sessoes-http/rotas {:auth auth :repo-sessoes repo-sessoes :objeto-store objeto-store}))
```

to:

```clojure
        (into (sessoes-http/rotas {:auth auth :repo-sessoes repo-sessoes :objeto-store objeto-store
                                   :resolver-vereador resolver-vereador-fn :relogio relogio-producao}))
```

(Both `resolver-vereador-fn` and `relogio-producao` already exist as locals in `montar` — lines 52 and 71 — this task only threads them into a fragment that didn't receive them before.)

- [ ] **Step 4: Read the existing `presenca_http_in_test.clj` in full, then add tests mirroring its exact harness**

Read `apps/backend/test/integration/oplenario/sessoes/presenca_http_in_test.clj` first. Add cases for the new route asserting: (a) success path (papel vereador, cadastro vinculado) → 201 `{:id}`, and the fake/real repo receives a call with `fonte "autoatendimento"` and `tipo "entrada"`; (b) papel "secretario" hitting this route → 403 (gate is `papel-vereador`, not `papel`); (c) `resolver-vereador` returning nil → 404; (d) sessão inexistente → 404. Match the file's existing fake-repo/fake-idp conventions exactly (same family as `votacao_http_in_test.clj` shown earlier in this plan's research).

- [ ] **Step 5: Run**

Run: `cd apps/backend && clojure -M:test`
Expected: all green, including the new cases.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/sessoes/controllers.clj apps/backend/src/oplenario/sessoes/diplomat/http/in.clj \
        apps/backend/src/oplenario/rotas.clj apps/backend/test/integration/oplenario/sessoes/presenca_http_in_test.clj
git commit -m "feat(be): POST /sessoes/:id/presenca/confirmar — autoatendimento do vereador (Onda C3)"
```

---

### Task 4: Backend — `POST /sessoes/:id/votacoes/:votacao-id/meu-voto`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/wire/in/votacao.clj`
- Modify: `apps/backend/src/oplenario/legislativo/adapters/in/votacao.clj`
- Modify: `apps/backend/src/oplenario/legislativo/controllers.clj`
- Modify: `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/meu_voto_controller_test.clj` (new, DB-free — fake repo)
- Test: `apps/backend/test/integration/oplenario/marco_m3_test.clj` (new — real Postgres, mirrors `marco_m2_test.clj`'s harness/fixtures exactly)

**Interfaces:**
- Consumes: `motor.api/politica-dsl` (existing, unchanged), `legislativo.components.repositorio/RepoLegislativo` protocol methods `buscar-votacao`, `registrar-voto!`, `transacao` (all already exist).
- Produces: `controllers/meu-voto [repo-leg consultar-sessao resolver-vereador registro ator sessao-id votacao-id hoje instante m] -> {:id uuid} | nil`. Reuses `adapters-out/votacao/voto->wire` unmodified.

- [ ] **Step 1: Add the wire/in schema (no `vereador-id` — structural anti-forja)**

In `apps/backend/src/oplenario/legislativo/wire/in/votacao.clj`, add after `RegistrarVoto` (after line 25):

```clojure
(def MeuVoto
  "Corpo de POST /sessoes/:id/votacoes/:votacao-id/meu-voto (Onda C3). SO' `voto` — `vereador-id` NAO existe
  neste contrato (nem opcional): e' resolvido do ator no controller, anti-forja por construcao — a mesma
  disciplina estrutural do sigilo em `votos_secretos` (§22.6), so' que aqui o campo simplesmente nao existe
  na FORMA do contrato, em vez de ser descartado depois de chegar."
  [:map {:closed true}
   [:voto (km/enum-de logic/tipos-voto)]])
```

- [ ] **Step 2: Add the adapter**

In `apps/backend/src/oplenario/legislativo/adapters/in/votacao.clj`, add after `registrar-voto->dominio` (after line 65):

```clojure
(def ^:private campos-meu-voto ["voto"])

(defn meu-voto->dominio
  "Corpo (wire/in.MeuVoto: so' `voto`) + `ator` + `votacao-id` (UUID coagido do path) -> mapa de dominio
  PARCIAL p/ Repo/registrar-voto! (o controller injeta o `vereador-id` resolvido do ator antes de gravar —
  este adapter nao o le nem o gera, ele simplesmente nao existe no wire de entrada)."
  [ator votacao-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-meu-voto)]
    (validar! wire/MeuVoto m "corpo de meu-voto invalido")
    {:id (random-uuid) :votacao-id votacao-id :voto (:voto m) :created-by (:identidade-id ator)}))
```

- [ ] **Step 3: Add the controller**

In `apps/backend/src/oplenario/legislativo/controllers.clj`:

1. Add `[oplenario.motor.api :as motor]` to the `:require` block (not currently imported in this namespace).
2. Add after `registrar-voto` (after line 60):

```clojure
;; ============================ Onda C3: meu-voto (o vereador vota do proprio celular) ============================

(def ^:private expr-mandato-vigente
  "tem_mandato_vigente(ator.identidade, hoje())")

(def ^:private expr-presente-nesta-sessao
  "esta_presente_em(recurso.sessao_id, recurso.vereador_id, agora())")

(defn meu-voto
  "Onda C3 — o vereador vota do PROPRIO celular. `vereador-id` NUNCA vem do corpo (resolvido do ator via
  `resolver-vereador`, mesmo contrato anti-forja de `acusar-ciencia`). Authz herdada da sessao (mesma Casa,
  `sessao-autorizada`) + a AMARRA votacao<->sessao (`votacao-na-sessao`), como a rota da Mesa. Por cima,
  a POLICY FINA (1a producao real de `motor/politica-dsl`, disciplina 5): mandato vigente + presenca
  registrada NESTA sessao. Os dois fatos rodam em avaliacoes DSL SEPARADAS (`hoje()` e `agora()` compartilham
  UM so' slot `:agora` no motor — motor/runtime.clj — nao coexistem numa MESMA expressao; ver
  docs/superpowers/specs/2026-07-11-onda-c-slice3-cockpit-votacao-design.md §3.2) + dois checks TRIVIAIS em
  Clojure puro (sem DSL: nao sao fatos resolvidos por nome, so' campos ja carregados) — estado 'aberta' e
  modalidade != 'secreta' (voto secreto NUNCA passa por aqui, mesmo que a policy DSL nao barrasse: e' regra
  de negocio de borda, nao so' authz). Qualquer falha -> authz/check! lanca -> 403 generico (nunca detalha
  qual precondicao falhou). Modalidade 'nominal' -> registra (reusa `repo/registrar-voto!`, MESMO caminho da
  Mesa); qualquer outra (so' 'simbolica' pode chegar aqui, dado o gate acima) -> :validacao/invalido (400).
  nil (sessao/votacao inexistente ou de outra sessao, OU ator sem cadastro de vereador) -> borda traduz 404."
  [repo-leg consultar-sessao resolver-vereador registro ator sessao-id votacao-id hoje instante m]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (sessao-autorizada consultar-sessao ator sessao-id)
      (let [ente-id (:ente-id ator)]
        (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
          (when (= "secreta" (:modalidade v))
            (throw (ex-info "voto secreto nao e' registravel pelo proprio celular"
                            {:tipo :validacao/invalido :campos [:modalidade] :modalidade "secreta"})))
          (let [ator-dsl {:identidade (:identidade-id ator)}
                recurso-dsl {:sessao_id (:sessao-id v) :vereador_id vereador-id}]
            (repo/transacao repo-leg ente-id
              (fn [tx]
                (authz/check! ator-dsl :votacao/meu-voto recurso-dsl
                  (fn [a r]
                    (and (= "aberta" (:estado v))
                         ((motor/politica-dsl {:registro registro :tx tx :expr expr-mandato-vigente :agora hoje}) a r)
                         ((motor/politica-dsl {:registro registro :tx tx :expr expr-presente-nesta-sessao :agora instante}) a r))))))
            (case (:modalidade v)
              "nominal" (repo/registrar-voto! repo-leg ente-id (assoc m :vereador-id vereador-id))
              (throw (ex-info "modalidade nao registra votos individuais"
                              {:tipo :validacao/invalido :campos [:modalidade] :modalidade (:modalidade v)})))))))))
```

- [ ] **Step 4: Add the handler + wire it into `rotas`**

In `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`, add after `voto-handler` (after line 61):

```clojure
(defn- meu-voto-handler
  "POST /sessoes/:id/votacoes/:votacao-id/meu-voto (Onda C3, papel 'vereador'). `hoje`/`instante` resolvidos
  AQUI, na borda (mesmo padrao de emitir-parecer-handler/`agora`) — o controller nao le o relogio."
  [repo-leg consultar-sessao resolver-vereador registro relogio]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          vid  (adapters-in/id-param->uuid (get-in req [:path-params :votacao-id]))
          instante (tempo/agora relogio)
          hoje (tempo/hoje-de instante zona-civil)
          m    (adapters-in/meu-voto->dominio ator vid (:json-params req))]
      (if-let [recibo (controllers/meu-voto repo-leg consultar-sessao resolver-vereador registro ator sid vid hoje instante m)]
        (http/json-resposta 201 (adapters-out/voto->wire recibo))
        (http/json-resposta 404 {:erro "vereador sem cadastro vinculado, ou sessao/votacao nao encontrada"})))))
```

Add `[oplenario.kernel.tempo :as tempo]` to this namespace's `:require` if not already present (check first — `zona-civil` at line 43 suggests `ZoneId` is imported directly via `:import`, but `tempo/agora`/`tempo/hoje-de` need the `oplenario.kernel.tempo` namespace required explicitly).

In the `rotas` fn (around line 361-427), add a new route entry using the already-defined `papel-vereador` local:

```clojure
      ["/sessoes/:id/votacoes/:votacao-id/meu-voto" :post
       [auth papel-vereador it/corpo-json (meu-voto-handler repo-legislativo consultar-sessao resolver-vereador registro relogio)]
       :route-name :legislativo/meu-voto]
```

(`registro` and `relogio` are already destructured params of `legislativo-http/rotas` and already passed by the host at `rotas.clj:105-106` — no host wiring change needed for this route.)

- [ ] **Step 5: DB-free controller unit test (fake repo, proves dispatch + policy composition without Postgres)**

Create `apps/backend/test/unit/oplenario/legislativo/meu_voto_controller_test.clj` mirroring the fake-repo style already shown in `votacao_http_in_test.clj` (reify `RepoLegislativo`/`RepoSessoes` with a `chamadas` atom). Cover:
- `resolver-vereador` returns nil → controller returns nil (no repo write attempted).
- sessão de outra Casa → `authz/negado?` thrown by `sessao-autorizada`.
- votação de outra sessão (amarra falha) → nil.
- modalidade "secreta" → throws `:validacao/invalido` (checked BEFORE the policy/tx, per Step 3's code) — assert `registrar-voto!` was never called (`chamadas` atom stays empty).
- modalidade "simbolica" with a policy that would otherwise pass → throws `:validacao/invalido` after the policy check runs but before any write.

For the `registro`/policy plumbing in this fake-repo test, pass a real `RegistroFatos` built from a stub `relacoes` map whose `"tem_mandato_vigente"` and `"esta_presente_em"` fns both ignore `tx` and return a fixed boolean per test case (this test is DB-free — it is NOT proving the real facts resolve correctly against Postgres; that's Step 6's job) — e.g. `{"tem_mandato_vigente" (fn [_tx _id _data] true) "esta_presente_em" (fn [_tx _sid _vid _inst] true)}`, started via `(component/start (rf/registro-fatos stub-relacoes))`. Also stub `repo/transacao` in the fake repo to just call `(f nil)` (no real tx needed since the stub facts ignore it).

- [ ] **Step 6: Run the unit test**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.meu-voto-controller-test`
Expected: PASS, all cases.

- [ ] **Step 7: Marco M3 integration test — real Postgres, the actual bug class this slice exists to prevent**

Create `apps/backend/test/integration/oplenario/marco_m3_test.clj`, copying `marco_m2_test.clj`'s fixture/harness (`use-fixtures :once` boots the real `sistema`, migrates, seeds referências) and its `seed-casa!`/`cassar-mandato!` helpers (adapt names as needed — reuse, don't reinvent). Prove, against REAL Postgres:

1. **Happy path:** seed a Casa + vereador with vigente mandato; open a votação `nominal` `aberta` on a sessão; register a `presenca_evento` (`tipo "entrada"`, `fonte "autoatendimento"`, `ocorrido_em` <= now) for that vereador on that sessão; call `controllers/meu-voto` with the resolved `vereador-id` (skip HTTP — call the controller directly, same as `marco_m2_test.clj` does for `motor/avaliar`/`politica-dsl`) → succeeds, `votos` row exists with the correct `vereador_id`.
2. **NEGA por ausência:** same setup but WITHOUT a presence event → `authz/negado?` (via the `nega?` helper copied from `marco_m2_test.clj`).
3. **NEGA por mandato cassado:** happy-path setup, then `cassar-mandato!`, same votação still aberta → `authz/negado?`.
4. **NEGA por modalidade secreta:** votação `secreta` instead of `nominal` (even with mandate+presence both true) → throws `:validacao/invalido` (not `authz/negado?` — this is the trivial pre-check in Step 3's code, confirm the test asserts the right exception `:tipo`).
5. **Sanity on the `hoje()`/`agora()` split (Global Constraints):** assert the mandate check still resolves correctly (uses `hoje` LocalDate) in the SAME test run where the presence check resolves correctly (uses `instante` Instant) — this is the actual regression this task's Step 3 code exists to prevent; if the split were removed and both builtins fed from one `:agora` value, one of the two facts would be evaluated against the wrong type. Do not skip this — it is the single riskiest line in this slice.

- [ ] **Step 8: Run the integration test + full suite**

Run: `cd apps/backend && clojure -M:test -n oplenario.marco-m3-test`
Expected: PASS, all 5 cases (or however many end up written).

Run: `cd apps/backend && clojure -M:test`
Expected: all green.

- [ ] **Step 9: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/wire/in/votacao.clj apps/backend/src/oplenario/legislativo/adapters/in/votacao.clj \
        apps/backend/src/oplenario/legislativo/controllers.clj apps/backend/src/oplenario/legislativo/diplomat/http/in.clj \
        apps/backend/test/unit/oplenario/legislativo/meu_voto_controller_test.clj \
        apps/backend/test/integration/oplenario/marco_m3_test.clj
git commit -m "feat(be): POST /sessoes/:id/votacoes/:votacao-id/meu-voto (Onda C3, Marco M3)

1ª produção real de motor/politica-dsl — mandato vigente + presença
registrada, cada fato em avaliação DSL separada (hoje()/agora() não
coexistem numa mesma expressão, ver constraints do plano). Secreta e
qualquer modalidade não-nominal ficam de fora por construção."
```

**Review checkpoint:** dispatch `ecc` **clojure-reviewer** + **security-reviewer** on Tasks 3+4 together (they share the anti-forja/policy shape) before starting Task 5.

---

### Task 5: Backend — `GET /meu/sessao-atual` (papel vereador, para o FE descobrir a sessão viva)

**Files:**
- Modify: `apps/backend/src/oplenario/paineis/controllers.clj`
- Create: `apps/backend/src/oplenario/paineis/wire/out/minha_sessao_atual.clj`
- Create: `apps/backend/src/oplenario/paineis/adapters/out/minha_sessao_atual.clj`
- Modify: `apps/backend/src/oplenario/paineis/diplomat/http/in.clj`
- Test: `apps/backend/test/integration/oplenario/paineis/minha_sessao_atual_http_in_test.clj` (new)

**Why this task exists (not in the original spec):** `usePlenario(sessaoId, token)` needs a `sessaoId`. The Mesa's equivalent discovery (`GET /paineis/sli/sessoes`) is gated `papel "secretario"` (`apps/backend/src/oplenario/paineis/diplomat/http/in.clj:102`) — a vereador's token would get a 403. `paineis/controllers.clj:22-25` (`sli-sessoes`) already reads the exact read-model needed ("sessões do tenant, em curso primeiro"); this task re-exposes just its FIRST entry under a vereador-gated route, instead of duplicating the query.

**Interfaces:**
- Consumes: `oplenario.paineis.controllers/sli-sessoes` (existing, unchanged, `[repo-paineis ator] -> {:sessoes [...]}`).
- Produces: `GET /meu/sessao-atual -> {"sessao-id": string|null, "situacao": string|null}` (200, always — never 404; "no live session" is a valid state, not an error).

- [ ] **Step 1: Read `paineis/controllers.clj`, `paineis/wire/out/sli_sessao.clj`, and `paineis/adapters/out/sli_sessao.clj` (if it exists under that name — check) in full before writing anything**, to match their exact style (this module's wire/adapter split for the existing SLI route is the template).

- [ ] **Step 2: Add the wire/out contract**

Create `apps/backend/src/oplenario/paineis/wire/out/minha_sessao_atual.clj`:

```clojure
(ns oplenario.paineis.wire.out.minha-sessao-atual
  "Representacao EXTERNA de SAIDA de GET /meu/sessao-atual (§22.10 wire/out, ADR-0001, Onda C3). Reusa a
  MESMA leitura de `sli-sessoes` (paineis/controllers) — so' projeta a PRIMEIRA entrada (a lista ja' vem
  ordenada 'em curso primeiro', mesmo contrato de GET /paineis/sli/sessoes). Sem sessao viva -> ambos nil,
  200 (ausencia de sessao e' um ESTADO, nao um erro).")

(def MinhaSessaoAtualOut
  [:map {:closed true}
   [:sessao-id {:optional true} [:maybe :string]]
   [:situacao {:optional true} [:maybe :string]]])
```

- [ ] **Step 3: Add the adapters/out**

Create `apps/backend/src/oplenario/paineis/adapters/out/minha_sessao_atual.clj`:

```clojure
(ns oplenario.paineis.adapters.out.minha-sessao-atual
  "Gate de SAIDA `models -> wire/out` de GET /meu/sessao-atual (§22.10 adapters/out, ADR-0001, Onda C3)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.minha-sessao-atual :as wire]))

(set! *warn-on-reflection* true)

(defn minha-sessao-atual->wire
  "{:sessoes [...]} (cru, do controller sli-sessoes) -> MinhaSessaoAtualOut (validado). So' a PRIMEIRA
  entrada (ja ordenada 'em curso primeiro'); lista vazia -> {:sessao-id nil :situacao nil}."
  [{:keys [sessoes]}]
  (let [primeira (first sessoes)
        out {:sessao-id (some-> primeira :sessao-id str)
             :situacao (:situacao primeira)}]
    (when-not (m/validate wire/MinhaSessaoAtualOut out)
      (throw (ex-info "projecao de minha-sessao-atual viola o contrato (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MinhaSessaoAtualOut out))})))
    out))
```

(Adjust field access `:sessao-id`/`:situacao` on `primeira` to match whatever shape `controllers/sli-sessoes` actually returns — confirm against Step 1's reading of the real code before finalizing; the SLI wire/out schema read during planning used `:sessao-id`/`:situacao` as the model keys, but verify the CONTROLLER's raw return (pre-wire) uses the same kebab keys.)

- [ ] **Step 4: Handler + route**

In `apps/backend/src/oplenario/paineis/diplomat/http/in.clj`, add a handler mirroring `sli-sessoes-handler` (around line 32-37) but gated `papel-vereador` instead of `papel`, and add the route to the `rotas` fn's route set:

```clojure
["/meu/sessao-atual" :get [auth papel-vereador (minha-sessao-atual-handler repo-paineis)] :route-name :paineis/minha-sessao-atual]
```

(Define `papel-vereador` locally in this `rotas` fn the same way `legislativo-http/rotas` does, if not already present there.)

- [ ] **Step 5: Test**

Create `apps/backend/test/integration/oplenario/paineis/minha_sessao_atual_http_in_test.clj` mirroring whatever harness the existing `paineis` HTTP tests use for `/paineis/sli/sessoes` (find and read that test file first). Cover: papel vereador + a live/agendada sessão → 200 with `sessao-id` populated; no sessões at all → 200 `{:sessao-id nil :situacao nil}` (never 404); papel secretario → still fine (this route is additive, doesn't remove secretario's access to the original `/paineis/sli/sessoes` — confirm the two routes coexist without conflict) or explicitly confirm the gate is vereador-only if that's the final call made in Step 4.

- [ ] **Step 6: Run + commit**

Run: `cd apps/backend && clojure -M:test`
Expected: all green.

```bash
git add apps/backend/src/oplenario/paineis/controllers.clj apps/backend/src/oplenario/paineis/wire/out/minha_sessao_atual.clj \
        apps/backend/src/oplenario/paineis/adapters/out/minha_sessao_atual.clj apps/backend/src/oplenario/paineis/diplomat/http/in.clj \
        apps/backend/test/integration/oplenario/paineis/minha_sessao_atual_http_in_test.clj
git commit -m "feat(be): GET /meu/sessao-atual — descoberta de sessão viva p/ o vereador (Onda C3)

Reusa a leitura de sli-sessoes (paineis) sob um gate 'vereador' — o
cockpit do celular precisa saber qual sessão abrir sem o papel secretario."
```

---

### Task 6: Backend — expõe `vereador-id` em `GET /meu/painel` (bootstrap de identidade p/ o FE)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/wire/out/meu_painel.clj`
- Modify: `apps/backend/src/oplenario/legislativo/adapters/out/meu_painel.clj`
- Modify: `apps/backend/src/oplenario/legislativo/controllers.clj`
- Modify: `apps/backend/src/oplenario/codegen/gerar_legislativo.clj` (no change needed — `MeuPainelOut` is already in the manifest; just re-running the generator picks up the new field)
- Test: existing `meu_painel`-related tests (find and extend — search for `MeuPainelOut`/`meu-painel->wire` test coverage)

**Why:** the cockpit needs to know its OWN `vereador-id` to interpret the shared SSE placar (`placar.votosNominais[meuVereadorId]`) and the shared presence list (`estado.presentes.includes(meuVereadorId)`) — both keyed by `vereador-id`, which the frontend is never handed anywhere else (§22.5: `vereador-id` is server-resolved, never client-supplied — the response side of that boundary can safely hand the vereador back its OWN id after a successful authenticated read of ITS OWN painel).

- [ ] **Step 1: Widen the wire/out contract**

In `apps/backend/src/oplenario/legislativo/wire/out/meu_painel.clj`, change `MeuPainelOut` (lines 35-39):

```clojure
(def MeuPainelOut
  "A resposta de GET /meu/painel (Onda C1, §11.2/§11.3; +vereador-id Onda C3 — bootstrap de identidade p/
  o cockpit ao vivo interpretar o placar/presenca compartilhados, ambos keyed por vereador-id)."
  [:map {:closed true}
   [:vereador-id {:optional true} [:maybe :string]]
   [:proposicoes [:sequential ProposicaoResumoMeuPainelOut]]
   [:pareceres [:sequential ParecerResumoMeuPainelOut]]
   [:ciencias [:sequential CienciaPendenteOut]]])
```

- [ ] **Step 2: Thread it through the adapter**

In `apps/backend/src/oplenario/legislativo/adapters/out/meu_painel.clj`, change `meu-painel->wire` (lines 21-30):

```clojure
(defn meu-painel->wire
  "{:vereador-id :proposicoes :pareceres :ciencias} (cru, kebab, do controller) -> MeuPainelOut (validado)."
  [{:keys [vereador-id proposicoes pareceres ciencias]}]
  (let [out {:vereador-id (some-> vereador-id str)
             :proposicoes (mapv proposicao->wire proposicoes)
             :pareceres (mapv parecer->wire pareceres)
             :ciencias (mapv ciencia->wire ciencias)}]
    (when-not (m/validate wire/MeuPainelOut out)
      (throw (ex-info "painel do vereador viola o contrato MeuPainelOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MeuPainelOut out))})))
    out))
```

- [ ] **Step 3: Thread it through the controller**

In `apps/backend/src/oplenario/legislativo/controllers.clj`, change `meu-painel` (lines 306-317):

```clojure
(defn meu-painel
  "Onda C1 — leitura composta 'minhas proposicoes + meus pareceres + ciencias pendentes' do vereador ATOR
  (anti-forja: SEMPRE o vereador resolvido do proprio ator). Onda C3: tambem devolve o `vereador-id`
  resolvido (bootstrap de identidade p/ o cockpit — ver docstring do wire/out). `resolver-vereador` e' a fn
  injetada pelo HOST que resolve identidade-id->vereador-id NESTE ente. Um ator com papel 'vereador' mas SEM
  cadastro vinculado (`resolver-vereador` nil) devolve painel VAZIO (vereador-id nil incluso) — nao lanca."
  [repo-legislativo resolver-vereador ator]
  (if-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (assoc (repo/meu-painel repo-legislativo (:ente-id ator) vereador-id) :vereador-id vereador-id)
    {:vereador-id nil :proposicoes [] :pareceres [] :ciencias []}))
```

- [ ] **Step 4: Find and extend the existing test coverage**

Run: `cd apps/backend && grep -rl "MeuPainelOut\|meu-painel->wire\|controllers/meu-painel" test/` to find the relevant test file(s). Add assertions that the wire response now includes `vereador-id` matching the resolved id (and `nil` in the no-cadastro case).

- [ ] **Step 5: Run + commit**

Run: `cd apps/backend && clojure -M:test`
Expected: all green (watch for any OTHER existing test asserting `MeuPainelOut`'s exact key set with `:closed true` strictness that might need updating — Malli `:closed true` maps reject unknown keys on the OUTPUT side too if a test does an exact-equality comparison against a literal map missing the new key; update any such literal fixtures).

```bash
git add apps/backend/src/oplenario/legislativo/wire/out/meu_painel.clj apps/backend/src/oplenario/legislativo/adapters/out/meu_painel.clj \
        apps/backend/src/oplenario/legislativo/controllers.clj
# (+ whichever test file Step 4 found)
git commit -m "feat(be): /meu/painel devolve vereador-id (Onda C3, bootstrap de identidade)"
```

- [ ] **Step 6: Regenerate the FE contract**

Run: `cd apps/backend && clojure -M -m oplenario.codegen.gerar-legislativo ../frontend/src/lib/contrato-legislativo.gen.ts` (path relative to `apps/backend`; adjust if the monorepo layout needs a different relative path — confirm `apps/frontend/src/lib/contrato-legislativo.gen.ts` is the exact file touched, `git diff --stat` after running should show ONLY this file with a small addition to `MeuPainelOut`, no other unrelated regeneration drift).
Expected: `MeuPainelOut` interface in `apps/frontend/src/lib/contrato-legislativo.gen.ts` gains `vereadorId?: string | null;`.

```bash
git add apps/frontend/src/lib/contrato-legislativo.gen.ts
git commit -m "chore(fe): regenera contrato-legislativo.gen.ts (vereador-id em MeuPainelOut)"
```

---

### Task 7: Frontend — hooks de mutação (`use-confirmar-presenca`, `use-meu-voto`) + hook de descoberta (`use-minha-sessao-atual`)

**Files:**
- Create: `apps/frontend/src/lib/use-confirmar-presenca.ts`
- Create: `apps/frontend/src/lib/use-confirmar-presenca.test.ts`
- Create: `apps/frontend/src/lib/use-meu-voto.ts`
- Create: `apps/frontend/src/lib/use-meu-voto.test.ts`
- Create: `apps/frontend/src/lib/use-minha-sessao-atual.ts`
- Create: `apps/frontend/src/lib/use-minha-sessao-atual.test.ts`

**Interfaces:**
- Consumes: nothing new beyond `fetch`/`camelizarChaves` (`apps/frontend/src/lib/boundary.ts`, already used everywhere).
- Produces: `useConfirmarPresenca(token) -> {confirmar: (sessaoId: string) => Promise<{id: string}>, estado, erro}`; `useMeuVoto(token) -> {votar: (sessaoId: string, votacaoId: string, voto: "sim"|"nao"|"abstencao") => Promise<{id: string}>, estado, erro}`; `useMinhaSessaoAtual(token) -> {sessaoId: string|null, situacao: string|null, estado}`. All three are consumed by Task 9's page.

All tests in this task run against the **mocked `fetch`** pattern already established in `use-acusar-ciencia.test.ts` — read that file first and mirror its mocking style exactly (do not introduce a new test-doubling approach).

- [ ] **Step 1: Read `apps/frontend/src/lib/use-acusar-ciencia.ts` and `.test.ts` in full** (already read once during planning — re-confirm the exact mock/fetch-stub idiom before writing the new hooks' tests, since this is the pattern to mirror byte-for-byte in structure).

- [ ] **Step 2: `use-confirmar-presenca.ts`**

```typescript
"use client";

// Hook de mutação — POST /api/sessoes/:id/presenca/confirmar (Onda C3, autoatendimento). Mirror de
// use-acusar-ciencia.ts (vivoRef + enviandoRef, mesmo contrato de erro). Sem corpo: `vereador-id` nunca vem
// do cliente (a borda resolve do ator, anti-forja por construção).

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";

export type ConfirmarPresencaOut = { id: string };

type Estado = "ocioso" | "enviando" | "erro";

export function useConfirmarPresenca(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function confirmar(sessaoId: string): Promise<ConfirmarPresencaOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await fetch(`/api/sessoes/${sessaoId}/presenca/confirmar`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao confirmar presença (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as ConfirmarPresencaOut;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current && !tratado) {
        setEstado("erro");
        setErro("falha de rede — tente novamente");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }

  return { confirmar, estado, erro };
}
```

- [ ] **Step 3: `use-confirmar-presenca.test.ts`** — mirror `use-acusar-ciencia.test.ts` structure exactly (renderHook, mocked global `fetch`), covering: success (calls the right URL/method, no body, returns `{id}`), non-ok response surfaces server `erro` message, network throw sets `estado "erro"`, concurrent call while `enviando` throws synchronously.

- [ ] **Step 4: Run**

Run: `cd apps/frontend && docker compose exec frontend npx vitest run src/lib/use-confirmar-presenca.test.ts` (adjust the exact docker-compose service/command to match this repo's established container invocation — check how the most recent FE slice ran its tests, e.g. `oplenario-nfr-pendencias`/CLAUDE.md memory "Docker mandatório").
Expected: PASS.

- [ ] **Step 5: `use-meu-voto.ts`**

```typescript
"use client";

// Hook de mutação — POST /api/sessoes/:id/votacoes/:votacaoId/meu-voto (Onda C3). Mirror de
// use-acusar-ciencia.ts. `vereador-id` nunca vem do cliente; o corpo só carrega `voto`.

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";

export type VotoNominalIn = "sim" | "nao" | "abstencao";
export type MeuVotoOut = { id: string };

type Estado = "ocioso" | "enviando" | "erro";

export function useMeuVoto(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function votar(sessaoId: string, votacaoId: string, voto: VotoNominalIn): Promise<MeuVotoOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await fetch(`/api/sessoes/${sessaoId}/votacoes/${votacaoId}/meu-voto`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ voto }),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        // não repassa detalhe de POR QUE não pode votar (mesma disciplina do backend — 403 fail-closed
        // genérico não vaza qual precondição falhou); qualquer erro do servidor vira a mesma mensagem curta.
        const msg = r.status === 403 ? "Não é possível votar agora." : (corpoErro?.erro ?? `falha ao votar (status ${r.status})`);
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as MeuVotoOut;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current && !tratado) {
        setEstado("erro");
        setErro("falha de rede — tente novamente");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }

  return { votar, estado, erro };
}
```

- [ ] **Step 6: `use-meu-voto.test.ts`** — mirror Step 3's test, plus a specific case asserting a 403 response surfaces the generic `"Não é possível votar agora."` message (not whatever the server happened to put in `{:erro ...}`).

- [ ] **Step 7: Run**

Run: `cd apps/frontend && docker compose exec frontend npx vitest run src/lib/use-meu-voto.test.ts`
Expected: PASS.

- [ ] **Step 8: `use-minha-sessao-atual.ts`**

```typescript
"use client";

// Hook de leitura — GET /api/meu/sessao-atual (Onda C3): qual sessão o cockpit do vereador deve abrir.
// Mirror simplificado de use-sli-sessoes.ts (mesmo racional de fetch+estado), mas escopo "vereador" (a
// rota do secretário /paineis/sli/sessoes não aceita este papel).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";

export type MinhaSessaoAtualOut = { sessaoId: string | null; situacao: string | null };

type Estado = "carregando" | "pronto" | "erro";

export function useMinhaSessaoAtual(token: string | null) {
  const [dados, setDados] = useState<MinhaSessaoAtualOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (!token) return;
    let vivo = true;
    (async () => {
      try {
        const r = await fetch("/api/meu/sessao-atual", {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const resultado = camelizarChaves(await r.json()) as MinhaSessaoAtualOut;
        setDados(resultado);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  if (!token) return { sessaoId: null, situacao: null, estado: "erro" as Estado };
  return { sessaoId: dados?.sessaoId ?? null, situacao: dados?.situacao ?? null, estado };
}
```

- [ ] **Step 9: `use-minha-sessao-atual.test.ts`** — mirror `use-sli-sessoes.test.ts`, covering: success with a live session, success with `{sessaoId: null}` (no live session — NOT an error state), non-ok → `estado "erro"`.

- [ ] **Step 10: Run all three + commit**

Run: `cd apps/frontend && docker compose exec frontend npx vitest run src/lib/use-confirmar-presenca.test.ts src/lib/use-meu-voto.test.ts src/lib/use-minha-sessao-atual.test.ts`
Expected: all PASS.

```bash
git add apps/frontend/src/lib/use-confirmar-presenca.ts apps/frontend/src/lib/use-confirmar-presenca.test.ts \
        apps/frontend/src/lib/use-meu-voto.ts apps/frontend/src/lib/use-meu-voto.test.ts \
        apps/frontend/src/lib/use-minha-sessao-atual.ts apps/frontend/src/lib/use-minha-sessao-atual.test.ts
git commit -m "feat(fe): hooks do cockpit — confirmar presença, meu-voto, descoberta de sessão (Onda C3)"
```

---

### Task 8: Frontend — view-model puro `meu-voto-vista.ts`

**Files:**
- Create: `apps/frontend/src/lib/meu-voto-vista.ts`
- Create: `apps/frontend/src/lib/meu-voto-vista.test.ts`

**Interfaces:**
- Consumes: `EstadoPlenario` (`apps/frontend/src/lib/plenario-reducer.ts`, existing, unchanged — specifically `.placar: PlacarVotacao | null` and `.presentes: string[]`).
- Produces: `derivarMeuVoto(estado: EstadoPlenario | null, meuVereadorId: string | null): VistaMeuVoto`, consumed by Task 9's page.

- [ ] **Step 1: Read `apps/frontend/src/lib/placar-vista.ts` and `placar-vista.test.ts` in full** (already read once during planning) to match the exact pure-function/discriminated-union style this codebase uses for view-models.

- [ ] **Step 2: Write `meu-voto-vista.ts`**

```typescript
// View-model PURO do cockpit de voto do vereador: deriva se o botão de votar deve estar habilitado, qual
// voto (se algum) já foi registrado para ESTE vereador na votação corrente, e se a própria presença já foi
// confirmada nesta sessão — tudo a partir do MESMO EstadoPlenario que alimenta o placar da Mesa (nenhum
// estado paralelo: o cockpit lê o placar OFICIAL). Testado em meu-voto-vista.test.ts.

import type { EstadoPlenario, VotoNominal } from "./plenario-reducer";

export type CicloVoto = "sem-votacao" | "pode-votar" | "ja-votou" | "secreta" | "encerrada" | "sem-presenca";

export interface VistaMeuVoto {
  ciclo: CicloVoto;
  meuVoto: VotoNominal | null; // preenchido só quando ciclo === "ja-votou"
  presente: boolean;
}

export function derivarMeuVoto(estado: EstadoPlenario | null, meuVereadorId: string | null): VistaMeuVoto {
  const presente = !!meuVereadorId && !!estado && estado.presentes.includes(meuVereadorId);

  if (!estado || !estado.placar) {
    return { ciclo: "sem-votacao", meuVoto: null, presente };
  }
  const { placar } = estado;

  if (placar.encerrada) {
    return { ciclo: "encerrada", meuVoto: null, presente };
  }
  if (placar.modalidade !== "nominal") {
    // secreta (ou modalidade ainda não provada, ex.: reconexão) — mesma disciplina fail-closed do placar-vista:
    // sem prova de nominal, o cockpit NUNCA oferece o botão de voto pelo celular (só a Mesa/terminal registra).
    return { ciclo: "secreta", meuVoto: null, presente };
  }
  const meuVoto = meuVereadorId ? (placar.votosNominais[meuVereadorId] ?? null) : null;
  if (meuVoto) {
    return { ciclo: "ja-votou", meuVoto, presente };
  }
  if (!presente) {
    return { ciclo: "sem-presenca", meuVoto: null, presente };
  }
  return { ciclo: "pode-votar", meuVoto: null, presente };
}
```

- [ ] **Step 3: Write `meu-voto-vista.test.ts`**

```typescript
import { describe, expect, it } from "vitest";
import { derivarMeuVoto } from "./meu-voto-vista";
import { estadoInicial, type EstadoPlenario } from "./plenario-reducer";

const BASE: EstadoPlenario = {
  ...estadoInicial({ id: "s1", estado: "aberta" } as never),
};

describe("derivarMeuVoto", () => {
  it("sem votação corrente → sem-votacao", () => {
    const v = derivarMeuVoto(BASE, "v1");
    expect(v.ciclo).toBe("sem-votacao");
  });

  it("votação nominal aberta, presente, sem voto ainda → pode-votar", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: ["v1"],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: {}, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("pode-votar");
  });

  it("votação nominal aberta, mas AUSENTE → sem-presenca (nunca oferece o botão)", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: [],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: {}, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("sem-presenca");
  });

  it("já votou (o placar já ecoou o próprio voto) → ja-votou, com o voto certo", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: ["v1"],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: { v1: "sim" }, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    const v = derivarMeuVoto(estado, "v1");
    expect(v.ciclo).toBe("ja-votou");
    expect(v.meuVoto).toBe("sim");
  });

  it("modalidade secreta (ou não provada) → nunca oferece o botão", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      placar: {
        votacaoId: "vot1", modalidade: "secreta", objetoTipo: "proposicao", encerrada: false,
        votosNominais: {}, votosSecretos: 3, resultado: null, totais: null, baseMembros: null,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("secreta");
  });

  it("votação encerrada → encerrada (mesmo se antes desse voto)", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: true,
        votosNominais: { v1: "sim" }, votosSecretos: 0, resultado: "aprovada",
        totais: { sim: 6, nao: 3, abstencao: 1 }, baseMembros: 11,
      },
    };
    expect(derivarMeuVoto(estado, "v1").ciclo).toBe("encerrada");
  });

  it("meuVereadorId null → nunca 'ja-votou' mesmo com votosNominais preenchido, presente sempre false", () => {
    const estado: EstadoPlenario = {
      ...BASE,
      presentes: ["v1"],
      placar: {
        votacaoId: "vot1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
        votosNominais: { v1: "sim" }, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
      },
    };
    const v = derivarMeuVoto(estado, null);
    expect(v.presente).toBe(false);
    expect(v.ciclo).not.toBe("ja-votou");
  });
});
```

(If `estadoInicial`'s signature requires a fuller `SessaoOut`-shaped argument than the `as never` cast above tolerates at the type level, adjust the fixture to whatever minimal literal actually satisfies the real type — check `contrato.ts`'s `SessaoOut` definition first.)

- [ ] **Step 4: Run**

Run: `cd apps/frontend && docker compose exec frontend npx vitest run src/lib/meu-voto-vista.test.ts`
Expected: PASS, all 7 cases.

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/meu-voto-vista.ts apps/frontend/src/lib/meu-voto-vista.test.ts
git commit -m "feat(fe): view-model puro do cockpit de voto (Onda C3)"
```

---

### Task 9: Frontend — a página do cockpit + ativa a aba "Votar"

**Files:**
- Create: `apps/frontend/src/app/(vereador)/votar/page.tsx`
- Create: `apps/frontend/src/app/(vereador)/votar/votar.css` (or fold into the existing `vereador-shell.css` if that file's convention is one shared stylesheet — check first)
- Modify: `apps/frontend/src/app/(vereador)/layout.tsx`

**Interfaces:**
- Consumes: `useAuth()` (existing), `useMinhaSessaoAtual`, `useMeuPainel` (existing, now also returns `vereadorId`), `usePlenario`, `derivarPlacar`, `useConfirmarPresenca`, `useMeuVoto`, `derivarMeuVoto` (all from Tasks 4-8 + existing).
- Produces: the "Votar" tab becomes a real, linked route.

- [ ] **Step 1: Read `apps/frontend/src/app/(vereador)/vereador/page.tsx` in full** (the existing C1 home page) to match its exact composition style (how it wires `useAuth`+data hooks+loading/error states+JSX), and skim `apps/frontend/src/app/(interno)/sessao-ao-vivo` (or wherever the Mesa's live-session screen lives — locate via `grep -rl usePlenario apps/frontend/src/app`) for how it already renders `derivarPlacar` output, since the cockpit reuses the same placar rendering conventions (just adds voting controls).

- [ ] **Step 2: Write the page**

Create `apps/frontend/src/app/(vereador)/votar/page.tsx`. Structure (fill in JSX/markup following the exact conventions read in Step 1 — this step specifies the DATA FLOW precisely; the implementer copies the surrounding markup idioms, not the following as literal-final code):

```typescript
"use client";

// Cockpit ao vivo do vereador (Onda C3, Marco MFE-3): confirma presença + vota do próprio celular sobre o
// MESMO SSE/placar oficial que a Mesa vê (usePlenario/placar-vista, nenhum estado paralelo).

import { useAuth } from "@/lib/auth";
import { useMinhaSessaoAtual } from "@/lib/use-minha-sessao-atual";
import { useMeuPainel } from "@/lib/use-meu-painel";
import { usePlenario } from "@/lib/use-plenario";
import { derivarPlacar } from "@/lib/placar-vista";
import { useConfirmarPresenca } from "@/lib/use-confirmar-presenca";
import { useMeuVoto } from "@/lib/use-meu-voto";
import { derivarMeuVoto } from "@/lib/meu-voto-vista";
import "./votar.css";

export default function VotarPage() {
  const { token } = useAuth();
  const { sessaoId, estado: estadoSessaoAtual } = useMinhaSessaoAtual(token);
  const { dados: painel } = useMeuPainel(token);
  const meuVereadorId = painel?.vereadorId ?? null;

  const { estado: estadoPlenario, conexao } = usePlenario(sessaoId ?? "", token);
  const { confirmar, estado: estadoConfirmar } = useConfirmarPresenca(token);
  const { votar, estado: estadoVotar, erro: erroVotar } = useMeuVoto(token);

  if (estadoSessaoAtual === "carregando") return <main className="votar-pagina"><p>Carregando…</p></main>;
  if (!sessaoId) return <main className="votar-pagina"><p>Nenhuma sessão em curso agora.</p></main>;

  const vista = derivarMeuVoto(estadoPlenario, meuVereadorId);
  const placar = derivarPlacar(estadoPlenario?.placar ?? null);

  async function aoConfirmarPresenca() {
    if (!sessaoId) return;
    await confirmar(sessaoId);
  }

  async function aoVotar(voto: "sim" | "nao" | "abstencao") {
    if (!sessaoId || !estadoPlenario?.placar) return;
    await votar(sessaoId, estadoPlenario.placar.votacaoId, voto);
  }

  return (
    <main className="votar-pagina">
      {/* placar oficial — reusa a MESMA derivação/estrutura visual que o painel da Mesa já usa
          (mirror da renderização encontrada no Passo 1, não uma 2ª implementação de placar) */}

      {!vista.presente && (
        <button onClick={aoConfirmarPresenca} disabled={estadoConfirmar === "enviando"}>
          Confirmar presença
        </button>
      )}

      {vista.ciclo === "pode-votar" && (
        <div className="votar-botoes">
          <button onClick={() => aoVotar("sim")} disabled={estadoVotar === "enviando"}>Sim</button>
          <button onClick={() => aoVotar("nao")} disabled={estadoVotar === "enviando"}>Não</button>
          <button onClick={() => aoVotar("abstencao")} disabled={estadoVotar === "enviando"}>Abstenção</button>
        </div>
      )}
      {vista.ciclo === "ja-votou" && <p>Você votou: {vista.meuVoto}.</p>}
      {vista.ciclo === "secreta" && <p>Voto secreto: só pelo terminal da Mesa.</p>}
      {vista.ciclo === "sem-presenca" && <p>Confirme sua presença para poder votar.</p>}
      {vista.ciclo === "encerrada" && <p>Votação encerrada.</p>}
      {erroVotar && <p role="alert">{erroVotar}</p>}
      {conexao === "reconectando" && <p>Reconectando…</p>}
    </main>
  );
}
```

Follow the ACTUAL placar-rendering JSX/CSS classes found in Step 1's read — do not invent new markup for the shared placar display; only the voting controls/presence button below it are genuinely new.

- [ ] **Step 3: Activate the "Votar" tab**

In `apps/frontend/src/app/(vereador)/layout.tsx`, change the `TABS` array (lines 103-109):

```typescript
const TABS = [
  { rotulo: "Início", href: "/vereador", ativo: true },
  { rotulo: "Pauta", href: null, ativo: false },
  { rotulo: "Votar", href: "/votar", ativo: true },
  { rotulo: "Matérias", href: null, ativo: false },
  { rotulo: "Perfil", href: null, ativo: false },
] as const;
```

Update the comment above `TABS` (lines 97-102) to drop "Votar" from the list of not-yet-built tabs, since this task builds it.

- [ ] **Step 4: CSS** — read `vereador-shell.css` first; either add `.votar-pagina`/`.votar-botoes` rules there (if that's the established single-shared-stylesheet convention for this app-shell) or create `votar.css` alongside the page (mirror whichever convention `(vereador)/vereador/page.tsx` actually uses — check if it imports its own CSS file or relies purely on the shell's). Ensure both themes (`GUIDELINES-CHECKLIST.md` — light/dark contrast) are covered, following the `vereador-app.html` design-system reference at `produto/design-system/o-plenario/telas/vereador-app.html`.

- [ ] **Step 5: `tsc`/`eslint`/`next build`**

Run inside the container: `docker compose exec frontend npx tsc --noEmit && docker compose exec frontend npx eslint src/app/\(vereador\)/votar src/lib/use-confirmar-presenca.ts src/lib/use-meu-voto.ts src/lib/use-minha-sessao-atual.ts src/lib/meu-voto-vista.ts && docker compose exec frontend npx next build`
Expected: all clean, build succeeds.

- [ ] **Step 6: Live verification (paridade visual, both themes, desktop+mobile viewport)**

Start the stack (`docker compose up -d --build` per `apps/backend`'s established local-run convention), seed a demo secretário + vereador with a sessão in progress (reuse/extend `apps/backend/demo/seed_demo.clj` if it doesn't already cover "sessão aberta com votação nominal aberta" — check first), then drive the actual page with `mcp__claude-in-chrome` (or Playwright) at both a mobile and desktop viewport, both themes: confirm presence, open a nominal vote from a second tab/session as the Mesa, cast a vote as the vereador, and confirm the placar updates live and matches the design reference (`vereador-app.html`) side-by-side per `GUIDELINES-CHECKLIST.md`.

- [ ] **Step 7: Commit**

```bash
git add "apps/frontend/src/app/(vereador)/votar" "apps/frontend/src/app/(vereador)/layout.tsx" \
        apps/backend/demo/seed_demo.clj  # only if Step 6 extended the seed
git commit -m "feat(fe): cockpit ao vivo do vereador — confirma presença + vota (Onda C3, fecha Marco MFE-3)"
```

---

### Task 10: Revisão final de branch inteira + merge

- [ ] Dispatch `ecc` **react-reviewer** + **security-reviewer** over the full frontend diff (Tasks 7-9 together).
- [ ] Dispatch `ecc` **clojure-reviewer** + **security-reviewer** + **database-reviewer** over the full backend diff (Tasks 1-6 together) — this is the review that should specifically re-examine the `hoje()`/`agora()` split and the kebab/underscore DSL-map translation (Global Constraints), since those are the two subtlest correctness risks in this slice.
- [ ] Apply any CRÍTICO/MAJOR findings, re-run the full backend (`clojure -M:test`) and frontend (`vitest run`, `tsc`, `eslint`, `next build`) suites green.
- [ ] Update `docs/13-plano-track-fe.md` §11.3 (C3 row) to `✅ MERGED→main` with the branch/commit reference, mirroring exactly how C1/C2 rows were updated.
- [ ] Update memory `oplenario-fe-execucao` / `oplenario-proxima-sessao` per this project's standing session-close protocol.
- [ ] Merge `fe-17-cockpit-votacao` → `main` (fast-forward or `--no-ff`, matching this project's established convention for prior Onda C slices — check the C2 merge commit for which style was used) — only after the user approves, per this project's standing "confirmar antes de prosseguir" protocol.
