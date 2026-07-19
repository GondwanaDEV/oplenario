# Onda E — Inbox interno de notificações (fatia 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um vereador autor de uma proposição que virou lei vê, na inbox do app dele, a notificação "a sua proposição virou lei" — e pode marcá-la como lida.

**Architecture:** `legislativo` passa a ser **segundo consumidor do próprio `norma.publicada`**: dentro da tx do relay resolve `proposicao → autor_id` (same-schema) e `vereador → identidade` por **resolvedor injetado pelo host**, e emite `notificacao.requisitada` com `canal "in_app"`. `paineis` ganha um **segundo consumidor** desse mesmo evento que projeta numa tabela nova `paineis.notificacao_caixa` (o ledger de e-mail existente ganha uma guarda de canal e ignora `in_app`). A borda de leitura/escrita é `/meu/notificacoes` + `/meu/notificacoes/:id/lida`, sempre escopada pela identidade do `(:ator req)`.

**Tech Stack:** Clojure (HoneySQL, next.jdbc, Malli, Pedestal, Component, Migratus, kaocha); PostgreSQL 16 com RLS; Next.js 16 / React 19 / TypeScript; vitest.

**Spec (aprovada):** `docs/superpowers/specs/2026-07-19-onda-e-inbox-notificacoes-design.md` (commit `0430f85`).
**Branch:** `onda-e-inbox-notificacoes` (já criada, off `main`).

---

## Global Constraints

Valem para **todas** as tasks. Não repetidas dentro de cada uma.

- **Mandato Docker — nunca rodar `clojure`, `node` ou `npm` direto no host.** Backend em container efêmero; frontend via `docker exec` no container de pé.

  **Testes do backend** (rodar da **raiz do repo**; 1º run baixa o `.m2`, ~2min — usar timeout alto):
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
  A suíte inteira: trocar `--focus <ns>` por `--skip :e2e --skip :keycloak`.
  Lint (baseline medido em 6fd68c2: errors 0, warnings 30 — julgar por DELTA, não por zero absoluto): trocar o comando final por `clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2026.05.25"}}}' -M -m clj-kondo.main --lint src test demo`.
  As fixtures chamam `migracao/migrar!`, que exige o role OWNER `oplenario` (é o da `DATABASE_URL` acima), **não** `oplenario_pool`.

  **Testes do frontend:** `docker exec oplenario-frontend-1 npx vitest run <arquivo>`.
  **Nunca** rodar a suíte FE inteira de uma vez (leva 7+ min e já travou agentes) — sempre arquivo a arquivo.

- **Stack de pé antes de qualquer teste de integração:** `cd apps/backend && docker compose up -d`. Portas deste box (via `.env`): Postgres **5544**, MinIO **9100**, frontend **3000**, backend **8888**. Conferir a rede com `docker network ls | grep oplenario_default`.
- **Nunca mutar o mount vivo.** Containers efêmeros montam o repo em `/app`; nada de `npm install`/`npm ci` que escreva em `apps/frontend/node_modules` do mount — isso já derrubou o frontend deste projeto uma vez.
- **O projetor NUNCA lança.** O relay do outbox é **um só, compartilhado por todos os módulos**: um `throw` num handler faz o mesmo evento ser reprocessado a cada tick para sempre e bloqueia head-of-line o bus inteiro. Todo handler novo envolve o corpo em `(try ... (catch Throwable e (log/warn ...) nil))` — `Throwable`, não `Exception` (as `:pre` das fns de `db/` lançam `AssertionError`, que é `Error`).
- **RLS FORCE + `WITH CHECK`.** Toda tabela tenant nova: `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `tenant_isolation` com `USING` **e** `WITH CHECK` sobre `ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid`, e `GRANT SELECT, INSERT, UPDATE ... TO oplenario_app`. **Sem `DELETE`** (Inv.10).
- **Nunca editar migration já aplicada** (o Migratus registra o id e não re-roda) — sempre arquivo NOVO, com `.up.sql` e `.down.sql`. Separador de statements = `--;;`. Só `timestamptz`, nunca `timestamp` sem zona (`migracoes_lint_test` quebra o CI).
- **Teto (`LIMIT`) sempre no SQL, nunca em Clojure depois do fetch.** Cortar em memória descarta os itens mais recentes — a armadilha que já mordeu este projeto na F3.
- **Identidade sempre de `(:ator req)`.** `destinatario_identidade_id` nunca vem de path, query ou corpo. O `WHERE` de posse mora no **mesmo** `WHERE` do tenant (anti-confused-deputy).
- **§22.10 — módulos não se importam.** `legislativo` nunca importa `cadastros` nem `transparencia`; `paineis` nunca importa `legislativo`. O que cruza vem por **fn injetada pelo host** (`rotas.clj`/`sistema.clj`) ou por **nome de evento como string literal**. O `db/` de um módulo só é importado pelo Repo-Component dele (o `arquitetura_test` enforça).
- **Silhueta ADR-0001:** `wire/out` (Malli `:closed`) ← `adapters/out` (gate de saída, valida e lança se drift) ← `diplomat/http/in` (rotas-dado) → `controllers` (só models) → Repo-Component → `db/`. Sem `port/`, sem ORM.
- **Português** em nomes de função, mensagens, docstrings e comentários; kebab-case no wire JSON.
- **TDD red → green** em toda task: escrever o teste, **rodar e ver falhar**, implementar o mínimo, rodar e ver passar.
- **Um commit por task**, com `git add` explícito por caminho (nunca `git add -A` — já varreu arquivos alheios neste repo).
- **Contraste AA medido em pixel composto**, um tema por chamada com flush, nos dois temas (`produto/design-system/o-plenario/GUIDELINES-CHECKLIST.md` §5.1). Nunca escrever um ratio de memória.

---

## Estrutura de arquivos

**Criar (backend):**

| Arquivo | Responsabilidade |
|---|---|
| `apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.up.sql` | Tabela `paineis.notificacao_caixa` + 2 índices + RLS FORCE + policy + grants. |
| `apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.down.sql` | `DROP TABLE`. |
| `apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj` | SQL da inbox: `inserir!` (ON CONFLICT DO NOTHING), `listar-do-destinatario`, `contar-nao-lidas`, `marcar-lida!`. |
| `apps/backend/src/oplenario/paineis/wire/out/notificacao.clj` | `NotificacaoOut` + `MinhasNotificacoesOut` + `MarcarLidaOut` (Malli `:closed`). |
| `apps/backend/src/oplenario/paineis/adapters/out/notificacao.clj` | Gate de saída models→wire, valida e filtra `ente-id`/`destinatario`. |
| `apps/backend/src/oplenario/paineis/adapters/in/notificacao.clj` | `id-param->uuid` (path-param → UUID, malformado = `nil`). |
| `apps/backend/src/oplenario/legislativo/events/notificacao.clj` | Cópia deliberada do contrato `notificacao.requisitada` (§22.10 proíbe importar `transparencia`). |
| `apps/backend/src/oplenario/legislativo/logic/notificacao.clj` | Puro: `chave-idempotencia` + `renderizar` da notificação de norma publicada. |
| `apps/backend/src/oplenario/codegen/gerar_paineis.clj` | Manifesto de codegen Malli→TS do módulo `paineis`. |
| `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj` | Tenancy/RLS da tabela + db + projeção + idempotência. |
| `apps/backend/test/integration/oplenario/paineis/minhas_notificacoes_http_in_test.clj` | Borda HTTP DB-free (200/401/posse/404/idempotência). |
| `apps/backend/test/integration/oplenario/legislativo/notificacao_autor_test.clj` | Produtor: `norma.publicada` → notificação na inbox do autor; sem identidade → nada, sem erro. |
| `apps/backend/test/unit/oplenario/legislativo/notificacao_logic_test.clj` | Renderização + chave determinística. |
| `apps/backend/test/unit/oplenario/eventos_notificacao_contrato_test.clj` | Drift-guard: os dois `RequisitadaPayload` (transparencia/legislativo) são estruturalmente iguais. |
| `apps/backend/test/unit/oplenario/codegen/gerar_paineis_test.clj` | Manifesto do codegen de `paineis`. |

**Criar (frontend):**

| Arquivo | Responsabilidade |
|---|---|
| `apps/frontend/src/lib/contrato-paineis.gen.ts` | **Gerado** pelo codegen — não editar à mão. |
| `apps/frontend/src/lib/notificacoes-vista.ts` | View-model puro: agrupamento temporal + estado visual. |
| `apps/frontend/src/lib/notificacoes-vista.test.ts` | Testes do view-model. |
| `apps/frontend/src/lib/use-minhas-notificacoes.ts` | Hook de leitura (`GET /api/meu/notificacoes`) + `recarregar`. |
| `apps/frontend/src/lib/use-minhas-notificacoes.test.ts` | Testes do hook de leitura. |
| `apps/frontend/src/lib/use-marcar-lida.ts` | Hook de mutação (`POST /api/meu/notificacoes/:id/lida`). |
| `apps/frontend/src/lib/use-marcar-lida.test.ts` | Testes do hook de mutação. |
| `apps/frontend/src/app/(vereador)/notificacoes/page.tsx` | A tela portada. |
| `apps/frontend/src/app/(vereador)/notificacoes/notificacoes.css` | CSS da tela (porte de `notificacoes.html`). |
| `apps/frontend/src/app/(vereador)/notificacoes/page.test.tsx` | Testes de render da tela. |

**Modificar:**

| Arquivo | Mudança |
|---|---|
| `apps/backend/src/oplenario/transparencia/events/notificacao.clj` | `canal` documenta `"in_app"`; campo `categoria` `{:optional true}`. |
| `apps/backend/src/oplenario/paineis/components/repositorio.clj` | Guarda de canal no branch de e-mail; `projetar-inbox!` (2º consumidor); métodos `minhas-notificacoes` e `marcar-notificacao-lida!` no protocolo + record. |
| `apps/backend/src/oplenario/paineis/diplomat/consumers.clj` | Registra o 2º consumidor `paineis-inbox`. |
| `apps/backend/src/oplenario/paineis/controllers.clj` | `minhas-notificacoes` e `marcar-lida`. |
| `apps/backend/src/oplenario/paineis/diplomat/http/in.clj` | 2 rotas novas, gate `auth` **sem papel**. |
| `apps/backend/src/oplenario/legislativo/db/proposicao.clj` | `autor-vereador-da-proposicao` (leitura estreita). |
| `apps/backend/src/oplenario/legislativo/diplomat/producers.clj` | `emitir-notificacao-requisitada!`. |
| `apps/backend/src/oplenario/legislativo/components/repositorio.clj` | `notificar-autor-da-norma!` (fn plana, roda na tx do relay, nunca lança). |
| `apps/backend/src/oplenario/legislativo/diplomat/consumers.clj` | Deixa de ser stub: `registrar` recebe o resolvedor injetado. |
| `apps/backend/src/oplenario/cadastros/components/repositorio.clj` | `identidade-do-vereador-em-tx` (fn plana, para o host injetar no consumer). |
| `apps/backend/src/oplenario/sistema.clj` | Fia o consumer de `legislativo` no registro do relay. |
| `apps/backend/demo/seed_demo.clj` | `seed-demo/notificacoes` — vereador com identidade, proposição dele, norma publicada. |
| `apps/frontend/src/app/(vereador)/layout.tsx` | Tab "Avisos" passa a ter rota real. |

---

## Tasks

### Task 1 — Migration `paineis.notificacao_caixa` (tabela + índices + RLS/grants)

**Files:**
- Create: `apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.up.sql`
- Create: `apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.down.sql`
- Test: `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj`

**Interfaces:**
- Consumes: `oplenario.migracao/migrar!`, `oplenario.kernel.tenancy/com-tenant*` (já existem).
- Produces: tabela `paineis.notificacao_caixa` com colunas `id uuid`, `ente_id uuid`, `destinatario_identidade_id uuid`, `categoria text`, `assunto text`, `corpo text`, `objeto_tipo text`, `objeto_id uuid`, `idempotency_key text`, `criado_em timestamptz`, `lida_em timestamptz NULL`; `UNIQUE (ente_id, idempotency_key)`; índices `idx_notificacao_caixa_destinatario` e `idx_notificacao_caixa_nao_lidas`.

**Steps:**

- [ ] **Step 1: Escrever o teste de tenancy/RLS (vermelho).**
  Criar `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj`:
  ```clojure
  (ns oplenario.paineis.notificacao-caixa-test
    "INTEGRACAO (PG real) — Onda E fatia 1: a tabela da INBOX (`paineis.notificacao_caixa`, mig 0062).
    Task 1 prova SO' o contrato da TABELA: isolamento de tenant (FORCE RLS + WITH CHECK) e a UNIQUE
    (ente_id, idempotency_key) que torna o redrive um no-op. As fns de `db/` entram nas Tasks 4/6/7."
    (:require [clojure.test :refer [deftest is use-fixtures]]
              [com.stuartsierra.component :as component]
              [next.jdbc :as jdbc]
              [oplenario.config :as config]
              [oplenario.kernel.components.datasource :as datasource]
              [oplenario.kernel.tenancy :as tenancy]
              [oplenario.migracao :as migracao]))

  (def ^:dynamic *ds* nil)

  (use-fixtures :once
    (fn [t]
      (let [c (component/start (datasource/datasource (config/carregar)))]
        (migracao/migrar! (:ds c))
        (binding [*ds* (:ds c)]
          (try (t) (finally (component/stop c)))))))

  (defn- inserir! [ente destinatario chave]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (jdbc/execute-one! tx
          ["INSERT INTO paineis.notificacao_caixa
              (id, ente_id, destinatario_identidade_id, categoria, assunto, corpo,
               objeto_tipo, objeto_id, idempotency_key)
            VALUES (?, ?, ?, 'norma_publicada', 'assunto', 'corpo', 'proposicao', ?, ?)
            ON CONFLICT (ente_id, idempotency_key) DO NOTHING
            RETURNING id"
           (random-uuid) ente destinatario (random-uuid) chave]))))

  (defn- contar [ente]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (:c (jdbc/execute-one! tx ["SELECT count(*) AS c FROM paineis.notificacao_caixa"])))))

  (deftest linha-nasce-nao-lida
    (let [ente (random-uuid) dest (random-uuid)]
      (is (some? (inserir! ente dest "k1")) "insercao devolve a linha")
      (is (= 1 (contar ente)))
      (is (nil? (tenancy/com-tenant* *ds* ente
                  (fn [tx] (:notificacao_caixa/lida_em
                            (jdbc/execute-one! tx ["SELECT lida_em FROM paineis.notificacao_caixa"])))))
          "lida_em nasce NULL = nao lida")))

  (deftest unique-por-chave-de-idempotencia
    (let [ente (random-uuid) dest (random-uuid)]
      (inserir! ente dest "mesma-chave")
      (is (nil? (inserir! ente dest "mesma-chave")) "ON CONFLICT DO NOTHING -> 2a insercao e' no-op")
      (is (= 1 (contar ente)) "uma unica linha para a mesma chave logica")))

  (deftest isolamento-de-tenant
    (let [ente-a (random-uuid) ente-b (random-uuid) dest (random-uuid)]
      (inserir! ente-a dest "k-a")
      (is (= 1 (contar ente-a)))
      (is (= 0 (contar ente-b)) "a notificacao de uma Casa nunca aparece na outra (FORCE RLS)")))

  (deftest with-check-barra-escrita-cross-tenant
    (let [ente-a (random-uuid) ente-b (random-uuid)]
      (is (thrown? Exception
            (tenancy/com-tenant* *ds* ente-a
              (fn [tx]
                (jdbc/execute-one! tx
                  ["INSERT INTO paineis.notificacao_caixa
                      (id, ente_id, destinatario_identidade_id, categoria, assunto, corpo,
                       objeto_tipo, objeto_id, idempotency_key)
                    VALUES (?, ?, ?, 'norma_publicada', 'a', 'c', 'proposicao', ?, 'k-forjada')"
                   (random-uuid) ente-b (random-uuid) (random-uuid)]))))
          "WITH CHECK barra gravar linha de OUTRO ente mesmo com o GUC do proprio")))
  ```

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.paineis.notificacao-caixa-test --reporter documentation
  ```
  Falha esperada: `PSQLException: ERROR: relation "paineis.notificacao_caixa" does not exist` nos 4 testes.

- [ ] **Step 3: Escrever a migration `.up.sql`.**
  `apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.up.sql`:
  ```sql
  -- Onda E fatia 1: modulo PAINEIS — INBOX interna (`notificacao_caixa`). SEGUNDA projecao do MESMO evento
  -- `notificacao.requisitada`, com tabela PROPRIA (decisao D4 da spec): `notificacao_entrega` (mig 0004+0050)
  -- e' um ledger de TENTATIVA DE ENTREGA POR CANAL (pendente->enviada|falha); um inbox e' a MENSAGEM + o
  -- ESTADO DE LEITURA do destinatario. Enfiar `lida_em` naquele ledger misturaria os dois, e 'pendente'/
  -- 'enviada' nao significam nada para in-app. Nenhuma tabela existente e' alterada; nenhum JOIN entre elas.
  --
  -- SEM PII: `destinatario_identidade_id` e' o UUID de identidade (handle pseudonimo, NAO e-mail/nome/CPF);
  -- assunto/corpo derivam de dado PUBLICO (a norma publicada e' ato publico por natureza).
  --
  -- Inv.10: `lida_em` e' ESTADO ATUAL MUTAVEL, nao historico. Aceito e registrado (spec §4.4) — se um dia
  -- houver exigencia formal de trilha de leitura, o remedio pattern-consistent e' um ledger COMPANHEIRO
  -- append-only (espelhando participacao.moderacao_comentario), nunca tornar esta tabela append-only.
  --
  -- Sem colunas de staging (lote_id/efetivado_em) nem particao hash: e' PROJECAO, nao verdade de dominio
  -- (mesmo racional das migs 0044/0048/0049) — nao ha importacao de legado de uma projecao.
  --
  -- NAO regrant `USAGE ON SCHEMA paineis` (mesma nota da mig 0048): a concessao pertence a mig 0009 e
  -- schema-level GRANT nao e' contado por referencia — um REVOKE no down desfaria a 0009.
  CREATE TABLE IF NOT EXISTS paineis.notificacao_caixa (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    ente_id       uuid NOT NULL,
    -- a quem a mensagem e' endereçada (UUID de identidade; ref por VALOR, sem FK cross-schema §22.10)
    destinatario_identidade_id uuid NOT NULL,
    -- classe da MENSAGEM, nao estado de entrega (decisao D5): 'norma_publicada' nesta fatia;
    -- 'falha'/'prazo'/'sessao'/'sistema' quando um segundo produtor chegar.
    categoria     text NOT NULL,
    assunto       text NOT NULL,
    corpo         text NOT NULL,
    -- destino do clique — ref polimorfica OPACA (mesma convencao de paineis.pendencia/notificacao_entrega)
    objeto_tipo   text NOT NULL,
    objeto_id     uuid NOT NULL,
    -- chave DETERMINISTICA vinda do payload: um redrive/backfill do mesmo evento e' no-op (ON CONFLICT)
    idempotency_key text NOT NULL,
    criado_em     timestamptz NOT NULL DEFAULT now(),
    -- NULL = nao lida. Sem DEFAULT: a ausencia E' o estado inicial.
    lida_em       timestamptz,
    PRIMARY KEY (ente_id, id),
    UNIQUE (ente_id, idempotency_key)
  );
  --;;
  -- o acesso da borda de leitura: "as minhas notificacoes, mais recentes primeiro" (GET /meu/notificacoes).
  CREATE INDEX IF NOT EXISTS idx_notificacao_caixa_destinatario
    ON paineis.notificacao_caixa (ente_id, destinatario_identidade_id, criado_em DESC);
  --;;
  -- a CONTAGEM de nao lidas (parcial: o lido cresce sem limite mas sai do indice quente).
  CREATE INDEX IF NOT EXISTS idx_notificacao_caixa_nao_lidas
    ON paineis.notificacao_caixa (ente_id, destinatario_identidade_id)
    WHERE lida_em IS NULL;
  --;;
  ALTER TABLE paineis.notificacao_caixa ENABLE ROW LEVEL SECURITY;
  --;;
  -- FORCE: nem o dono (se nao-superuser) bypassa — o consumer projeta como oplenario_app com o GUC setado.
  ALTER TABLE paineis.notificacao_caixa FORCE ROW LEVEL SECURITY;
  --;;
  DROP POLICY IF EXISTS tenant_isolation ON paineis.notificacao_caixa;
  --;;
  -- Projecao NAO tem staging (sem clausula app.ver_lote). NULLIF(...,'') = fail-closed sem GUC.
  CREATE POLICY tenant_isolation ON paineis.notificacao_caixa
    USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
    WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
  --;;
  -- INSERT (projecao) + UPDATE (marcar lida). Sem DELETE (Inv.10; re-projecao seria TRUNCATE via DDL).
  GRANT SELECT, INSERT, UPDATE ON paineis.notificacao_caixa TO oplenario_app;
  ```

- [ ] **Step 4: Escrever a migration `.down.sql`.**
  `apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.down.sql`:
  ```sql
  -- NAO revoga USAGE ON SCHEMA paineis (mesma nota da mig 0048): a concessao pertence a mig 0009 e
  -- schema-level GRANT nao e' contado por referencia — um REVOKE aqui quebraria as tabelas irmas.
  DROP TABLE IF EXISTS paineis.notificacao_caixa;
  ```

- [ ] **Step 5: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `4 tests, N assertions, 0 failures`.

- [ ] **Step 6: Rodar o lint de migrations.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.migracoes-lint-test --reporter documentation
  ```
  Esperado: `2 tests, ... 0 failures` (nenhum `timestamp` sem zona).

- [ ] **Step 7: Commit.**
  ```bash
  git add apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.up.sql \
          apps/backend/resources/migrations/20260719000062-paineis-notificacao-caixa.down.sql \
          apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj
  git commit -m "feat(paineis): tabela da inbox interna (notificacao_caixa) com RLS FORCE"
  ```

---

### Task 2 — Contrato do evento: `canal "in_app"` + `categoria` opcional

**Files:**
- Modify: `apps/backend/src/oplenario/transparencia/events/notificacao.clj`
- Test: `apps/backend/test/unit/oplenario/transparencia/notificacao_evento_test.clj` (criar)

**Interfaces:**
- Consumes: `malli.core/validate`.
- Produces: `oplenario.transparencia.events.notificacao/RequisitadaPayload` ganha `[:categoria {:optional true} :string]`; `requisitada-tipo` inalterado (`"notificacao.requisitada"`); `canal` aceita `"in_app"` (o schema já é `:string` — a mudança é de **vocabulário documentado**, não de tipo).

**Steps:**

- [ ] **Step 1: Escrever o teste (vermelho).**
  `apps/backend/test/unit/oplenario/transparencia/notificacao_evento_test.clj`:
  ```clojure
  (ns oplenario.transparencia.notificacao-evento-test
    "Unit: o CONTRATO de `notificacao.requisitada` (Onda E fatia 1, spec §4.2). Duas mudancas COMPATIVEIS:
    `canal` admite \"in_app\" alem de \"email\" (o schema ja' era :string — o que muda e' o vocabulario) e
    `categoria` entra OPCIONAL (nao quebra o produtor do cidadao, que nao a manda)."
    (:require [clojure.test :refer [deftest is]]
              [malli.core :as m]
              [oplenario.transparencia.events.notificacao :as ev]))

  (def ^:private base
    {:destinatario-identidade-id (str (random-uuid))
     :canal "email" :consent-base "acompanhamento" :idempotency-key "k"
     :assunto "a" :corpo "c" :objeto-tipo "proposicao" :objeto-id (str (random-uuid))})

  (deftest payload-sem-categoria-continua-valido
    (is (m/validate ev/RequisitadaPayload base) "o produtor do cidadao (F7 E2) nao manda categoria"))

  (deftest payload-com-categoria-e-canal-in-app-e-valido
    (is (m/validate ev/RequisitadaPayload
                    (assoc base :canal "in_app" :consent-base "vinculo" :categoria "norma_publicada"))
        "notificacao INTERNA: canal in_app + consent-base vinculo + categoria"))

  (deftest payload-fechado-recusa-campo-desconhecido
    (is (not (m/validate ev/RequisitadaPayload (assoc base :urgencia "alta")))
        ":closed true — campo fora do contrato nao passa"))

  (deftest construtor-lanca-em-payload-invalido
    (is (thrown? clojure.lang.ExceptionInfo
                 (ev/requisitada (random-uuid) (assoc base :categoria 42)))
        "categoria nao-string e' recusada na fonte (o outbox so' recebe evento bem-formado)"))
  ```

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.transparencia.notificacao-evento-test --reporter documentation
  ```
  Falha esperada: `payload-com-categoria-e-canal-in-app-e-valido` e `construtor-lanca-em-payload-invalido` falham (o `:closed true` hoje recusa `:categoria`).

- [ ] **Step 3: Adicionar `categoria` ao schema.**
  Em `apps/backend/src/oplenario/transparencia/events/notificacao.clj`, dentro de `RequisitadaPayload`, substituir o comentário e a linha de `canal` por:
  ```clojure
     ;; canal de entrega. "email" (fan-out do cidadao, F7 E2) | "in_app" (inbox interna, Onda E fatia 1).
     ;; Cada PROJETOR trata APENAS o seu canal (spec §4.3): o ledger de entrega ignora != "email"; a inbox
     ;; ignora != "in_app". Sem isso o worker `entregar-pendentes!` tentaria mandar e-mail de um in-app.
     [:canal :string]
  ```
  e, ao final do mapa (depois de `[:objeto-id :string]`), acrescentar:
  ```clojure
     ;; classe da MENSAGEM (spec D5: "falha" e' categoria de dominio, nao estado de entrega). OPCIONAL de
     ;; proposito — o produtor do cidadao (F7 E2) nao a manda e nao deve ser tocado por esta fatia.
     [:categoria {:optional true} :string]
  ```
  E, na docstring do ns, acrescentar ao final do parágrafo "SEM PII DURAVEL":
  ```
    ONDA E (fatia 1): o vocabulario cresceu — `canal` admite "in_app" e `categoria` (opcional) entrou. O
    contrato segue :closed; a inbox interna (`paineis.notificacao_caixa`) e' um SEGUNDO projetor do MESMO
    evento, com tabela propria. `legislativo` tem uma COPIA deste schema (events/notificacao.clj) porque
    §22.10 proibe import cross-modulo — o drift entre as duas e' barrado por `eventos-notificacao-contrato-test`.
  ```

- [ ] **Step 4: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `4 tests, ... 0 failures`.

- [ ] **Step 5: Commit.**
  ```bash
  git add apps/backend/src/oplenario/transparencia/events/notificacao.clj \
          apps/backend/test/unit/oplenario/transparencia/notificacao_evento_test.clj
  git commit -m "feat(eventos): notificacao.requisitada admite canal in_app + categoria opcional"
  ```

---

### Task 3 — Guarda de canal no projetor de e-mail existente

**Files:**
- Modify: `apps/backend/src/oplenario/paineis/components/repositorio.clj`
- Test: `apps/backend/test/integration/oplenario/paineis/notificacao_test.clj` (acrescentar 1 deftest)

**Interfaces:**
- Consumes: `oplenario.paineis.db.notificacao-entrega/registrar-intent!` (já existe).
- Produces: `despachar!` no branch `"notificacao.requisitada"` só chama `registrar-intent!` quando `(= "email" (:canal payload))`; qualquer outro canal → `log/debug` + `nil` (nunca lança).

**Steps:**

- [ ] **Step 1: Escrever o teste (vermelho).**
  Acrescentar ao final de `apps/backend/test/integration/oplenario/paineis/notificacao_test.clj`:
  ```clojure
  ;; ---------- Onda E fatia 1: roteamento por canal (spec §4.3) ----------

  (deftest notificacao-in-app-nao-entra-no-ledger-de-email
    ;; criterio de aceitacao 7: uma notificacao com canal "in_app" NAO vira intent de entrega de e-mail —
    ;; senao o worker `entregar-pendentes!` tentaria mandar e-mail de uma mensagem que so' vive na inbox.
    (let [ente (random-uuid)]
      (emitir! ente "notificacao.requisitada"
               {:destinatario-identidade-id (str (random-uuid)) :canal "in_app"
                :consent-base "vinculo" :idempotency-key (str "k-" (random-uuid))
                :categoria "norma_publicada" :assunto "A sua proposicao virou lei"
                :corpo "corpo" :objeto-tipo "proposicao" :objeto-id (str (random-uuid))})
      (drenar!)
      (is (empty? (ledger ente)) "canal in_app -> nenhum intent no ledger de entrega de e-mail")))

  (deftest notificacao-email-continua-entrando-no-ledger
    ;; a guarda tem DENTES nos dois sentidos: o canal legitimo segue projetando (nao vira no-op geral).
    (let [ente (random-uuid)]
      (emitir! ente "notificacao.requisitada"
               {:destinatario-identidade-id (str (random-uuid)) :canal "email"
                :consent-base "acompanhamento" :idempotency-key (str "k-" (random-uuid))
                :assunto "Movimentacao" :corpo "corpo"
                :objeto-tipo "proposicao" :objeto-id (str (random-uuid))})
      (drenar!)
      (is (= 1 (count (ledger ente))) "canal email -> intent 'pendente' no ledger, como antes")))
  ```

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.paineis.notificacao-test --reporter documentation
  ```
  Falha esperada: `notificacao-in-app-nao-entra-no-ledger-de-email` — `expected: (empty? (ledger ente))  actual: (not (empty? [...]))` (hoje o projetor grava qualquer canal).

- [ ] **Step 3: Implementar a guarda.**
  Em `apps/backend/src/oplenario/paineis/components/repositorio.clj`, acrescentar antes de `despachar!`:
  ```clojure
  (def ^:private canal-email
    "O UNICO canal que o ledger de ENTREGA (notificacao_entrega) materializa. Onda E fatia 1: o mesmo evento
    `notificacao.requisitada` passou a carregar tambem `in_app` (inbox interna, projetada por
    `projetar-inbox!` numa tabela propria). Cada projetor trata APENAS o seu canal (spec §4.3); sem esta
    guarda o worker `entregar-pendentes!` tentaria enviar e-mail de uma notificacao que nunca teve endereco."
    "email")

  (defn- registrar-intent-de-email!
    "Materializa o intent de entrega SO' quando o canal e' 'email'. Outro canal -> log/debug + nil (nunca
    lanca, nunca grava): nao e' erro, e' um evento endereçado a OUTRO projetor do mesmo modulo."
    [tx ente-id payload]
    (if (= canal-email (:canal payload))
      (db-notificacao/registrar-intent! tx {:ente-id ente-id
                                            :destinatario (:destinatario-identidade-id payload)
                                            :canal (:canal payload)
                                            :idempotency-key (:idempotency-key payload)
                                            :consent-base (:consent-base payload)
                                            :assunto (:assunto payload) :corpo (:corpo payload)
                                            :objeto-tipo (:objeto-tipo payload)
                                            :objeto-id (UUID/fromString (:objeto-id payload))})
      (do (log/debug "paineis: notificacao de outro canal ignorada pelo ledger de e-mail"
                     {:ente-id ente-id :canal (:canal payload)})
          nil)))
  ```
  E substituir o corpo do branch `"notificacao.requisitada"` de `despachar!` (as linhas de `db-notificacao/registrar-intent!`) por:
  ```clojure
      "notificacao.requisitada"
      (registrar-intent-de-email! tx ente-id payload)
  ```
  Manter o comentário existente acima do branch e acrescentar a ele:
  ```clojure
      ;; Onda E fatia 1: ROTEAMENTO POR CANAL — este branch cuida SO' de `email`. A inbox (`in_app`) e' um
      ;; SEGUNDO consumidor registrado (`paineis-inbox`, `projetar-inbox!`), com dedup independente.
  ```

- [ ] **Step 4: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `8 tests, ... 0 failures` (os 6 antigos + os 2 novos).

- [ ] **Step 5: Commit.**
  ```bash
  git add apps/backend/src/oplenario/paineis/components/repositorio.clj \
          apps/backend/test/integration/oplenario/paineis/notificacao_test.clj
  git commit -m "fix(paineis): ledger de entrega ignora notificacao de canal != email"
  ```

---

### Task 4 — Projetor da inbox (2º consumidor de `notificacao.requisitada`)

**Files:**
- Create: `apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj`
- Modify: `apps/backend/src/oplenario/paineis/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/paineis/diplomat/consumers.clj`
- Test: `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj` (acrescentar deftests)

**Interfaces:**
- Consumes: `oplenario.kernel.tenancy/set-tenant!`, `oplenario.kernel.ids/novo-id`, `oplenario.kernel.db-util/linha->kebab`, `oplenario.kernel.outbox/registrar`.
- Produces:
  - `oplenario.paineis.db.notificacao-caixa/inserir!` — `[tx {:keys [ente-id destinatario-identidade-id categoria assunto corpo objeto-tipo objeto-id idempotency-key]}] → {:id ... } | nil` (nil = já existia).
  - `oplenario.paineis.components.repositorio/projetar-inbox!` — `[tx {:keys [tipo ente-id payload]}] → nil` (handler do bus; nunca lança).
  - `oplenario.paineis.diplomat.consumers/registrar` passa a registrar também o consumidor `"paineis-inbox"` para `"notificacao.requisitada"`.

**Steps:**

- [ ] **Step 1: Escrever os testes (vermelho).**
  Acrescentar ao final de `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj` — e, no `ns`, acrescentar aos `:require`:
  ```clojure
              [oplenario.kernel.eventos :as eventos]
              [oplenario.kernel.outbox :as outbox]
              [oplenario.paineis.diplomat.consumers :as paineis-consumers]
  ```
  ```clojure
  ;; ---------- Task 4: o PROJETOR da inbox (2o consumidor de `notificacao.requisitada`) ----------

  (defn- drenar! []
    (outbox/drenar! *ds* (paineis-consumers/registrar {})))

  (defn- emitir! [ente payload]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (eventos/emitir! (outbox/bus) tx
                 (eventos/evento "notificacao.requisitada" ente payload)))))

  (defn- caixa [ente]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (jdbc/execute! tx ["SELECT destinatario_identidade_id, categoria, assunto, corpo,
                                          objeto_tipo, objeto_id, idempotency_key, lida_em
                                   FROM paineis.notificacao_caixa ORDER BY criado_em"]))))

  (defn- payload-in-app [dest chave]
    {:destinatario-identidade-id (str dest) :canal "in_app" :consent-base "vinculo"
     :idempotency-key chave :categoria "norma_publicada"
     :assunto "A sua proposicao virou lei" :corpo "Lei 3/2026 — Dispoe sobre X."
     :objeto-tipo "proposicao" :objeto-id (str (random-uuid))})

  (deftest projeta-in-app-na-inbox
    (let [ente (random-uuid) dest (random-uuid)]
      (emitir! ente (payload-in-app dest "k-inbox-1"))
      (drenar!)
      (let [linhas (caixa ente)]
        (is (= 1 (count linhas)))
        (let [l (first linhas)]
          (is (= dest (:notificacao_caixa/destinatario_identidade_id l)))
          (is (= "norma_publicada" (:notificacao_caixa/categoria l)))
          (is (= "A sua proposicao virou lei" (:notificacao_caixa/assunto l)))
          (is (= "proposicao" (:notificacao_caixa/objeto_tipo l)))
          (is (nil? (:notificacao_caixa/lida_em l)) "nasce nao lida")))))

  (deftest projecao-e-idempotente-no-redrive
    ;; criterio de aceitacao 1: re-executar o MESMO evento logico nao cria uma segunda notificacao.
    (let [ente (random-uuid) dest (random-uuid) p (payload-in-app dest "k-inbox-repetida")]
      (emitir! ente p) (drenar!)
      (emitir! ente p) (drenar!)  ; envelope NOVO (idempotency-key do envelope e' aleatoria) -> o consumer roda
      (is (= 1 (count (caixa ente))) "a UNIQUE (ente_id, idempotency_key) torna a 2a projecao um no-op")))

  (deftest canal-email-nao-entra-na-inbox
    (let [ente (random-uuid)]
      (emitir! ente (assoc (payload-in-app (random-uuid) "k-email") :canal "email"))
      (drenar!)
      (is (empty? (caixa ente)) "cada projetor trata so' o seu canal (spec §4.3)")))

  (deftest payload-malformado-nao-envenena-o-relay
    ;; o relay e' COMPARTILHADO: um payload ruim tem de ser tolerado (log + nil), nunca propagado.
    (let [ente (random-uuid)]
      (emitir! ente (assoc (payload-in-app (random-uuid) "k-ruim") :objeto-id "nao-e-uuid"))
      (is (nil? (drenar!)) "drenar! nao lanca")
      (is (empty? (caixa ente)) "nada foi gravado")
      ;; e o bus segue drenando o PROXIMO evento normalmente
      (emitir! ente (payload-in-app (random-uuid) "k-depois"))
      (drenar!)
      (is (= 1 (count (caixa ente))) "o evento seguinte projeta — o relay nao travou")))
  ```
  > Nota: `outbox/drenar!` devolve o número de eventos drenados ou `nil` conforme a implementação do kernel; o assert relevante é **não lançar**. Se `drenar!` devolver um valor não-nil, trocar `(is (nil? (drenar!)) ...)` por `(is (some? (drenar!)) "drenar! nao lanca")` — verificar a assinatura em `kernel/outbox.clj` no momento da implementação e ajustar o assert (nunca o comportamento).

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.paineis.notificacao-caixa-test --reporter documentation
  ```
  Falha esperada: `projeta-in-app-na-inbox` → `expected: (= 1 (count linhas))  actual: (not (= 1 0))` (nenhum consumidor projeta a inbox ainda).

- [ ] **Step 3: Criar `db/notificacao_caixa.clj` (só o `inserir!` nesta task).**
  `apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj`:
  ```clojure
  (ns oplenario.paineis.db.notificacao-caixa
    "Persistencia de 'paineis.notificacao_caixa' (Onda E fatia 1, mig 0062) — a INBOX interna: a MENSAGEM +
    o estado de leitura do destinatario. Tabela SEPARADA de `notificacao_entrega` (ledger de tentativa de
    entrega por canal) de proposito — decisao D4 da spec; nenhum JOIN entre as duas. Funcoes sobre a `tx`
    corrente (FORCE RLS isola, mig 0062); `ente_id` em TODA query. Importado SO' pelo Repo-Component
    (regra do import-lint, arquitetura-test)."
    (:require [honey.sql :as sql]
              [next.jdbc :as jdbc]
              [oplenario.kernel.db-util :as comum]
              [oplenario.kernel.ids :as ids]))

  (set! *warn-on-reflection* true)

  (defn inserir!
    "Projeta uma notificacao in-app na inbox. `ON CONFLICT (ente_id, idempotency_key) DO NOTHING`: a chave
    DETERMINISTICA do payload torna redrive/backfill um no-op (criterio de aceitacao 1). Devolve a linha
    inserida, ou nil se ja' existia — NUNCA lanca 23505 (envenenaria o relay compartilhado)."
    [tx {:keys [ente-id destinatario-identidade-id categoria assunto corpo objeto-tipo objeto-id
                idempotency-key]}]
    {:pre [(some? ente-id) (some? destinatario-identidade-id) (some? categoria) (some? idempotency-key)]}
    (comum/linha->kebab
     (jdbc/execute-one! tx
       (sql/format {:insert-into :paineis.notificacao_caixa
                    :values [{:id (ids/novo-id) :ente_id ente-id
                              :destinatario_identidade_id destinatario-identidade-id
                              :categoria categoria :assunto assunto :corpo corpo
                              :objeto_tipo objeto-tipo :objeto_id objeto-id
                              :idempotency_key idempotency-key}]
                    :on-conflict [:ente_id :idempotency_key]
                    :do-nothing true
                    :returning [:id]}))))
  ```

- [ ] **Step 4: Adicionar `projetar-inbox!` ao Repo-Component.**
  Em `apps/backend/src/oplenario/paineis/components/repositorio.clj`, acrescentar `[oplenario.paineis.db.notificacao-caixa :as db-caixa]` aos `:require` e, logo depois de `projetar-evento!`, acrescentar:
  ```clojure
  (def ^:private canal-in-app
    "O UNICO canal que a INBOX materializa (espelho de `canal-email`; spec §4.3)."
    "in_app")

  (defn projetar-inbox!
    "Handler do SEGUNDO consumidor do modulo (`paineis-inbox`, mesma disciplina de
    `transparencia/fan-out-notificacao!`): projeta `notificacao.requisitada` de canal `in_app` na
    `paineis.notificacao_caixa`. Identidade de dedup SEPARADA da do projetor de entrega — cada um roda
    effectively-once por conta propria, entao a inbox nao depende da ordem nem do sucesso do ledger de e-mail.

    NUNCA lanca (o relay e' COMPARTILHADO por todos os modulos — um throw aqui trava a fila de todo mundo):
    try/catch Throwable envolve TUDO, INCLUSIVE `set-tenant!` (que lanca em ente-id nil, e a coluna
    shared.outbox.ente_id e' NULLABLE). `catch Throwable`, nao Exception: as `:pre` de db/ lancam
    AssertionError, que e' Error. Payload malformado (objeto-id nao-UUID) -> log + nil, evento drenado.

    Canal != in_app -> no-op silencioso (o evento e' de outro projetor, nao e' erro)."
    [tx {:keys [ente-id payload]}]
    (try
      (tenancy/set-tenant! tx ente-id)
      (when (= canal-in-app (:canal payload))
        (db-caixa/inserir! tx {:ente-id ente-id
                               :destinatario-identidade-id (UUID/fromString
                                                            (:destinatario-identidade-id payload))
                               ;; `categoria` e' OPCIONAL no contrato do evento (o produtor do cidadao nao a
                               ;; manda); um in-app sem categoria cai em "sistema" em vez de violar o NOT NULL.
                               :categoria (or (:categoria payload) "sistema")
                               :assunto (:assunto payload) :corpo (:corpo payload)
                               :objeto-tipo (:objeto-tipo payload)
                               :objeto-id (UUID/fromString (:objeto-id payload))
                               :idempotency-key (:idempotency-key payload)}))
      (catch Throwable e
        (log/warn e "paineis: projecao da inbox tolerada (payload malformado ou falha de escrita)"
                  {:ente-id ente-id})
        nil)))
  ```

- [ ] **Step 5: Registrar o 2º consumidor.**
  Em `apps/backend/src/oplenario/paineis/diplomat/consumers.clj`, acrescentar depois de `tipos-consumidos`:
  ```clojure
  ;; Onda E fatia 1: SEGUNDO consumidor do modulo — a INBOX interna. Nome DISTINTO = dedup independente por
  ;; (consumidor, key), exatamente como `transparencia-portal` x `transparencia-notificacao`. Consome o MESMO
  ;; `notificacao.requisitada`, mas so' age no canal `in_app` (ver repo/projetar-inbox!).
  (def ^:private nome-consumidor-inbox "paineis-inbox")
  (def ^:private tipos-inbox ["notificacao.requisitada"])
  ```
  e substituir o corpo de `registrar` por:
  ```clojure
  (defn registrar
    "Funde os handlers dos projetores de `paineis` num `registro` EXISTENTE (outbox/registrar por tipo) —
    combinavel com o(s) de outro(s) projetor(es) no MESMO relay. Registra DOIS consumidores: o projetor
    geral (`paineis`, todos os tipos, incl. o ledger de entrega de e-mail) e a INBOX (`paineis-inbox`, so'
    `notificacao.requisitada` de canal in_app) — nomes distintos = dedup independente."
    [registro]
    (as-> registro reg
      (reduce (fn [r tipo] (outbox/registrar r nome-consumidor tipo repo/projetar-evento!)) reg tipos-consumidos)
      (reduce (fn [r tipo] (outbox/registrar r nome-consumidor-inbox tipo repo/projetar-inbox!)) reg tipos-inbox)))
  ```

- [ ] **Step 6: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `8 tests, ... 0 failures` (os 4 da Task 1 + os 4 novos).

- [ ] **Step 7: Rodar a suíte irmã (regressão do ledger de e-mail).**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.paineis.notificacao-test \
    --focus oplenario.paineis.consumers-test --reporter documentation
  ```
  Esperado: 0 failures. Em particular o drift-guard `todo-tipo-consumido-tem-branch-de-projecao` de `consumers_test.clj` continua verde (o tipo novo não entrou em `tipos-consumidos`, entrou em `tipos-inbox`) — **se ele quebrar**, é porque ele varre o registro inteiro: nesse caso, estender o teste para reconhecer o consumidor `paineis-inbox` (ele não passa por `despachar!`), nunca relaxar o guard.

- [ ] **Step 8: Commit.**
  ```bash
  git add apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj \
          apps/backend/src/oplenario/paineis/components/repositorio.clj \
          apps/backend/src/oplenario/paineis/diplomat/consumers.clj \
          apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj
  git commit -m "feat(paineis): projetor da inbox interna (2o consumidor de notificacao.requisitada)"
  ```

---

### Task 5 — Produtor: `legislativo` consome o próprio `norma.publicada`

**Files:**
- Create: `apps/backend/src/oplenario/legislativo/events/notificacao.clj`
- Create: `apps/backend/src/oplenario/legislativo/logic/notificacao.clj`
- Create: `apps/backend/test/unit/oplenario/legislativo/notificacao_logic_test.clj`
- Create: `apps/backend/test/unit/oplenario/eventos_notificacao_contrato_test.clj`
- Create: `apps/backend/test/integration/oplenario/legislativo/notificacao_autor_test.clj`
- Modify: `apps/backend/src/oplenario/legislativo/db/proposicao.clj`
- Modify: `apps/backend/src/oplenario/legislativo/diplomat/producers.clj`
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/legislativo/diplomat/consumers.clj`
- Modify: `apps/backend/src/oplenario/cadastros/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/sistema.clj`

**Interfaces:**
- Consumes: `oplenario.kernel.eventos/emitir!`, `oplenario.kernel.outbox/bus`, `oplenario.kernel.tenancy/set-tenant!`.
- Produces:
  - `oplenario.legislativo.events.notificacao/requisitada-tipo` = `"notificacao.requisitada"`; `RequisitadaPayload`; `requisitada [ente-id payload] → envelope`.
  - `oplenario.legislativo.logic.notificacao/chave-idempotencia [norma-id destinatario-identidade-id] → String`.
  - `oplenario.legislativo.logic.notificacao/renderizar [norma] → {:assunto String :corpo String}`.
  - `oplenario.legislativo.db.proposicao/autor-vereador-da-proposicao [tx ente-id proposicao-id] → {:autor-id uuid} | nil`.
  - `oplenario.legislativo.diplomat.producers/emitir-notificacao-requisitada! [bus tx ente-id payload] → nil`.
  - `oplenario.legislativo.components.repositorio/notificar-autor-da-norma!` — **`[resolver-identidade-do-vereador] → (fn [tx evento])`** (fábrica: fecha sobre o resolvedor injetado e devolve o handler do bus).
  - `oplenario.legislativo.diplomat.consumers/registrar [registro resolver-identidade-do-vereador] → registro`.
  - `oplenario.cadastros.components.repositorio/identidade-do-vereador-em-tx [tx ente-id vereador-id] → uuid | nil`.

**Steps:**

- [ ] **Step 1: Escrever o teste da lógica pura (vermelho).**
  `apps/backend/test/unit/oplenario/legislativo/notificacao_logic_test.clj`:
  ```clojure
  (ns oplenario.legislativo.notificacao-logic-test
    "Unit: logica PURA da notificacao 'a sua proposicao virou lei' (Onda E fatia 1). Renderizacao (info
    PUBLICA — a norma publicada e' ato publico) + chave de idempotencia DETERMINISTICA."
    (:require [clojure.test :refer [deftest is]]
              [clojure.string :as str]
              [oplenario.legislativo.logic.notificacao :as logic]))

  (def ^:private norma
    {:tipo-norma "lei" :numero 3 :ano 2026 :ementa "Dispoe sobre as hortas comunitarias."
     :urn "urn:lex:br;ceara;fortaleza:municipal:lei:2026-06-28;3"})

  (deftest chave-e-deterministica
    (let [nid (random-uuid) dest (str (random-uuid))]
      (is (= (logic/chave-idempotencia nid dest) (logic/chave-idempotencia nid dest))
          "mesma (norma, destinatario) -> mesma chave (redrive vira no-op no ON CONFLICT)")
      (is (not= (logic/chave-idempotencia nid dest) (logic/chave-idempotencia nid (str (random-uuid))))
          "destinatarios diferentes -> chaves diferentes")))

  (deftest renderiza-assunto-e-corpo-publicos
    (let [{:keys [assunto corpo]} (logic/renderizar norma)]
      (is (str/includes? assunto "Lei 3/2026") "assunto identifica a norma")
      (is (str/includes? corpo "hortas comunitarias") "corpo carrega a ementa (dado publico)")
      (is (not (str/includes? corpo "CPF")) "nenhuma PII no conteudo")))
  ```

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.legislativo.notificacao-logic-test --reporter documentation
  ```
  Falha esperada: `Could not locate oplenario/legislativo/logic/notificacao__init.class ... on classpath`.

- [ ] **Step 3: Implementar a lógica pura.**
  `apps/backend/src/oplenario/legislativo/logic/notificacao.clj`:
  ```clojure
  (ns oplenario.legislativo.logic.notificacao
    "Logica PURA da notificacao interna de `norma.publicada` (Onda E fatia 1) — sem I/O, unit-testavel.
    Espelha `transparencia.logic.notificacao` (mesmo idioma: renderizar + chave determinística), mas o
    conteudo aqui e' 'a SUA proposicao virou lei' — ciencia-de-fato, nao pedido de acao (spec D3: o que
    exige acao mora em pendencias; a inbox e' acompanhamento)."
    (:require [clojure.string :as str]))

  (defn chave-idempotencia
    "Chave DETERMINISTICA da notificacao na inbox (UNIQUE ente_id, idempotency_key, mig 0062). Deriva de
    (norma-id, destinatario): uma notificacao logica por (norma publicada, autor). Um redrive/backfill que
    re-execute o consumer gera a MESMA chave -> a insercao e' no-op. NAO usa a idempotency-key ALEATORIA do
    envelope (essa dedup o consumer, nao a mensagem logica)."
    [norma-id destinatario-identidade-id]
    (str "norma:" norma-id ":dest:" destinatario-identidade-id))

  (defn- identificador-norma
    "Identificador humano da norma: '<Tipo> <numero>/<ano>' (ex.: 'Lei 3/2026')."
    [{:keys [tipo-norma numero ano]}]
    (str (str/capitalize (str tipo-norma)) " " numero "/" ano))

  (defn renderizar
    "Renderiza {:assunto :corpo} — info PUBLICA (a norma publicada e' ato publico por natureza). O
    destinatario NAO aparece no texto (a entrega e' 1:1); nenhuma PII entra no evento nem na tabela."
    [norma]
    (let [id-norma (identificador-norma norma)]
      {:assunto (str "A sua proposicao virou lei — " id-norma)
       :corpo   (str "A proposicao de sua autoria foi promulgada e publicada como " id-norma ".\n\n"
                     "Ementa: " (:ementa norma) "\n"
                     "URN: " (:urn norma) "\n\n"
                     "O texto vigente esta' disponivel no acervo de legislacao da Camara.")}))
  ```

- [ ] **Step 4: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `2 tests, 5 assertions, 0 failures`.

- [ ] **Step 5: Escrever o drift-guard dos dois contratos (vermelho).**
  `apps/backend/test/unit/oplenario/eventos_notificacao_contrato_test.clj`:
  ```clojure
  (ns oplenario.eventos-notificacao-contrato-test
    "Unit (HOST-level, por isso fora da pasta de um modulo): `notificacao.requisitada` tem DOIS produtores em
    modulos que §22.10 proibe de se importarem (`transparencia`, o fan-out do cidadao; `legislativo`, a
    notificacao interna). O contrato e', portanto, DUPLICADO de proposito — o nome do evento e' contrato de
    FIACAO do bus, nao um tipo compartilhado (mesma disciplina de tempo_real/canais.clj e dos consumers, que
    hardcodam strings). Este teste e' o unico lugar do repo autorizado a olhar os dois: se as duas copias
    driftarem, o consumidor (`paineis`) passa a receber formas diferentes do mesmo evento e o bug so'
    apareceria em runtime."
    (:require [clojure.test :refer [deftest is]]
              [oplenario.legislativo.events.notificacao :as leg]
              [oplenario.transparencia.events.notificacao :as transp]))

  (deftest o-nome-do-evento-e-o-mesmo
    (is (= transp/requisitada-tipo leg/requisitada-tipo "notificacao.requisitada")))

  (deftest os-dois-payloads-sao-estruturalmente-iguais
    (is (= transp/RequisitadaPayload leg/RequisitadaPayload)
        "as duas copias do contrato driftaram — reconcilie ANTES de mergear"))
  ```

- [ ] **Step 6: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.eventos-notificacao-contrato-test --reporter documentation
  ```
  Falha esperada: `Could not locate oplenario/legislativo/events/notificacao__init.class`.

- [ ] **Step 7: Criar a cópia do contrato em `legislativo`.**
  `apps/backend/src/oplenario/legislativo/events/notificacao.clj`:
  ```clojure
  (ns oplenario.legislativo.events.notificacao
    "Contrato de `notificacao.requisitada` PARA O PRODUTOR INTERNO (Onda E fatia 1) — ADR-0001: events/ =
    nome + schema Malli do payload.

    POR QUE UMA COPIA: o mesmo evento ja' e' produzido por `transparencia` (fan-out do cidadao, F7 E2), mas
    §22.10 PROIBE `legislativo` importar `transparencia`. O nome do evento e' o CONTRATO DE FIACAO do bus,
    nao um tipo compartilhado — mesma disciplina que ja' faz `tempo_real/canais.clj` e os `diplomat/consumers`
    hardcodarem strings de tipo. A duplicacao e' DELIBERADA e o drift entre as duas copias e' barrado em CI
    por `oplenario.eventos-notificacao-contrato-test`.

    USO INTERNO: `canal` = \"in_app\" (a inbox, `paineis.notificacao_caixa`), `consent-base` = \"vinculo\"
    (a pessoa e' agente da Casa; comunicacao institucional do sistema que ela opera, nao marketing),
    `categoria` = \"norma_publicada\".

    SEM PII: `destinatario-identidade-id` e' o UUID de identidade (handle pseudonimo); assunto/corpo derivam
    da norma publicada (ato publico). Viajam como STRING no jsonb do outbox (jsonista nao tem modulo UUID)."
    (:require [malli.core :as m]
              [oplenario.kernel.eventos :as eventos]))

  (def requisitada-tipo
    "Nome do evento. IDENTICO ao de transparencia — e' a mesma fiacao de bus, consumida por `paineis`."
    "notificacao.requisitada")

  (def RequisitadaPayload
    "Payload de `notificacao.requisitada`. COPIA ESTRUTURAL do de transparencia (ver docstring do ns) —
    manter em sincronia; o drift e' barrado por `eventos-notificacao-contrato-test`."
    [:map {:closed true}
     [:destinatario-identidade-id :string]
     [:canal :string]
     [:consent-base :string]
     [:idempotency-key :string]
     [:assunto :string]
     [:corpo :string]
     [:objeto-tipo :string]
     [:objeto-id :string]
     [:categoria {:optional true} :string]])

  (defn requisitada
    "Constroi o envelope de `notificacao.requisitada` p/ o tenant `ente-id`, VALIDANDO o payload contra o
    contrato. Lanca :payload-invalido se nao casa — defesa na fonte: o outbox so recebe evento bem-formado.
    NOTA: quem chama e' o consumer (`notificar-autor-da-norma!`), que envolve TUDO num try/catch — este throw
    nunca alcanca o relay."
    [ente-id payload]
    (when-not (m/validate RequisitadaPayload payload)
      (throw (ex-info "payload de notificacao.requisitada invalido (contrato do evento)"
                      {:erro :payload-invalido :explain (m/explain RequisitadaPayload payload)})))
    (eventos/evento requisitada-tipo ente-id payload))
  ```
  > **Atenção à ordem das chaves:** o teste do Step 5 compara os dois schemas por igualdade estrutural. Em `transparencia`, `[:categoria {:optional true} :string]` foi acrescentado **ao final** do mapa (Task 2, Step 3) — manter a mesma posição aqui. Se a igualdade falhar, alinhar a **ordem**, nunca afrouxar o teste.

- [ ] **Step 8: Rodar e ver passar.** Mesmo comando do Step 6. Esperado: `2 tests, 3 assertions, 0 failures`.

- [ ] **Step 9: Escrever o teste de integração do produtor (vermelho).**
  `apps/backend/test/integration/oplenario/legislativo/notificacao_autor_test.clj`:
  ```clojure
  (ns oplenario.legislativo.notificacao-autor-test
    "INTEGRACAO (PG real) — Onda E fatia 1: o PRODUTOR interno. `legislativo` consome o PROPRIO
    `norma.publicada` (2o consumidor, dedup independente), resolve proposicao->autor (same-schema) e
    vereador->identidade pelo RESOLVEDOR INJETADO PELO HOST (legislativo NUNCA importa cadastros, §22.10),
    e emite `notificacao.requisitada` de canal in_app. `paineis` projeta na inbox. Prova o vertical inteiro
    com o relay REAL, alem dos criterios de aceitacao 1 (uma notificacao, idempotente) e 2 (autor sem
    identidade -> nada, e sem erro)."
    (:require [clojure.test :refer [deftest is use-fixtures]]
              [com.stuartsierra.component :as component]
              [next.jdbc :as jdbc]
              [oplenario.config :as config]
              [oplenario.kernel.components.datasource :as datasource]
              [oplenario.kernel.outbox :as outbox]
              [oplenario.kernel.tenancy :as tenancy]
              [oplenario.legislativo.components.repositorio :as legislativo-repo]
              [oplenario.legislativo.diplomat.consumers :as legislativo-consumers]
              [oplenario.migracao :as migracao]
              [oplenario.paineis.diplomat.consumers :as paineis-consumers])
    (:import (java.time LocalDate)))

  (def ^:dynamic *ds* nil)
  (def ^:dynamic *leg* nil)

  (use-fixtures :once
    (fn [t]
      (let [c (component/start (datasource/datasource (config/carregar)))]
        (migracao/migrar! (:ds c))
        (binding [*ds* (:ds c)
                  *leg* (legislativo-repo/->RepoLegislativoPg c (outbox/bus))]
          (try (t) (finally (component/stop c)))))))

  ;; resolvedor FAKE injetado (o host injeta o real, que le' cadastros.vereador): vereador-id -> identidade-id
  (defn- resolver-fixo [mapa] (fn [_tx _ente-id vereador-id] (get mapa vereador-id)))

  (defn- drenar! [resolver]
    (outbox/drenar! *ds* (-> {}
                             (legislativo-consumers/registrar resolver)
                             (paineis-consumers/registrar))))

  (defn- caixa [ente]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (jdbc/execute! tx ["SELECT destinatario_identidade_id, categoria, assunto, objeto_id
                                   FROM paineis.notificacao_caixa"]))))

  (defn- publicar-norma-de-autor!
    "Protocola uma proposicao com autor vereador, leva ao desfecho promulgavel, promulga e publica.
    Devolve {:proposicao-id :norma-id}."
    [ente vereador-id]
    (let [{pid :id} (legislativo-repo/protocolar! *leg* ente
                      {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                       :municipio-nome "Fortaleza" :ementa "Dispoe sobre as hortas comunitarias."
                       :autor-tipo "vereador" :autor-id vereador-id :autor-texto "Ver. Fulana"})
          {aid :id} (legislativo-repo/gerar-autografo! *leg* ente
                      {:id (random-uuid) :proposicao-id pid :ano 2026
                       :texto-versao-id (random-uuid)
                       :destinatario-texto "Prefeito Municipal de Fortaleza"})
          {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! *leg* ente
                      {:id (random-uuid) :autografo-id aid})]
      (legislativo-repo/registrar-resposta-executivo! *leg* ente
        {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
      (let [{nid :id} (legislativo-repo/promulgar-norma! *leg* ente
                        {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                         :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                         :data-promulgacao (LocalDate/of 2026 6 28)
                         :ementa "Dispoe sobre as hortas comunitarias." :texto-versao-id (random-uuid)})]
        (legislativo-repo/publicar-norma! *leg* ente
          {:id nid :veiculo-publicacao "Diario Oficial do Municipio" :updated-by nil :lock-version 0})
        {:proposicao-id pid :norma-id nid})))

  (deftest norma-publicada-notifica-o-autor-vereador
    (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)
          {:keys [proposicao-id]} (publicar-norma-de-autor! ente vereador)]
      (drenar! (resolver-fixo {vereador identidade}))
      (let [linhas (caixa ente)]
        (is (= 1 (count linhas)) "criterio 1: UMA notificacao na inbox do autor")
        (let [l (first linhas)]
          (is (= identidade (:notificacao_caixa/destinatario_identidade_id l)) "endereçada a identidade dele")
          (is (= "norma_publicada" (:notificacao_caixa/categoria l)))
          (is (= proposicao-id (:notificacao_caixa/objeto_id l)) "o clique leva a' materia")))))

  (deftest reprocessar-nao-duplica
    (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)
          resolver (resolver-fixo {vereador identidade})]
      (publicar-norma-de-autor! ente vereador)
      (drenar! resolver)
      (drenar! resolver)   ; 2a passada: o dedup do consumer + a UNIQUE da inbox seguram
      (is (= 1 (count (caixa ente))) "criterio 1: reexecutar nao cria uma segunda notificacao")))

  (deftest autor-sem-identidade-vinculada-nao-notifica-e-nao-quebra
    (let [ente (random-uuid) vereador (random-uuid)]
      (publicar-norma-de-autor! ente vereador)
      (is (some? (drenar! (resolver-fixo {}))) "criterio 2: o relay segue drenando (nenhuma excecao)")
      (is (empty? (caixa ente)) "sem identidade resolvivel -> silencio honesto, nenhuma notificacao")))

  (deftest autor-nao-vereador-nao-notifica
    (let [ente (random-uuid)
          {pid :id} (legislativo-repo/protocolar! *leg* ente
                      {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                       :municipio-nome "Fortaleza" :ementa "De autoria do Executivo."
                       :autor-tipo "executivo" :autor-texto "Prefeitura"})]
      (let [{aid :id} (legislativo-repo/gerar-autografo! *leg* ente
                        {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
                         :destinatario-texto "Prefeito"})
            {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! *leg* ente
                        {:id (random-uuid) :autografo-id aid})]
        (legislativo-repo/registrar-resposta-executivo! *leg* ente
          {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
        (let [{nid :id} (legislativo-repo/promulgar-norma! *leg* ente
                          {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                           :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                           :data-promulgacao (LocalDate/of 2026 6 28) :ementa "De autoria do Executivo."
                           :texto-versao-id (random-uuid)})]
          (legislativo-repo/publicar-norma! *leg* ente
            {:id nid :veiculo-publicacao "DOM" :updated-by nil :lock-version 0})))
      (drenar! (resolver-fixo {}))
      (is (empty? (caixa ente)) "autor_tipo != 'vereador' -> nao ha' dono nominal, nao notifica")))
  ```
  > **Se `(some? (drenar! ...))` não casar** com o retorno real de `outbox/drenar!`, trocar o assert por `(is (nil? (drenar! ...)) ...)` — o que se prova é **não lançar**, não o valor.

- [ ] **Step 10: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.legislativo.notificacao-autor-test --reporter documentation
  ```
  Falha esperada: `ArityException Wrong number of args (2) passed to: oplenario.legislativo.diplomat.consumers/registrar` (o ns é um stub sem `registrar`) — na prática, `Unable to resolve symbol: registrar`.

- [ ] **Step 11: Leitura estreita do autor em `db/proposicao.clj`.**
  Acrescentar em `apps/backend/src/oplenario/legislativo/db/proposicao.clj` (depois de `buscar`):
  ```clojure
  (defn autor-vereador-da-proposicao
    "Onda E fatia 1: o `autor_id` da proposicao QUANDO o autor e' vereador — a resolucao 'dono nominal' da
    notificacao interna. SAME-SCHEMA (nunca cruza modulo, §22.10); leitura ESTREITA de proposito (so' o id;
    nada de ementa/jsonb — quem renderiza e' a norma). Autor de outro tipo (executivo/comissao/mesa) ou
    proposicao inexistente -> nil, e o consumer simplesmente nao notifica (silencio honesto)."
    [tx ente-id proposicao-id]
    (some-> (jdbc/execute-one! tx
              (sql/format {:select [:autor_id] :from [:legislativo.proposicoes]
                           :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]
                                   [:= :autor_tipo [:inline "vereador"]]
                                   [:is-not :autor_id nil]]}))
            :proposicoes/autor_id))
  ```
  > **Verificar o namespace da chave de retorno** ao implementar: as fns deste arquivo usam `linha->proposicao`/`comum/linha->kebab`. Se o alias da tabela produzir outra chave que não `:proposicoes/autor_id`, usar `(comum/linha->kebab ...)` e `:autor-id`. O contrato público desta fn é: **devolve um `java.util.UUID` ou `nil`**.

- [ ] **Step 12: Producer do evento.**
  Em `apps/backend/src/oplenario/legislativo/diplomat/producers.clj`: acrescentar `[oplenario.legislativo.events.notificacao :as ev-notificacao]` aos `:require` e, ao final:
  ```clojure
  (defn emitir-notificacao-requisitada!
    "Emite `notificacao.requisitada` no `bus` DENTRO da `tx` corrente (Onda E fatia 1). Aqui a `tx` e' a do
    RELAY (event-chaining, §22.9 E2: a linha nova commita junto com o dedup do evento-gatilho e o relay a
    drena na iteracao seguinte) — mesma mecanica do fan-out de transparencia. `payload` casa
    events.notificacao/RequisitadaPayload."
    [bus tx ente-id payload]
    (eventos/emitir! bus tx (ev-notificacao/requisitada ente-id payload)))
  ```

- [ ] **Step 13: O handler no Repo-Component de `legislativo`.**
  Em `apps/backend/src/oplenario/legislativo/components/repositorio.clj`: garantir nos `:require` `[clojure.tools.logging :as log]`, `[oplenario.kernel.outbox :as outbox]`, `[oplenario.kernel.tenancy :as tenancy]`, `[oplenario.legislativo.logic.notificacao :as logic-notif]` (acrescentar os que faltarem) e adicionar, **como fn plana** (fora do `defrecord`, ao lado dos demais defns de topo):
  ```clojure
  (defn notificar-autor-da-norma!
    "FABRICA do handler do 2o consumidor de `legislativo` (Onda E fatia 1): recebe o resolvedor injetado
    pelo HOST e devolve `(fn [tx evento])` registravel no bus.

    `resolver-identidade-do-vereador` = (fn [tx ente-id vereador-id] -> identidade-id | nil). E' o INVERSO do
    `resolver-vereador` de /meu/painel (§22.5.3, exceção nomeada): `legislativo` NUNCA importa `cadastros`;
    o host fecha sobre o repo de cadastros e injeta a fn. Recebe a `tx` DO RELAY de proposito — assim a
    resolucao roda na MESMA transacao/tenant, sem abrir conexao nova nem depender de um datasource ja'
    iniciado no momento em que o registro de consumidores e' montado (sistema.clj monta o registro ANTES do
    start dos components).

    FLUXO: proposicao_id -> autor_id (same-schema, autor_tipo='vereador') -> identidade -> emite
    `notificacao.requisitada` canal 'in_app'. Sem destinatario resolvivel (autor nao e' vereador, ou vereador
    sem identidade vinculada): NAO notifica, log/debug, segue — silencio honesto, nao erro (spec §4.1).

    NUNCA lanca (o relay e' COMPARTILHADO — mesmo racional de transparencia/fan-out-notificacao!): try/catch
    Throwable envolve TUDO, inclusive `set-tenant!` (que lanca em ente-id nil, e shared.outbox.ente_id e'
    NULLABLE) e a validacao Malli do construtor do evento. `Throwable`, nao `Exception`: as `:pre` de db/
    lancam AssertionError."
    [resolver-identidade-do-vereador]
    (fn [tx {:keys [ente-id payload]}]
      (try
        (tenancy/set-tenant! tx ente-id)
        (let [pid (UUID/fromString (:proposicao-id payload))
              nid (UUID/fromString (:norma-id payload))]
          (if-let [vereador-id (proposicao/autor-vereador-da-proposicao tx ente-id pid)]
            (if-let [identidade-id (resolver-identidade-do-vereador tx ente-id vereador-id)]
              (let [{:keys [assunto corpo]} (logic-notif/renderizar payload)
                    dest (str identidade-id)]
                (producers/emitir-notificacao-requisitada! (outbox/bus) tx ente-id
                  {:destinatario-identidade-id dest
                   :canal "in_app"
                   :consent-base "vinculo"
                   :idempotency-key (logic-notif/chave-idempotencia nid dest)
                   :categoria "norma_publicada"
                   :assunto assunto
                   :corpo corpo
                   :objeto-tipo "proposicao"
                   :objeto-id (str pid)}))
              (log/debug "legislativo: autor vereador sem identidade vinculada — norma publicada nao notificada"
                         {:ente-id ente-id :vereador-id vereador-id}))
            (log/debug "legislativo: norma publicada de autor nao-vereador — sem dono nominal a notificar"
                       {:ente-id ente-id :proposicao-id pid})))
        (catch Throwable e
          (log/warn e "legislativo: notificacao do autor tolerada (payload malformado ou falha de leitura)"
                    {:ente-id ente-id})
          nil))))
  ```
  > `payload` de `norma.publicada` carrega `:tipo-norma`, `:numero`, `:ano`, `:ementa`, `:urn` — exatamente o que `logic-notif/renderizar` consome; por isso ele é passado direto, sem reler a norma do banco.

- [ ] **Step 14: `registrar` em `legislativo/diplomat/consumers.clj`.**
  Substituir o stub inteiro por:
  ```clojure
  (ns oplenario.legislativo.diplomat.consumers
    "Inbound (§22.10 diplomat/consumers, ADR-0001, Onda E fatia 1): `legislativo` passa a ser consumidor do
    PROPRIO `norma.publicada` — o produtor da notificacao interna 'a sua proposicao virou lei'. Nome de
    consumidor PROPRIO (`legislativo-notificacao`) = dedup independente por (consumidor, key), exatamente
    como `transparencia-portal` x `transparencia-notificacao`: o portal e a inbox drenam o mesmo evento sem
    interferencia.

    O handler recebe o RESOLVEDOR injetado pelo HOST (identidade do vereador) — `legislativo` nunca importa
    `cadastros` (§22.10). O tipo do evento e' STRING LITERAL, nao import de events/ (contrato de fiacao do bus)."
    (:require [oplenario.kernel.outbox :as outbox]
              [oplenario.legislativo.components.repositorio :as repo]))

  (def ^:private nome-consumidor "legislativo-notificacao")

  (def tipos-consumidos
    "FONTE UNICA dos tipos consumidos por `legislativo` (hoje um so')."
    ["norma.publicada"])

  (defn registrar
    "Funde o handler de notificacao num `registro` EXISTENTE. `resolver-identidade-do-vereador` =
    (fn [tx ente-id vereador-id] -> identidade-id | nil), injetada pelo host (sistema.clj)."
    [registro resolver-identidade-do-vereador]
    (let [handler (repo/notificar-autor-da-norma! resolver-identidade-do-vereador)]
      (reduce (fn [r tipo] (outbox/registrar r nome-consumidor tipo handler))
              registro tipos-consumidos)))
  ```

- [ ] **Step 15: Rodar e ver passar.** Mesmo comando do Step 10. Esperado: `4 tests, ... 0 failures`.

- [ ] **Step 16: Fn plana no Repo de `cadastros` + fiação no host.**
  Em `apps/backend/src/oplenario/cadastros/components/repositorio.clj`, acrescentar como **fn plana** (fora do `defrecord`, junto dos defns de topo):
  ```clojure
  (defn identidade-do-vereador-em-tx
    "vereador-id -> identidade-id NESTA Casa, na `tx` JA' ABERTA do chamador (Onda E fatia 1). Fn PLANA (nao
    metodo do protocolo) de proposito: o chamador e' um consumer do relay, que ja' esta' dentro de uma tx —
    abrir `com-tenant*` aqui seria redundante e trocaria o role, quebrando o UPDATE seguinte do relay em
    shared.outbox (mesmo racional de `projetar-evento!`). Devolve nil quando o vereador nao existe neste ente
    ou nao tem identidade vinculada — o consumer trata como 'nao notifica', nunca como erro."
    [tx ente-id vereador-id]
    (:identidade-id (vereador/buscar tx ente-id vereador-id)))
  ```
  > Confirmar a chave devolvida por `vereador/buscar` (o `SELECT` traz `identidade_id`; as fns do módulo passam por `comum/linha->kebab`, logo `:identidade-id`). Se vier namespaced, ajustar o keyword — o contrato público é **UUID ou nil**.

  Em `apps/backend/src/oplenario/sistema.clj`: acrescentar aos `:require`
  ```clojure
              [oplenario.legislativo.diplomat.consumers :as legislativo-consumers]
  ```
  e, no `let` de `novo-sistema`, substituir a construção do `registro` por:
  ```clojure
        ;; Onda E fatia 1: `legislativo` entra como produtor da notificacao interna (2o consumidor do proprio
        ;; `norma.publicada`). O resolvedor vereador->identidade e' INJETADO aqui (inversao de dependencia,
        ;; §22.10 — legislativo nunca importa cadastros) e recebe a `tx` DO RELAY, entao nao depende de
        ;; nenhum component ja' iniciado no momento em que este registro e' montado.
        resolver-identidade-do-vereador (fn [tx ente-id vereador-id]
                                          (repo-cadastros/identidade-do-vereador-em-tx tx ente-id vereador-id))
        registro    (-> (tr-consumer/registro canal-store)
                        (transparencia-consumers/registrar)
                        (paineis-consumers/registrar)
                        (legislativo-consumers/registrar resolver-identidade-do-vereador))]
  ```

- [ ] **Step 17: Rodar o import-lint + a suíte inteira.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --skip :e2e --skip :keycloak --reporter documentation
  ```
  Esperado: 0 failures. O `arquitetura_test` deve continuar verde — `legislativo` não importa `cadastros` (só o host importa os dois) e o `db/` de cada módulo segue importado só pelo seu Repo-Component.

- [ ] **Step 18: Commit.**
  ```bash
  git add apps/backend/src/oplenario/legislativo/events/notificacao.clj \
          apps/backend/src/oplenario/legislativo/logic/notificacao.clj \
          apps/backend/src/oplenario/legislativo/db/proposicao.clj \
          apps/backend/src/oplenario/legislativo/diplomat/producers.clj \
          apps/backend/src/oplenario/legislativo/diplomat/consumers.clj \
          apps/backend/src/oplenario/legislativo/components/repositorio.clj \
          apps/backend/src/oplenario/cadastros/components/repositorio.clj \
          apps/backend/src/oplenario/sistema.clj \
          apps/backend/test/unit/oplenario/legislativo/notificacao_logic_test.clj \
          apps/backend/test/unit/oplenario/eventos_notificacao_contrato_test.clj \
          apps/backend/test/integration/oplenario/legislativo/notificacao_autor_test.clj
  git commit -m "feat(legislativo): norma publicada notifica o autor vereador na inbox interna"
  ```

---

### Task 6 — `GET /meu/notificacoes` (silhueta ADR-0001 completa)

**Files:**
- Create: `apps/backend/src/oplenario/paineis/wire/out/notificacao.clj`
- Create: `apps/backend/src/oplenario/paineis/adapters/out/notificacao.clj`
- Modify: `apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj`
- Modify: `apps/backend/src/oplenario/paineis/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/paineis/controllers.clj`
- Modify: `apps/backend/src/oplenario/paineis/diplomat/http/in.clj`
- Test: `apps/backend/test/integration/oplenario/paineis/minhas_notificacoes_http_in_test.clj`
- Test: `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj` (acrescentar deftests de db)

**Interfaces:**
- Consumes: `oplenario.http/json-resposta`, `oplenario.interceptors/autenticacao`.
- Produces:
  - `db.notificacao-caixa/listar-do-destinatario [tx ente-id destinatario-identidade-id] → [linha…]` (teto **50** no SQL, `ORDER BY criado_em DESC, id ASC`).
  - `db.notificacao-caixa/contar-nao-lidas [tx ente-id destinatario-identidade-id] → long`.
  - `components.repositorio/RepoPaineis` ganha `(minhas-notificacoes [this ente-id destinatario-identidade-id])` → `{:notificacoes [...] :nao-lidas long}` (uma única tx).
  - `controllers/minhas-notificacoes [repo-paineis ator] → {:notificacoes [...] :nao-lidas long}`.
  - `wire.out.notificacao/NotificacaoOut` = `{:id :categoria :assunto :corpo :objeto-tipo :objeto-id :criado-em :lida-em}` (todos string; `:lida-em` `[:maybe :string]`); `MinhasNotificacoesOut` = `{:notificacoes [...] :nao-lidas :int}`.
  - `adapters.out.notificacao/minhas-notificacoes->wire [{:keys [notificacoes nao-lidas]}] → MinhasNotificacoesOut`.
  - Rota `["/meu/notificacoes" :get [auth (minhas-notificacoes-handler repo-paineis)] :route-name :paineis/minhas-notificacoes]`.

**Steps:**

- [ ] **Step 1: Escrever o teste de db (vermelho).**
  Acrescentar ao final de `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj` (e ao `ns`, `[oplenario.paineis.components.repositorio :as paineis-repo]`; ligar `*paineis*` no fixture com `(paineis-repo/->RepoPaineisPg c)` — seguir a forma de `notificacao_test.clj`):
  ```clojure
  ;; ---------- Task 6: leitura "as minhas notificacoes" ----------

  (defn- semear! [ente dest n]
    (dotimes [i n] (inserir! ente dest (str "k-leitura-" i))))

  (deftest minhas-notificacoes-so-traz-as-do-proprio-destinatario
    (let [ente (random-uuid) eu (random-uuid) outro (random-uuid)]
      (semear! ente eu 2)
      (inserir! ente outro "k-do-outro")
      (let [{:keys [notificacoes nao-lidas]} (paineis-repo/minhas-notificacoes *paineis* ente eu)]
        (is (= 2 (count notificacoes)) "criterio 4: so' as minhas — a do outro ator nunca aparece")
        (is (= 2 nao-lidas) "contagem de nao lidas")
        (is (every? #(= eu (:destinatario-identidade-id %)) notificacoes)))))

  (deftest minhas-notificacoes-vazio
    (let [{:keys [notificacoes nao-lidas]} (paineis-repo/minhas-notificacoes *paineis* (random-uuid) (random-uuid))]
      (is (= [] notificacoes) "criterio 3: lista vazia")
      (is (= 0 nao-lidas) "criterio 3: contagem 0")))

  (deftest minhas-notificacoes-mais-recentes-primeiro-com-teto-no-sql
    (let [ente (random-uuid) eu (random-uuid)]
      (semear! ente eu 55)
      (let [{:keys [notificacoes nao-lidas]} (paineis-repo/minhas-notificacoes *paineis* ente eu)]
        (is (= 50 (count notificacoes)) "teto 50 aplicado no SQL (LIMIT), nunca em Clojure depois do fetch")
        (is (= 55 nao-lidas) "a contagem NAO e' limitada pelo teto — a UI nunca mente sobre o que existe")
        (is (apply >= (map (comp inst-ms :criado-em) notificacoes)) "mais recentes primeiro"))))

  (deftest isolamento-de-tenant-na-leitura
    (let [ente-a (random-uuid) ente-b (random-uuid) eu (random-uuid)]
      (inserir! ente-a eu "k-tenant-a")
      (is (= 1 (count (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente-a eu)))))
      (is (= 0 (count (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente-b eu))))
          "criterio 5: a MESMA identidade em outra Casa nao ve' nada")))
  ```
  > `inst-ms` exige `java.time.Instant` — se `:criado-em` vier como `Instant`, usar `(map #(.toEpochMilli ^java.time.Instant (:criado-em %)) ...)`. Ajustar o assert à representação real; o que se prova é a **ordem decrescente**.

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.paineis.notificacao-caixa-test --reporter documentation
  ```
  Falha esperada: `No such var: paineis-repo/minhas-notificacoes`.

- [ ] **Step 3: SQL de leitura.**
  Em `apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj`, acrescentar:
  ```clojure
  (def ^:private teto-inbox
    "Teto RIGIDO da inbox (spec §4.5). SEM PAGINACAO nesta fatia, de proposito: a resposta declara a
    contagem TOTAL de nao lidas, entao a UI nunca mente sobre o que existe. Paginacao entra quando um
    usuario real passar do teto. O teto e' aplicado no SQL (`:limit`), NUNCA em Clojure depois do fetch —
    cortar em memoria descarta os itens MAIS RECENTES (armadilha que ja' mordeu o projeto na F3)."
    50)

  (def ^:private colunas
    [:id :destinatario_identidade_id :categoria :assunto :corpo :objeto_tipo :objeto_id :criado_em :lida_em])

  (defn listar-do-destinatario
    "As notificacoes DO PROPRIO ator, mais recentes primeiro (`:id` asc como desempate estavel, mesma
    disciplina de db/proposicao/listar). `destinatario-identidade-id` vem SEMPRE do `(:ator req)` — nunca
    de path/query/corpo. Usa idx_notificacao_caixa_destinatario (mig 0062)."
    [tx ente-id destinatario-identidade-id]
    {:pre [(some? ente-id) (some? destinatario-identidade-id)]}
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format {:select colunas :from :paineis.notificacao_caixa
                    :where [:and [:= :ente_id ente-id]
                            [:= :destinatario_identidade_id destinatario-identidade-id]]
                    :order-by [[:criado_em :desc] [:id :asc]]
                    :limit teto-inbox}))))

  (defn contar-nao-lidas
    "Contagem TOTAL de nao lidas do ator — NAO limitada pelo teto da listagem (e' justamente o numero que
    impede a UI de mentir quando ha' mais de 50). Usa o indice PARCIAL idx_notificacao_caixa_nao_lidas."
    [tx ente-id destinatario-identidade-id]
    {:pre [(some? ente-id) (some? destinatario-identidade-id)]}
    (:c (jdbc/execute-one! tx
          (sql/format {:select [[[:count :*] :c]] :from :paineis.notificacao_caixa
                       :where [:and [:= :ente_id ente-id]
                               [:= :destinatario_identidade_id destinatario-identidade-id]
                               [:is :lida_em nil]]}))))
  ```
  > A chave devolvida por `execute-one!` para o alias `c` pode vir namespaced. Se vier, usar `(val (first ...))` ou o keyword correto; o contrato é **long**.

- [ ] **Step 4: Método no Repo-Component.**
  Em `apps/backend/src/oplenario/paineis/components/repositorio.clj`, acrescentar ao `defprotocol RepoPaineis`:
  ```clojure
    (minhas-notificacoes [this ente-id destinatario-identidade-id]
      "Inbox do PROPRIO ator (Onda E fatia 1): {:notificacoes [...] :nao-lidas n} numa UNICA tx do tenant
       (mesma disciplina de dashboard-mesa). O escopo por destinatario esta' no WHERE do SQL, junto do
       tenant — a authz fina desta rota NAO e' de papel, e' de posse.")
  ```
  e ao `defrecord RepoPaineisPg`:
  ```clojure
    (minhas-notificacoes [this ente-id destinatario-identidade-id]
      (transacao this ente-id
        (fn [tx]
          {:notificacoes (db-caixa/listar-do-destinatario tx ente-id destinatario-identidade-id)
           :nao-lidas    (db-caixa/contar-nao-lidas tx ente-id destinatario-identidade-id)})))
  ```

- [ ] **Step 5: Rodar e ver passar (db).** Mesmo comando do Step 2. Esperado: 12 tests, 0 failures.

- [ ] **Step 6: Escrever o teste da borda HTTP (vermelho).**
  `apps/backend/test/integration/oplenario/paineis/minhas_notificacoes_http_in_test.clj`:
  ```clojure
  (ns oplenario.paineis.minhas-notificacoes-http-in-test
    "Onda E fatia 1 (borda HTTP da inbox) — prova a silhueta end-to-end (controller -> repo -> adapters/out
    -> wire/out), o gate `auth` SEM PAPEL (notificacao e' endereçada a uma IDENTIDADE, nao a um cargo) e o
    escopo por ator. DB-free: RepoPaineis FAKE (reify) + idp-dev real (precedente pendencias-http-in-test)."
    (:require [clojure.test :refer [deftest is]]
              [io.pedestal.http :as ph]
              [io.pedestal.test :as pt]
              [jsonista.core :as json]
              [oplenario.config :as config]
              [oplenario.http :as http]
              [oplenario.identidade.components.repositorio :as repo-id]
              [oplenario.interceptors :as it]
              [oplenario.kernel.components.idp-dev :as idp-dev]
              [oplenario.paineis.components.repositorio :as repo-paineis]
              [oplenario.rotas :as rotas])
    (:import (java.time Instant)))

  (defn- fake-repo-paineis
    "Guarda o (ente-id, destinatario) recebido em `visto` e devolve `resultado` — assim o teste prova que a
    borda passa a IDENTIDADE DO ATOR, nunca um valor do request."
    [visto resultado]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify repo-paineis/RepoPaineis
      (minhas-notificacoes [_ ente-id dest] (reset! visto [ente-id dest]) resultado)))

  (defn- fake-repo-identidade [papeis]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify repo-id/RepoIdentidade
      (snapshot-ator [_ _ente-id _identidade-id]
        {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis papeis})))

  (defn- service-fn [papeis repo-p]
    (-> (http/servico (config/carregar)
                      (rotas/montar {:idp (idp-dev/idp-dev)
                                     :repo-identidade (fake-repo-identidade papeis)
                                     :repo-paineis repo-p})
                      it/globais)
        ph/create-server ::ph/service-fn))

  (defn- token [ente-id ident-id]
    (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
  (defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
  (defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

  (defn- notificacao-canonica [ente dest]
    {:id (random-uuid) :ente-id ente :destinatario-identidade-id dest :categoria "norma_publicada"
     :assunto "A sua proposicao virou lei — Lei 3/2026" :corpo "Ementa: ..."
     :objeto-tipo "proposicao" :objeto-id (random-uuid)
     :criado-em (Instant/parse "2026-07-19T12:00:00Z") :lida-em nil})

  (deftest minhas-notificacoes-200
    (let [ente (random-uuid) eu (random-uuid) visto (atom nil)
          repo (fake-repo-paineis visto {:notificacoes [(notificacao-canonica ente eu)] :nao-lidas 1})
          r (pt/response-for (service-fn #{"vereador"} repo)
                             :get "/meu/notificacoes" :headers (com-bearer (token ente eu)))
          body (ler-json r)]
      (is (= 200 (:status r)))
      (is (= [ente eu] @visto) "a identidade vem do ATOR, nunca do request")
      (is (= 1 (:nao-lidas body)))
      (let [n (first (:notificacoes body))]
        (is (= "norma_publicada" (:categoria n)))
        (is (string? (:id n)) "uuid projetado como string")
        (is (= "2026-07-19T12:00:00Z" (:criado-em n)) "instante como string ISO")
        (is (nil? (:lida-em n)) "nao lida -> null")
        (is (not (contains? n :ente-id)) "tenant nao vaza")
        (is (not (contains? n :destinatario-identidade-id)) "o destinatario nao volta no wire (e' sempre 'eu')"))))

  (deftest minhas-notificacoes-vazio-200
    (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis (atom nil) {:notificacoes [] :nao-lidas 0}))
                             :get "/meu/notificacoes" :headers (com-bearer (token (random-uuid) (random-uuid))))
          body (ler-json r)]
      (is (= 200 (:status r)) "criterio 3: ator sem notificacao -> 200")
      (is (= [] (:notificacoes body)))
      (is (= 0 (:nao-lidas body)))))

  (deftest minhas-notificacoes-sem-papel-tambem-200
    ;; gate AUTH APENAS (spec §4.5): a notificacao e' endereçada a uma identidade, nao a um cargo — um
    ;; servidor sem papel de vereador tem inbox propria e deve conseguir le-la.
    (let [r (pt/response-for (service-fn #{} (fake-repo-paineis (atom nil) {:notificacoes [] :nao-lidas 0}))
                             :get "/meu/notificacoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
      (is (= 200 (:status r)) "sem papel nenhum -> ainda 200 (o escopo e' de POSSE, nao de cargo)")))

  (deftest minhas-notificacoes-sem-token-401
    (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis (atom nil) {:notificacoes [] :nao-lidas 0}))
                             :get "/meu/notificacoes")]
      (is (= 401 (:status r)) "fail-closed: sem credencial -> 401")))
  ```

- [ ] **Step 7: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.paineis.minhas-notificacoes-http-in-test --reporter documentation
  ```
  Falha esperada: `404` no lugar de `200` (rota inexistente).

- [ ] **Step 8: `wire/out`.**
  `apps/backend/src/oplenario/paineis/wire/out/notificacao.clj`:
  ```clojure
  (ns oplenario.paineis.wire.out.notificacao
    "Representacao EXTERNA de SAIDA da INBOX (§22.10 wire/out, ADR-0001, Onda E fatia 1) — o contrato que
    `adapters/out` produz e o codegen exporta p/ o TS. Tudo JSON-serializavel (uuid/Instant viram string).
    `categoria`/`objeto-tipo` ficam :string (nao enum fechado): a inbox e' PROJECAO de um evento cujo
    vocabulario e' validado na FONTE (o produtor), nao duplicado aqui — mesmo racional de wire/out/pendencia.

    O `destinatario` NAO faz parte do contrato: a rota e' sempre 'as minhas', resolvida do ator; devolve-lo
    seria vazar um identificador que o cliente nao precisa e nao pode usar para nada.")

  (def NotificacaoOut
    [:map {:closed true}
     [:id :string]
     [:categoria :string]
     [:assunto :string]
     [:corpo :string]
     [:objeto-tipo :string]
     [:objeto-id :string]
     [:criado-em :string]
     ;; null = nao lida (o estado de leitura E' a ausencia do carimbo)
     [:lida-em [:maybe :string]]])

  (def MinhasNotificacoesOut
    "Resposta de GET /meu/notificacoes: a lista (teto 50, mais recentes primeiro) + a contagem TOTAL de nao
    lidas (NAO limitada pelo teto — e' o que impede a UI de mentir quando ha' mais que o teto)."
    [:map {:closed true}
     [:notificacoes [:sequential NotificacaoOut]]
     [:nao-lidas :int]])

  (def MarcarLidaOut
    "Resposta de POST /meu/notificacoes/:id/lida (Task 7): o recibo idempotente."
    [:map {:closed true}
     [:id :string]
     [:lida-em :string]])
  ```

- [ ] **Step 9: `adapters/out`.**
  `apps/backend/src/oplenario/paineis/adapters/out/notificacao.clj`:
  ```clojure
  (ns oplenario.paineis.adapters.out.notificacao
    "Gate de SAIDA `models -> wire/out` da INBOX (§22.10 adapters/out, ADR-0001) — chamado SO pelo diplomat.
    Projeta o read-model (kebab, do db) p/ a representacao externa (strings) e FILTRA `ente-id` e
    `destinatario-identidade-id`, que nunca vazam. Validada contra o wire (drift de campo = bug de servidor
    -> 500, nunca resposta malformada)."
    (:require [malli.core :as m]
              [malli.error :as me]
              [oplenario.paineis.wire.out.notificacao :as wire]))

  (set! *warn-on-reflection* true)

  (defn- ->str [x] (some-> x str))

  (defn- notificacao->wire [n]
    {:id (->str (:id n)) :categoria (:categoria n) :assunto (:assunto n) :corpo (:corpo n)
     :objeto-tipo (:objeto-tipo n) :objeto-id (->str (:objeto-id n))
     :criado-em (->str (:criado-em n)) :lida-em (->str (:lida-em n))})

  (defn minhas-notificacoes->wire
    "{:notificacoes [...] :nao-lidas n} -> MinhasNotificacoesOut (validada)."
    [{:keys [notificacoes nao-lidas]}]
    (let [out {:notificacoes (mapv notificacao->wire notificacoes) :nao-lidas (int (or nao-lidas 0))}]
      (when-not (m/validate wire/MinhasNotificacoesOut out)
        (throw (ex-info "projecao da inbox viola o contrato MinhasNotificacoesOut (bug de servidor)"
                        {:erros (me/humanize (m/explain wire/MinhasNotificacoesOut out))})))
      out))

  (defn marcar-lida->wire
    "Recibo de marcacao de leitura -> MarcarLidaOut (validada). Task 7."
    [recibo]
    (let [out {:id (->str (:id recibo)) :lida-em (->str (:lida-em recibo))}]
      (when-not (m/validate wire/MarcarLidaOut out)
        (throw (ex-info "recibo de leitura viola o contrato MarcarLidaOut (bug de servidor)"
                        {:erros (me/humanize (m/explain wire/MarcarLidaOut out))})))
      out))
  ```

- [ ] **Step 10: Controller + rota.**
  Em `apps/backend/src/oplenario/paineis/controllers.clj`, acrescentar:
  ```clojure
  (defn minhas-notificacoes
    "Inbox do PROPRIO `ator` (Onda E fatia 1). Diferente dos demais paineis deste modulo, o escopo NAO e'
    tenant-wide: e' (tenant, identidade do ator). A identidade vem SEMPRE do ator — nada no request escolhe
    'de quem' e' a inbox (anti-forja por construcao, mesmo contrato de /meu/painel do legislativo)."
    [repo-paineis ator]
    (repo/minhas-notificacoes repo-paineis (:ente-id ator) (:identidade-id ator)))
  ```
  Em `apps/backend/src/oplenario/paineis/diplomat/http/in.clj`: acrescentar `[oplenario.paineis.adapters.out.notificacao :as adapters-out-notificacao]` aos `:require`, o handler:
  ```clojure
  (defn- minhas-notificacoes-handler
    "GET /meu/notificacoes (Onda E fatia 1). Gate `auth` APENAS, SEM papel: a notificacao e' endereçada a uma
    IDENTIDADE, nao a um cargo — exigir 'vereador' deixaria de fora servidores que tambem terao inbox. A
    authz fina e' de POSSE e mora no WHERE do SQL, junto do tenant."
    [repo-paineis]
    (fn [req]
      (http/json-resposta 200 (adapters-out-notificacao/minhas-notificacoes->wire
                               (controllers/minhas-notificacoes repo-paineis (:ator req))))))
  ```
  e, no set de `rotas`, acrescentar:
  ```clojure
      ["/meu/notificacoes" :get [auth (minhas-notificacoes-handler repo-paineis)]
       :route-name :paineis/minhas-notificacoes]
  ```
  Atualizar a docstring de `rotas` acrescentando: *"`GET /meu/notificacoes` (Onda E) é a única rota do módulo SEM gate de papel — só `auth`; ver a docstring do handler."*

- [ ] **Step 11: Rodar e ver passar (borda).** Mesmo comando do Step 7. Esperado: `4 tests, ... 0 failures`.

- [ ] **Step 12: Commit.**
  ```bash
  git add apps/backend/src/oplenario/paineis/wire/out/notificacao.clj \
          apps/backend/src/oplenario/paineis/adapters/out/notificacao.clj \
          apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj \
          apps/backend/src/oplenario/paineis/components/repositorio.clj \
          apps/backend/src/oplenario/paineis/controllers.clj \
          apps/backend/src/oplenario/paineis/diplomat/http/in.clj \
          apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj \
          apps/backend/test/integration/oplenario/paineis/minhas_notificacoes_http_in_test.clj
  git commit -m "feat(paineis): GET /meu/notificacoes (inbox do proprio ator, teto no SQL)"
  ```

---

### Task 7 — `POST /meu/notificacoes/:id/lida` (idempotente, guard de posse no mesmo WHERE)

**Files:**
- Create: `apps/backend/src/oplenario/paineis/adapters/in/notificacao.clj`
- Modify: `apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj`
- Modify: `apps/backend/src/oplenario/paineis/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/paineis/controllers.clj`
- Modify: `apps/backend/src/oplenario/paineis/diplomat/http/in.clj`
- Test: `apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj` (acrescentar)
- Test: `apps/backend/test/integration/oplenario/paineis/minhas_notificacoes_http_in_test.clj` (acrescentar)

**Interfaces:**
- Produces:
  - `adapters.in.notificacao/id-param->uuid [s] → UUID | nil` (malformado = `nil`, a borda traduz para 404).
  - `db.notificacao-caixa/marcar-lida! [tx {:keys [ente-id id destinatario-identidade-id]}] → {:id :lida-em} | nil`.
  - `components.repositorio/RepoPaineis` ganha `(marcar-notificacao-lida! [this ente-id m])`.
  - `controllers/marcar-lida [repo-paineis ator id] → {:id :lida-em} | nil`.
  - Rota `["/meu/notificacoes/:id/lida" :post [auth (marcar-lida-handler repo-paineis)] :route-name :paineis/marcar-notificacao-lida]`.

**Steps:**

- [ ] **Step 1: Escrever os testes de db (vermelho).**
  Acrescentar em `notificacao_caixa_test.clj`:
  ```clojure
  ;; ---------- Task 7: marcar como lida ----------

  (defn- id-da-unica [ente dest]
    (:id (first (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente dest)))))

  (deftest marcar-lida-e-idempotente
    (let [ente (random-uuid) eu (random-uuid)]
      (inserir! ente eu "k-lida")
      (let [id (id-da-unica ente eu)
            r1 (paineis-repo/marcar-notificacao-lida! *paineis* ente {:id id :destinatario-identidade-id eu})
            r2 (paineis-repo/marcar-notificacao-lida! *paineis* ente {:id id :destinatario-identidade-id eu})]
        (is (some? (:lida-em r1)) "1a chamada carimba")
        (is (= (:lida-em r1) (:lida-em r2))
            "criterio 6: 2a chamada nao muda lida_em nem devolve erro (COALESCE preserva o 1o carimbo)")
        (is (= 0 (:nao-lidas (paineis-repo/minhas-notificacoes *paineis* ente eu))) "sai da contagem"))))

  (deftest marcar-lida-de-outro-destinatario-e-nil
    (let [ente (random-uuid) eu (random-uuid) outro (random-uuid)]
      (inserir! ente outro "k-do-outro-2")
      (let [id (id-da-unica ente outro)]
        (is (nil? (paineis-repo/marcar-notificacao-lida! *paineis* ente {:id id :destinatario-identidade-id eu}))
            "criterio 4: nem com o id em maos — o guard de posse esta' no MESMO WHERE do tenant")
        (is (nil? (:lida-em (first (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente outro)))))
            "a notificacao do outro continua NAO lida"))))

  (deftest marcar-lida-id-inexistente-e-nil
    (is (nil? (paineis-repo/marcar-notificacao-lida! *paineis* (random-uuid)
                {:id (random-uuid) :destinatario-identidade-id (random-uuid)}))))
  ```

- [ ] **Step 2: Rodar e ver falhar.** (comando do Task 6 Step 2). Falha: `No such var: paineis-repo/marcar-notificacao-lida!`.

- [ ] **Step 3: SQL da marcação.**
  Em `db/notificacao_caixa.clj`:
  ```clojure
  (defn marcar-lida!
    "Marca a notificacao como lida. IDEMPOTENTE por `COALESCE(lida_em, now())`: a 2a chamada re-grava o
    MESMO carimbo (nao move a data) e devolve o mesmo recibo — criterio de aceitacao 6.

    POR QUE COALESCE, e nao `WHERE lida_em IS NULL` (a forma literal da spec §4.5): com o WHERE, a 2a
    chamada atualizaria 0 linhas e a borda nao teria como distinguir 'ja' lida' de 'nao existe / nao e'
    sua' — devolveria 404 para uma operacao legitima. Com COALESCE, update-count 0 significa EXATAMENTE
    uma coisa: a linha nao existe OU nao e' do ator. O efeito visivel e' o mesmo (o carimbo nunca se move).

    ANTI-CONFUSED-DEPUTY: `destinatario_identidade_id` esta' no MESMO WHERE do `ente_id` — marcar a
    notificacao de outra pessoa nao e' possivel nem com o id adivinhado. Devolve {:id :lida-em} ou nil."
    [tx {:keys [ente-id id destinatario-identidade-id]}]
    {:pre [(some? ente-id) (some? id) (some? destinatario-identidade-id)]}
    (comum/linha->kebab
     (jdbc/execute-one! tx
       (sql/format {:update :paineis.notificacao_caixa
                    :set {:lida_em [:coalesce :lida_em [:now]]}
                    :where [:and [:= :ente_id ente-id] [:= :id id]
                            [:= :destinatario_identidade_id destinatario-identidade-id]]
                    :returning [:id :lida_em]}))))
  ```

- [ ] **Step 4: Repo + controller.**
  Em `components/repositorio.clj`, no `defprotocol`:
  ```clojure
    (marcar-notificacao-lida! [this ente-id m]
      "Marca como lida a notificacao `(:id m)` do destinatario `(:destinatario-identidade-id m)` — guard de
       posse no MESMO WHERE do tenant. Idempotente; devolve {:id :lida-em} ou nil (inexistente/nao e' sua).")
  ```
  e no `defrecord`:
  ```clojure
    (marcar-notificacao-lida! [this ente-id m]
      (transacao this ente-id #(db-caixa/marcar-lida! % (assoc m :ente-id ente-id))))
  ```
  Em `controllers.clj`:
  ```clojure
  (defn marcar-lida
    "Marca uma notificacao do PROPRIO ator como lida (Onda E fatia 1). O destinatario e' SEMPRE o do ator —
    nunca do path/corpo (anti-forja). nil = inexistente OU de outro destinatario: a borda traduz os DOIS
    para 404, sem distingui-los (nao vaza existencia)."
    [repo-paineis ator id]
    (repo/marcar-notificacao-lida! repo-paineis (:ente-id ator)
                                   {:id id :destinatario-identidade-id (:identidade-id ator)}))
  ```

- [ ] **Step 5: Rodar e ver passar (db).** Mesmo comando. Esperado: 15 tests, 0 failures.

- [ ] **Step 6: Escrever os testes de borda (vermelho).**
  Acrescentar em `minhas_notificacoes_http_in_test.clj` (e estender o `fake-repo-paineis` para aceitar também o resultado da marcação):
  ```clojure
  (defn- fake-repo-marcacao
    "RepoPaineis fake para POST: guarda o `m` recebido e devolve `resultado` (ou nil = nao e' sua/inexistente)."
    [visto resultado]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify repo-paineis/RepoPaineis
      (marcar-notificacao-lida! [_ ente-id m] (reset! visto [ente-id m]) resultado)))

  (deftest marcar-lida-200
    (let [ente (random-uuid) eu (random-uuid) id (random-uuid) visto (atom nil)
          r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao visto {:id id :lida-em (Instant/parse "2026-07-19T13:00:00Z")}))
                             :post (str "/meu/notificacoes/" id "/lida")
                             :headers (com-bearer (token ente eu)))
          body (ler-json r)]
      (is (= 200 (:status r)))
      (is (= ente (first @visto)))
      (is (= eu (:destinatario-identidade-id (second @visto)))
          "o destinatario e' o do ATOR, nunca do path")
      (is (= (str id) (:id body)))
      (is (= "2026-07-19T13:00:00Z" (:lida-em body)))))

  (deftest marcar-lida-de-outro-ator-404
    (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao (atom nil) nil))
                             :post (str "/meu/notificacoes/" (random-uuid) "/lida")
                             :headers (com-bearer (token (random-uuid) (random-uuid))))]
      (is (= 404 (:status r)) "criterio 4: id de outro destinatario -> 404, nunca 200 silencioso")))

  (deftest marcar-lida-id-malformado-404
    (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao (atom nil) nil))
                             :post "/meu/notificacoes/nao-e-uuid/lida"
                             :headers (com-bearer (token (random-uuid) (random-uuid))))]
      (is (= 404 (:status r)) "id que nao parseia = recurso inexistente (nao vaza nada, nunca 500)")))

  (deftest marcar-lida-sem-token-401
    (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-marcacao (atom nil) nil))
                             :post (str "/meu/notificacoes/" (random-uuid) "/lida"))]
      (is (= 401 (:status r)))))
  ```

- [ ] **Step 7: Rodar e ver falhar.** (comando do Task 6 Step 7). Falha: `404` em `marcar-lida-200` (rota inexistente).

- [ ] **Step 8: `adapters/in` + handler + rota.**
  `apps/backend/src/oplenario/paineis/adapters/in/notificacao.clj`:
  ```clojure
  (ns oplenario.paineis.adapters.in.notificacao
    "Gate de ENTRADA da borda de escrita da inbox (§22.10 adapters/in, ADR-0001, Onda E fatia 1). O unico
    dado que vem do cliente e' o `:id` do path — o destinatario NUNCA (vem do ator).

    DIFERENCA DELIBERADA em relacao a `legislativo/adapters/in/votacao/id-param->uuid` (que lanca
    :validacao/invalido -> 400): aqui um id malformado devolve `nil` e a borda traduz p/ 404, igual ao id
    inexistente e ao id de outro destinatario. Motivo: a spec §4.5 exige que os tres casos sejam
    INDISTINGUIVEIS — um 400 so' para o malformado revelaria que o formato do id e' checado antes da posse,
    dando ao atacante um oraculo de forma. Nenhum caminho legitimo do FE manda id malformado."
    (:import (java.util UUID)))

  (set! *warn-on-reflection* true)

  (defn id-param->uuid
    "Path-param (string) -> UUID, ou nil se nao parseia (a borda traduz p/ 404 — ver docstring do ns)."
    [s]
    (try (UUID/fromString s) (catch Exception _ nil)))
  ```
  Em `diplomat/http/in.clj`, acrescentar `[oplenario.paineis.adapters.in.notificacao :as adapters-in-notificacao]` aos `:require`, o handler:
  ```clojure
  (defn- marcar-lida-handler
    "POST /meu/notificacoes/:id/lida (Onda E fatia 1). Gate `auth` apenas (mesmo racional do GET). O
    destinatario e' SEMPRE o do ator; o `:id` do path e' o UNICO dado do cliente. Id malformado, inexistente
    OU de outro destinatario -> 404, sem distincao (nunca 200 silencioso, nunca vaza existencia)."
    [repo-paineis]
    (fn [req]
      (if-let [id (adapters-in-notificacao/id-param->uuid (get-in req [:path-params :id]))]
        (if-let [recibo (controllers/marcar-lida repo-paineis (:ator req) id)]
          (http/json-resposta 200 (adapters-out-notificacao/marcar-lida->wire recibo))
          (http/json-resposta 404 {:erro "notificacao nao encontrada"}))
        (http/json-resposta 404 {:erro "notificacao nao encontrada"}))))
  ```
  e a rota:
  ```clojure
      ["/meu/notificacoes/:id/lida" :post [auth (marcar-lida-handler repo-paineis)]
       :route-name :paineis/marcar-notificacao-lida]
  ```

- [ ] **Step 9: Rodar e ver passar (borda).** Mesmo comando. Esperado: `8 tests, ... 0 failures`.

- [ ] **Step 10: Suíte inteira + lint.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --skip :e2e --skip :keycloak --reporter documentation
  docker run --rm -v "$(pwd)":/app -w /app/apps/backend -v oplenario_backend_m2:/root/.m2 \
    clojure:temurin-21-tools-deps clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2026.05.25"}}}' -M -m clj-kondo.main --lint src test demo
  ```
  Esperado: 0 failures; `linting took Nms, errors: 0, warnings: 0`.

- [ ] **Step 11: Commit.**
  ```bash
  git add apps/backend/src/oplenario/paineis/adapters/in/notificacao.clj \
          apps/backend/src/oplenario/paineis/db/notificacao_caixa.clj \
          apps/backend/src/oplenario/paineis/components/repositorio.clj \
          apps/backend/src/oplenario/paineis/controllers.clj \
          apps/backend/src/oplenario/paineis/diplomat/http/in.clj \
          apps/backend/test/integration/oplenario/paineis/notificacao_caixa_test.clj \
          apps/backend/test/integration/oplenario/paineis/minhas_notificacoes_http_in_test.clj
  git commit -m "feat(paineis): POST /meu/notificacoes/:id/lida idempotente com guard de posse"
  ```

---

### Task 8 — Codegen `gerar_paineis.clj`

**Files:**
- Create: `apps/backend/src/oplenario/codegen/gerar_paineis.clj`
- Create: `apps/backend/test/unit/oplenario/codegen/gerar_paineis_test.clj`
- Create (gerado): `apps/frontend/src/lib/contrato-paineis.gen.ts`

**Interfaces:**
- Consumes: `oplenario.codegen.malli-ts/gerar`, `paineis.wire.out.notificacao/{NotificacaoOut,MinhasNotificacoesOut,MarcarLidaOut}`.
- Produces: `oplenario.codegen.gerar-paineis/manifesto`, `gerar-tudo`, `-main`; e as interfaces TS `NotificacaoOut`, `MinhasNotificacoesOut`, `MarcarLidaOut` em `apps/frontend/src/lib/contrato-paineis.gen.ts`.

**Steps:**

- [ ] **Step 1: Escrever o teste (vermelho).**
  `apps/backend/test/unit/oplenario/codegen/gerar_paineis_test.clj`:
  ```clojure
  (ns oplenario.codegen.gerar-paineis-test
    "Unit: o manifesto do codegen do modulo PAINEIS (Onda E fatia 1) — espelha gerar-portal-test. `paineis`
    ainda nao tinha manifesto proprio (existiam gerar/gerar-legislativo/gerar-cadastros/gerar-portal); os
    tipos da Mesa/board saem por `gerar.clj` (host-level, FE Onda A1) e ficam onde estao."
    (:require [clojure.test :refer [deftest is]]
              [clojure.string :as str]
              [oplenario.codegen.gerar-paineis :as gerar-paineis]))

  (deftest gerar-tudo-emite-as-interfaces-da-inbox-com-banner
    (let [out (gerar-paineis/gerar-tudo)]
      (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
      (is (every? #(str/includes? out (str "export interface " % " {"))
                  ["NotificacaoOut" "MinhasNotificacoesOut" "MarcarLidaOut"])
          "as 3 interfaces do manifesto presentes")
      (is (not (re-find #": unknown;" out)) "nenhum campo caiu no fallback bare 'unknown'")))

  (deftest minhas-notificacoes-referencia-notificacao-por-nome
    ;; NotificacaoOut vem ANTES no manifesto p/ a igualdade estrutural casar no campo aninhado
    ;; (mesmo racional de NormaOut/FichaOut em gerar-portal).
    (let [out (gerar-paineis/gerar-tudo)]
      (is (str/includes? out "notificacoes: NotificacaoOut[];")
          "referencia nomeada, nao Record<string, unknown> inlinado")))

  (deftest lida-em-e-nulavel-no-contrato
    (let [out (gerar-paineis/gerar-tudo)]
      (is (str/includes? out "lidaEm: string | null;")
          "null = nao lida — o FE precisa enxergar isso no tipo")))
  ```
  > O nome do campo no TS (`lidaEm` vs `lida-em`) depende do camelizador do `malli-ts`. Conferir a saída real de um `.gen.ts` existente (ex.: `contrato-legislativo.gen.ts` usa `urnLex`) — **camelCase confirmado**; se por acaso divergir, ajustar o assert à saída real, nunca o gerador.

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    clojure:temurin-21-tools-deps clojure -M:test --focus oplenario.codegen.gerar-paineis-test --reporter documentation
  ```
  Falha esperada: `Could not locate oplenario/codegen/gerar_paineis__init.class`.

- [ ] **Step 3: Implementar o manifesto.**
  `apps/backend/src/oplenario/codegen/gerar_paineis.clj`:
  ```clojure
  (ns oplenario.codegen.gerar-paineis
    "Entrypoint do codegen Malli->TS do modulo PAINEIS (Onda E fatia 1). Espelha oplenario.codegen.gerar-portal
    (mesmo racional/ferramenta, manifesto proprio). Ate' aqui `paineis` nao tinha manifesto: os tipos da Mesa e
    do board saem por `gerar.clj` (host-level, FE Onda A1) e permanecem la' — este arquivo nasce com a INBOX e
    e' o lugar onde os proximos wire/out do modulo entram. Roda via:
      clojure -M -m oplenario.codegen.gerar-paineis [caminho-de-saida]
    Default = target/generated-ts/contrato-paineis.gen.ts."
    (:require [clojure.java.io :as io]
              [oplenario.codegen.malli-ts :as ts]
              [oplenario.paineis.wire.out.notificacao :as notificacao]))

  (def manifesto
    "NotificacaoOut ANTES de MinhasNotificacoesOut (referencia nomeada: o campo :notificacoes aninha
    NotificacaoOut — a igualdade estrutural exige a entrada ja' presente no mapa nome-por-schema, mesmo
    racional do manifesto do portal)."
    [["NotificacaoOut" notificacao/NotificacaoOut]
     ["MinhasNotificacoesOut" notificacao/MinhasNotificacoesOut]
     ["MarcarLidaOut" notificacao/MarcarLidaOut]])

  (defn gerar-tudo [] (ts/gerar manifesto))

  (defn -main [& [saida]]
    (let [caminho (or saida "target/generated-ts/contrato-paineis.gen.ts")
          conteudo (gerar-tudo)]
      (io/make-parents caminho)
      (spit caminho conteudo)
      (println "[oplenario] tipos TS de paineis gerados em" caminho "(" (count manifesto) "interfaces)")))
  ```

- [ ] **Step 4: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `3 tests, ... 0 failures`.

- [ ] **Step 5: Gerar o arquivo TS de verdade.**
  ```bash
  docker run --rm -v "$(pwd)":/app -w /app/apps/backend -v oplenario_backend_m2:/root/.m2 \
    clojure:temurin-21-tools-deps \
    clojure -M -m oplenario.codegen.gerar-paineis ../frontend/src/lib/contrato-paineis.gen.ts
  ```
  Esperado: `[oplenario] tipos TS de paineis gerados em ../frontend/src/lib/contrato-paineis.gen.ts ( 3 interfaces)`.
  Conferir: `head -20 apps/frontend/src/lib/contrato-paineis.gen.ts` deve abrir com `// GERADO por oplenario.codegen.malli-ts …` e conter `export interface NotificacaoOut {`.

- [ ] **Step 6: Type-check do frontend.**
  ```bash
  docker exec oplenario-frontend-1 npx tsc --noEmit
  ```
  Esperado: sem saída (0 erros).

- [ ] **Step 7: Commit.**
  ```bash
  git add apps/backend/src/oplenario/codegen/gerar_paineis.clj \
          apps/backend/test/unit/oplenario/codegen/gerar_paineis_test.clj \
          apps/frontend/src/lib/contrato-paineis.gen.ts
  git commit -m "feat(codegen): manifesto Malli->TS do paineis (contrato da inbox)"
  ```

---

### Task 9 — FE: view-model puro `notificacoes-vista.ts`

**Files:**
- Create: `apps/frontend/src/lib/notificacoes-vista.ts`
- Create: `apps/frontend/src/lib/notificacoes-vista.test.ts`

**Interfaces:**
- Consumes: `MinhasNotificacoesOut`, `NotificacaoOut` de `./contrato-paineis.gen`.
- Produces:
  - `type GrupoTemporal = "hoje" | "semana" | "antes"`.
  - `interface NotificacaoVista { id, categoria, assunto, corpo, objetoTipo, objetoId, criadoEm, lida: boolean, href: string, quando: string }`.
  - `interface GrupoVista { chave: GrupoTemporal; rotulo: string; itens: NotificacaoVista[] }`.
  - `interface InboxVista { grupos: GrupoVista[]; naoLidas: number; vazia: boolean }`.
  - `derivarInbox(dados: MinhasNotificacoesOut | null | undefined, agoraIso?: string): InboxVista`.

**Steps:**

- [ ] **Step 1: Escrever os testes (vermelho).**
  `apps/frontend/src/lib/notificacoes-vista.test.ts`:
  ```ts
  import { describe, expect, it } from "vitest";
  import { derivarInbox } from "./notificacoes-vista";
  import type { MinhasNotificacoesOut } from "./contrato-paineis.gen";

  const AGORA = "2026-07-19T15:00:00Z";

  function n(id: string, criadoEm: string, lidaEm: string | null = null) {
    return {
      id,
      categoria: "norma_publicada",
      assunto: `Assunto ${id}`,
      corpo: "Ementa: ...",
      objetoTipo: "proposicao",
      objetoId: `pid-${id}`,
      criadoEm,
      lidaEm,
    };
  }

  function dados(itens: ReturnType<typeof n>[], naoLidas = itens.length): MinhasNotificacoesOut {
    return { notificacoes: itens, naoLidas } as unknown as MinhasNotificacoesOut;
  }

  describe("derivarInbox", () => {
    it("sem dados -> estrutura vazia coerente, nunca lança", () => {
      const v = derivarInbox(null, AGORA);
      expect(v.grupos).toEqual([]);
      expect(v.naoLidas).toBe(0);
      expect(v.vazia).toBe(true);
    });

    it("agrupa por Hoje / Esta semana / Antes", () => {
      const v = derivarInbox(
        dados([
          n("a", "2026-07-19T09:00:00Z"), // hoje
          n("b", "2026-07-16T09:00:00Z"), // 3 dias atrás -> esta semana
          n("c", "2026-06-01T09:00:00Z"), // antes
        ]),
        AGORA
      );
      expect(v.grupos.map((g) => g.chave)).toEqual(["hoje", "semana", "antes"]);
      expect(v.grupos.map((g) => g.rotulo)).toEqual(["Hoje", "Esta semana", "Antes"]);
      expect(v.grupos[0].itens.map((i) => i.id)).toEqual(["a"]);
      expect(v.grupos[2].itens.map((i) => i.id)).toEqual(["c"]);
    });

    it("grupo sem itens não aparece (nada de seção vazia na tela)", () => {
      const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")]), AGORA);
      expect(v.grupos).toHaveLength(1);
      expect(v.grupos[0].chave).toBe("hoje");
    });

    it("preserva a ordem do servidor dentro do grupo (mais recentes primeiro)", () => {
      const v = derivarInbox(
        dados([n("nova", "2026-07-19T14:00:00Z"), n("velha", "2026-07-19T08:00:00Z")]),
        AGORA
      );
      expect(v.grupos[0].itens.map((i) => i.id)).toEqual(["nova", "velha"]);
    });

    it("deriva `lida` da presença do carimbo, não de um booleano do servidor", () => {
      const v = derivarInbox(
        dados([n("lida", "2026-07-19T09:00:00Z", "2026-07-19T10:00:00Z"), n("nova", "2026-07-19T09:30:00Z")], 1),
        AGORA
      );
      const itens = v.grupos[0].itens;
      expect(itens.find((i) => i.id === "lida")!.lida).toBe(true);
      expect(itens.find((i) => i.id === "nova")!.lida).toBe(false);
      expect(v.naoLidas).toBe(1);
    });

    it("href aponta para a ficha da matéria (destino do clique)", () => {
      const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")]), AGORA);
      expect(v.grupos[0].itens[0].href).toBe("/ficha-materia/pid-a");
    });

    it("objeto de tipo desconhecido não gera link quebrado", () => {
      const item = { ...n("a", "2026-07-19T09:00:00Z"), objetoTipo: "coisa_nova" };
      const v = derivarInbox(dados([item]), AGORA);
      expect(v.grupos[0].itens[0].href).toBe("");
    });

    it("`quando` é relativo e legível", () => {
      const v = derivarInbox(
        dados([n("a", "2026-07-19T14:00:00Z"), n("b", "2026-07-17T14:00:00Z")]),
        AGORA
      );
      expect(v.grupos[0].itens[0].quando).toBe("há 1h");
      expect(v.grupos[1].itens[0].quando).toBe("há 2 dias");
    });

    it("naoLidas do servidor vence a contagem local (pode haver mais que o teto de 50)", () => {
      const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")], 73), AGORA);
      expect(v.naoLidas).toBe(73);
      expect(v.vazia).toBe(false);
    });
  });
  ```

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker exec oplenario-frontend-1 npx vitest run src/lib/notificacoes-vista.test.ts
  ```
  Falha esperada: `Failed to resolve import "./notificacoes-vista"`.

- [ ] **Step 3: Implementar o view-model.**
  `apps/frontend/src/lib/notificacoes-vista.ts`:
  ```ts
  // View-model puro da inbox (Onda E fatia 1) — 100% testável sem rede e sem DOM: agrupa temporalmente o
  // que veio de GET /meu/notificacoes e deriva o estado visual. NENHUM fetch aqui (os hooks
  // use-minhas-notificacoes / use-marcar-lida ficam no page.tsx), mesmo padrão de meu-painel-vista.ts.
  //
  // `agoraIso` é OPCIONAL (default = o relógio real) só para manter a função genuinamente pura — os testes
  // fixam o instante explicitamente. Comparação de ISO-8601 como string funciona porque os timestamps do
  // backend são sempre UTC 'Z' (mesmo idioma de ficha-materia-vista.ts / mesa-vista.ts).

  import type { MinhasNotificacoesOut, NotificacaoOut } from "./contrato-paineis.gen";

  export type GrupoTemporal = "hoje" | "semana" | "antes";

  export interface NotificacaoVista {
    id: string;
    categoria: string;
    assunto: string;
    corpo: string;
    objetoTipo: string;
    objetoId: string;
    criadoEm: string;
    /** Derivado da PRESENÇA do carimbo — o servidor não manda booleano; `lidaEm: null` é o estado. */
    lida: boolean;
    /** Destino do clique. "" quando o tipo de objeto não tem tela — melhor sem link que com link quebrado. */
    href: string;
    /** Rótulo relativo já formatado ("há 20min", "há 2 dias"). */
    quando: string;
  }

  export interface GrupoVista {
    chave: GrupoTemporal;
    rotulo: string;
    itens: NotificacaoVista[];
  }

  export interface InboxVista {
    grupos: GrupoVista[];
    /** Contagem TOTAL de não lidas, vinda do servidor — pode ser maior que o número de itens (teto de 50). */
    naoLidas: number;
    vazia: boolean;
  }

  const ROTULOS: Record<GrupoTemporal, string> = {
    hoje: "Hoje",
    semana: "Esta semana",
    antes: "Antes",
  };

  const ORDEM: GrupoTemporal[] = ["hoje", "semana", "antes"];

  const MS_HORA = 3_600_000;
  const MS_DIA = 24 * MS_HORA;

  /** Rota da tela de destino por tipo de objeto. Tipo desconhecido -> "" (sem link, nunca link quebrado). */
  function hrefDoObjeto(objetoTipo: string, objetoId: string): string {
    if (objetoTipo === "proposicao") return `/ficha-materia/${objetoId}`;
    return "";
  }

  function grupoDe(criadoEm: string, agoraIso: string): GrupoTemporal {
    const delta = Date.parse(agoraIso) - Date.parse(criadoEm);
    if (delta < MS_DIA) return "hoje";
    if (delta < 7 * MS_DIA) return "semana";
    return "antes";
  }

  function quandoRelativo(criadoEm: string, agoraIso: string): string {
    const delta = Math.max(0, Date.parse(agoraIso) - Date.parse(criadoEm));
    if (delta < MS_HORA) {
      const min = Math.max(1, Math.floor(delta / 60_000));
      return `há ${min}min`;
    }
    if (delta < MS_DIA) {
      const h = Math.floor(delta / MS_HORA);
      return `há ${h}h`;
    }
    const d = Math.floor(delta / MS_DIA);
    return d === 1 ? "há 1 dia" : `há ${d} dias`;
  }

  function paraVista(n: NotificacaoOut, agoraIso: string): NotificacaoVista {
    return {
      id: n.id,
      categoria: n.categoria,
      assunto: n.assunto,
      corpo: n.corpo,
      objetoTipo: n.objetoTipo,
      objetoId: n.objetoId,
      criadoEm: n.criadoEm,
      lida: n.lidaEm != null,
      href: hrefDoObjeto(n.objetoTipo, n.objetoId),
      quando: quandoRelativo(n.criadoEm, agoraIso),
    };
  }

  /**
   * `dados` (MinhasNotificacoesOut) -> a inbox derivada. `dados` ausente (fetch ainda não resolveu) ->
   * estrutura vazia coerente, nunca lança. A ORDEM dentro de cada grupo é a do servidor (mais recentes
   * primeiro, garantida pelo ORDER BY do SQL) — não reordenamos aqui.
   */
  export function derivarInbox(
    dados: MinhasNotificacoesOut | null | undefined,
    agoraIso: string = new Date().toISOString()
  ): InboxVista {
    const itens = (dados?.notificacoes ?? []).map((n) => paraVista(n, agoraIso));
    const grupos: GrupoVista[] = ORDEM.map((chave) => ({
      chave,
      rotulo: ROTULOS[chave],
      itens: itens.filter((i) => grupoDe(i.criadoEm, agoraIso) === chave),
    })).filter((g) => g.itens.length > 0);
    return {
      grupos,
      naoLidas: dados?.naoLidas ?? 0,
      vazia: itens.length === 0,
    };
  }
  ```

- [ ] **Step 4: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `Test Files 1 passed · Tests 9 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  git add apps/frontend/src/lib/notificacoes-vista.ts apps/frontend/src/lib/notificacoes-vista.test.ts
  git commit -m "feat(fe): view-model puro da inbox (agrupamento temporal + estado visual)"
  ```

---

### Task 10 — FE: hooks `use-minhas-notificacoes` + `use-marcar-lida`

**Files:**
- Create: `apps/frontend/src/lib/use-minhas-notificacoes.ts` + `.test.ts`
- Create: `apps/frontend/src/lib/use-marcar-lida.ts` + `.test.ts`

**Interfaces:**
- Consumes: `apiFetch` de `./api-fetch`, `camelizarChaves` de `./boundary`, `semCredencial` de `./modo`, `MinhasNotificacoesOut`/`MarcarLidaOut` de `./contrato-paineis.gen`.
- Produces:
  - `useMinhasNotificacoes(token: string | null) → { dados: MinhasNotificacoesOut | null; estado: "carregando"|"pronto"|"erro"; recarregar: () => Promise<void> }`.
  - `useMarcarLida(token: string | null) → { marcar: (id: string) => Promise<MarcarLidaOut>; estado: "ocioso"|"enviando"|"erro"; erro: string | null }`.

**Steps:**

- [ ] **Step 1: Escrever os testes do hook de leitura (vermelho).**
  `apps/frontend/src/lib/use-minhas-notificacoes.test.ts`:
  ```ts
  import { describe, expect, it, vi, afterEach } from "vitest";
  import { renderHook, waitFor, act } from "@testing-library/react";
  import { useMinhasNotificacoes } from "./use-minhas-notificacoes";

  const respostaFake = {
    notificacoes: [
      {
        id: "n1",
        categoria: "norma_publicada",
        assunto: "A sua proposicao virou lei",
        corpo: "Ementa: ...",
        "objeto-tipo": "proposicao",
        "objeto-id": "p1",
        "criado-em": "2026-07-19T12:00:00Z",
        "lida-em": null,
      },
    ],
    "nao-lidas": 1,
  };

  describe("useMinhasNotificacoes", () => {
    afterEach(() => vi.restoreAllMocks());

    it("busca /api/meu/notificacoes e cameliza", async () => {
      global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
      const { result } = renderHook(() => useMinhasNotificacoes("tok"));
      expect(result.current.estado).toBe("carregando");
      await waitFor(() => expect(result.current.estado).toBe("pronto"));
      expect(result.current.dados?.naoLidas).toBe(1);
      expect(result.current.dados?.notificacoes[0].objetoId).toBe("p1");
      const chamada = vi.mocked(global.fetch).mock.calls[0];
      expect(chamada[0]).toBe("/api/meu/notificacoes");
      expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
    });

    it("sem token -> 'erro' sem chamar fetch", () => {
      global.fetch = vi.fn() as unknown as typeof fetch;
      const { result } = renderHook(() => useMinhasNotificacoes(null));
      expect(result.current.estado).toBe("erro");
      expect(global.fetch).not.toHaveBeenCalled();
    });

    it("resposta não-ok -> estado 'erro'", async () => {
      global.fetch = vi.fn(async () => ({ ok: false, status: 401 }) as Response) as unknown as typeof fetch;
      const { result } = renderHook(() => useMinhasNotificacoes("tok"));
      await waitFor(() => expect(result.current.estado).toBe("erro"));
    });

    it("recarregar() refaz o GET e substitui os dados", async () => {
      global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
      const { result } = renderHook(() => useMinhasNotificacoes("tok"));
      await waitFor(() => expect(result.current.estado).toBe("pronto"));

      const depois = { notificacoes: [], "nao-lidas": 0 };
      global.fetch = vi.fn(async () => ({ ok: true, json: async () => depois }) as Response) as unknown as typeof fetch;
      await act(async () => {
        await result.current.recarregar();
      });
      expect(result.current.dados?.naoLidas).toBe(0);
    });
  });
  ```

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker exec oplenario-frontend-1 npx vitest run src/lib/use-minhas-notificacoes.test.ts
  ```
  Falha esperada: `Failed to resolve import "./use-minhas-notificacoes"`.

- [ ] **Step 3: Implementar o hook de leitura.**
  `apps/frontend/src/lib/use-minhas-notificacoes.ts`:
  ```ts
  "use client";

  // Hook da inbox (Onda E fatia 1) — GET /api/meu/notificacoes. Sem parâmetro de identidade (é sempre "as
  // minhas", resolvidas do ator na borda — anti-forja por construção); mirror direto de use-meu-painel.ts,
  // incluindo `recarregar` (revalidação contra o servidor depois de marcar como lida) e o `tokenAtualRef`
  // que impede uma resposta em voo de um token ANTERIOR de sobrescrever dados do token NOVO.
  //
  // PESSIMISTA de propósito nesta fatia: a página faz `await marcar(id)` e só então `await recarregar()`.
  // UI otimista (riscar o item na hora + rollback em erro) é melhoria futura, não o comportamento atual.

  import { useCallback, useEffect, useRef, useState } from "react";
  import { apiFetch } from "./api-fetch";
  import { camelizarChaves } from "./boundary";
  import type { MinhasNotificacoesOut } from "./contrato-paineis.gen";
  import { semCredencial } from "./modo";

  type Estado = "carregando" | "pronto" | "erro";

  async function buscar(token: string | null): Promise<MinhasNotificacoesOut | null> {
    const r = await apiFetch("/api/meu/notificacoes", { token: token ?? undefined, cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as MinhasNotificacoesOut;
  }

  export function useMinhasNotificacoes(token: string | null) {
    const [dados, setDados] = useState<MinhasNotificacoesOut | null>(null);
    const [estado, setEstado] = useState<Estado>("carregando");
    const vivoRef = useRef(true);
    const tokenAtualRef = useRef(token);

    useEffect(() => {
      tokenAtualRef.current = token;
    }, [token]);

    useEffect(() => {
      vivoRef.current = true;
      return () => {
        vivoRef.current = false;
      };
    }, []);

    useEffect(() => {
      if (semCredencial(token)) return; // o caso sem token é derivado no retorno (sem setState no effect)
      let vivo = true;
      (async () => {
        try {
          const resultado = await buscar(token);
          if (!vivo) return;
          if (resultado === null) {
            setEstado("erro");
            return;
          }
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

    const recarregar = useCallback(async () => {
      if (semCredencial(token)) return;
      const tokenDaChamada = token;
      try {
        const resultado = await buscar(token);
        if (!vivoRef.current) return;
        if (tokenAtualRef.current !== tokenDaChamada) return; // o token mudou com o fetch em voo
        if (resultado === null) {
          setEstado("erro");
          return;
        }
        setDados(resultado);
        setEstado("pronto");
      } catch {
        if (vivoRef.current && tokenAtualRef.current === tokenDaChamada) setEstado("erro");
      }
    }, [token]);

    if (semCredencial(token)) return { dados: null, estado: "erro" as Estado, recarregar };
    return { dados, estado, recarregar };
  }
  ```

- [ ] **Step 4: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: `Tests 4 passed`.

- [ ] **Step 5: Escrever os testes do hook de mutação (vermelho).**
  `apps/frontend/src/lib/use-marcar-lida.test.ts`:
  ```ts
  import { describe, expect, it, vi, afterEach } from "vitest";
  import { renderHook, waitFor, act } from "@testing-library/react";
  import { useMarcarLida } from "./use-marcar-lida";

  describe("useMarcarLida", () => {
    afterEach(() => vi.restoreAllMocks());

    it("POSTa no id certo e cameliza o recibo", async () => {
      global.fetch = vi.fn(async () =>
        ({ ok: true, json: async () => ({ id: "n1", "lida-em": "2026-07-19T13:00:00Z" }) }) as Response
      ) as unknown as typeof fetch;
      const { result } = renderHook(() => useMarcarLida("tok"));
      let recibo;
      await act(async () => {
        recibo = await result.current.marcar("n1");
      });
      expect(recibo).toEqual({ id: "n1", lidaEm: "2026-07-19T13:00:00Z" });
      const chamada = vi.mocked(global.fetch).mock.calls[0];
      expect(chamada[0]).toBe("/api/meu/notificacoes/n1/lida");
      expect(chamada[1]?.method).toBe("POST");
      expect(result.current.estado).toBe("ocioso");
    });

    it("sem token -> lança e não chama fetch", async () => {
      global.fetch = vi.fn() as unknown as typeof fetch;
      const { result } = renderHook(() => useMarcarLida(null));
      await expect(result.current.marcar("n1")).rejects.toThrow();
      expect(global.fetch).not.toHaveBeenCalled();
    });

    it("404 -> estado 'erro' com mensagem legível", async () => {
      global.fetch = vi.fn(async () =>
        ({ ok: false, status: 404, json: async () => ({ erro: "notificacao nao encontrada" }) }) as Response
      ) as unknown as typeof fetch;
      const { result } = renderHook(() => useMarcarLida("tok"));
      await act(async () => {
        await expect(result.current.marcar("n1")).rejects.toThrow("notificacao nao encontrada");
      });
      await waitFor(() => expect(result.current.estado).toBe("erro"));
      expect(result.current.erro).toBe("notificacao nao encontrada");
    });

    it("id vazio não vira URL malformada", async () => {
      global.fetch = vi.fn() as unknown as typeof fetch;
      const { result } = renderHook(() => useMarcarLida("tok"));
      await expect(result.current.marcar("")).rejects.toThrow();
      expect(global.fetch).not.toHaveBeenCalled();
    });
  });
  ```

- [ ] **Step 6: Rodar e ver falhar.**
  ```bash
  docker exec oplenario-frontend-1 npx vitest run src/lib/use-marcar-lida.test.ts
  ```
  Falha esperada: `Failed to resolve import "./use-marcar-lida"`.

- [ ] **Step 7: Implementar o hook de mutação.**
  `apps/frontend/src/lib/use-marcar-lida.ts`:
  ```ts
  "use client";

  // Hook de mutação — POST /api/meu/notificacoes/:id/lida (Onda E fatia 1). Mirror de use-acusar-ciencia.ts
  // (vivoRef + enviandoRef, mesmo contrato de erro). Sem corpo: o único dado é o `id` do path; o
  // destinatário nunca vem do cliente (a borda resolve do ator, anti-forja por construção).
  //
  // A operação é IDEMPOTENTE no servidor (COALESCE preserva o primeiro carimbo), então um clique duplo é
  // inofensivo — o `enviandoRef` existe só para não disparar dois round-trips simultâneos.

  import { useEffect, useRef, useState } from "react";
  import { apiFetch } from "./api-fetch";
  import { camelizarChaves } from "./boundary";
  import type { MarcarLidaOut } from "./contrato-paineis.gen";
  import { semCredencial } from "./modo";

  type Estado = "ocioso" | "enviando" | "erro";

  export function useMarcarLida(token: string | null) {
    const [estado, setEstado] = useState<Estado>("ocioso");
    const [erro, setErro] = useState<string | null>(null);
    const vivoRef = useRef(true);
    const enviandoRef = useRef(false);

    useEffect(() => {
      return () => {
        vivoRef.current = false;
      };
    }, []);

    async function marcar(id: string): Promise<MarcarLidaOut> {
      if (semCredencial(token)) {
        throw new Error("sem token de autenticacao");
      }
      if (!id) {
        // guard de URL: sem isto, `id` vazio geraria POST /api/meu/notificacoes//lida (404 confuso).
        throw new Error("id da notificação ausente");
      }
      if (enviandoRef.current) {
        throw new Error("envio em andamento");
      }
      enviandoRef.current = true;
      setEstado("enviando");
      setErro(null);
      let tratado = false;
      try {
        const r = await apiFetch(`/api/meu/notificacoes/${encodeURIComponent(id)}/lida`, {
          token: token ?? undefined,
          method: "POST",
        });
        if (!r.ok) {
          const corpoErro = await r.json().catch(() => null);
          const msg = corpoErro?.erro ?? `falha ao marcar como lida (status ${r.status})`;
          tratado = true;
          if (vivoRef.current) {
            setEstado("erro");
            setErro(msg);
          }
          throw new Error(msg);
        }
        const dados = camelizarChaves(await r.json()) as MarcarLidaOut;
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

    return { marcar, estado, erro };
  }
  ```

- [ ] **Step 8: Rodar e ver passar.** Mesmo comando do Step 6. Esperado: `Tests 4 passed`.

- [ ] **Step 9: Commit.**
  ```bash
  git add apps/frontend/src/lib/use-minhas-notificacoes.ts apps/frontend/src/lib/use-minhas-notificacoes.test.ts \
          apps/frontend/src/lib/use-marcar-lida.ts apps/frontend/src/lib/use-marcar-lida.test.ts
  git commit -m "feat(fe): hooks da inbox (leitura + marcar como lida)"
  ```

---

### Task 11 — FE: página da inbox no shell do vereador

**Files:**
- Create: `apps/frontend/src/app/(vereador)/notificacoes/page.tsx`
- Create: `apps/frontend/src/app/(vereador)/notificacoes/notificacoes.css`
- Create: `apps/frontend/src/app/(vereador)/notificacoes/page.test.tsx`
- Modify: `apps/frontend/src/app/(vereador)/layout.tsx`

**Interfaces:**
- Consumes: `useAuth` (`@/lib/auth`), `useMinhasNotificacoes`, `useMarcarLida`, `derivarInbox`, `comToken` (`@/lib/nav`).
- Produces: rota `/notificacoes` dentro do shell `(vereador)`; tab **"Avisos"** ativo apontando para ela.

**Escopo da tela (porte reduzido de `produto/design-system/o-plenario/telas/notificacoes.html`):** cabeçalho com badge de não lidas, agrupamento Hoje / Esta semana / Antes, a **nota de dedup §5.1**, e cada item com ícone, assunto, corpo, link "Abrir a ficha" e botão de marcar como lida. **Fora**: "marcar todas como lidas", abas de filtro por tipo, contador no sino (todos declarados fora de escopo na spec §5).

**Steps:**

- [ ] **Step 1: Escrever os testes de render (vermelho).**
  `apps/frontend/src/app/(vereador)/notificacoes/page.test.tsx`:
  ```tsx
  import { describe, expect, it, vi, afterEach } from "vitest";
  import { render, screen, waitFor } from "@testing-library/react";
  import PaginaNotificacoes from "./page";

  vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

  const umaNaoLida = {
    notificacoes: [
      {
        id: "n1",
        categoria: "norma_publicada",
        assunto: "A sua proposicao virou lei — Lei 3/2026",
        corpo: "Ementa: Dispoe sobre as hortas comunitarias.",
        "objeto-tipo": "proposicao",
        "objeto-id": "p1",
        "criado-em": new Date().toISOString(),
        "lida-em": null,
      },
    ],
    "nao-lidas": 1,
  };

  describe("PaginaNotificacoes", () => {
    afterEach(() => vi.restoreAllMocks());

    it("mostra a notificação, o grupo e o badge de não lidas", async () => {
      global.fetch = vi.fn(async () => ({ ok: true, json: async () => umaNaoLida }) as Response) as unknown as typeof fetch;
      render(<PaginaNotificacoes />);
      await waitFor(() => expect(screen.getByText(/virou lei/)).toBeDefined());
      expect(screen.getByRole("heading", { level: 2, name: "Hoje" })).toBeDefined();
      expect(screen.getByLabelText("1 não lida")).toBeDefined();
      expect(screen.getByRole("link", { name: /Abrir a ficha/ }).getAttribute("href")).toContain("/ficha-materia/p1");
    });

    it("não-lida é sinalizada por mais que cor (ponto com rótulo acessível)", async () => {
      global.fetch = vi.fn(async () => ({ ok: true, json: async () => umaNaoLida }) as Response) as unknown as typeof fetch;
      render(<PaginaNotificacoes />);
      await waitFor(() => expect(screen.getByRole("img", { name: "Não lida" })).toBeDefined());
    });

    it("estado vazio é honesto", async () => {
      global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ notificacoes: [], "nao-lidas": 0 }) }) as Response) as unknown as typeof fetch;
      render(<PaginaNotificacoes />);
      await waitFor(() => expect(screen.getByText(/Nenhuma notificação/)).toBeDefined());
    });

    it("erro de carga não quebra a tela", async () => {
      global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
      render(<PaginaNotificacoes />);
      await waitFor(() => expect(screen.getByText(/Não foi possível carregar/)).toBeDefined());
    });

    it("marcar como lida chama o POST e revalida", async () => {
      const chamadas: string[] = [];
      global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
        chamadas.push(`${init?.method ?? "GET"} ${url}`);
        if (init?.method === "POST") {
          return { ok: true, json: async () => ({ id: "n1", "lida-em": "2026-07-19T13:00:00Z" }) } as Response;
        }
        return { ok: true, json: async () => umaNaoLida } as Response;
      }) as unknown as typeof fetch;
      render(<PaginaNotificacoes />);
      await waitFor(() => expect(screen.getByText(/virou lei/)).toBeDefined());
      screen.getByRole("button", { name: /Marcar como lida/ }).click();
      await waitFor(() =>
        expect(chamadas).toContain("POST /api/meu/notificacoes/n1/lida")
      );
      await waitFor(() => expect(chamadas.filter((c) => c.startsWith("GET")).length).toBeGreaterThan(1));
    });
  });
  ```

- [ ] **Step 2: Rodar e ver falhar.**
  ```bash
  docker exec oplenario-frontend-1 npx vitest run "src/app/(vereador)/notificacoes/page.test.tsx"
  ```
  Falha esperada: `Failed to resolve import "./page"`.

- [ ] **Step 3: Implementar a página.**
  `apps/frontend/src/app/(vereador)/notificacoes/page.tsx`:
  ```tsx
  "use client";

  // A inbox do vereador (Onda E fatia 1) — porte reduzido de
  // produto/design-system/o-plenario/telas/notificacoes.html: cabeçalho + badge, agrupamento temporal, a
  // nota de dedup §5.1 e a lista. FORA desta fatia (spec §5, deliberado): "marcar todas como lidas",
  // abas de filtro por tipo (com uma só categoria existindo, aba é teatro) e contador no sino do topo
  // (o sino conta PENDÊNCIAS, não notificações — mudar isso quebraria a dedup que a própria tela documenta).
  //
  // Não-lida é marcada por PONTO + NEGRITO + tinta de fundo — nunca só cor (GUIDELINES-CHECKLIST).
  // Composição: useAuth (token, já resolvido pelo GuardVereador do layout) + useMinhasNotificacoes +
  // useMarcarLida + derivarInbox (view-model puro).

  import Link from "next/link";
  import { useAuth } from "@/lib/auth";
  import { useMinhasNotificacoes } from "@/lib/use-minhas-notificacoes";
  import { useMarcarLida } from "@/lib/use-marcar-lida";
  import { derivarInbox, type NotificacaoVista } from "@/lib/notificacoes-vista";
  import { comToken } from "@/lib/nav";
  import "./notificacoes.css";

  export default function PaginaNotificacoes() {
    const { token } = useAuth();
    const { dados, estado, recarregar } = useMinhasNotificacoes(token);
    const { marcar, estado: estadoMarcacao, erro: erroMarcacao } = useMarcarLida(token);
    const vista = derivarInbox(dados);

    if (estado === "erro") {
      return (
        <main className="tela-estado">
          <h1>Não foi possível carregar suas notificações</h1>
          <p>Tente novamente em instantes.</p>
        </main>
      );
    }
    if (estado === "carregando") {
      return (
        <main className="tela-estado">
          <h1>Carregando…</h1>
        </main>
      );
    }

    async function marcarLida(n: NotificacaoVista) {
      try {
        await marcar(n.id);
        await recarregar();
      } catch {
        // o erro já fica exposto via `erroMarcacao`; aqui só evita a unhandled promise rejection.
      }
    }

    return (
      <>
        <div className="nt-cab">
          <span className="eyebrow">Central de notificações</span>
          <h1>
            Notificações
            {vista.naoLidas > 0 && (
              <span className="badge" aria-label={`${vista.naoLidas} não lida${vista.naoLidas > 1 ? "s" : ""}`}>
                {vista.naoLidas}
              </span>
            )}
          </h1>
          <p className="sub">O que mudou no que é seu.</p>
        </div>

        <p className="dedup-nota">
          Aqui você <b>acompanha</b>. O que exige a sua ação fica em <b>Início</b> e sai de lá sozinho quando
          o ato é concluído. “Lido” mora só aqui.
        </p>

        {erroMarcacao && <p className="erro-inline">{erroMarcacao}</p>}

        {vista.vazia && <p className="vazio">Nenhuma notificação por enquanto.</p>}

        {vista.grupos.map((g) => (
          <section className="grupo" key={g.chave}>
            <h2>{g.rotulo}</h2>
            <div className="lista">
              {g.itens.map((n) => (
                <article className={n.lida ? "nt" : "nt nao-lida"} key={n.id}>
                  <span className="nt-ic" aria-hidden="true">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M14 3v4a1 1 0 0 0 1 1h4" />
                      <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2Z" />
                      <path d="M9 13h6" />
                    </svg>
                  </span>
                  <div className="nt-mid">
                    <h3>{n.assunto}</h3>
                    <p>{n.corpo}</p>
                    {n.href && (
                      <Link className="ir" href={comToken(n.href, token)}>
                        Abrir a ficha →
                      </Link>
                    )}
                  </div>
                  <div className="nt-dir">
                    <span className="nt-quando">{n.quando}</span>
                    {n.lida ? (
                      <span className="nt-lida-marca">Lida</span>
                    ) : (
                      <>
                        <span className="nt-ponto" role="img" aria-label="Não lida" />
                        <button
                          className="nt-lida"
                          type="button"
                          disabled={estadoMarcacao === "enviando"}
                          onClick={() => marcarLida(n)}
                        >
                          Marcar como lida
                        </button>
                      </>
                    )}
                  </div>
                </article>
              ))}
            </div>
          </section>
        ))}
      </>
    );
  }
  ```

- [ ] **Step 4: Escrever o CSS.**
  `apps/frontend/src/app/(vereador)/notificacoes/notificacoes.css` — porte dos blocos `.pg-cab`, `.grupo`, `.lista`, `.nt*` e `.dedup-nota` de `produto/design-system/o-plenario/telas/notificacoes.html`, adaptados ao shell mobile do vereador (tokens/chassi já são globais via `globals.css`; `.app-topo`/`.tabbar` vivem em `../vereador-shell.css` e **não** são duplicados aqui). Copiar as regras verbatim daquele `<style>` para as classes usadas na página — **exceto** as de filtro (`.segs`) e as de categoria não existente nesta fatia (`.nt-ic.prazo`, `.nt-ic.sessao`, `.nt.falha`, `.nt-falha-tag`), que ficam de fora até haver um segundo produtor.
  Acrescentar as duas classes que a página introduz:
  ```css
  /* rótulo textual do item já lido — o par do ponto (nunca só cor: ponto+negrito+tinta no não lido) */
  .nt-lida-marca { font-family: var(--mono); font-size: var(--t-12); color: var(--texto-2); }
  /* erro inline da marcação (sem isso o erro do POST seria engolido) */
  .erro-inline { font-size: var(--t-13); color: var(--acento-texto); margin: 0.5rem 0 0.7rem; }
  .vazio { font-size: var(--t-13); color: var(--texto-2); margin: 1.2rem 0 0; }
  ```

- [ ] **Step 5: Ligar o tab no shell.**
  Em `apps/frontend/src/app/(vereador)/layout.tsx`, substituir a constante `TABS` inteira pela versão abaixo. A alteração é pontual: o tab desabilitado **"Matérias"** (que hoje é placeholder, sem rota própria dentro do shell mobile) cede o lugar a **"Avisos"**, com rota real — a tabbar continua com 5 posições, e `Pauta`/`Perfil` seguem desabilitados como estavam.
  ```tsx
  const TABS = [
    { rotulo: "Início", href: "/vereador", ativo: true },
    { rotulo: "Pauta", href: null, ativo: false },
    { rotulo: "Votar", href: "/votar", ativo: true },
    // Onda E fatia 1: a inbox ganhou rota real — este tab deixa de ser placeholder. NÃO leva contador:
    // o sino/badge de contagem é dos PENDÊNCIAS (dedup §5.1 da tela de design), não das notificações.
    { rotulo: "Avisos", href: "/notificacoes", ativo: true },
    { rotulo: "Perfil", href: null, ativo: false },
  ] as const;
  ```
  (o item `Matérias`, que era desabilitado, cede o lugar a `Avisos` — a tabbar continua com 5 posições e o comentário existente sobre `Matérias` deve ser atualizado para registrar isso.)

- [ ] **Step 6: Rodar e ver passar.**
  ```bash
  docker exec oplenario-frontend-1 npx vitest run "src/app/(vereador)/notificacoes/page.test.tsx"
  docker exec oplenario-frontend-1 npx vitest run "src/app/(vereador)/layout.test.tsx"
  ```
  Esperado: `Tests 5 passed` e o teste do layout verde (se ele asserta a lista de tabs, atualizar a expectativa para incluir `Avisos`).

- [ ] **Step 7: Lint + type-check.**
  ```bash
  docker exec oplenario-frontend-1 npx tsc --noEmit
  docker exec oplenario-frontend-1 npm run lint
  ```
  Esperado: 0 erros em ambos.

- [ ] **Step 8: Verificação de contraste AA nos 2 temas.**
  Com a stack de pé, abrir `http://localhost:3000/notificacoes?token=<token de dev>` e medir **em pixel composto**, **um tema por chamada com flush** (a 2ª passada limpa elimina o artefato de stale-bg do `body`):
  - texto do assunto sobre o fundo do item **não lido** (`color-mix(marca 5%, surface)`) — nos 2 temas;
  - `.nt-quando` (mono, `--texto-2`) sobre o mesmo fundo;
  - o botão `.nt-lida` (borda + label) sobre `--surface`;
  - o `.badge` de contagem — **branco sobre telha é a armadilha §5.1**: usar `--telha-fundo`, nunca `--telha`.
  Registrar os ratios medidos no corpo do commit. Alvo: **≥ 4.5:1** para texto normal, **≥ 3:1** para bordas/ícones informativos. Se algum falhar, corrigir o token (nunca "arredondar" o número).

- [ ] **Step 9: Commit.**
  ```bash
  git add "apps/frontend/src/app/(vereador)/notificacoes/page.tsx" \
          "apps/frontend/src/app/(vereador)/notificacoes/notificacoes.css" \
          "apps/frontend/src/app/(vereador)/notificacoes/page.test.tsx" \
          "apps/frontend/src/app/(vereador)/layout.tsx"
  git commit -m "feat(fe): tela da inbox no shell do vereador (AA medida nos 2 temas)"
  ```

---

### Task 12 — Seed + prova ao vivo

**Files:**
- Modify: `apps/backend/demo/seed_demo.clj`

**Interfaces:**
- Consumes: `legislativo-repo/{protocolar!,gerar-autografo!,iniciar-tramitacao-executiva!,registrar-resposta-executivo!,promulgar-norma!,publicar-norma!}`, `vereador-db/inserir!`, `id/inserir!`, `vinc/criar!`.
- Produces: `seed-demo/notificacoes` — cria vereador com identidade nova + vínculo `vereador` + papel, protocola proposição de autoria dele, promulga e publica a norma, e imprime a URL da inbox com o token.

**Steps:**

- [ ] **Step 1: Escrever a fn de seed.**
  Em `apps/backend/demo/seed_demo.clj`, **não é preciso `:require` novo** para conceder papel: o arquivo já
  importa `[oplenario.identidade.db.vinculo :as vinc]`, e a concessão é `vinc/adicionar-papel!` — idempotente
  (`ON CONFLICT DO NOTHING`), chamada dentro de `tenancy/com-tenant*`, exatamente como as fns `vereadores`,
  `vereador` e `secretario` já fazem no mesmo arquivo. Copie a forma delas. Acrescente ao final do arquivo:
  ```clojure
  ;; ---------- Onda E fatia 1 — INBOX interna: "a sua proposicao virou lei" ----------

  (defn notificacoes
    "Semente da INBOX do vereador (Onda E fatia 1). Sob o MESMO ente da demo (ids-file de `base` — rodar
    `base` primeiro): cria um VEREADOR com identidade propria + vinculo + papel 'vereador', protocola uma
    proposicao DE AUTORIA DELE e a leva ate' NORMA PUBLICADA usando os Repo de VERDADE. `publicar-norma!`
    emite `norma.publicada`; o relay do app servido drena, `legislativo` resolve autor->identidade e emite
    `notificacao.requisitada` (in_app); `paineis` projeta na inbox. Imprime a URL + o token do vereador.
    NAO e' idempotente (cria uma norma NOVA a cada chamada) — rodar uma vez por demo fresca."
    [_]
    (com-ds
     (fn [ds]
       (let [{:keys [ente]} (edn/read-string (slurp ids-file))
             repo (repo-legislativo ds)
             ident-vereador (random-uuid)
             vereador-id (random-uuid)]
         ;; identidade supratenant + vinculo + papel (o ator que vai LER a inbox)
         (id/inserir! ds {:id ident-vereador :cpf (cpf-valido) :nome "Vereadora Ana Ribeiro"})
         (tenancy/com-tenant* ds ente
           (fn [tx]
             (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident-vereador :tipo "vereador"})
             ;; cadastro institucional do vereador, JA' ligado a' identidade — e' o que o resolvedor
             ;; injetado (cadastros/identidade-do-vereador-em-tx) vai encontrar.
             (vereador-db/inserir! tx {:id vereador-id :ente-id ente :identidade-id ident-vereador
                                       :nome "Ana Ribeiro" :nome-parlamentar "Ana Ribeiro"})))
         ;; a materia DELA, ate' virar lei
         (let [{pid :id} (legislativo-repo/protocolar! repo ente
                           {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                            :municipio-nome "Fortaleza"
                            :ementa "Dispoe sobre as hortas comunitarias urbanas."
                            :autor-tipo "vereador" :autor-id vereador-id :autor-texto "Ver. Ana Ribeiro"})
               {aid :id} (legislativo-repo/gerar-autografo! repo ente
                           {:id (random-uuid) :proposicao-id pid :ano 2026
                            :texto-versao-id (random-uuid)
                            :destinatario-texto "Prefeito Municipal de Fortaleza"})
               {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! repo ente
                           {:id (random-uuid) :autografo-id aid})]
           (legislativo-repo/registrar-resposta-executivo! repo ente
             {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
           (let [{nid :id} (legislativo-repo/promulgar-norma! repo ente
                             {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                              :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                              :data-promulgacao (LocalDate/of 2026 6 28)
                              :ementa "Dispoe sobre as hortas comunitarias urbanas."
                              :texto-versao-id (random-uuid)})]
             (legislativo-repo/publicar-norma! repo ente
               {:id nid :veiculo-publicacao "Diario Oficial do Municipio" :updated-by nil :lock-version 0})))
         (let [token (format "{\"identidade-id\":\"%s\",\"ente-id\":\"%s\"}" ident-vereador ente)]
           (println "\n=== INBOX DA DEMO PRONTA ===")
           (println "Aguarde o relay drenar (~1s) e abra:")
           (println (str "http://localhost:3000/notificacoes?token=" token))
           (println "============================\n"))))))
  ```
  > **Conferir na implementação:** (a) o papel `"vereador"` — se o `GuardVereador` do FE exigir o papel no `/eu`, conceder também o papel pelo caminho que a Onda D usa (`repo-identidade/adicionar-papel!` ou equivalente); (b) `vereador-db/inserir!` já aceita `:identidade-id` (visto em `cadastros/db/vereador.clj`), então não é preciso `ligar-identidade!` depois.

- [ ] **Step 2: Rodar a demo de ponta a ponta.**
  ```bash
  cd apps/backend && docker compose up -d --build
  # base (cria a Casa) — se ainda não foi rodada nesta demo:
  docker run --rm --network oplenario_default -v "$(pwd)/..":/repo -w /repo/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -v oplenario_demo_scratch:/demo-scratch \
    -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' -e MINIO_ENDPOINT='http://minio:9000' \
    clojure:temurin-21-tools-deps \
    clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed seed-demo/base
  # a inbox:
  docker run --rm --network oplenario_default -v "$(pwd)/..":/repo -w /repo/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -v oplenario_demo_scratch:/demo-scratch \
    -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' -e MINIO_ENDPOINT='http://minio:9000' \
    clojure:temurin-21-tools-deps \
    clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed seed-demo/notificacoes
  ```
  Esperado: o bloco `=== INBOX DA DEMO PRONTA ===` com a URL.
  > O volume de scratch (`/demo-scratch`, onde vive `demo-ids.edn`) tem de ser **o mesmo** entre `base` e `notificacoes` — conferir como as invocações anteriores da demo o montam e reproduzir; se `base` foi rodada de outro jeito, rodar `notificacoes` do mesmo jeito.
  > **`docker compose up -d` sozinho reusa a imagem velha** e dá 404 nas rotas novas — usar `--build`.

- [ ] **Step 3: Provar no banco.**
  ```bash
  docker exec -i oplenario-postgres-1 psql -U oplenario -d oplenario -c \
    "SELECT categoria, assunto, lida_em FROM paineis.notificacao_caixa ORDER BY criado_em DESC LIMIT 5;"
  ```
  Esperado: 1 linha `norma_publicada | A sua proposicao virou lei — Lei N/2026 | (null)`.
  Se vier vazio: o relay ainda não drenou (esperar ~1s e repetir) ou o consumidor de `legislativo` não está fiado — conferir `sistema.clj` (Task 5, Step 16) e o log do container `app`.

- [ ] **Step 4: Prova visual ao vivo.**
  Abrir a URL impressa no navegador. Verificar:
  1. a notificação aparece no grupo **Hoje**, com badge `1`;
  2. o link "Abrir a ficha" leva à ficha da matéria;
  3. clicar em **Marcar como lida** faz o ponto sumir, o badge zerar e a notificação virar "Lida";
  4. recarregar a página mantém o estado (é servidor, não local);
  5. clicar de novo (após um reload, com o item já lido) **não** produz erro — idempotência visível;
  6. alternar o tema claro/escuro mantém a legibilidade dos dois lados.

- [ ] **Step 5: Prova de isolamento entre atores (critério 4, ao vivo).**
  Com o `id` da notificação em mãos (do `psql` do Step 3) e o token do **secretário** (impresso por `seed-demo/base`):
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer {\"identidade-id\":\"<IDENT-DO-SECRETARIO>\",\"ente-id\":\"<ENTE>\"}" \
    http://localhost:8888/meu/notificacoes/<ID-DA-NOTIFICACAO>/lida
  ```
  Esperado: `404` — outro ator não marca a notificação alheia nem com o id em mãos.

- [ ] **Step 6: Suíte completa dos dois lados.**
  ```bash
  # backend
  docker run --rm --network oplenario_default -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps clojure -M:test --skip :e2e --skip :keycloak --reporter documentation
  docker run --rm -v "$(pwd)":/app -w /app/apps/backend -v oplenario_backend_m2:/root/.m2 \
    clojure:temurin-21-tools-deps clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2026.05.25"}}}' -M -m clj-kondo.main --lint src test demo
  # frontend — arquivo a arquivo (nunca a suíte inteira de uma vez)
  docker exec oplenario-frontend-1 npx vitest run src/lib/notificacoes-vista.test.ts
  docker exec oplenario-frontend-1 npx vitest run src/lib/use-minhas-notificacoes.test.ts
  docker exec oplenario-frontend-1 npx vitest run src/lib/use-marcar-lida.test.ts
  docker exec oplenario-frontend-1 npx vitest run "src/app/(vereador)/notificacoes/page.test.tsx"
  docker exec oplenario-frontend-1 npx vitest run "src/app/(vereador)/layout.test.tsx"
  docker exec oplenario-frontend-1 npx tsc --noEmit
  docker exec oplenario-frontend-1 npm run lint
  ```
  Esperado (critério 8): backend 0 failures, `errors: 0, warnings: 0`; frontend todos passando, 0 erros de tipo e de lint.
  > **Gotcha conhecido:** com `oplenario-app-1` de pé durante a suíte, `outbox-relay-test` pode falhar por contenção de advisory lock — **não é bug**. Se acontecer, parar o `app` (`docker compose stop app`) e repetir.

- [ ] **Step 7: Commit.**
  ```bash
  git add apps/backend/demo/seed_demo.clj
  git commit -m "chore(demo): seed da inbox (norma publicada de autor vereador)"
  ```

- [ ] **Step 8: Revisão `ecc` antes do merge (spec §7).**
  Rodar, sobre o diff da branch: `clojure-reviewer`, `database-reviewer` e `security-reviewer` no backend; `react-reviewer` no frontend. Incorporar CRÍTICOS e MAJORs com teste de regressão para cada um; registrar os MEDIUM/LOW conscientemente deixados como carry no corpo do commit de fechamento. Só então propor o merge para `main`.

---

## Auto-revisão do plano

### 1. Cobertura da spec

**Decisões (§3) e arquitetura (§4):**

| Item da spec | Task que implementa |
|---|---|
| D1 — público interno (vereador) | 5 (produtor com destinatário interno), 11 (tela no shell do vereador) |
| D2 — destinatário derivado por relação com o objeto | 5 (`proposicao → autor_id → identidade` por resolvedor injetado) |
| D3 — primeiro produtor é `norma.publicada` | 5 |
| D4 — duas projeções do mesmo evento, tabela própria | 1 (tabela), 4 (2º consumidor) |
| D5 — `falha` é categoria da mensagem | 1 (coluna `categoria`), 2 (campo no contrato) |
| §4.1 — produtor em `legislativo`, nunca lança, sem destinatário → silêncio | 5 (Steps 13/14 + testes `autor-sem-identidade…`, `autor-nao-vereador…`) |
| §4.2 — `canal "in_app"` + `categoria` opcional + `consent-base "vinculo"` | 2 (transparencia), 5 Step 7 (cópia em legislativo) |
| §4.3 — roteamento por canal / guarda no projetor de e-mail | 3 (guarda), 4 (`projetar-inbox!` só `in_app`) |
| §4.4 — tabela, índices, UNIQUE, RLS FORCE, sem PII, Inv.10 | 1 |
| §4.5 — `GET /meu/notificacoes` (auth sem papel, teto 50 no SQL, contagem total) | 6 |
| §4.5 — `POST …/:id/lida` (idempotente, posse no mesmo WHERE, 404) | 7 |
| §4.5 — silhueta ADR-0001 completa + codegen `gerar_paineis.clj` | 6, 7 (silhueta), 8 (codegen) |
| §4.6 — hooks, view-model puro, não-lida por mais que cor, AA nos 2 temas | 9, 10, 11 |
| §5 — fora de escopo (marcar todas / filtros / sino / preferência de canal / SMTP / 2º produtor) | Nenhuma task os implementa; registrado explicitamente no escopo da Task 11 e nos comentários de código |
| §7 — revisão `ecc` antes do merge | 12 Step 8 |

**Critérios de aceitação (§6):**

| # | Critério | Onde é provado |
|---|---|---|
| 1 | Uma notificação; reexecutar não cria segunda | Task 5 (`norma-publicada-notifica-o-autor-vereador`, `reprocessar-nao-duplica`) + Task 4 (`projecao-e-idempotente-no-redrive`) |
| 2 | Autor sem identidade → sem notificação e sem erro | Task 5 (`autor-sem-identidade-vinculada-nao-notifica-e-nao-quebra`) |
| 3 | Ator sem notificação → 200, lista vazia, contagem 0 | Task 6 (`minhas-notificacoes-vazio` db + `minhas-notificacoes-vazio-200` HTTP) |
| 4 | A não lê nem marca a de B, nem com o id | Task 6 (`minhas-notificacoes-so-traz-as-do-proprio-destinatario`), Task 7 (`marcar-lida-de-outro-destinatario-e-nil`, `marcar-lida-de-outro-ator-404`), Task 12 Step 5 (ao vivo) |
| 5 | Isolamento de tenant | Task 1 (`isolamento-de-tenant`, `with-check-barra-escrita-cross-tenant`), Task 6 (`isolamento-de-tenant-na-leitura`) |
| 6 | Marcar lida é idempotente | Task 7 (`marcar-lida-e-idempotente`), Task 12 Step 4 item 5 |
| 7 | `in_app` não entra no ledger de e-mail | Task 3 (`notificacao-in-app-nao-entra-no-ledger-de-email` + o par com dentes) |
| 8 | Suítes verdes, lint 0, tela verificada nos 2 temas | Task 11 Steps 6–8, Task 12 Step 6 |

**Lacuna encontrada e corrigida na auto-revisão:** os critérios 3 e 4 exigiam prova **tanto** no nível de db quanto na borda; a primeira versão da Task 6 só tinha teste de borda com fake. Os deftests de db (`minhas-notificacoes-*`, `isolamento-de-tenant-na-leitura`) foram acrescentados à Task 6 Step 1 — sem eles, o `WHERE` de posse estaria coberto só por um dublê.

### 2. Varredura de placeholder

Nenhum "TBD", "similar à Task N" ou "adicione tratamento de erro apropriado". Todo passo que muda código mostra o código; todo comando é literal com saída esperada. Os **cinco** pontos em que o plano manda *conferir* (não *inventar*) são deliberados e cada um declara o contrato invariante que a verificação não pode alterar:

1. retorno de `outbox/drenar!` (Task 4 Step 1, Task 5 Step 9) — o que se prova é **não lançar**;
2. keyword de retorno de `autor-vereador-da-proposicao` (Task 5 Step 11) — contrato: **UUID ou nil**;
3. keyword de retorno de `vereador/buscar` (Task 5 Step 16) — contrato: **UUID ou nil**;
4. keyword do `count` em `contar-nao-lidas` (Task 6 Step 3) — contrato: **long**;
5. camelização do codegen (`lidaEm`, Task 8 Step 1) — confirmada contra `contrato-legislativo.gen.ts` (`urnLex`), a nota cobre só o caso de o gerador mudar.

Nenhum deles deixa uma decisão de design em aberto.

### 3. Consistência de tipos e nomes entre tasks

- `paineis.notificacao_caixa` (Task 1) ↔ `db/notificacao_caixa.clj` (Tasks 4/6/7): colunas idênticas; `inserir!` grava exatamente as 8 colunas NOT NULL da migration.
- `RequisitadaPayload` (Task 2, transparencia) ↔ (Task 5 Step 7, legislativo): mesma ordem de campos, com `categoria` **ao final** nos dois — e o drift-guard `eventos-notificacao-contrato-test` transforma isso em regra de CI, não em disciplina humana.
- `projetar-inbox!` (Task 4) lê `:destinatario-identidade-id`, `:canal`, `:categoria`, `:assunto`, `:corpo`, `:objeto-tipo`, `:objeto-id`, `:idempotency-key` — exatamente as chaves que o produtor da Task 5 Step 13 emite.
- `minhas-notificacoes` devolve `{:notificacoes [...] :nao-lidas long}` em Task 6 (repo → controller → adapter) e é consumido com esse shape pelo fake da borda (Task 6 Step 6) e pelo `MinhasNotificacoesOut` (Task 8) → `MinhasNotificacoesOut` TS (`notificacoes`, `naoLidas`) usado em Task 9/10/11.
- `marcar-notificacao-lida!` devolve `{:id :lida-em}` em Task 7 (repo) → `MarcarLidaOut` (`id`, `lida-em`) → TS `{ id, lidaEm }` consumido pelo `useMarcarLida` (Task 10) — o teste do hook afirma exatamente `{ id: "n1", lidaEm: "…" }`.
- `NotificacaoOut` do wire (`id, categoria, assunto, corpo, objeto-tipo, objeto-id, criado-em, lida-em`) ↔ `NotificacaoVista` (Task 9), que acrescenta só campos **derivados** (`lida`, `href`, `quando`) e não inventa campo de servidor.
- Rotas: `/meu/notificacoes` e `/meu/notificacoes/:id/lida` idênticas entre backend (Tasks 6/7), hooks (Task 10, com o prefixo `/api` do proxy same-origin do Next) e a prova `curl` (Task 12 Step 5, direto no `:8888`, sem `/api`).
- Nome de consumidor: `"paineis-inbox"` (Task 4) e `"legislativo-notificacao"` (Task 5) — distintos entre si e dos existentes (`paineis`, `transparencia-portal`, `transparencia-notificacao`), garantindo dedup independente.
- Categoria `"norma_publicada"`: gravada pela Task 5, lida pela Task 4, exibida pela Task 11, asseverada no `psql` da Task 12 — mesma string literal em todos.
