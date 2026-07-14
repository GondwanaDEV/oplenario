# Cadastro de Vereadores (read-only master-detail) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the vereadores master-detail screen against real data, birthing the first HTTP edge on the `cadastros` module (read-only; writes deferred).

**Architecture:** Backend adds a read border to `cadastros` following silhueta ADR-0001 (`db` queries → Repo methods → `controllers` → `wire/out` + `adapters/out` → `diplomat/http/in` route table → registered in `rotas.clj/montar`). Two endpoints: `GET /cadastros/vereadores` (list) and `GET /cadastros/vereadores/:id` (ficha aggregate). Frontend adds a `(interno)/cadastros/vereadores` route: pure view-model + two hooks + a master-detail page, mirroring the `tramitacao-board` vertical.

**Tech Stack:** Clojure (HoneySQL, next.jdbc, Malli, Pedestal, Stuart Sierra Component), Next.js 16 / React 19 / TS, vitest.

## Global Constraints

- **Read-only slice.** No migration, no write path. All 5 tables already exist (migration 0010).
- **Silhueta ADR-0001:** `wire/in`·`wire/out`, adapters in/out, `diplomat/http/in` route table, Repo-Component; no `port/`, no ORM. `cadastros` never imports another module (§22.10); cross-module facts arrive injected by the host.
- **Tenant by RLS.** Every db query filters via the tenant tx (`RepoCadastros/transacao`); JOINs also match `ente_id` (defence in depth, mirror `relacoes/cadastro`).
- **Gate:** papel `secretario` (`it/exige-papel "secretario"`), same as every internal read.
- **404 uniforme:** an id that doesn't exist OR belongs to another tenant → 404 (never leaks existence).
- **Portuguese** in code comments/copy; kebab-case on the wire (jsonista).
- **Adapters/out validate against the wire schema** (`m/validate` → throw on drift, mirror `adapters/out/proposicao`). Drift = 500, never a malformed body that poisons the codegen.
- **AA in both themes**, measured pixel-composite (`GUIDELINES-CHECKLIST.md`; licença chip amber → `--aviso-texto`).
- **Test DB env (do NOT use `oplenario_pool`):** fixtures call `migracao/migrar!` which needs the OWNER role `oplenario`; MinIO `MINIO_ENDPOINT=http://localhost:9100 MINIO_ACCESS_KEY=oplenario MINIO_SECRET_KEY=dev12345`. Run backend tests via an ephemeral Clojure container on the same docker network (see [[oplenario-c3-execucao]]), never `clojure` on the host.

---

## File map

**Backend (all under `apps/backend/src/oplenario/cadastros/`):**
- Modify `db/vereador.clj` — add `listar` + `mandato-vigente` (pick current mandato covering a date).
- Modify `db/comissao.clj` — add `comissoes-do-vereador`.
- Modify `components/repositorio.clj` — add protocol methods `listar-vereadores`, `ficha-vereador`.
- Create `controllers.clj` — compose Repo reads into domain maps (list, ficha).
- Create `wire/out/vereador.clj` — `VereadorLinhaOut`, `VereadorFichaOut`.
- Create `adapters/out/vereador.clj` — validated `models → wire`.
- Create `diplomat/http/in.clj` — `rotas` fn (2 GET routes + handlers).
- Modify `../rotas.clj` — `(into (cadastros-http/rotas {...}))` in `montar`.
- Create `codegen/gerar_cadastros.clj` — emits the TS contract.

**Frontend (all under `apps/frontend/src/`):**
- Create `lib/contrato-cadastros.gen.ts` — generated types.
- Create `lib/cadastro-vereadores-vista.ts` (+ `.test.ts`) — pure view-model.
- Create `lib/use-vereadores.ts`, `lib/use-vereador-ficha.ts` (+ tests) — hooks.
- Create `app/(interno)/cadastros/vereadores/page.tsx` + `cadastro-vereadores.css`.
- Modify `app/(interno)/layout.tsx` (or `topo.tsx`) — add "Vereadores" nav entry.

---

## Task 1: db query `listar` + `mandato-vigente` (vereador list read)

**Files:**
- Modify: `apps/backend/src/oplenario/cadastros/db/vereador.clj`
- Test: `apps/backend/test/oplenario/cadastros/db/vereador_test.clj` (create if absent)

**Interfaces:**
- Produces: `(vereador/listar tx ente-id data)` → seq of `{:id :nome :nome-parlamentar :partido :estado-mandato :cargo-mesa}` (kebab, uuid/enum passthrough); `(vereador/mandato-vigente tx ente-id vereador-id data)` → the current mandato map covering `data` or nil.

- [ ] **Step 1: Write the failing test** — seed 1 ente, 1 legislatura, 2 vereadores (one with vigente mandato + partido, one licenciado), assert `listar` returns both ordered by nome with correct `estado-mandato`/`partido`, and a vereador with no mandato appears with `estado-mandato` nil.

```clojure
(deftest listar-devolve-vereadores-com-mandato-vigente
  (with-open [_ (fixtures/tenant! ente-id)]
    (jdbc/with-transaction [tx ds]
      (tenancy/set-tenant! tx ente-id)
      ;; ... seed vereador A (vigente, PT), vereador B (licenciado, PSDB), vereador C (sem mandato)
      (let [rows (vereador/listar tx ente-id hoje)]
        (is (= ["Ana" "Bruno" "Carla"] (map :nome rows)))          ; ordenado por nome
        (is (= "vigente"    (:estado-mandato (first rows))))
        (is (= "PT"         (:partido (first rows))))
        (is (= "licenciado" (:estado-mandato (second rows))))
        (is (nil? (:estado-mandato (nth rows 2))))))))             ; sem mandato -> nil, mas aparece
```

- [ ] **Step 2: Run test to verify it fails** — Expected: FAIL, `listar` unresolved.

- [ ] **Step 3: Implement** `mandato-vigente` and `listar` in `db/vereador.clj`. `mandato-vigente` selects the mandato covering `data` by vigência (any estado — a licenciado is still the current mandato), most recent first. `listar` LEFT JOINs that mandato + the mesa cargo. Mirror the `relacoes/cadastro` JOIN idiom (match `ente_id`).

```clojure
(defn mandato-vigente
  "O mandato do vereador que COBRE `data` pela vigencia (qualquer estado — licenciado ainda e' o corrente).
   O mais recente se houver mais de um. nil se nenhum cobre `data`."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :legislatura_id :partido :estado :natureza
                            :vigencia_inicio :vigencia_fim :fim_efetivo]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc]] :limit 1}))))

(defn listar
  "Lista de vereadores da Casa com o mandato que cobre `data` (partido/estado) e o cargo na Mesa vigente.
   Vereador sem mandato corrente aparece so' com o nome (estado-mandato/partido nil). Ordena por nome."
  [tx ente-id data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format
        {:select [:v.id :v.nome :v.nome_parlamentar :m.partido
                  [:m.estado :estado_mandato] [:cc.cargo :cargo_mesa]]
         :from [[:cadastros.vereador :v]]
         :left-join [[:cadastros.mandato :m]
                     [:and [:= :m.vereador_id :v.id] [:= :m.ente_id :v.ente_id]
                      [:<= :m.vigencia_inicio data]
                      [:or [:is :m.vigencia_fim nil] [:>= :m.vigencia_fim data]]]
                     [:cadastros.comissao :mesa]
                     [:and [:= :mesa.tipo "mesa"] [:= :mesa.ente_id :v.ente_id]
                      [:<= :mesa.vigencia_inicio data]
                      [:or [:is :mesa.vigencia_fim nil] [:>= :mesa.vigencia_fim data]]]
                     [:cadastros.comissao_cargo :cc]
                     [:and [:= :cc.comissao_id :mesa.id] [:= :cc.vereador_id :v.id] [:= :cc.ente_id :v.ente_id]
                      [:<= :cc.vigencia_inicio data]
                      [:or [:is :cc.vigencia_fim nil] [:>= :cc.vigencia_fim data]]]]
         :where [:= :v.ente_id ente-id]
         :order-by [[:v.nome :asc]]}))))
```

- [ ] **Step 4: Run test to verify it passes.**
- [ ] **Step 5: Commit** — `feat(cadastros): db/vereador listar + mandato-vigente (read)`.

---

## Task 2: db query `comissoes-do-vereador` + Repo aggregate methods

**Files:**
- Modify: `apps/backend/src/oplenario/cadastros/db/comissao.clj`
- Modify: `apps/backend/src/oplenario/cadastros/components/repositorio.clj`
- Test: `apps/backend/test/oplenario/cadastros/db/comissao_test.clj` + `.../components/repositorio_test.clj`

**Interfaces:**
- Consumes: `vereador/listar`, `vereador/mandato-vigente` (Task 1), `estrutura/buscar-legislatura`.
- Produces: `(comissao/comissoes-do-vereador tx ente-id vereador-id data)` → seq `{:nome :tipo :cargo}` (cargo nil unless named); Repo `(listar-vereadores repo ente-id data)` → Task-1 rows; Repo `(ficha-vereador repo ente-id id data)` → `{:vereador {...} :mandato {...}|nil :legislatura {...}|nil :comissoes [...]}` or nil if vereador not found.

- [ ] **Step 1: Write the failing db test** — seed vereador in 2 comissões (one as presidente via `comissao_cargo`), assert `comissoes-do-vereador` returns both with the right `cargo` (presidente / nil).

```clojure
(deftest comissoes-do-vereador-inclui-cargo-nomeado
  ;; seed comissao CJ (membro + cargo presidente) e comissao Educacao (membro so')
  (let [cs (comissao/comissoes-do-vereador tx ente-id ver-id hoje)]
    (is (= #{"Constituição e Justiça" "Educação"} (set (map :nome cs))))
    (is (= "presidente" (:cargo (first (filter #(= "Constituição e Justiça" (:nome %)) cs)))))
    (is (nil? (:cargo (first (filter #(= "Educação" (:nome %)) cs)))))))
```

- [ ] **Step 2: Run — Expected FAIL.**

- [ ] **Step 3: Implement** `comissoes-do-vereador` in `db/comissao.clj` (membro ⋈ comissao, LEFT JOIN cargo of same vereador+comissão vigente):

```clojure
(defn comissoes-do-vereador
  "Comissoes vigentes em `data` de que o vereador e' membro, com o cargo nomeado (se houver)."
  [tx ente-id vereador-id data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format
        {:select [:c.nome :c.tipo [:cc.cargo :cargo]]
         :from [[:cadastros.comissao_membro :cm]]
         :join [[:cadastros.comissao :c] [:and [:= :c.id :cm.comissao_id] [:= :c.ente_id :cm.ente_id]]]
         :left-join [[:cadastros.comissao_cargo :cc]
                     [:and [:= :cc.comissao_id :cm.comissao_id] [:= :cc.vereador_id :cm.vereador_id]
                      [:= :cc.ente_id :cm.ente_id]
                      [:<= :cc.vigencia_inicio data]
                      [:or [:is :cc.vigencia_fim nil] [:>= :cc.vigencia_fim data]]]]
         :where [:and [:= :cm.ente_id ente-id] [:= :cm.vereador_id vereador-id]
                 [:<= :cm.vigencia_inicio data]
                 [:or [:is :cm.vigencia_fim nil] [:>= :cm.vigencia_fim data]]]
         :order-by [[:c.nome :asc]]}))))
```

- [ ] **Step 4:** Add the two Repo protocol methods + record impls in `components/repositorio.clj`. `listar-vereadores` delegates to `vereador/listar`; `ficha-vereador` composes in ONE tx (mirror `ficha-completa-da-proposicao`):

```clojure
;; protocol
(listar-vereadores [this ente-id data])
(ficha-vereador   [this ente-id id data])

;; record
(listar-vereadores [this ente-id data] (transacao this ente-id #(vereador/listar % ente-id data)))
(ficha-vereador [this ente-id id data]
  (transacao this ente-id
    (fn [tx]
      (when-let [v (vereador/buscar tx id)]
        (let [m (vereador/mandato-vigente tx ente-id id data)
              leg (when (:legislatura-id m) (estrutura/buscar-legislatura tx (:legislatura-id m)))
              cs (comissao/comissoes-do-vereador tx ente-id id data)]
          {:vereador v :mandato m :legislatura leg :comissoes cs})))))
```

- [ ] **Step 5:** Write a Repo test (`ficha-vereador` returns nil for unknown id; returns composed map for a seeded vereador). Run both test files — Expected PASS.
- [ ] **Step 6: Commit** — `feat(cadastros): comissoes-do-vereador + Repo listar/ficha aggregate`.

---

## Task 3: wire/out + adapters/out (the read contract)

**Files:**
- Create: `apps/backend/src/oplenario/cadastros/wire/out/vereador.clj`
- Create: `apps/backend/src/oplenario/cadastros/adapters/out/vereador.clj`
- Test: `apps/backend/test/oplenario/cadastros/adapters/out/vereador_test.clj`

**Interfaces:**
- Consumes: Repo shapes from Task 2.
- Produces: `wire/VereadorLinhaOut`, `wire/VereadorFichaOut`; `(adapters/lista->wire rows)`, `(adapters/ficha->wire ficha)`.

- [ ] **Step 1: Write the failing test** — a domain ficha map projects to `VereadorFichaOut` and validates; a row with nil partido/estado projects to `VereadorLinhaOut`; a malformed projection throws.

```clojure
(deftest ficha->wire-projeta-e-valida
  (is (m/validate wire/VereadorFichaOut
                  (adapters/ficha->wire {:vereador {:id id :nome "Ana" :nome-parlamentar nil}
                                         :mandato {:partido "PT" :estado "vigente" :natureza "titular"
                                                   :vigencia-inicio (LocalDate/parse "2025-01-01")}
                                         :legislatura {:numero 19 :ano-inicio 2025 :ano-fim 2028}
                                         :comissoes [{:nome "CJ" :tipo "permanente" :cargo "presidente"}]}))))
```

- [ ] **Step 2: Run — Expected FAIL.**

- [ ] **Step 3: Implement** `wire/out/vereador.clj` (schemas, `:closed true`, `estado` stays `:string` — no closed enum, mirror proposicao rationale; dates as ISO string):

```clojure
(def VereadorLinhaOut
  [:map {:closed true}
   [:id :string] [:nome :string]
   [:nome-parlamentar {:optional true} [:maybe :string]]
   [:partido {:optional true} [:maybe :string]]
   [:estado-mandato {:optional true} [:maybe :string]]
   [:cargo-mesa {:optional true} [:maybe :string]]])

(def ComissaoDoVereadorOut
  [:map {:closed true}
   [:nome :string] [:tipo :string]
   [:cargo {:optional true} [:maybe :string]]])

(def MandatoVigenteOut
  [:map {:closed true}
   [:partido {:optional true} [:maybe :string]]
   [:estado :string] [:natureza :string]
   [:posse :string]                                   ; vigencia-inicio ISO
   [:legislatura-numero {:optional true} [:maybe :int]]
   [:legislatura-ano-inicio {:optional true} [:maybe :int]]
   [:legislatura-ano-fim {:optional true} [:maybe :int]]
   [:cargo-mesa {:optional true} [:maybe :string]]])

(def VereadorFichaOut
  [:map {:closed true}
   [:id :string] [:nome :string]
   [:nome-parlamentar {:optional true} [:maybe :string]]
   [:mandato {:optional true} [:maybe MandatoVigenteOut]]  ; nil se sem mandato corrente
   [:comissoes [:sequential ComissaoDoVereadorOut]]])
```

- [ ] **Step 4: Implement** `adapters/out/vereador.clj` with the `validado` guard pattern from `adapters/out/proposicao` (throw ex-info on `m/validate` failure). `cargo-mesa` on the ficha's `MandatoVigenteOut` is derived from the ficha `comissoes` (the mesa entry's cargo) OR carried on the mandato — pick: read it from `listar` for the list; for the ficha, derive from `comissoes` where `tipo="mesa"`. Keep one source: compute `cargo-mesa` in the adapter from `comissoes`.
- [ ] **Step 5: Run — Expected PASS. Commit** — `feat(cadastros): wire/out + adapters/out vereador (read contract)`.

---

## Task 4: controllers (domain composition)

**Files:**
- Create: `apps/backend/src/oplenario/cadastros/controllers.clj`
- Test: `apps/backend/test/oplenario/cadastros/controllers_test.clj`

**Interfaces:**
- Consumes: `RepoCadastros/listar-vereadores`, `/ficha-vereador`.
- Produces: `(controllers/listar-vereadores repo ente-id data)` → domain list; `(controllers/ficha-vereador repo ente-id id data)` → domain ficha or nil.

- [ ] **Step 1: Write the failing test** with a fake Repo (reify `RepoCadastros`) — `ficha-vereador` returns nil when the Repo returns nil; returns the composed map otherwise. `listar-vereadores` passes through.
- [ ] **Step 2: Run — Expected FAIL.**
- [ ] **Step 3: Implement** thin controllers (read slice: they just call the Repo; the aggregation already lives in the Repo tx). Keep the ns so the diplomat depends on `controllers`, never the Repo protocol directly (ADR layering).

```clojure
(ns oplenario.cadastros.controllers
  (:require [oplenario.cadastros.components.repositorio :as repo]))
(defn listar-vereadores [r ente-id data] (repo/listar-vereadores r ente-id data))
(defn ficha-vereador   [r ente-id id data] (repo/ficha-vereador r ente-id id data))
```

- [ ] **Step 4: Run — Expected PASS. Commit** — `feat(cadastros): controllers de leitura de vereador`.

---

## Task 5: diplomat/http/in route table + register in rotas.clj

**Files:**
- Create: `apps/backend/src/oplenario/cadastros/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj` (require + `(into (cadastros-http/rotas {...}))`)
- Test: `apps/backend/test/oplenario/cadastros/diplomat/http/in_test.clj` (route-level, against Postgres via the test system)

**Interfaces:**
- Consumes: `controllers`, `adapters/out`, `it/exige-papel`, `http/json-resposta`.
- Produces: `(cadastros-http/rotas {:auth auth :repo-cadastros repo :relogio relogio})` → set of Pedestal route vectors.

- [ ] **Step 1: Write the failing test** — via the test HTTP system (mirror an existing `diplomat/http/in_test`): `GET /cadastros/vereadores` with a `secretario` actor → 200 + `{:itens [...]}` (or a bare list — match the wire envelope decided below); with a non-secretario actor → 403; `GET /cadastros/vereadores/:unknown-id` → 404. Seed via demo helper.

- [ ] **Step 2: Run — Expected FAIL.**

- [ ] **Step 3: Implement** `diplomat/http/in.clj`. `data` (LocalDate `hoje`) is resolved AT THE BORDER from the injected `relogio` (mirror `meu-voto-handler`'s `tempo/hoje-de` + `zona-civil "America/Fortaleza"`), never read in the controller. The list response envelope is `{:vereadores [VereadorLinhaOut ...]}` (a closed map, room to add metadata later — decide and keep consistent with the adapter).

```clojure
(defn- listar-handler [repo relogio]
  (fn [req]
    (let [ente-id (get-in req [:ator :ente-id])
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)]
      (http/json-resposta 200 {:vereadores (adapters/lista->wire (controllers/listar-vereadores repo ente-id hoje))}))))

(defn- ficha-handler [repo relogio]
  (fn [req]
    (let [ente-id (get-in req [:ator :ente-id])
          id (parse-uuid (get-in req [:path-params :id]))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)]
      (if-let [f (and id (controllers/ficha-vereador repo ente-id id hoje))]
        (http/json-resposta 200 (adapters/ficha->wire f))
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

(defn rotas [{:keys [auth repo-cadastros relogio]}]
  #{["/cadastros/vereadores"     :get [auth (it/exige-papel "secretario") (listar-handler repo-cadastros relogio)]
     :route-name :cadastros-vereadores-listar]
    ["/cadastros/vereadores/:id" :get [auth (it/exige-papel "secretario") (ficha-handler repo-cadastros relogio)]
     :route-name :cadastros-vereador-ficha]})
```

- [ ] **Step 4: Register** in `rotas.clj/montar`: add `[oplenario.cadastros.diplomat.http.in :as cadastros-http]` to `:require`, and `(into (cadastros-http/rotas {:auth auth :repo-cadastros repo-cadastros :relogio relogio-producao}))` in the `->` threading (repo-cadastros is already a `montar` param).
- [ ] **Step 5: Run** the route test + full backend suite — Expected PASS (mind the known `outbox-relay-test` flake if `oplenario-app-1` is up).
- [ ] **Step 6: Commit** — `feat(cadastros): borda HTTP GET /cadastros/vereadores (+ ficha), gate secretario`.

---

## Task 6: codegen → contrato-cadastros.gen.ts

**Files:**
- Create: `apps/backend/src/oplenario/codegen/gerar_cadastros.clj` (mirror `gerar_legislativo.clj`)
- Create (generated): `apps/frontend/src/lib/contrato-cadastros.gen.ts`

**Interfaces:**
- Produces: TS interfaces `VereadorLinhaOut`, `VereadorFichaOut`, `MandatoVigenteOut`, `ComissaoDoVereadorOut` for the FE.

- [ ] **Step 1:** Write `gerar_cadastros.clj` — a manifesto listing the wire/out schemas, calling `malli-ts` like `gerar_legislativo`. (No test — it's a generator; the FE `tsc` is the check.)
- [ ] **Step 2: Run** `clojure -M -m oplenario.codegen.gerar-cadastros apps/frontend/src/lib/contrato-cadastros.gen.ts` (from `apps/backend/`). Verify the file has all 4 interfaces + the list envelope type.
- [ ] **Step 3: Commit** — `feat(cadastros): codegen contrato-cadastros.gen.ts`.

---

## Task 7: FE view-model `cadastro-vereadores-vista.ts`

**Files:**
- Create: `apps/frontend/src/lib/cadastro-vereadores-vista.ts`
- Test: `apps/frontend/src/lib/cadastro-vereadores-vista.test.ts`

**Interfaces:**
- Consumes: `VereadorLinhaOut` (Task 6).
- Produces: `avatar(nome): {iniciais, cor}` (deterministic); `estadoChip(estado): {rotulo, tom}` (`vigente`→ativo/verde, `licenciado`→licença/âmbar, else→neutro honesto, catch-all fail-closed); `filtrar(linhas, busca): linhas`; `selecaoInicial(linhas, urlId): id|null`.

- [ ] **Step 1: Write failing tests** — determinism (same nome → same cor across calls), estado catch-all (`"cassado"`→neutro rotulo, not crash; `null`→neutro), busca case-insensitive over nome/nome-parlamentar/partido, `selecaoInicial` returns urlId if present in list else first row else null.
- [ ] **Step 2: Run — Expected FAIL.**
- [ ] **Step 3: Implement** the pure functions. Palette = the source screen's `--jade`/`--cobalto`/`--telha`/`--jade-claro`/`#7A4FA0`/`--cobalto-fundo`/`#9C6B1E`; index by a stable charCode sum of the id (or nome) mod palette length.
- [ ] **Step 4: Run — Expected PASS. Commit** — `feat(fe): cadastro-vereadores view-model puro`.

---

## Task 8: FE hooks `use-vereadores` + `use-vereador-ficha`

**Files:**
- Create: `apps/frontend/src/lib/use-vereadores.ts` (+ `.test.ts`)
- Create: `apps/frontend/src/lib/use-vereador-ficha.ts` (+ `.test.ts`)

**Interfaces:**
- Consumes: the app's `apiFetch` boundary + `contrato-cadastros.gen.ts`.
- Produces: `useVereadores(): {estado, dados}` (list; loading/erro/ok); `useVereadorFicha(id): {estado, dados}` (fetch-on-id; resets on id change; degrades independently).

- [ ] **Step 1: Write failing tests** — mirror `use-tramitacao-board.test.ts` / `use-mesa`. Assert: loading→ok path, erro path (fetch rejects → `estado:'erro'`, not thrown), `useVereadorFicha(null)` stays idle, changing id refetches and resets prior data.
- [ ] **Step 2: Run — Expected FAIL.**
- [ ] **Step 3: Implement** both hooks mirroring `use-tramitacao-board` (boundary kebab→camel already handled by the shared `apiFetch`/`boundary`; confirm against `use-mesa`). `useVereadorFicha` keyed on id, reset-on-change.
- [ ] **Step 4: Run — Expected PASS. Commit** — `feat(fe): hooks use-vereadores + use-vereador-ficha`.

---

## Task 9: FE page (master-detail) + nav + CSS

**Files:**
- Create: `apps/frontend/src/app/(interno)/cadastros/vereadores/page.tsx`
- Create: `apps/frontend/src/app/(interno)/cadastros/vereadores/cadastro-vereadores.css`
- Modify: `apps/frontend/src/app/(interno)/layout.tsx` (or `topo.tsx`) — add "Vereadores" nav link
- Test: `apps/frontend/src/app/(interno)/cadastros/vereadores/page.test.tsx`

**Interfaces:**
- Consumes: Tasks 7–8.

- [ ] **Step 1: Write a failing page test** — renders the list from a mocked hook; selecting a row updates `?v=`; the ficha renders the selected vereador's mandato + comissões; deferred sections render `EmBreve` (not fabricated numbers); the CTA buttons (Novo/Editar/Licença) are disabled/`EmBreve`.
- [ ] **Step 2: Run — Expected FAIL.**
- [ ] **Step 3: Implement** the page: master `role="listbox"` (roving tabindex, arrows+Enter, `aria-current`; reuse the ficha-materia tab keyboard pattern), detail ficha (avatar, chip via `estadoChip`, stats row with real comissões count + `EmBreve` for proposições/presença, Mandato block, Comissões tags with presidente highlighted, Contato = `EmBreve`). Selection in `?v=` via `useSearchParams`/`router.replace` (no history spam). Port the CSS from `cadastro-vereadores.html` into the route-local `.css` (project's per-route CSS discipline — include the azulejo/chip rules verbatim if reused). Add the "Vereadores" nav entry.
- [ ] **Step 4: Run** `pnpm test` + `tsc` + `eslint` + `next build` — Expected PASS/clean.
- [ ] **Step 5: Commit** — `feat(fe): pagina cadastro de vereadores (master-detail, read-only)`.

---

## Task 10: Live verification (real stack, both themes) + AA

**Files:** none (verification only; fixes land as follow-up commits if needed).

- [ ] **Step 1:** Ensure a seed with vereadores exists (the dev DB already has vínculos; if the ficha needs comissão membership, extend `demo/seed_demo.clj` with a comissão + membro + cargo for one seeded vereador — commit any seed addition separately).
- [ ] **Step 2:** Bring up the real stack (`docker compose up -d --build`), rebuild the `app` container so the new routes are live (backend has no hot-reload).
- [ ] **Step 3:** Drive the page in the browser (claude-in-chrome), both themes: list renders real vereadores → select a row → ficha shows real mandato + comissões → deferred sections show honest `EmBreve`. Screenshot both themes.
- [ ] **Step 4:** Measure AA contrast pixel-composite in both themes (`GUIDELINES-CHECKLIST.md` §5.1 — licença chip amber uses `--aviso-texto`; the `aria-current` selected-row inset bar). Fix any miss, commit.
- [ ] **Step 5:** Final whole-branch review (`ecc`: clojure + database + security on the backend edge; react + security on the FE) before merge. Address findings with regression tests.

---

## Self-review notes (coverage)

- Spec §2.1 list read → Tasks 1, 3, 4, 5. §2.2 ficha aggregate → Tasks 2, 3, 4, 5. §2.3 wiring+codegen → Tasks 5, 6.
- Spec §3 FE (view-model/hooks/page/nav) → Tasks 7, 8, 9. §4 data flow (URL selection, default row, client-side busca) → Tasks 7, 9. §5 error/border states → Tasks 8, 9. §6 tests/live → every task + Task 10.
- Spec §7 deferrals: stats/contato/actions render `EmBreve` (Task 9); busca client-side, no `?busca=` param (Tasks 5, 7); no migration/writes (Global Constraints).
- Cross-task type consistency: `estado-mandato`/`partido`/`cargo-mesa` (Task 1) → `VereadorLinhaOut` (Task 3) → view-model (Task 7). `ficha-vereador` shape (Task 2) → `VereadorFichaOut` (Task 3). `cargo-mesa` on the ficha derived from `comissoes` in the adapter (Task 3) — single source, noted.
</content>
