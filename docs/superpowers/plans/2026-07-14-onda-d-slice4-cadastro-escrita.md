# Cadastro de Vereadores — escrita (Tier 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the `secretario` the ability to build the Casa's cadastral composition from the UI — create/edit a vereador, register a mandato, and record a licença — filling the 3 `EmBreve` stubs the read-only Slice 3 left behind.

**Architecture:** Extends the ADR-0001 silhueta proven in Slice 3 with the write side. New `wire/in` (Malli `:closed` request bodies) + `adapters/in` (validate/coerce → domain) feed thin `controllers` pass-throughs, which call new `RepoCadastros` write methods (each in one tenant tx: `atualizar-vereador!`, `registrar-mandato!`, `registrar-licenca!`). A new migration adds an `EXCLUDE` anti-overlap constraint on `cadastros.mandato`. The frontend replaces the 3 disabled CTAs with real forms (pure validation view-models + mutation hooks + a small legislatura-vigente read), refetching the list/ficha after each success.

**Tech Stack:** Clojure (HoneySQL, next.jdbc, Malli, Pedestal, Stuart Sierra Component, Migratus); Next.js 16 / React 19 / TS; vitest.

## Global Constraints

- **Tier 1 scope only.** IN: create vereador (`identidade_id` NULL), edit vereador, register mandato, record licença (record-only: insert `mandato_licenca` + flip the vigente mandato to `estado='licenciado'` in one tx). OUT (deferred, own security review): identity-link/login provisioning (CPF via `oplenario_id_resolver`), automatic suplente promotion, comissão/suplência/legislatura writes.
- **Silhueta ADR-0001:** `wire/in`·`wire/out`, `adapters/in`·`adapters/out`, `diplomat/http/in` route table, Repo-Component; no `port/`, no ORM. `cadastros` NEVER imports another module (§22.10) — use core `parse-uuid` for path params (do NOT import legislativo's `id-param->uuid`).
- **Tenant by RLS.** Every INSERT/UPDATE runs inside `RepoCadastros/transacao` (= `tenancy/com-tenant*`); every row carries `ente_id` from the ator; UPDATE/JOIN predicates also match `ente_id` (defence in depth, mirror `relacoes/cadastro`).
- **Inv.10 — no DELETE.** The cadastral grant is `SELECT, INSERT, UPDATE` (no DELETE). "Editing" = UPDATE.
- **`efetivado_em = now()` on every interactive write.** Interactive writes bypass the `lote_id`/`ver_lote` staging path (that path is import-only). The RLS `USING` clause hides rows with `efetivado_em IS NULL` (no `ver_lote` GUC set), so every UI INSERT sets `efetivado_em = [:now]` to be immediately visible — the existing `db/vereador.clj` insert fns already bake this in (`:efetivado_em [:now]`).
- **Gate:** papel `secretario` on every write route (`it/exige-papel "secretario"`, same as the reads).
- **Status contract:** `201 {:id ...}` on create (mandato/licença/vereador), `200 {:id ...}` on edit; `400` invalid body (auto via the global `erro` interceptor when `adapters/in` throws `:validacao/invalido`); `403` missing `secretario` (auto via `exige-papel`); `401` no token; `404` malformed/unknown `:id` OR unknown vereador/legislatura; `409` overlapping vigente mandato / licença with no vigente mandato (caught LOCALLY in the handler — `:conflito/*` is NOT globally mapped).
- **404 uniforme:** an id that doesn't parse OR belongs to another tenant → 404 (never 500, never leaks existence). Guard `parse-uuid` at the border like Slice 3.
- **`adapters/out` validate against the wire schema** (`m/validate` → throw on drift) — drift = 500, never a malformed body that poisons codegen.
- **Portuguese** in code comments/copy; kebab-case on the wire (jsonista). FE hook states use `"ocioso" | "enviando" | "erro"` (codebase convention; the spec's `"pronto"` wording is cosmetic — match `use-registrar-resposta.ts`).
- **AA in both themes**, measured pixel-composite per `produto/design-system/o-plenario/GUIDELINES-CHECKLIST.md` (§5.1: licença chip amber → `--aviso-texto`; white-on-telha → `--telha-fundo`). Use the `independent-accessibility-verification` skill — never write a ratio from memory.
- **NEVER edit an applied migration** (Migratus records the id as applied and won't re-run it) — always a NEW migration file.
- **Backend tests run in an ephemeral Clojure container** on the compose network (docker mandate — never `clojure` on the host). Canonical invocation (from repo root, first run downloads `.m2` ~2min → run in background with a high timeout; the network is `oplenario_default`, confirm with `docker network ls`):
  ```bash
  docker run --rm --network oplenario_default \
    -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 \
    -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' \
    -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps \
    clojure -M:test --focus <ns> --reporter documentation
  ```
  Fixtures call `migracao/migrar!` which needs the OWNER role `oplenario` (the `DATABASE_URL` above), NOT `oplenario_pool`. Ensure the stack is up first: `cd apps/backend && docker compose up -d`.
- **Frontend tests/lint run in the frontend container** (docker mandate): `docker compose exec frontend npm test` (or `docker compose exec frontend npx vitest run <file>`), `docker compose exec frontend npm run lint`, `docker compose exec frontend npx tsc --noEmit`.

---

## File map

**Backend — all under `apps/backend/`:**
- Create `resources/migrations/20260620000059-cadastros-mandato-exclude-vigente.up.sql` (+ `.down.sql`) — `EXCLUDE USING gist` anti-overlap of vigente mandatos.
- Modify `src/oplenario/cadastros/db/vereador.clj` — add `atualizar!`, `mandato-vigente-de-vereador`, `mandato-sobreposto?`. (Reuse existing `inserir!`, `inserir-mandato!`, `mudar-estado!`, `inserir-licenca!`. **Mandato writes stay in `db/vereador.clj`** — they already live there; do NOT create a new `db/mandato.clj` (avoids a needless restructure; the spec's suggested split is superseded by the existing layout).)
- Create `src/oplenario/cadastros/wire/in/vereador.clj` — `CriarVereador`, `EditarVereador`, `RegistrarMandato`, `RegistrarLicenca`.
- Create `src/oplenario/cadastros/adapters/in/vereador.clj` — 4 `wire → dominio` coercers (validate + inject id/ente-id + parse dates/uuids).
- Create `src/oplenario/cadastros/wire/out/legislatura.clj` — `LegislaturaVigenteOut` (for the mandato form's legislatura selector).
- Create `src/oplenario/cadastros/adapters/out/legislatura.clj` — validated `dominio → wire`.
- Modify `src/oplenario/cadastros/components/repositorio.clj` — protocol + impl: `atualizar-vereador!`, `registrar-mandato!`, `registrar-licenca!`.
- Modify `src/oplenario/cadastros/controllers.clj` — pass-throughs: `criar-vereador`, `editar-vereador`, `registrar-mandato`, `registrar-licenca`, `legislatura-vigente`.
- Modify `src/oplenario/cadastros/diplomat/http/in.clj` — 4 write routes + 1 `legislatura-vigente` read route + handlers.
- Modify `src/oplenario/codegen/gerar_cadastros.clj` — add `LegislaturaVigenteOut` to the manifesto; regenerate the FE contract.
- Modify test files: `test/integration/oplenario/cadastros/db_test.clj`, `test/unit/oplenario/cadastros/controllers_test.clj`, `test/integration/oplenario/cadastros/vereador_http_in_test.clj`.
- Create tests: `test/unit/oplenario/cadastros/vereador_adapters_in_test.clj`, `test/integration/oplenario/cadastros/repositorio_escrita_test.clj`.

**Frontend — all under `apps/frontend/src/`:**
- Create `lib/cadastro-vereadores-forms.ts` (+ `.test.ts`) — pure form validation view-models.
- Create `lib/use-criar-vereador.ts`, `lib/use-editar-vereador.ts`, `lib/use-registrar-mandato.ts`, `lib/use-registrar-licenca.ts`, `lib/use-legislatura-vigente.ts` (+ tests).
- Modify `lib/use-vereadores.ts`, `lib/use-vereador-ficha.ts` — accept a `versao` refetch token.
- Modify `lib/contrato-cadastros.gen.ts` — regenerated (adds `LegislaturaVigenteOut`).
- Create `app/(interno)/cadastros/vereadores/novo-vereador-form.tsx`, `editar-vereador-form.tsx`, `registrar-mandato-form.tsx`, `registrar-licenca-form.tsx`.
- Modify `app/(interno)/cadastros/vereadores/page.tsx` + `cadastro-vereadores.css` — mount the forms, wire refetch, remove the `EmBreve` stubs.

---

## Task 1: Migration — EXCLUDE anti-overlap of vigente mandatos

**Files:**
- Create: `apps/backend/resources/migrations/20260620000059-cadastros-mandato-exclude-vigente.up.sql`
- Create: `apps/backend/resources/migrations/20260620000059-cadastros-mandato-exclude-vigente.down.sql`
- Test: `apps/backend/test/integration/oplenario/cadastros/db_test.clj` (new deftest)

**Interfaces:**
- Consumes: existing `cadastros.mandato` table (migration 0010), `btree_gist` extension (already installed by 0010), `oplenario.cadastros.db.vereador/inserir-mandato!`.
- Produces: constraint `uq_mandato_vigente_sem_overlap` on `cadastros.mandato` — no more than one `estado='vigente'` efetivado mandato per `(ente_id, vereador_id)` over overlapping `daterange`.

- [ ] **Step 1: Write the failing test** in `db_test.clj` (append at end, before the final `))` of the file — a top-level `deftest`)

```clojure
(deftest exclude-rejeita-mandato-vigente-sobreposto
  ;; Slice 4: a escrita interativa pode criar sobreposicao (a leitura da Slice 3 nao podia). O EXCLUDE e' a
  ;; REDE — dois mandatos 'vigente' efetivados do MESMO vereador com vigencias que se tocam sao rejeitados.
  (let [ente (random-uuid) leg (random-uuid) ver (random-uuid)
        ini (LocalDate/parse "2025-01-01")]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara EXCLUDE"})
        (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 20 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (vereador/inserir! tx {:id ver :ente-id ente :nome "Ana"})
        (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg
                                       :estado "vigente" :vigencia-inicio ini})))
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente
            (fn [tx]
              (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg
                                             :estado "vigente" :vigencia-inicio ini}))))
        "2o mandato 'vigente' sobreposto do mesmo vereador -> EXCLUDE rejeita")
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; um mandato 'licenciado' sobreposto NAO viola (o WHERE do EXCLUDE so' cobre estado='vigente').
        (is (some? (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg
                                                  :estado "licenciado" :vigencia-inicio ini}))
            "mandato 'licenciado' sobreposto e' permitido (fora do predicado do EXCLUDE)")))))
```

- [ ] **Step 2: Run the test — expect FAIL** (constraint doesn't exist yet, so the 2nd insert succeeds and `thrown?` fails)

Run: `docker run ... -w /app/apps/backend ... clojure -M:test --focus oplenario.cadastros.db-test` (full invocation in Global Constraints)
Expected: FAIL on `exclude-rejeita-mandato-vigente-sobreposto`.

- [ ] **Step 3: Write `...59-cadastros-mandato-exclude-vigente.up.sql`**

```sql
-- Onda D Slice 4: EXCLUDE anti-overlap de mandato VIGENTE efetivado por (ente_id, vereador_id). A leitura da
-- Slice 3 nao podia criar sobreposicao; a escrita desta fatia pode -> fecha o carry (c) da Slice 3. Espelha
-- uq_uma_mesa_ativa (migration 0010): btree_gist ja' instalado; o staging (efetivado_em NULL) fica de fora
-- ate efetivar. O guard app-level do repo devolve 409 amigavel; ESTA constraint e' a rede (last line).
ALTER TABLE cadastros.mandato ADD CONSTRAINT uq_mandato_vigente_sem_overlap
  EXCLUDE USING gist (
    ente_id WITH =,
    vereador_id WITH =,
    daterange(vigencia_inicio, COALESCE(vigencia_fim, 'infinity'::date), '[]') WITH &&
  ) WHERE (estado = 'vigente' AND efetivado_em IS NOT NULL);
```

- [ ] **Step 4: Write `...59-cadastros-mandato-exclude-vigente.down.sql`**

```sql
ALTER TABLE cadastros.mandato DROP CONSTRAINT IF EXISTS uq_mandato_vigente_sem_overlap;
```

- [ ] **Step 5: Run the test — expect PASS**

Run: `... clojure -M:test --focus oplenario.cadastros.db-test`
Expected: PASS. (Migration applies against the clean test DB before fixtures run.)

- [ ] **Step 6: Verify the demo seed does not violate the new constraint**

The seed (`apps/backend/demo/seed_demo.clj`, `vereadores`) inserts exactly ONE vigente mandato per vereador (3 vigente for 3 distinct vereadores, 1 licenciado, 1 without) — no single vereador gets two overlapping vigente mandatos, so a fresh seed is fine. **Note:** the `vereadores` seed is intentionally non-idempotent (protocols new rows each run); running it TWICE on the same Casa now fails on the 2nd call (a 2nd overlapping vigente mandato) — this is the constraint working as intended. Its docstring already says "Rodar uma vez por demo fresca." No code change; just confirm by reading `seed_demo.clj:296-310`.

- [ ] **Step 7: Commit**

```bash
git add apps/backend/resources/migrations/20260620000059-cadastros-mandato-exclude-vigente.up.sql \
        apps/backend/resources/migrations/20260620000059-cadastros-mandato-exclude-vigente.down.sql \
        apps/backend/test/integration/oplenario/cadastros/db_test.clj
git commit -m "feat(cadastros): EXCLUDE anti-overlap de mandato vigente (Slice 4 carry c)"
```

---

## Task 2: wire/in + adapters/in for the 4 write bodies

**Files:**
- Create: `apps/backend/src/oplenario/cadastros/wire/in/vereador.clj`
- Create: `apps/backend/src/oplenario/cadastros/adapters/in/vereador.clj`
- Test: `apps/backend/test/unit/oplenario/cadastros/vereador_adapters_in_test.clj`

**Interfaces:**
- Consumes: `ator` (`{:ente-id :uuid :identidade-id :uuid ...}`), the `:json-params` string-keyed map, path `:id` already parsed to UUID by the handler.
- Produces:
  - `criar-vereador->dominio [ator wire-in] → {:id uuid :ente-id uuid :nome string :nome-parlamentar (maybe string)}`
  - `editar-vereador->dominio [wire-in] → {(:nome)? string (:nome-parlamentar)? (maybe string)}` (≥1 key, `:nome` non-blank if present)
  - `registrar-mandato->dominio [ator vereador-id wire-in] → {:id :ente-id :vereador-id :legislatura-id (uuid) :partido :estado "vigente" :natureza :vigencia-inicio (LocalDate) :vigencia-fim (maybe LocalDate)}`
  - `registrar-licenca->dominio [ator wire-in] → {:id :ente-id :inicio (LocalDate) :fim (maybe LocalDate) :motivo (maybe string)}`
  - All throw `ex-info` with `{:tipo :validacao/invalido}` on bad input (→ 400 via the global `erro` interceptor).

- [ ] **Step 1: Write the failing test** `test/unit/oplenario/cadastros/vereador_adapters_in_test.clj`

```clojure
(ns oplenario.cadastros.vereador-adapters-in-test
  "UNITARIO (DB-free) — Slice 4: o gate de ENTRADA wire/in -> dominio das 4 escritas. Prova validacao
  (fail-closed -> :validacao/invalido), injecao de id/ente-id (nunca do cliente) e coercao de datas/uuids."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.cadastros.adapters.in.vereador :as a])
  (:import (java.time LocalDate)))

(def ^:private ATOR {:ente-id (random-uuid) :identidade-id (random-uuid)})

(defn- validacao-invalida? [f]
  (try (f) false
    (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

;; ---- criar ----
(deftest criar-injeta-id-e-ente-e-mantem-nome
  (let [m (a/criar-vereador->dominio ATOR {"nome" "Helena Matos" "nome-parlamentar" "Helena"})]
    (is (uuid? (:id m)) "id gerado no servidor, nunca do cliente")
    (is (= (:ente-id ATOR) (:ente-id m)) "ente-id vem do ator")
    (is (= "Helena Matos" (:nome m)))
    (is (= "Helena" (:nome-parlamentar m)))))

(deftest criar-sem-nome-e-invalido
  (is (validacao-invalida? #(a/criar-vereador->dominio ATOR {"nome-parlamentar" "so apelido"})))
  (is (validacao-invalida? #(a/criar-vereador->dominio ATOR {"nome" "   "})) "nome em branco -> invalido"))

(deftest criar-recusa-campo-extra
  (is (validacao-invalida? #(a/criar-vereador->dominio ATOR {"nome" "X" "identidade-id" "forja"}))
      ":closed recusa campo fora do contrato (anti-forja de identidade-id)"))

;; ---- editar ----
(deftest editar-exige-ao-menos-um-campo
  (is (validacao-invalida? #(a/editar-vereador->dominio {})) "corpo vazio -> invalido")
  (is (= {:nome "Novo Nome"} (a/editar-vereador->dominio {"nome" "Novo Nome"})))
  (is (= {:nome-parlamentar "Apelido"} (a/editar-vereador->dominio {"nome-parlamentar" "Apelido"})))
  (is (validacao-invalida? #(a/editar-vereador->dominio {"nome" "  "})) "nome presente e em branco -> invalido"))

;; ---- mandato ----
(deftest mandato-coage-datas-e-uuid-e-injeta-estado-vigente
  (let [ver (random-uuid) leg (random-uuid)
        m (a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str leg) "partido" "PT"
                                                  "natureza" "titular" "vigencia-inicio" "2025-01-01"})]
    (is (= ver (:vereador-id m)) "vereador-id vem do path, nao do corpo")
    (is (= leg (:legislatura-id m)) "legislatura-id coagido de string p/ uuid")
    (is (= "vigente" (:estado m)) "estado nasce 'vigente' (nao e' entrada)")
    (is (= (LocalDate/of 2025 1 1) (:vigencia-inicio m)))
    (is (nil? (:vigencia-fim m)))))

(deftest mandato-data-ou-uuid-invalido-e-invalido
  (let [ver (random-uuid)]
    (is (validacao-invalida? #(a/registrar-mandato->dominio ATOR ver {"legislatura-id" "nao-uuid" "natureza" "titular" "vigencia-inicio" "2025-01-01"})))
    (is (validacao-invalida? #(a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str (random-uuid)) "natureza" "titular" "vigencia-inicio" "01/01/2025"})))
    (is (validacao-invalida? #(a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str (random-uuid)) "natureza" "prefeito" "vigencia-inicio" "2025-01-01"}))
        "natureza fora do enum -> invalido")))

;; ---- licenca ----
(deftest licenca-coage-inicio-e-injeta-id-ente
  (let [m (a/registrar-licenca->dominio ATOR {"inicio" "2026-03-01" "motivo" "saude"})]
    (is (uuid? (:id m)))
    (is (= (:ente-id ATOR) (:ente-id m)))
    (is (= (LocalDate/of 2026 3 1) (:inicio m)))
    (is (nil? (:fim m)))
    (is (= "saude" (:motivo m)))))

(deftest licenca-sem-inicio-e-invalido
  (is (validacao-invalida? #(a/registrar-licenca->dominio ATOR {"motivo" "sem data"}))))
```

- [ ] **Step 2: Run — expect FAIL** (namespaces don't exist)

Run: `... clojure -M:test --focus oplenario.cadastros.vereador-adapters-in-test`
Expected: FAIL (could not locate `oplenario.cadastros.adapters.in.vereador`).

- [ ] **Step 3: Write `wire/in/vereador.clj`**

```clojure
(ns oplenario.cadastros.wire.in.vereador
  "Representacao EXTERNA de ENTRADA das 4 escritas do cadastro de vereadores (§22.10 wire/in, ADR-0001,
  Onda D Slice 4). `:closed true` recusa campo extra (anti-forja: identidade_id/ente_id NUNCA vem do corpo).
  Datas e legislatura-id sao :string no wire (ISO/UUID) — o adapters/in coage p/ LocalDate/UUID.")

(def CriarVereador
  [:map {:closed true}
   [:nome [:string {:min 1}]]
   [:nome-parlamentar {:optional true} [:maybe :string]]])

(def EditarVereador
  "PATCH parcial: ambos opcionais; o adapters/in exige >=1 presente e :nome nao-branco se presente."
  [:map {:closed true}
   [:nome {:optional true} [:maybe :string]]
   [:nome-parlamentar {:optional true} [:maybe :string]]])

(def RegistrarMandato
  [:map {:closed true}
   [:legislatura-id [:string {:min 1}]]
   [:partido {:optional true} [:maybe :string]]
   [:natureza [:enum "titular" "suplencia"]]
   [:vigencia-inicio [:string {:min 1}]]
   [:vigencia-fim {:optional true} [:maybe :string]]])

(def RegistrarLicenca
  [:map {:closed true}
   [:inicio [:string {:min 1}]]
   [:fim {:optional true} [:maybe :string]]
   [:motivo {:optional true} [:maybe :string]]])
```

- [ ] **Step 4: Write `adapters/in/vereador.clj`**

```clojure
(ns oplenario.cadastros.adapters.in.vereador
  "Gate de ENTRADA wire/in -> dominio das 4 escritas (§22.10 adapters/in, ADR-0001, Onda D Slice 4). Chamado
  SO' pelo diplomat/. Valida (fail-closed -> :validacao/invalido -> 400) e COAGE (datas ISO -> LocalDate,
  legislatura-id -> UUID); INJETA o que nao vem do corpo (`id` gerado, `ente-id`/`vereador-id` do ator/path,
  §22.5). `cadastros` nao importa outro modulo (§22.10) — usa `parse-uuid` do core, nao o helper de legislativo."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.cadastros.wire.in.vereador :as wire])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so' os nomes-de-campo (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn- ->data!
  "String ISO (AAAA-MM-DD) -> LocalDate. nil-safe (nil -> nil, p/ campos opcionais). Parse invalido -> 400."
  [s campo]
  (when (some? s)
    (try (LocalDate/parse s)
      (catch DateTimeParseException _ (invalido! "data invalida (esperado AAAA-MM-DD)" {:campos [campo]})))))

(defn- ->uuid! [s campo]
  (or (parse-uuid s) (invalido! "identificador invalido" {:campos [campo]})))

(def ^:private campos-criar   ["nome" "nome-parlamentar"])
(def ^:private campos-editar  ["nome" "nome-parlamentar"])
(def ^:private campos-mandato ["legislatura-id" "partido" "natureza" "vigencia-inicio" "vigencia-fim"])
(def ^:private campos-licenca ["inicio" "fim" "motivo"])

(defn criar-vereador->dominio [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (so-esperados wire-in campos-criar)]
    (validar! wire/CriarVereador mm "corpo de criar vereador invalido")
    (when (str/blank? (:nome mm)) (invalido! "nome obrigatorio (nao-branco)" {:campos [:nome]}))
    {:id (random-uuid) :ente-id (:ente-id ator) :nome (:nome mm) :nome-parlamentar (:nome-parlamentar mm)}))

(defn editar-vereador->dominio [wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (so-esperados wire-in campos-editar)]
    (validar! wire/EditarVereador mm "corpo de editar vereador invalido")
    (when-not (or (contains? mm :nome) (contains? mm :nome-parlamentar))
      (invalido! "informe ao menos um campo (nome ou nome-parlamentar)" {:campos [:nome :nome-parlamentar]}))
    (when (and (contains? mm :nome) (str/blank? (:nome mm)))
      (invalido! "nome nao pode ser vazio" {:campos [:nome]}))
    (select-keys mm [:nome :nome-parlamentar])))

(defn registrar-mandato->dominio [ator vereador-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (so-esperados wire-in campos-mandato)]
    (validar! wire/RegistrarMandato mm "corpo de registrar mandato invalido")
    {:id (random-uuid) :ente-id (:ente-id ator) :vereador-id vereador-id
     :legislatura-id (->uuid! (:legislatura-id mm) :legislatura-id)
     :partido (:partido mm) :estado "vigente" :natureza (:natureza mm)
     :vigencia-inicio (->data! (:vigencia-inicio mm) :vigencia-inicio)
     :vigencia-fim (->data! (:vigencia-fim mm) :vigencia-fim)}))

(defn registrar-licenca->dominio [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (so-esperados wire-in campos-licenca)]
    (validar! wire/RegistrarLicenca mm "corpo de registrar licenca invalido")
    {:id (random-uuid) :ente-id (:ente-id ator)
     :inicio (->data! (:inicio mm) :inicio) :fim (->data! (:fim mm) :fim) :motivo (:motivo mm)}))
```

- [ ] **Step 5: Run — expect PASS**

Run: `... clojure -M:test --focus oplenario.cadastros.vereador-adapters-in-test`
Expected: PASS (all deftests).

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/cadastros/wire/in/vereador.clj \
        apps/backend/src/oplenario/cadastros/adapters/in/vereador.clj \
        apps/backend/test/unit/oplenario/cadastros/vereador_adapters_in_test.clj
git commit -m "feat(cadastros): wire/in + adapters/in das 4 escritas de vereador"
```

---

## Task 3: db writes — atualizar!, mandato-vigente-de-vereador, mandato-sobreposto?

**Files:**
- Modify: `apps/backend/src/oplenario/cadastros/db/vereador.clj`
- Test: `apps/backend/test/integration/oplenario/cadastros/db_test.clj` (new deftests)

**Interfaces:**
- Consumes: the tenant `tx`, existing `inserir!`/`inserir-mandato!`/`inserir-licenca!`/`mudar-estado!`.
- Produces:
  - `atualizar! [tx ente-id id campos] → update-count (long)` — UPDATEs only present keys of `campos` (`:nome`, `:nome-parlamentar`) on the efetivada row.
  - `mandato-vigente-de-vereador [tx ente-id vereador-id data] → mandato-map | nil` — the `estado='vigente'` mandato covering `data`.
  - `mandato-sobreposto? [tx ente-id vereador-id inicio fim] → boolean` — a vigente efetivado mandato whose range overlaps `[inicio, fim]` (nil `fim` = open).

- [ ] **Step 1: Write the failing tests** — append to `db_test.clj`

```clojure
(deftest atualizar-troca-nome-e-nome-parlamentar
  (let [ente (random-uuid) ver (random-uuid)]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "X"})
        (vereador/inserir! tx {:id ver :ente-id ente :nome "Antigo" :nome-parlamentar "Velho"})
        (is (= 1 (vereador/atualizar! tx ente ver {:nome "Novo" :nome-parlamentar "Zé Novo"})) "1 linha atualizada")
        (let [v (vereador/buscar tx ente ver)]
          (is (= "Novo" (:nome v)))
          (is (= "Zé Novo" (:nome-parlamentar v))))
        ;; UPDATE parcial: so' :nome-parlamentar (mantem :nome)
        (vereador/atualizar! tx ente ver {:nome-parlamentar "Só Apelido"})
        (let [v (vereador/buscar tx ente ver)]
          (is (= "Novo" (:nome v)) ":nome preservado num PATCH que so' mandou :nome-parlamentar")
          (is (= "Só Apelido" (:nome-parlamentar v))))
        (is (= 0 (vereador/atualizar! tx ente (random-uuid) {:nome "X"})) "id inexistente -> 0 linhas")))))

(deftest mandato-vigente-de-vereador-so-pega-estado-vigente
  (let [ente (random-uuid) leg (random-uuid) ver (random-uuid)
        ini (LocalDate/parse "2025-01-01") hoje (LocalDate/parse "2026-07-14")]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "X"})
        (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 20 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (vereador/inserir! tx {:id ver :ente-id ente :nome "Ana"})
        (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg
                                       :estado "vigente" :vigencia-inicio ini})
        (let [mv (vereador/mandato-vigente-de-vereador tx ente ver hoje)]
          (is (= "vigente" (:estado mv)))
          (is (some? (:id mv))))
        ;; um vereador SEM mandato vigente -> nil
        (is (nil? (vereador/mandato-vigente-de-vereador tx ente (random-uuid) hoje)))))))

(deftest mandato-sobreposto-detecta-overlap-de-vigente
  (let [ente (random-uuid) leg (random-uuid) ver (random-uuid)
        ini (LocalDate/parse "2025-01-01")]
    (seed-municipio!)
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "X"})
        (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 20 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (vereador/inserir! tx {:id ver :ente-id ente :nome "Ana"})
        (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg
                                       :estado "vigente" :vigencia-inicio ini})  ; aberto (fim nil)
        (is (true?  (vereador/mandato-sobreposto? tx ente ver (LocalDate/parse "2026-01-01") nil))
            "novo intervalo dentro do aberto -> sobrepoe")
        (is (false? (vereador/mandato-sobreposto? tx ente (random-uuid) (LocalDate/parse "2026-01-01") nil))
            "outro vereador -> nao sobrepoe")))))
```

- [ ] **Step 2: Run — expect FAIL** (`atualizar!` etc. undefined)

Run: `... clojure -M:test --focus oplenario.cadastros.db-test`
Expected: FAIL (Unable to resolve `vereador/atualizar!`).

- [ ] **Step 3: Add the fns to `db/vereador.clj`** (after `inserir!`/`buscar`, near the mandato section)

```clojure
(defn atualizar!
  "UPDATE de nome/nome-parlamentar da linha EFETIVADA do vereador. So' seta as chaves PRESENTES em `campos`
   (:nome / :nome-parlamentar) — um PATCH parcial nunca zera o campo que o cliente nao mandou. Devolve o
   update-count (0 = linha inexistente/outro-tenant, ja' filtrada pela RLS). Defesa em profundidade: casa
   ente_id explicito + efetivado_em NOT NULL (a RLS ja' esconde staging, isto e' cinto-e-suspensorio)."
  [tx ente-id id campos]
  (let [set-map (cond-> {}
                  (contains? campos :nome)             (assoc :nome (:nome campos))
                  (contains? campos :nome-parlamentar) (assoc :nome_parlamentar (:nome-parlamentar campos)))
        r (jdbc/execute-one! tx
            (sql/format {:update :cadastros.vereador
                         :set set-map
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:is-not :efetivado_em nil]]}))]
    (:next.jdbc/update-count r)))

(defn mandato-vigente-de-vereador
  "O mandato com estado='vigente' que COBRE `data` (p/ a licenca resolver o alvo). nil se nenhum — cobre
   'sem mandato vigente' E 'ja' licenciado' (um licenciado tem estado != 'vigente'). Tie-break por :id."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :estado :vigencia_inicio :vigencia_fim]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:= :estado "vigente"]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc] [:id]] :limit 1}))))

(defn mandato-sobreposto?
  "True se JA' existe mandato 'vigente' efetivado do vereador cuja vigencia sobrepoe [inicio, fim] (fim nil
   = aberto = 'infinity'). Guard app-level (UX 409); o EXCLUDE (migration ...59) e' a rede. SQL cru
   parametrizado — o operador `&&` de daterange e' direto assim (o db_test ja' usa SQL cru p/ casos pontuais)."
  [tx ente-id vereador-id inicio fim]
  (some?
    (jdbc/execute-one! tx
      ["SELECT 1 FROM cadastros.mandato
        WHERE ente_id = ? AND vereador_id = ? AND estado = 'vigente' AND efetivado_em IS NOT NULL
          AND daterange(vigencia_inicio, COALESCE(vigencia_fim, 'infinity'::date), '[]')
              && daterange(?::date, COALESCE(?::date, 'infinity'::date), '[]')
        LIMIT 1"
       ente-id vereador-id inicio fim])))
```

- [ ] **Step 4: Run — expect PASS**

Run: `... clojure -M:test --focus oplenario.cadastros.db-test`
Expected: PASS (new deftests + the existing suite still green).

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/cadastros/db/vereador.clj \
        apps/backend/test/integration/oplenario/cadastros/db_test.clj
git commit -m "feat(cadastros): db writes atualizar!/mandato-vigente-de-vereador/mandato-sobreposto?"
```

---

## Task 4: Repo write methods (each in one tenant tx)

**Files:**
- Modify: `apps/backend/src/oplenario/cadastros/components/repositorio.clj`
- Test: `apps/backend/test/integration/oplenario/cadastros/repositorio_escrita_test.clj` (new)

**Interfaces:**
- Consumes: `db/vereador` fns from Tasks 3 + existing `inserir!`/`inserir-mandato!`/`inserir-licenca!`/`mudar-estado!`, `db/estrutura/buscar-legislatura`.
- Produces (protocol methods on `RepoCadastros`):
  - `atualizar-vereador! [this ente-id id campos] → update-count (long)`
  - `registrar-mandato! [this ente-id mandato] → {:id uuid} | nil (vereador/legislatura ausente) | throws {:tipo :conflito/mandato-sobreposto}`
  - `registrar-licenca! [this ente-id vereador-id licenca data] → {:id uuid} | nil (vereador ausente) | throws {:tipo :conflito/sem-mandato-vigente}`
- (Reuse existing `criar-vereador!` for create — no conflict logic.)

- [ ] **Step 1: Write the failing test** `test/integration/oplenario/cadastros/repositorio_escrita_test.clj`

```clojure
(ns oplenario.cadastros.repositorio-escrita-test
  "INTEGRACAO (PG real) — Slice 4: os metodos de ESCRITA do RepoCadastros, cada um numa tx do tenant. Prova
  a tx da licenca (linha gravada + estado do mandato vira 'licenciado' atomicamente), o guard de sobreposicao
  (409), os sinais de 404 (nil) e o isolamento RLS (escrita de um tenant nao atinge outro)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (assoc (repo/repositorio) :datasource c)]
        (try (t) (finally (component/stop c)))))))

(defn- semear! [ente leg ver ini]
  (repo/transacao *repo* ente
    (fn [tx]
      (#'oplenario.cadastros.db.estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "X"})
      (#'oplenario.cadastros.db.estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 20 :ano-inicio 2025 :ano-fim 2028 :vigente true})
      (vereador/inserir! tx {:id ver :ente-id ente :nome "Ana"}))))

;; NOTE: `semear!` precisa do municipio de referencia. Reusa o helper do db_test OU insira o municipio como
;; DONO antes (fora da tx do tenant), igual `seed-municipio!` no db_test. Repita aqui o insert de municipio:
(defn- seed-municipio! []
  (#'oplenario.cadastros.db.referencia/inserir-municipio!
    (:ds (:datasource *repo*)) {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 1}))

(deftest registrar-mandato-cria-e-recusa-sobreposto
  (let [ente (random-uuid) leg (random-uuid) ver (random-uuid) ini (LocalDate/parse "2025-01-01")]
    (seed-municipio!) (semear! ente leg ver ini)
    (is (= ver nil) "placeholder — remova; ver abaixo")))  ; substituir pelos asserts reais (Step 3 orienta)
```

> The test above is a scaffold — **write the real assertions in Step 3's companion** so they compile against the final API. Concretely, the deftests to implement are:
> - `registrar-mandato-cria-e-recusa-sobreposto`: 1st `registrar-mandato!` → `{:id ...}`; 2nd overlapping vigente → `thrown?` with `:conflito/mandato-sobreposto`; unknown vereador-id → `nil`; unknown legislatura-id → `nil`.
> - `registrar-licenca-flipa-estado-atomicamente`: seed a vigente mandato, `registrar-licenca!` → `{:id ...}`; then read the mandato and assert `estado = "licenciado"` and a `mandato_licenca` row exists; a vereador with no vigente mandato → `thrown?` `:conflito/sem-mandato-vigente`; unknown vereador → `nil`.
> - `atualizar-vereador-conta-linhas`: existing id → `1`; unknown id → `0`.
> - `escrita-isola-cross-tenant`: `registrar-mandato!` under ente A does not appear/affect ente B (RLS).

Replace the scaffold body with these deftests (mirror `db_test.clj` structure for reads; use `repo/transacao *repo* ente` to read back state).

- [ ] **Step 2: Run — expect FAIL** (protocol methods undefined; placeholder assert fails)

Run: `... clojure -M:test --focus oplenario.cadastros.repositorio-escrita-test`
Expected: FAIL.

- [ ] **Step 3: Add the protocol methods + impl to `components/repositorio.clj`**

Add to the `defprotocol RepoCadastros` (in the vereador/mandato/licenca section):

```clojure
  (atualizar-vereador! [this ente-id id campos]
    "UPDATE parcial de nome/nome-parlamentar da linha efetivada. Devolve update-count (0 = inexistente).")
  (registrar-mandato! [this ente-id mandato]
    "INSERT de mandato 'vigente' numa tx: 404 (nil) se vereador/legislatura ausente; throws
     :conflito/mandato-sobreposto se ja' ha' vigente sobreposto (guard + a rede EXCLUDE); senao {:id}.")
  (registrar-licenca! [this ente-id vereador-id licenca data]
    "Licenca record-only numa tx: 404 (nil) se vereador ausente; throws :conflito/sem-mandato-vigente se
     nao ha' mandato vigente cobrindo `data`; senao INSERT licenca + UPDATE mandato.estado='licenciado' -> {:id}.")
```

Add to the `defrecord RepoCadastrosPg` impls:

```clojure
  (atualizar-vereador! [this ente-id id campos]
    (transacao this ente-id #(vereador/atualizar! % ente-id id campos)))
  (registrar-mandato! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (cond
          (nil? (vereador/buscar tx ente-id (:vereador-id m)))                 nil
          (nil? (estrutura/buscar-legislatura tx (:legislatura-id m)))         nil
          (vereador/mandato-sobreposto? tx ente-id (:vereador-id m)
                                        (:vigencia-inicio m) (:vigencia-fim m))
          (throw (ex-info "mandato vigente sobreposto" {:tipo :conflito/mandato-sobreposto}))
          :else (do (vereador/inserir-mandato! tx m) {:id (:id m)})))))
  (registrar-licenca! [this ente-id vereador-id l data]
    (transacao this ente-id
      (fn [tx]
        (if (nil? (vereador/buscar tx ente-id vereador-id))
          nil
          (if-let [mv (vereador/mandato-vigente-de-vereador tx ente-id vereador-id data)]
            (do (vereador/inserir-licenca! tx (assoc l :mandato-id (:id mv)))
                (vereador/mudar-estado! tx {:id (:id mv) :estado "licenciado"})
                {:id (:id l)})
            (throw (ex-info "sem mandato vigente para licenciar" {:tipo :conflito/sem-mandato-vigente})))))))
```

(`estrutura` and `vereador` are already required in this ns; no import change.)

- [ ] **Step 4: Run — expect PASS**

Run: `... clojure -M:test --focus oplenario.cadastros.repositorio-escrita-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/cadastros/components/repositorio.clj \
        apps/backend/test/integration/oplenario/cadastros/repositorio_escrita_test.clj
git commit -m "feat(cadastros): repo writes atualizar-vereador!/registrar-mandato!/registrar-licenca!"
```

---

## Task 5: controllers pass-throughs

**Files:**
- Modify: `apps/backend/src/oplenario/cadastros/controllers.clj`
- Test: `apps/backend/test/unit/oplenario/cadastros/controllers_test.clj` (append)

**Interfaces:**
- Consumes: `RepoCadastros` (`criar-vereador!`, `atualizar-vereador!`, `registrar-mandato!`, `registrar-licenca!`, `legislatura-vigente`).
- Produces:
  - `criar-vereador [repo ente-id m] → {:id (:id m)}` (inserts, returns the generated id).
  - `editar-vereador [repo ente-id id campos] → update-count`
  - `registrar-mandato [repo ente-id m] → {:id} | nil | (throws :conflito/*)`
  - `registrar-licenca [repo ente-id vereador-id l data] → {:id} | nil | (throws :conflito/*)`
  - `legislatura-vigente [repo ente-id] → legislatura-map | nil`

- [ ] **Step 1: Write the failing test** — append to `controllers_test.clj`. Extend `fake-repo` OR add a focused fake. Add:

```clojure
(deftest criar-vereador-passa-adiante-e-devolve-id
  (let [ente (random-uuid) chamadas (atom [])
        m {:id (random-uuid) :ente-id ente :nome "Ana"}
        repo (reify repo-cadastros/RepoCadastros
               (criar-vereador! [_ e mm] (swap! chamadas conj [:criar e mm]) {:next.jdbc/update-count 1}))]
    (is (= {:id (:id m)} (controllers/criar-vereador repo ente m)) "devolve {:id} com o id gerado")
    (is (= [[:criar ente m]] @chamadas) "repassou ente-id + dominio sem alteracao")))

(deftest editar-vereador-passa-o-update-count
  (let [ente (random-uuid) id (random-uuid)
        repo (reify repo-cadastros/RepoCadastros
               (atualizar-vereador! [_ _ _ _] 1))]
    (is (= 1 (controllers/editar-vereador repo ente id {:nome "X"})))))

(deftest registrar-mandato-e-licenca-sao-pass-through
  (let [ente (random-uuid) ver (random-uuid) hoje (LocalDate/of 2026 7 14)
        repo (reify repo-cadastros/RepoCadastros
               (registrar-mandato! [_ _ m] {:id (:id m)})
               (registrar-licenca! [_ _ _ l _] {:id (:id l)}))
        m {:id (random-uuid) :vereador-id ver} l {:id (random-uuid)}]
    (is (= {:id (:id m)} (controllers/registrar-mandato repo ente m)))
    (is (= {:id (:id l)} (controllers/registrar-licenca repo ente ver l hoje)))))
```

(These `reify` forms implement only the methods each test exercises — add `#_{:clj-kondo/ignore [:missing-protocol-method]}` before each `reify`, matching the file's convention.)

- [ ] **Step 2: Run — expect FAIL**

Run: `... clojure -M:test --focus oplenario.cadastros.controllers-test`
Expected: FAIL (controllers/criar-vereador undefined).

- [ ] **Step 3: Add the fns to `controllers.clj`**

```clojure
(defn criar-vereador
  "INSERT de vereador (identidade_id NULL, efetivado_em=now()). Devolve {:id} com o id gerado no adapters/in."
  [repo-cadastros ente-id m]
  (repo/criar-vereador! repo-cadastros ente-id m)
  {:id (:id m)})

(defn editar-vereador
  "UPDATE parcial de nome/nome-parlamentar — devolve o update-count (o diplomat mapeia 0 -> 404)."
  [repo-cadastros ente-id id campos]
  (repo/atualizar-vereador! repo-cadastros ente-id id campos))

(defn registrar-mandato
  "Pass-through: {:id} | nil (404) | throws :conflito/mandato-sobreposto (409)."
  [repo-cadastros ente-id m]
  (repo/registrar-mandato! repo-cadastros ente-id m))

(defn registrar-licenca
  "Pass-through: {:id} | nil (404) | throws :conflito/sem-mandato-vigente (409)."
  [repo-cadastros ente-id vereador-id l data]
  (repo/registrar-licenca! repo-cadastros ente-id vereador-id l data))

(defn legislatura-vigente
  "A legislatura vigente da Casa (p/ o seletor do form de mandato), ou nil."
  [repo-cadastros ente-id]
  (repo/legislatura-vigente repo-cadastros ente-id))
```

- [ ] **Step 4: Run — expect PASS**

Run: `... clojure -M:test --focus oplenario.cadastros.controllers-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/cadastros/controllers.clj \
        apps/backend/test/unit/oplenario/cadastros/controllers_test.clj
git commit -m "feat(cadastros): controllers pass-through das escritas + legislatura-vigente"
```

---

## Task 6: diplomat — 4 write routes + legislatura-vigente read + wire/out + codegen

**Files:**
- Create: `apps/backend/src/oplenario/cadastros/wire/out/legislatura.clj`
- Create: `apps/backend/src/oplenario/cadastros/adapters/out/legislatura.clj`
- Modify: `apps/backend/src/oplenario/cadastros/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/codegen/gerar_cadastros.clj`
- Modify: `apps/frontend/src/lib/contrato-cadastros.gen.ts` (regenerated)
- Test: `apps/backend/test/integration/oplenario/cadastros/vereador_http_in_test.clj` (append)

**Interfaces:**
- Consumes: `controllers/*` (Task 5), `adapters-in/*` (Task 2), `it/exige-papel`, `tempo/hoje-de`+`tempo/agora`, `parse-uuid`.
- Produces (routes; all gated `secretario` after `auth`):
  - `POST /cadastros/vereadores` → 201 `{:id}`
  - `PATCH /cadastros/vereadores/:id` → 200 `{:id}` / 404
  - `POST /cadastros/vereadores/:id/mandatos` → 201 `{:id}` / 404 / 409
  - `POST /cadastros/vereadores/:id/licencas` → 201 `{:id}` / 404 / 409
  - `GET /cadastros/legislatura-vigente` → 200 `LegislaturaVigenteOut` / 404
- Produces (wire): `LegislaturaVigenteOut` `{:id string :numero int :ano-inicio int :ano-fim int :vigente boolean}`.

- [ ] **Step 1: Write the failing test** — append to `vereador_http_in_test.clj`. First extend `fake-repo-cadastros` (or add a richer fake) so it implements the write methods + `legislatura-vigente`. Add a second fake constructor:

```clojure
(defn- fake-repo-escrita
  "RepoCadastros fake com as escritas + legislatura-vigente. Cada fn devolve o que o teste precisa; use nil
  p/ 404 e (throw (ex-info ... {:tipo :conflito/...})) p/ 409."
  [{:keys [criar editar mandato licenca leg]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (criar-vereador!    [_ _ _]     (or criar {:next.jdbc/update-count 1}))
    (atualizar-vereador! [_ _ _ _]  (if (some? editar) editar 1))
    (registrar-mandato! [_ _ m]     (if (fn? mandato) (mandato m) mandato))
    (registrar-licenca! [_ _ _ l _] (if (fn? licenca) (licenca l) licenca))
    (legislatura-vigente [_ _]      leg)))
```

Then the deftests (mount via `service-fn` — reuse the existing helper; add a `POST/PATCH` variant that passes a JSON body):

```clojure
(defn- post [service tok path body]
  (pt/response-for service :post path
    :headers (assoc (com-bearer tok) "content-type" "application/json")
    :body (json/write-value-as-string body)))
(defn- patch* [service tok path body]
  (pt/response-for service :patch path
    :headers (assoc (com-bearer tok) "content-type" "application/json")
    :body (json/write-value-as-string body)))

(deftest criar-vereador-201
  (let [ente (random-uuid)
        r (post (service-fn #{"secretario"} (fake-repo-escrita {}))
                (token ente (random-uuid)) "/cadastros/vereadores" {:nome "Nova Vereadora"})]
    (is (= 201 (:status r)))
    (is (string? (:id (ler-json r))) "devolve {:id}")))

(deftest criar-vereador-corpo-invalido-400
  (let [r (post (service-fn #{"secretario"} (fake-repo-escrita {}))
                (token (random-uuid) (random-uuid)) "/cadastros/vereadores" {:nome-parlamentar "sem nome"})]
    (is (= 400 (:status r)) "sem :nome -> adapters/in lanca :validacao/invalido -> 400")))

(deftest criar-vereador-sem-papel-403
  (let [r (post (service-fn #{"vereador"} (fake-repo-escrita {}))
                (token (random-uuid) (random-uuid)) "/cadastros/vereadores" {:nome "X"})]
    (is (= 403 (:status r)))))

(deftest editar-vereador-200-e-404
  (let [tok (token (random-uuid) (random-uuid))]
    (is (= 200 (:status (patch* (service-fn #{"secretario"} (fake-repo-escrita {:editar 1}))
                                tok (str "/cadastros/vereadores/" (random-uuid)) {:nome "Novo"}))))
    (is (= 404 (:status (patch* (service-fn #{"secretario"} (fake-repo-escrita {:editar 0}))
                                tok (str "/cadastros/vereadores/" (random-uuid)) {:nome "Novo"}))))
    (is (= 404 (:status (patch* (service-fn #{"secretario"} (fake-repo-escrita {:editar 1}))
                                tok "/cadastros/vereadores/nao-uuid" {:nome "Novo"})))
        ":id malformado -> 404, nunca 500")))

(deftest registrar-mandato-201-404-409
  (let [tok (token (random-uuid) (random-uuid)) ver (random-uuid)
        corpo {:legislatura-id (str (random-uuid)) :natureza "titular" :vigencia-inicio "2025-01-01"}
        sobrepoe (fn [_] (throw (ex-info "x" {:tipo :conflito/mandato-sobreposto})))]
    (is (= 201 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:mandato (fn [m] {:id (:id m)})}))
                              tok (str "/cadastros/vereadores/" ver "/mandatos") corpo))))
    (is (= 404 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:mandato nil}))
                              tok (str "/cadastros/vereadores/" ver "/mandatos") corpo))))
    (is (= 409 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:mandato sobrepoe}))
                              tok (str "/cadastros/vereadores/" ver "/mandatos") corpo))))))

(deftest registrar-licenca-201-404-409
  (let [tok (token (random-uuid) (random-uuid)) ver (random-uuid)
        corpo {:inicio "2026-03-01"}
        sem-vigente (fn [_] (throw (ex-info "x" {:tipo :conflito/sem-mandato-vigente})))]
    (is (= 201 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:licenca (fn [l] {:id (:id l)})}))
                              tok (str "/cadastros/vereadores/" ver "/licencas") corpo))))
    (is (= 404 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:licenca nil}))
                              tok (str "/cadastros/vereadores/" ver "/licencas") corpo))))
    (is (= 409 (:status (post (service-fn #{"secretario"} (fake-repo-escrita {:licenca sem-vigente}))
                              tok (str "/cadastros/vereadores/" ver "/licencas") corpo))))))

(deftest legislatura-vigente-200-e-404
  (let [tok (token (random-uuid) (random-uuid))
        leg {:id (random-uuid) :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true}]
    (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-escrita {:leg leg}))
                             :get "/cadastros/legislatura-vigente" :headers (com-bearer tok))]
      (is (= 200 (:status r)))
      (is (= 19 (:numero (ler-json r)))))
    (is (= 404 (:status (pt/response-for (service-fn #{"secretario"} (fake-repo-escrita {:leg nil}))
                                         :get "/cadastros/legislatura-vigente" :headers (com-bearer tok)))))))
```

- [ ] **Step 2: Run — expect FAIL** (routes/methods not there)

Run: `... clojure -M:test --focus oplenario.cadastros.vereador-http-in-test`
Expected: FAIL.

- [ ] **Step 3: Write `wire/out/legislatura.clj`**

```clojure
(ns oplenario.cadastros.wire.out.legislatura
  "Representacao EXTERNA de SAIDA de GET /cadastros/legislatura-vigente (Onda D Slice 4) — o seletor do form
  de mandato precisa do id + rotulo (numero/anos) da legislatura vigente. Do Eixo 8 gera o tipo TS.")

(def LegislaturaVigenteOut
  [:map {:closed true}
   [:id :string]
   [:numero :int]
   [:ano-inicio :int]
   [:ano-fim :int]
   [:vigente :boolean]])
```

- [ ] **Step 4: Write `adapters/out/legislatura.clj`**

```clojure
(ns oplenario.cadastros.adapters.out.legislatura
  "Projecao dominio -> wire de LegislaturaVigenteOut (§22.10 adapters/out). Valida contra o contrato
  (drift = 500, review sec: nunca corpo malformado que envenene o codegen)."
  (:require [malli.core :as m]
            [oplenario.cadastros.wire.out.legislatura :as wire]))

(set! *warn-on-reflection* true)

(defn ->wire [leg]
  (let [w {:id (str (:id leg)) :numero (:numero leg) :ano-inicio (:ano-inicio leg)
           :ano-fim (:ano-fim leg) :vigente (boolean (:vigente leg))}]
    (when-not (m/validate wire/LegislaturaVigenteOut w)
      (throw (ex-info "legislatura-vigente diverge do contrato wire"
                      {:erros (m/explain wire/LegislaturaVigenteOut w)})))
    w))
```

- [ ] **Step 5: Edit `diplomat/http/in.clj`** — add requires + handlers + routes.

Add to the `:require`:
```clojure
            [oplenario.cadastros.adapters.in.vereador :as adapters-in]
            [oplenario.cadastros.adapters.out.legislatura :as adapters-leg]
```

Add the handlers (after `ficha-handler`):
```clojure
(defn- criar-vereador-handler [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in/criar-vereador->dominio ator (:json-params req))]
      (http/json-resposta 201 (controllers/criar-vereador repo ente-id m)))))

(defn- editar-vereador-handler [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          campos (adapters-in/editar-vereador->dominio (:json-params req))]
      (if (and id (pos? (controllers/editar-vereador repo ente-id id campos)))
        (http/json-resposta 200 {:id (str id)})
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

(defn- registrar-mandato-handler [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          m (adapters-in/registrar-mandato->dominio ator id (:json-params req))]
      (try
        (if-let [r (and id (controllers/registrar-mandato repo ente-id m))]
          (http/json-resposta 201 r)
          (http/json-resposta 404 {:erro "vereador ou legislatura nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/mandato-sobreposto (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "ja existe mandato vigente sobreposto para este vereador"})
            (throw e)))))))

(defn- registrar-licenca-handler [repo relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)
          l (adapters-in/registrar-licenca->dominio ator (:json-params req))]
      (try
        (if-let [r (and id (controllers/registrar-licenca repo ente-id id l hoje))]
          (http/json-resposta 201 r)
          (http/json-resposta 404 {:erro "vereador nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sem-mandato-vigente (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "vereador sem mandato vigente para licenciar"})
            (throw e)))))))

(defn- legislatura-vigente-handler [repo]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))]
      (if-let [leg (controllers/legislatura-vigente repo ente-id)]
        (http/json-resposta 200 (adapters-leg/->wire leg))
        (http/json-resposta 404 {:erro "nenhuma legislatura vigente"})))))
```

Extend the `rotas` set (keep the 2 existing GET routes; add 5):
```clojure
(defn rotas
  [{:keys [auth repo-cadastros relogio]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/cadastros/vereadores"     :get [auth papel (listar-handler repo-cadastros relogio)]
       :route-name :cadastros/listar-vereadores]
      ["/cadastros/vereadores/:id" :get [auth papel (ficha-handler repo-cadastros relogio)]
       :route-name :cadastros/ficha-vereador]
      ["/cadastros/vereadores" :post [auth papel (criar-vereador-handler repo-cadastros)]
       :route-name :cadastros/criar-vereador]
      ["/cadastros/vereadores/:id" :patch [auth papel (editar-vereador-handler repo-cadastros)]
       :route-name :cadastros/editar-vereador]
      ["/cadastros/vereadores/:id/mandatos" :post [auth papel (registrar-mandato-handler repo-cadastros)]
       :route-name :cadastros/registrar-mandato]
      ["/cadastros/vereadores/:id/licencas" :post [auth papel (registrar-licenca-handler repo-cadastros relogio)]
       :route-name :cadastros/registrar-licenca]
      ["/cadastros/legislatura-vigente" :get [auth papel (legislatura-vigente-handler repo-cadastros)]
       :route-name :cadastros/legislatura-vigente]}))
```

- [ ] **Step 6: Run the borda test — expect PASS**

Run: `... clojure -M:test --focus oplenario.cadastros.vereador-http-in-test`
Expected: PASS.

- [ ] **Step 7: Add `LegislaturaVigenteOut` to the codegen manifesto** in `codegen/gerar_cadastros.clj`

Add the require `[oplenario.cadastros.wire.out.legislatura :as legislatura]` and append to `manifesto`:
```clojure
   ["LegislaturaVigenteOut" legislatura/LegislaturaVigenteOut]
```

- [ ] **Step 8: Regenerate the FE contract** (in the container, writing straight to the FE lib)

Run:
```bash
docker run --rm --network oplenario_default \
  -v "$(pwd)":/app -w /app/apps/backend -v oplenario_backend_m2:/root/.m2 \
  -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
  clojure:temurin-21-tools-deps \
  clojure -M -m oplenario.codegen.gerar-cadastros ../frontend/src/lib/contrato-cadastros.gen.ts
```
Expected: prints "…tipos TS de cadastros gerados… ( 6 interfaces)". Verify `git diff apps/frontend/src/lib/contrato-cadastros.gen.ts` shows ONLY the added `LegislaturaVigenteOut` interface (no churn to the 5 existing ones).

- [ ] **Step 9: Commit**

```bash
git add apps/backend/src/oplenario/cadastros/wire/out/legislatura.clj \
        apps/backend/src/oplenario/cadastros/adapters/out/legislatura.clj \
        apps/backend/src/oplenario/cadastros/diplomat/http/in.clj \
        apps/backend/src/oplenario/codegen/gerar_cadastros.clj \
        apps/backend/test/integration/oplenario/cadastros/vereador_http_in_test.clj \
        apps/frontend/src/lib/contrato-cadastros.gen.ts
git commit -m "feat(cadastros): 4 rotas de escrita + legislatura-vigente + codegen"
```

- [ ] **Step 10: Run the whole cadastros suite green** (regression across Tasks 1–6)

Run: `... clojure -M:test --focus oplenario.cadastros.db-test --focus oplenario.cadastros.repositorio-escrita-test --focus oplenario.cadastros.controllers-test --focus oplenario.cadastros.vereador-adapters-in-test --focus oplenario.cadastros.vereador-http-in-test`
Expected: all PASS. (Optionally run the full `clojure -M:test --skip :e2e` once — expect only the known `outbox-relay` flake if the app container is up.)

---

## Task 7: FE form validation view-models (pure)

**Files:**
- Create: `apps/frontend/src/lib/cadastro-vereadores-forms.ts`
- Test: `apps/frontend/src/lib/cadastro-vereadores-forms.test.ts`

**Interfaces:**
- Produces (pure fns, no DOM/fetch):
  - `validarNovoVereador({nome}) → {erros: {nome?: string}, valido: boolean}`
  - `validarEditar({nome?, nomeParlamentar?}) → {erros: {nome?: string, geral?: string}, valido}`
  - `validarMandato({legislaturaId, natureza, vigenciaInicio, vigenciaFim?}) → {erros: {...}, valido}`
  - `validarLicenca({inicio, fim?}) → {erros: {...}, valido}`

- [ ] **Step 1: Write the failing test** `cadastro-vereadores-forms.test.ts`

```ts
import { describe, expect, it } from "vitest";
import {
  validarNovoVereador, validarEditar, validarMandato, validarLicenca,
} from "./cadastro-vereadores-forms";

describe("validarNovoVereador", () => {
  it("exige nome não-branco", () => {
    expect(validarNovoVereador({ nome: "" }).valido).toBe(false);
    expect(validarNovoVereador({ nome: "   " }).valido).toBe(false);
    expect(validarNovoVereador({ nome: "Helena" }).valido).toBe(true);
  });
  it("mensagem de erro em pt-BR", () => {
    expect(validarNovoVereador({ nome: "" }).erros.nome).toMatch(/nome/i);
  });
});

describe("validarEditar", () => {
  it("exige ao menos um campo preenchido", () => {
    expect(validarEditar({}).valido).toBe(false);
    expect(validarEditar({ nome: "", nomeParlamentar: "" }).valido).toBe(false);
    expect(validarEditar({ nomeParlamentar: "Apelido" }).valido).toBe(true);
  });
  it("nome preenchido não pode ser branco", () => {
    expect(validarEditar({ nome: "  " }).valido).toBe(false);
  });
});

describe("validarMandato", () => {
  const ok = { legislaturaId: "abc", natureza: "titular", vigenciaInicio: "2025-01-01" };
  it("caminho feliz", () => expect(validarMandato(ok).valido).toBe(true));
  it("exige legislatura", () => expect(validarMandato({ ...ok, legislaturaId: "" }).valido).toBe(false));
  it("exige natureza válida", () => expect(validarMandato({ ...ok, natureza: "" }).valido).toBe(false));
  it("data de início inválida reprova", () => expect(validarMandato({ ...ok, vigenciaInicio: "01/01/2025" }).valido).toBe(false));
  it("fim antes do início reprova", () =>
    expect(validarMandato({ ...ok, vigenciaFim: "2024-01-01" }).valido).toBe(false));
  it("fim vazio é aceito (mandato em aberto)", () =>
    expect(validarMandato({ ...ok, vigenciaFim: "" }).valido).toBe(true));
});

describe("validarLicenca", () => {
  it("exige início válido", () => {
    expect(validarLicenca({ inicio: "" }).valido).toBe(false);
    expect(validarLicenca({ inicio: "amanhã" }).valido).toBe(false);
    expect(validarLicenca({ inicio: "2026-03-01" }).valido).toBe(true);
  });
  it("fim antes do início reprova", () =>
    expect(validarLicenca({ inicio: "2026-03-01", fim: "2026-02-01" }).valido).toBe(false));
});
```

- [ ] **Step 2: Run — expect FAIL**

Run: `docker compose exec frontend npx vitest run src/lib/cadastro-vereadores-forms.test.ts`
Expected: FAIL (module not found).

- [ ] **Step 3: Write `cadastro-vereadores-forms.ts`**

```ts
// View-models puros de VALIDAÇÃO dos forms de escrita do cadastro de vereadores (Onda D Slice 4). Sem
// DOM/fetch — só regras de campo + mensagens pt-BR. Mesma disciplina de cadastro-vereadores-vista.ts.
// A validação de data espelha o backend (adapters/in: AAAA-MM-DD -> LocalDate/parse); aqui só barramos
// o formato/consistência antes do POST (o servidor revalida — o FE nunca é a única autoridade).

function branco(s: string | null | undefined): boolean {
  return s == null || s.trim() === "";
}

// AAAA-MM-DD estrito + data real (rejeita "2025-13-40"). Retorna o Date UTC ou null.
function dataISO(s: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return null;
  const d = new Date(`${s}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return null;
  // round-trip guard: `new Date` normaliza overflow (13 -> jan do ano seguinte); exigimos igualdade.
  return d.toISOString().slice(0, 10) === s ? d : null;
}

export type Resultado<E> = { erros: E; valido: boolean };
function fechar<E extends object>(erros: E): Resultado<E> {
  return { erros, valido: Object.keys(erros).length === 0 };
}

export function validarNovoVereador(v: { nome: string }): Resultado<{ nome?: string }> {
  const erros: { nome?: string } = {};
  if (branco(v.nome)) erros.nome = "Informe o nome do vereador.";
  return fechar(erros);
}

export function validarEditar(v: { nome?: string; nomeParlamentar?: string }): Resultado<{ nome?: string; geral?: string }> {
  const erros: { nome?: string; geral?: string } = {};
  const temNome = !branco(v.nome);
  const temParlamentar = !branco(v.nomeParlamentar);
  if (!temNome && !temParlamentar) erros.geral = "Preencha ao menos um campo para salvar.";
  if (v.nome !== undefined && v.nome !== "" && branco(v.nome)) erros.nome = "O nome não pode ficar em branco.";
  return fechar(erros);
}

export function validarMandato(v: {
  legislaturaId: string; natureza: string; vigenciaInicio: string; vigenciaFim?: string;
}): Resultado<{ legislaturaId?: string; natureza?: string; vigenciaInicio?: string; vigenciaFim?: string }> {
  const erros: { legislaturaId?: string; natureza?: string; vigenciaInicio?: string; vigenciaFim?: string } = {};
  if (branco(v.legislaturaId)) erros.legislaturaId = "Selecione a legislatura.";
  if (v.natureza !== "titular" && v.natureza !== "suplencia") erros.natureza = "Selecione a natureza do mandato.";
  const ini = dataISO(v.vigenciaInicio);
  if (!ini) erros.vigenciaInicio = "Data de início inválida (AAAA-MM-DD).";
  if (!branco(v.vigenciaFim)) {
    const fim = dataISO(v.vigenciaFim as string);
    if (!fim) erros.vigenciaFim = "Data de fim inválida (AAAA-MM-DD).";
    else if (ini && fim < ini) erros.vigenciaFim = "O fim não pode ser anterior ao início.";
  }
  return fechar(erros);
}

export function validarLicenca(v: { inicio: string; fim?: string }): Resultado<{ inicio?: string; fim?: string }> {
  const erros: { inicio?: string; fim?: string } = {};
  const ini = dataISO(v.inicio);
  if (!ini) erros.inicio = "Data de início inválida (AAAA-MM-DD).";
  if (!branco(v.fim)) {
    const fim = dataISO(v.fim as string);
    if (!fim) erros.fim = "Data de fim inválida (AAAA-MM-DD).";
    else if (ini && fim < ini) erros.fim = "O fim não pode ser anterior ao início.";
  }
  return fechar(erros);
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `docker compose exec frontend npx vitest run src/lib/cadastro-vereadores-forms.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/cadastro-vereadores-forms.ts apps/frontend/src/lib/cadastro-vereadores-forms.test.ts
git commit -m "feat(fe): view-models de validação dos forms de cadastro de vereador"
```

---

## Task 8: FE mutation hooks + legislatura-vigente hook

**Files:**
- Create: `apps/frontend/src/lib/use-criar-vereador.ts` (+ `.test.ts`)
- Create: `apps/frontend/src/lib/use-editar-vereador.ts` (+ `.test.ts`)
- Create: `apps/frontend/src/lib/use-registrar-mandato.ts` (+ `.test.ts`)
- Create: `apps/frontend/src/lib/use-registrar-licenca.ts` (+ `.test.ts`)
- Create: `apps/frontend/src/lib/use-legislatura-vigente.ts` (+ `.test.ts`)

**Interfaces:**
- Consumes: `apiFetch` (`./api-fetch`), `camelizarChaves` (`./boundary`), `semCredencial` (`./modo`), `LegislaturaVigenteOut` (`./contrato-cadastros.gen`).
- Produces:
  - `useCriarVereador(token) → { criar({nome, nomeParlamentar?}) → Promise<{id: string}>, estado, erro }`
  - `useEditarVereador(token, vereadorId) → { editar({nome?, nomeParlamentar?}) → Promise<{id}>, estado, erro }`
  - `useRegistrarMandato(token, vereadorId) → { registrar({legislaturaId, partido?, natureza, vigenciaInicio, vigenciaFim?}) → Promise<{id}>, estado, erro }`
  - `useRegistrarLicenca(token, vereadorId) → { registrar({inicio, fim?, motivo?}) → Promise<{id}>, estado, erro }`
  - `useLegislaturaVigente(token) → { dados: LegislaturaVigenteOut | null, estado }`
  - `estado: "ocioso" | "enviando" | "erro"` (fetch hook: `"carregando" | "pronto" | "erro"`).

- [ ] **Step 1: Write the failing test** for one representative mutation hook — `use-criar-vereador.test.ts` (mirror `use-registrar-resposta` patterns; mock `apiFetch`)

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";

const fetchMock = vi.hoisted(() => vi.fn());
vi.mock("./api-fetch", () => ({ apiFetch: fetchMock }));

import { useCriarVereador } from "./use-criar-vereador";

afterEach(() => { fetchMock.mockReset(); });

function ok(body: unknown) {
  return { ok: true, status: 201, json: async () => body } as Response;
}
function erro(status: number, body: unknown) {
  return { ok: false, status, json: async () => body } as Response;
}

describe("useCriarVereador", () => {
  it("POST /api/cadastros/vereadores e devolve {id}", async () => {
    fetchMock.mockResolvedValueOnce(ok({ id: "v-novo" }));
    const { result } = renderHook(() => useCriarVereador("tok"));
    let dados: { id: string } | undefined;
    await act(async () => { dados = await result.current.criar({ nome: "Helena" }); });
    expect(dados).toEqual({ id: "v-novo" });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/cadastros/vereadores");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ nome: "Helena" }); // nomeParlamentar undefined é filtrado
  });

  it("erro do servidor -> estado 'erro' e throw (sem crash)", async () => {
    fetchMock.mockResolvedValueOnce(erro(400, { erro: "corpo invalido" }));
    const { result } = renderHook(() => useCriarVereador("tok"));
    await expect(act(async () => { await result.current.criar({ nome: "" }); })).rejects.toThrow();
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
```

(Write analogous `.test.ts` for `use-editar-vereador` [PATCH, id in path], `use-registrar-mandato`, `use-registrar-licenca` [POST nested path], and `use-legislatura-vigente` [GET, mirror `use-vereadores.test.ts`]. Keep each focused: assert the path, method, kebab body, `{id}` return, and the error→`"erro"` path.)

- [ ] **Step 2: Run — expect FAIL**

Run: `docker compose exec frontend npx vitest run src/lib/use-criar-vereador.test.ts`
Expected: FAIL (module not found).

- [ ] **Step 3: Write the hooks.** `use-criar-vereador.ts` (the other three are the same shape with different path/method/body type — copy and adjust):

```ts
"use client";

// Hook de mutação — POST /api/cadastros/vereadores (Onda D Slice 4). Mirror de use-registrar-resposta.ts:
// estado ocioso/enviando/erro, corpoKebab filtrando `undefined`, apiFetch (boundary de auth), camelizar a
// resposta {id}. `criar` lança em falha (o caller decide a UX); `estado`/`erro` alimentam o form.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type CriarVereadorIn = { nome: string; nomeParlamentar?: string };
type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}
function corpoKebab(corpo: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo).filter(([, v]) => v !== undefined).map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useCriarVereador(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);
  useEffect(() => () => { vivoRef.current = false; }, []);

  async function criar(corpo: CriarVereadorIn): Promise<{ id: string }> {
    if (semCredencial(token)) throw new Error("sem token de autenticacao");
    if (enviandoRef.current) throw new Error("envio em andamento");
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch("/api/cadastros/vereadores", {
        token: token ?? undefined, method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao criar vereador (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) { setEstado("erro"); setErro(msg); }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as { id: string };
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current && !tratado) { setEstado("erro"); setErro("falha de rede — tente novamente"); }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }
  return { criar, estado, erro };
}
```

The variants:
- `use-editar-vereador.ts`: `useEditarVereador(token, vereadorId)`; path `` `/api/cadastros/vereadores/${encodeURIComponent(vereadorId)}` ``; `method: "PATCH"`; guard `if (!vereadorId) throw`; body type `{nome?, nomeParlamentar?}`; fn `editar`.
- `use-registrar-mandato.ts`: `useRegistrarMandato(token, vereadorId)`; path `` `/api/cadastros/vereadores/${encodeURIComponent(vereadorId)}/mandatos` ``; `POST`; body `{legislaturaId, partido?, natureza, vigenciaInicio, vigenciaFim?}`; fn `registrar`.
- `use-registrar-licenca.ts`: same shape, path `.../licencas`; body `{inicio, fim?, motivo?}`; fn `registrar`.
- `use-legislatura-vigente.ts`: a FETCH hook — mirror `use-vereadores.ts` exactly (states `"carregando"|"pronto"|"erro"`, `apiFetch("/api/cadastros/legislatura-vigente", {token, cache:"no-store"})`, a `404` → `dados: null, estado: "pronto"` (not an error — "no legislatura yet" is a valid empty state), other non-ok → `"erro"`; `camelizarChaves` the body to `LegislaturaVigenteOut`). Return `{ dados, estado }`.

- [ ] **Step 4: Run all five hook tests — expect PASS**

Run: `docker compose exec frontend npx vitest run src/lib/use-criar-vereador.test.ts src/lib/use-editar-vereador.test.ts src/lib/use-registrar-mandato.test.ts src/lib/use-registrar-licenca.test.ts src/lib/use-legislatura-vigente.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/use-criar-vereador.* apps/frontend/src/lib/use-editar-vereador.* \
        apps/frontend/src/lib/use-registrar-mandato.* apps/frontend/src/lib/use-registrar-licenca.* \
        apps/frontend/src/lib/use-legislatura-vigente.*
git commit -m "feat(fe): hooks de mutação (criar/editar/mandato/licença) + legislatura-vigente"
```

---

## Task 9: FE page wiring — mount forms, refetch, remove EmBreve stubs

**Files:**
- Modify: `apps/frontend/src/lib/use-vereadores.ts`, `apps/frontend/src/lib/use-vereador-ficha.ts` — add a `versao` refetch token.
- Create: `apps/frontend/src/app/(interno)/cadastros/vereadores/novo-vereador-form.tsx`
- Create: `apps/frontend/src/app/(interno)/cadastros/vereadores/editar-vereador-form.tsx`
- Create: `apps/frontend/src/app/(interno)/cadastros/vereadores/registrar-mandato-form.tsx`
- Create: `apps/frontend/src/app/(interno)/cadastros/vereadores/registrar-licenca-form.tsx`
- Modify: `apps/frontend/src/app/(interno)/cadastros/vereadores/page.tsx` + `cadastro-vereadores.css`
- Test: `apps/frontend/src/app/(interno)/cadastros/vereadores/page.test.tsx` (append cases)

**Interfaces:**
- Consumes: Task 7 view-models, Task 8 hooks, the read hooks with the new `versao` param.
- Produces: forms call `onSucesso(novoId?)`; the page bumps `versao` (refetch) and selects the affected vereador.

- [ ] **Step 1: Add `versao` to the read hooks (failing test first).** Append a case to `use-vereadores.test.ts`:

```ts
it("refaz o fetch quando `versao` muda", async () => {
  fetchMock.mockResolvedValue(ok({ vereadores: [] }));
  const { rerender } = renderHook(({ v }) => useVereadores("tok", v), { initialProps: { v: 0 } });
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  rerender({ v: 1 });
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
});
```
(Adjust to the existing test file's mock setup for `apiFetch`.)

- [ ] **Step 2: Run — expect FAIL** (hook ignores the 2nd arg)

Run: `docker compose exec frontend npx vitest run src/lib/use-vereadores.test.ts`
Expected: FAIL (called once, not twice).

- [ ] **Step 3: Add the `versao` param** to `use-vereadores.ts` and `use-vereador-ficha.ts`

In `use-vereadores.ts`: change the signature to `export function useVereadores(token: string | null, versao = 0)` and add `versao` to the effect dependency array: `}, [token, versao]);`. Same edit in `use-vereador-ficha.ts` (`export function useVereadorFicha(token, id, versao = 0)`, deps `[token, id, versao]`).

- [ ] **Step 4: Run — expect PASS**

Run: `docker compose exec frontend npx vitest run src/lib/use-vereadores.test.ts src/lib/use-vereador-ficha.test.ts`
Expected: PASS.

- [ ] **Step 5: Write the 4 form components.** Each is a controlled `<form>` using its view-model + hook, rendering pt-BR field errors and disabling submit while `estado === "enviando"` or invalid. `novo-vereador-form.tsx`:

```tsx
"use client";

// Form "Novo vereador" (Onda D Slice 4). Painel inline (não modal — evita o carry de focus-trap da C4);
// reusa .btn/.campo do chassi. Valida com validarNovoVereador (pt-BR), envia com useCriarVereador,
// chama onSucesso(novoId) p/ a página refazer o fetch e selecionar o novo. onCancelar fecha o painel.

import { useState } from "react";
import { useCriarVereador } from "@/lib/use-criar-vereador";
import { validarNovoVereador } from "@/lib/cadastro-vereadores-forms";

export function NovoVereadorForm({
  token, onSucesso, onCancelar,
}: { token: string | null; onSucesso: (novoId: string) => void; onCancelar: () => void }) {
  const [nome, setNome] = useState("");
  const [nomeParlamentar, setNomeParlamentar] = useState("");
  const [tocado, setTocado] = useState(false);
  const { criar, estado, erro } = useCriarVereador(token);
  const { erros, valido } = validarNovoVereador({ nome });

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      const { id } = await criar({ nome: nome.trim(), nomeParlamentar: nomeParlamentar.trim() || undefined });
      onSucesso(id);
    } catch { /* estado 'erro' já exibido abaixo */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Novo vereador">
      <div className="campo">
        <label htmlFor="nv-nome">Nome*</label>
        <input id="nv-nome" value={nome} onChange={(e) => setNome(e.target.value)}
               aria-invalid={tocado && !!erros.nome} aria-describedby={erros.nome ? "nv-nome-erro" : undefined} />
        {tocado && erros.nome && <p id="nv-nome-erro" role="alert" className="campo-erro">{erros.nome}</p>}
      </div>
      <div className="campo">
        <label htmlFor="nv-parlamentar">Nome parlamentar</label>
        <input id="nv-parlamentar" value={nomeParlamentar} onChange={(e) => setNomeParlamentar(e.target.value)} />
      </div>
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Salvando…" : "Criar vereador"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
```

`editar-vereador-form.tsx` — same shape; props `{ token, vereadorId, inicial: {nome, nomeParlamentar}, onSucesso, onCancelar }`; pre-fills state from `inicial`; validates with `validarEditar`; sends only changed fields (send `{nome}` and/or `{nomeParlamentar}`); calls `onSucesso(vereadorId)`.

`registrar-mandato-form.tsx` — props `{ token, vereadorId, legislatura: LegislaturaVigenteOut | null, onSucesso, onCancelar }`; fields: legislatura (a read-only display of `legislatura.numero/anos` — the only vigente option; the hidden value is `legislatura.id`; if `legislatura == null`, render a disabled state "Cadastre a legislatura vigente primeiro." and no submit), partido (text), natureza (`<select>` titular/suplencia), vigenciaInicio (`type="date"`), vigenciaFim (`type="date"`, optional); validate with `validarMandato`; on submit `registrar({legislaturaId: legislatura.id, partido: partido||undefined, natureza, vigenciaInicio, vigenciaFim: vigenciaFim||undefined})`; map a 409 (`estado === "erro"` with the server message) to a friendly inline alert; `onSucesso(vereadorId)`.

`registrar-licenca-form.tsx` — props `{ token, vereadorId, onSucesso, onCancelar }`; fields: inicio (`type="date"`, required), fim (optional), motivo (text, optional); validate with `validarLicenca`; on submit `registrar({inicio, fim: fim||undefined, motivo: motivo||undefined})`; `onSucesso(vereadorId)`.

- [ ] **Step 6: Wire the page (`page.tsx`).** Concrete edits:

1. Add imports: the 4 form components, `useLegislaturaVigente`, and remove the now-unused `EmBreve` import if no longer referenced.
2. Add state near the other `useState`s:
   ```tsx
   const [versao, setVersao] = useState(0);
   const [painel, setPainel] = useState<null | "novo" | "editar" | "mandato" | "licenca">(null);
   ```
3. Thread `versao` into the read hooks: `useVereadores(token, versao)` and `useVereadorFicha(token, efetivoId, versao)`.
4. Add the legislatura hook: `const { dados: legislaturaVigente } = useLegislaturaVigente(token);`
5. Success handler:
   ```tsx
   function aoConcluir(id: string) {
     setPainel(null);
     setSelecionadoManual(id);       // seleciona o vereador afetado
     setVersao((v) => v + 1);        // dispara refetch de lista + ficha
   }
   ```
6. Replace the header "Novo vereador" disabled button + its `EmBreve` block with an enabled button `onClick={() => setPainel("novo")}`, and conditionally render `<NovoVereadorForm token={token} onSucesso={aoConcluir} onCancelar={() => setPainel(null)} />` when `painel === "novo"` (e.g. in a panel below the page header).
7. In the ficha `ficha-acoes` block, replace the 3 disabled buttons + the `#vereador-acoes-em-breve` `EmBreve` with:
   - "Editar cadastro" → `onClick={() => setPainel("editar")}` (always enabled).
   - "Registrar mandato" → `onClick={() => setPainel("mandato")}` (always enabled).
   - "Registrar licença" → enabled ONLY when `ficha.mandato?.estado === "vigente"`; otherwise `disabled` with `title="Requer um mandato vigente."`.
   - Below the actions, render the matching form when `painel` is `"editar" | "mandato" | "licenca"`, passing `vereadorId={ficha.id}`, `inicial`/`legislatura` as needed, `onSucesso={aoConcluir}`, `onCancelar={() => setPainel(null)}`.
   - Keep the "Ver proposições" button as-is (still deferred) with its own single `EmBreve` note.
8. When the selected vereador changes (`efetivoId`), close any open ficha panel — add `useEffect(() => setPainel((p) => (p === "novo" ? p : null)), [efetivoId]);` so switching vereadores doesn't leave a stale edit/licença/mandato form open.

- [ ] **Step 7: Add CSS** to `cadastro-vereadores.css` for `.form-cad`, `.campo`, `.campo-erro`, `.form-acoes` — reuse chassi tokens; the `.campo-erro` text uses `--aviso-texto` (AA amber), inputs get a `≥3:1` border. Keep it minimal (the forms reuse `.btn`/existing patterns).

- [ ] **Step 8: Extend `page.test.tsx`.** Add cases (the file already mocks `global.fetch` + `next/navigation`; extend the fetch mock to answer the new endpoints by method+path):
   - Opening "Novo vereador" reveals the form; submitting a valid name POSTs `/api/cadastros/vereadores` and triggers a list refetch (fetch called again for the list).
   - Submit is disabled when the name is blank (after touch).
   - In the ficha, "Registrar licença" is disabled when the mandato is not `vigente` and enabled when it is.
   - A server 409 on mandato surfaces an inline alert (no crash).

- [ ] **Step 9: Run the page test + lint + typecheck — expect PASS/clean**

Run:
```bash
docker compose exec frontend npx vitest run "src/app/(interno)/cadastros/vereadores/page.test.tsx"
docker compose exec frontend npm run lint
docker compose exec frontend npx tsc --noEmit
```
Expected: tests PASS; lint and tsc clean.

- [ ] **Step 10: Commit**

```bash
git add apps/frontend/src/lib/use-vereadores.ts apps/frontend/src/lib/use-vereador-ficha.ts \
        "apps/frontend/src/app/(interno)/cadastros/vereadores/" apps/frontend/src/lib/use-vereadores.test.ts
git commit -m "feat(fe): forms de escrita na página de vereadores (novo/editar/mandato/licença) + refetch"
```

---

## Task 10: Accessibility verification (both themes) + live proof

**Files:** none (verification task; fixes, if any, go to the touched form/CSS files).

**Interfaces:** Consumes the running app (backend + frontend containers up).

- [ ] **Step 1: Invoke the `independent-accessibility-verification` skill.** Do NOT self-report ratios from memory — measure.

- [ ] **Step 2: Drive the app** (Chrome automation or ecc chrome-devtools). Log in as `secretario` (dev `?token=` bypass or the seeded ator), go to `/cadastros/vereadores`. Exercise: open "Novo vereador", create one; open "Editar", "Registrar mandato", "Registrar licença" on a vereador with a vigente mandato; confirm the ficha chip flips to "Licença" after a licença.

- [ ] **Step 3: Measure contrast pixel-composite in BOTH themes** (light + dark, one theme per pass with a flush between — the 2nd clean pass removes the stale-bg artifact). Targets: `.campo-erro` amber text (must be `--aviso-texto`, ≥4.5:1), input borders (≥3:1), the licença chip amber (§5.1), primary/ghost buttons, disabled "Registrar licença" state. Record each measured ratio.

- [ ] **Step 4: Verify keyboard + SR semantics:** each field has a `<label htmlFor>`; errors are `role="alert"` + `aria-describedby`; the disabled licença button explains why (`title`/`aria-describedby`); focus order through the form is sane; the panel is reachable and dismissible by keyboard.

- [ ] **Step 5: Fix any AA/a11y miss** in the form components / `cadastro-vereadores.css`, re-measure, and note the final ratios in the commit message.

- [ ] **Step 6: Commit (if fixes were needed)**

```bash
git add "apps/frontend/src/app/(interno)/cadastros/vereadores/"
git commit -m "fix(fe): AA medida nos 2 temas nos forms de cadastro (contraste + a11y)"
```

- [ ] **Step 7: Full-suite sanity** — run the backend suite once (`clojure -M:test --skip :e2e`, expect only the known outbox flake if the app container is live) and the FE suite (`docker compose exec frontend npm test`), confirm green before finishing the branch.

---

## Self-review

**Spec coverage:**
- Criar vereador → Tasks 2 (adapter), 4 (reuse `criar-vereador!`), 5 (controller), 6 (route), 8/9 (FE). ✓
- Editar vereador → Tasks 2, 3 (`atualizar!`), 4, 5, 6, 8/9. ✓
- Registrar mandato (+ overlap 409) → Tasks 1 (EXCLUDE), 2, 3 (`mandato-sobreposto?`), 4 (`registrar-mandato!`), 6, 8/9. ✓
- Registrar licença (record-only, 1 tx, flip estado) → Tasks 3 (`mandato-vigente-de-vereador`), 4 (`registrar-licenca!`), 6, 8/9. ✓
- HTTP surface (4 routes, gates, status codes, `hoje` at border) → Task 6. ✓
- Migration EXCLUDE + seed check → Task 1. ✓
- Invariants (RLS, no DELETE, `efetivado_em=now()`, `secretario` gate) → Global Constraints + enforced per task. ✓
- Tests by silhueta (db / controllers / diplomat / FE) → Tasks 1,3,4 (db+repo), 5 (controllers), 2,6 (adapters/borda), 7,8,9 (FE). ✓
- FE forms + view-models + mutation hooks + AA → Tasks 7,8,9,10. ✓
- Legislatura selector for the mandato form → resolved via a minimal `GET /cadastros/legislatura-vigente` (Task 6) + `use-legislatura-vigente` (Task 8). **Deviation from the spec's 4-route table, documented:** the spec lists only the 4 write routes, but the mandato form needs a legislatura to reference and the ficha's `MandatoVigenteOut` omits `legislatura-id`; a single read of the vigente legislatura is the smallest in-scope way to make the write usable (YAGNI: no full legislaturas list). Flag at the first review checkpoint.

**Deviations from the spec's suggested layout (intentional, to avoid restructuring):**
- Mandato write fns stay in `db/vereador.clj` (they already live there) instead of a new `db/mandato.clj`.
- FE hook states use `"ocioso"` (codebase convention) not the spec's `"pronto"` wording.

**Placeholder scan:** the only intentionally-scaffolded block is Task 4 Step 1 (repo test), which the step text explicitly directs to be replaced with the four named deftests before running — it is not left as a silent TODO. All code steps carry real code.

**Type consistency:** `{:id ...}` return shape is uniform across controller/handler/hook; `:conflito/mandato-sobreposto` and `:conflito/sem-mandato-vigente` are the exact keywords thrown in Task 4 and matched in Task 6; `atualizar!` returns an `update-count` consumed as `pos?`/`= 0` in Task 5/6; `mandato-vigente-de-vereador`/`mandato-sobreposto?` signatures match their callers in Task 4.
