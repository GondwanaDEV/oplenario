# Onda B · Slice 2 · `editor-proposicao` (criar/editar) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Abrir a primeira borda de escrita do módulo `legislativo` (protocolar/editar proposição) e o
primeiro formulário/mutação real do frontend, fechando a segunda vertical da Onda B (fluxo diário do
servidor, Marco MFE-2).

**Architecture:** Silhueta ADR-0001 de ponta a ponta (`wire/in`→`adapters/in`→`controllers`+`Repo`→
`adapters/out`→`wire/out`) para 3 rotas novas (`POST`/`GET :id`/`PATCH :id` em `/legislativo/proposicoes`),
reaproveitando `protocolar!`/`nova-versao!`/`promover-versao!` já existentes (F3.2) e compondo-os em
transações únicas no `Repo`. Frontend: duas páginas no App Shell interno compartilhando um formulário,
inventando o primeiro par de hooks de mutação do app a partir do idioma já provado dos hooks de leitura.

**Tech Stack:** Clojure/Pedestal/HoneySQL/next.jdbc/Malli (backend); Next.js 16/React 19/TypeScript/vitest
(frontend).

## Global Constraints

- **Spec aprovada:** `docs/superpowers/specs/2026-07-05-onda-b-slice2-editor-proposicao-design.md` —
  qualquer dúvida de escopo, essa é a autoridade.
- **Criar = protocolar! imediato.** Não existe estado de rascunho pré-protocolo para a proposição. Tela de
  criar tem UMA ação primária ("Protocolar"); tela de editar tem "Salvar alterações".
- **Texto inicial/editado é sempre `:inline`** (≤32KB). Texto maior → 400 (`objeto_store` fica de carry,
  não implementar nesta fatia).
- **Papel de autorização: `"secretario"`** em todas as 3 rotas novas — mesmo gate grosso de toda
  leitura/escrita interna hoje. Sem policy fina nova.
- **Wire kebab-case.** `jsonista` serializa keywords Clojure verbatim; o FE cameliza no boundary
  (`camelizarChaves`, `src/lib/boundary.ts`).
- **`id`/`created-by`/`updated-by` nunca vêm do corpo do cliente** — gerados/injetados em `adapters/in` a
  partir do `ator` resolvido na auth (mesmo padrão de `adapters/in/votacao.clj`).
- **TDD red→green em toda task de código** — escrever o teste, rodar e ver falhar, implementar o mínimo,
  rodar e ver passar, commitar.
- **Reviews `ecc`** (clojure+database no backend; react+security no frontend) antes do merge final — tasks
  dedicadas no fim de cada metade do plano.
- **Execução de testes:** backend via `clojure -M:test` no host contra os serviços Docker (Postgres/MinIO
  expostos por porta — mesmo padrão usado em toda fatia anterior; a imagem `app`/`migrate` do backend é
  runtime-only, sem Clojure CLI, então não há como rodar a suíte dentro dela). Frontend via
  `docker compose exec frontend npm test -- <arquivo>` (o container `frontend` já monta o source ao vivo —
  honra o mandato de Docker sem rodar `npm` solto no host).
- **`clj-kondo` (import-lint) limpo** ao final: `clojure -Sdeps '{:aliases {:kondo {:extra-deps
  {clj-kondo/clj-kondo {:mvn/version "2026.05.25"}} :main-opts ["-m" "clj-kondo.main"]}}}' -M:kondo --lint
  src test` (de dentro de `apps/backend`).

---

## Task 1: Branch da fatia

**Files:** nenhum arquivo de código — só controle de versão.

- [ ] **Step 1: Confirmar árvore de trabalho limpa**

Run: `git -C /Users/daoudatraore/oplenario status`
Expected: `nothing to commit, working tree clean` (branch `main`).

- [ ] **Step 2: Criar e mudar para a branch da fatia**

Run:
```bash
git -C /Users/daoudatraore/oplenario checkout -b fe-8-editor-proposicao
```
Expected: `Switched to a new branch 'fe-8-editor-proposicao'`.

---

## Task 2: Vocabulário novo (`logic.clj`) + migration (`origem_versao` ganha `"edicao"`)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/logic.clj`
- Create: `apps/backend/resources/migrations/20260620000054-legislativo-texto-versao-origem-edicao.up.sql`
- Create: `apps/backend/resources/migrations/20260620000054-legislativo-texto-versao-origem-edicao.down.sql`
- Test: `apps/backend/test/unit/oplenario/legislativo/logic_test.clj` (novo, ou estender se já existir)
- Test: `apps/backend/test/integration/oplenario/legislativo/texto_versao_origem_edicao_test.clj` (novo)

**Interfaces:**
- Produces: `oplenario.legislativo.logic/autor-tipos` — `#{"vereador" "mesa" "comissao" "executivo" "cidadao"}`.
- Produces: `oplenario.legislativo.logic/estados-proposicao-terminais` — `#{"publicada" "arquivada"}`.
- Modifies: `oplenario.legislativo.logic/origens-versao` ganha `"edicao"` — usado por Task 9.

- [ ] **Step 1: Escrever o teste de unidade (falhando) do vocabulário**

Criar/estender `apps/backend/test/unit/oplenario/legislativo/logic_test.clj`:
```clojure
(ns oplenario.legislativo.logic-test
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.logic :as logic]))

(deftest autor-tipos-espelha-o-check-da-migration-0013
  (is (= #{"vereador" "mesa" "comissao" "executivo" "cidadao"} logic/autor-tipos)))

(deftest estados-proposicao-terminais-espelha-o-trigger
  (is (= #{"publicada" "arquivada"} logic/estados-proposicao-terminais)))

(deftest origens-versao-ganha-edicao-onda-b-slice-2
  (is (contains? logic/origens-versao "edicao")))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.logic-test`
Expected: FAIL (`autor-tipos`/`estados-proposicao-terminais` unbound, `origens-versao` sem `"edicao"`).

- [ ] **Step 3: Implementar o vocabulário**

Em `apps/backend/src/oplenario/legislativo/logic.clj`, logo após `(def origens-versao ...)`:
```clojure
(def origens-versao
  #{"protocolo" "substitutivo" "aplicacao_emenda" "redacao_final" "promulgacao" "importacao_legado" "edicao"})
(def estados-versao #{"rascunho" "vigente" "superada" "arquivada"})

(def autor-tipos
  "Vocabulario de autor_tipo (espelha o CHECK da migration 20260620000013). 'cidadao' = iniciativa popular."
  #{"vereador" "mesa" "comissao" "executivo" "cidadao"})

(def estados-proposicao-terminais
  "Espelha o trigger trg_proposicoes_imut_estado (migration 20260620000013) — guarda o `editar!` (Task 4)."
  #{"publicada" "arquivada"})
```

- [ ] **Step 4: Rodar para ver passar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.logic-test`
Expected: PASS (3 testes).

- [ ] **Step 5: Escrever a migration (CHECK aceita `"edicao"`)**

Criar `apps/backend/resources/migrations/20260620000054-legislativo-texto-versao-origem-edicao.up.sql`:
```sql
-- Onda B Slice 2 (editor-proposicao): estende origem_versao com 'edicao' — correcao de metadados/texto
-- pelo servidor via PATCH, fora do processo formal de substitutivo/emenda/redacao-final (eixo D tem
-- fluxo proprio; usar 'substitutivo' aqui estaria semanticamente errado). CHECK constraint (nao enum do
-- Postgres); PARTITION BY HASH herda a constraint alterada no pai automaticamente em todas as particoes.
ALTER TABLE legislativo.proposicao_texto_versao DROP CONSTRAINT proposicao_texto_versao_origem_versao_check;
--;;
ALTER TABLE legislativo.proposicao_texto_versao ADD CONSTRAINT proposicao_texto_versao_origem_versao_check
  CHECK (origem_versao IN
    ('protocolo','substitutivo','aplicacao_emenda','redacao_final','promulgacao','importacao_legado','edicao'));
```

Criar `apps/backend/resources/migrations/20260620000054-legislativo-texto-versao-origem-edicao.down.sql`:
```sql
ALTER TABLE legislativo.proposicao_texto_versao DROP CONSTRAINT proposicao_texto_versao_origem_versao_check;
--;;
ALTER TABLE legislativo.proposicao_texto_versao ADD CONSTRAINT proposicao_texto_versao_origem_versao_check
  CHECK (origem_versao IN
    ('protocolo','substitutivo','aplicacao_emenda','redacao_final','promulgacao','importacao_legado'));
```

- [ ] **Step 6: Escrever o teste de integração (falhando) da migration**

Criar `apps/backend/test/integration/oplenario/legislativo/texto_versao_origem_edicao_test.clj`:
```clojure
(ns oplenario.legislativo.texto-versao-origem-edicao-test
  "Onda B Slice 2 — prova que a migration 20260620000054 aceita origem_versao='edicao' (o CHECK antigo
  lancaria)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(deftest nova-versao-com-origem-edicao-aceita
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                       :uf "CE" :municipio-nome "Fortaleza" :ementa "Materia de teste"})
              r (texto/nova-versao! tx {:id (random-uuid) :ente-id ente :proposicao-id (:id p)
                                        :origem-versao "edicao" :formato "markdown"
                                        :texto-inline "## Art. 1o Teste."})]
          (is (= 1 (:numero-versao r))))))))
```

- [ ] **Step 7: Rodar para ver falhar (antes de aplicar a migration nova)**

Run:
```bash
cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.legislativo.texto-versao-origem-edicao-test
```
Expected: FAIL (`PSQLException: violates check constraint "proposicao_texto_versao_origem_versao_check"`).

- [ ] **Step 8: Rodar a suíte inteira (aplica a migration automaticamente via `migracao/migrar!` no fixture) e ver passar**

Run: mesmo comando do Step 7.
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/logic.clj \
        apps/backend/resources/migrations/20260620000054-legislativo-texto-versao-origem-edicao.up.sql \
        apps/backend/resources/migrations/20260620000054-legislativo-texto-versao-origem-edicao.down.sql \
        apps/backend/test/unit/oplenario/legislativo/logic_test.clj \
        apps/backend/test/integration/oplenario/legislativo/texto_versao_origem_edicao_test.clj
git commit -m "feat(legislativo): vocabulario autor-tipos/estados-proposicao-terminais + origem-versao 'edicao'"
```

---

## Task 3: `cadastros` — resolver uf/nome-do-município

**Files:**
- Modify: `apps/backend/src/oplenario/cadastros/db/estrutura.clj`
- Modify: `apps/backend/src/oplenario/cadastros/components/repositorio.clj`
- Test: `apps/backend/test/integration/oplenario/cadastros/estrutura_test.clj` (estender se já existir, senão criar)

**Interfaces:**
- Produces: `oplenario.cadastros.db.estrutura/uf-e-municipio` — `(tx) -> {:uf :municipio-nome} | nil`.
- Produces: `oplenario.cadastros.components.repositorio/RepoCadastros` ganha `(uf-e-municipio [this ente-id])`.

- [ ] **Step 1: Escrever o teste de integração (falhando)**

Criar (ou adicionar a) `apps/backend/test/integration/oplenario/cadastros/estrutura_test.clj`:
```clojure
(ns oplenario.cadastros.estrutura-test
  "Onda B Slice 2 — uf-e-municipio: o FATO que legislativo/protocolar! precisa p/ a URN (eixo H), via join
  DENTRO do schema cadastros (municipios+ente), sem cross-schema (§22.10)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(deftest uf-e-municipio-resolve-do-ente-corrente
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; municipio de referencia ja seedado por migration (IBGE 2304400 = Fortaleza/CE); se o seed
        ;; mudar, ajustar o codigo aqui.
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara de Teste"})
        (is (= {:uf "CE" :municipio-nome "Fortaleza"} (estrutura/uf-e-municipio tx)))))))

(deftest uf-e-municipio-nil-quando-ente-sem-perfil
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (nil? (estrutura/uf-e-municipio tx)))))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.cadastros.estrutura-test`
Expected: FAIL (`uf-e-municipio` unbound) — se o teste `uf-e-municipio-resolve-do-ente-corrente` falhar por o
código IBGE de Fortaleza não estar seedado, ajustar para um município real já presente na migration de seed
de `cadastros.municipios` (checar `apps/backend/resources/migrations/` por `INSERT INTO cadastros.municipios`).

- [ ] **Step 3: Implementar `uf-e-municipio`**

Em `apps/backend/src/oplenario/cadastros/db/estrutura.clj`, após `buscar-ente`:
```clojure
(defn uf-e-municipio
  "uf + nome do municipio do ente CORRENTE (RLS de cadastros.ente escopa ao tenant) — o FATO que
  legislativo/protocolar! precisa p/ computar a URN (eixo H). Join DENTRO do schema cadastros
  (municipios+ente, sem cross-schema, §22.10). nil se o ente nao tem perfil cadastrado."
  [tx]
  (some-> (jdbc/execute-one! tx
            (sql/format {:select [:m.uf [:m.nome :municipio_nome]]
                         :from [[:cadastros.ente :e]]
                         :join [[:cadastros.municipios :m] [:= :e.municipio_ibge :m.codigo_ibge]]}))
          comum/linha->kebab))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Expor no `RepoCadastros`**

Em `apps/backend/src/oplenario/cadastros/components/repositorio.clj`, no `defprotocol RepoCadastros` logo
após `(buscar-ente [this ente-id])`:
```clojure
  (uf-e-municipio [this ente-id]
    "uf + nome do municipio do ente — o FATO que legislativo/protocolar! precisa (injetado pelo host,
     inversao de dependencia §22.10, Onda B Slice 2).")
```
E no `defrecord`, logo após `(buscar-ente [this ente-id] (transacao this ente-id estrutura/buscar-ente))`:
```clojure
  (uf-e-municipio [this ente-id] (transacao this ente-id estrutura/uf-e-municipio))
```

- [ ] **Step 6: Rodar a suíte do módulo cadastros para garantir que nenhum `reify`/fake do protocolo quebrou**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.cadastros`
Expected: PASS em tudo (nenhum teste que faça `reify RepoCadastros` parcial deveria quebrar — Clojure
protocolos não exigem implementar 100% dos métodos num `reify`, só falha se o método faltante for CHAMADO).

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/oplenario/cadastros/db/estrutura.clj \
        apps/backend/src/oplenario/cadastros/components/repositorio.clj \
        apps/backend/test/integration/oplenario/cadastros/estrutura_test.clj
git commit -m "feat(cadastros): resolver uf-e-municipio (fato p/ legislativo/protocolar!)"
```

---

## Task 4: `legislativo/db/proposicao.clj` — `editar!`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/db/proposicao.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/proposicao_editar_db_test.clj` (novo)

**Interfaces:**
- Consumes: `oplenario.legislativo.logic/estados-proposicao-terminais` (Task 2).
- Produces: `oplenario.legislativo.db.proposicao/editar!` — `(tx {:keys [id ente-id ementa autor-tipo
  autor-id autor-texto objeto-indicacao destinatario-id destinatario-texto tipo-requerimento
  categoria-mocao updated-by lock-version]}) -> {:id}`. PATCH parcial (só campos não-nil mudam). Lança em:
  inexistente, estado terminal, conflito de `lock-version`.
- `colunas` (privado) ganha `:lock_version` — necessário p/ o detalhe (Task 9) devolver o valor ao FE.

- [ ] **Step 1: Escrever os testes de integração (falhando)**

Criar `apps/backend/test/integration/oplenario/legislativo/proposicao_editar_db_test.clj`:
```clojure
(ns oplenario.legislativo.proposicao-editar-db-test
  "Onda B Slice 2 — db/proposicao.clj/editar!: PATCH parcial (CAS), guard de estado terminal, conflito de
  lock-version, inexistente."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente]
  (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                        :uf "CE" :municipio-nome "Fortaleza" :ementa "Ementa original"}))

(deftest editar-atualiza-so-os-campos-presentes
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/editar! tx {:id id :ente-id ente :ementa "Ementa corrigida" :updated-by (random-uuid) :lock-version 0})
          (let [atual (prop/buscar tx ente id)]
            (is (= "Ementa corrigida" (:ementa atual)))
            (is (= 1 (:lock-version atual)))))))))

(deftest editar-conflito-de-lock-version-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (prop/editar! tx {:id id :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 99}))))))))

(deftest editar-inexistente-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? clojure.lang.ExceptionInfo
                     (prop/editar! tx {:id (random-uuid) :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 0})))))))

(deftest editar-estado-terminal-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/mudar-estado! tx {:id id :ente-id ente :estado "arquivada" :updated-by (random-uuid) :lock-version 0})
          (is (thrown? clojure.lang.ExceptionInfo
                       (prop/editar! tx {:id id :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 1}))))))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.legislativo.proposicao-editar-db-test`
Expected: FAIL (`editar!` unbound).

- [ ] **Step 3: Implementar `editar!`**

Em `apps/backend/src/oplenario/legislativo/db/proposicao.clj`, adicionar `:lock_version` a `colunas`:
```clojure
(def ^:private colunas
  [:id :ente_id :tipo :ano :sequencial :urn_lex :ementa :autor_tipo :autor_id :autor_texto :estado
   :objeto_indicacao :destinatario_id :destinatario_texto :tipo_requerimento :categoria_mocao
   :atributos_especificos :texto_vigente_versao_id :lock_version :atualizado_em])
```

E, após `mudar-estado!`:
```clojure
(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:legislativo.proposicoes]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn editar!
  "Reescreve metadados (PATCH parcial: so' os campos presentes mudam) de uma proposicao NAO-TERMINAL (CAS
  por lock-version; SELECT...FOR UPDATE evita corrida entre o guard de estado e o UPDATE). O trigger
  tambem barra estado terminal (defesa em profundidade); a excecao aqui carrega a causa real. Mesmo padrao
  de db/documento.clj/editar-rascunho!, mas o guard e' 'nao terminal' (a proposicao nao tem fase rascunho —
  mutacao livre ate estado terminal, §22.4.3 disc.4), nao 'so rascunho'."
  [tx {:keys [id ente-id ementa autor-tipo autor-id autor-texto objeto-indicacao destinatario-id
              destinatario-texto tipo-requerimento categoria-mocao updated-by lock-version]}]
  (let [{:keys [estado]} (estado+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "editar!: proposicao inexistente" {:id id :ente-id ente-id})))
    (when (contains? logic/estados-proposicao-terminais estado)
      (throw (ex-info "editar!: proposicao em estado terminal nao edita" {:id id :estado estado}))))
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.proposicoes
                         :set (cond-> {:updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                                (some? ementa)             (assoc :ementa ementa)
                                (some? autor-tipo)         (assoc :autor_tipo autor-tipo)
                                (some? autor-id)           (assoc :autor_id autor-id)
                                (some? autor-texto)        (assoc :autor_texto autor-texto)
                                (some? objeto-indicacao)   (assoc :objeto_indicacao objeto-indicacao)
                                (some? destinatario-id)    (assoc :destinatario_id destinatario-id)
                                (some? destinatario-texto) (assoc :destinatario_texto destinatario-texto)
                                (some? tipo-requerimento)  (assoc :tipo_requerimento tipo-requerimento)
                                (some? categoria-mocao)    (assoc :categoria_mocao categoria-mocao))
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "editar!: conflito de lock_version ou proposicao inexistente" {:id id :lock-version lock-version})))
    {:id id}))
```
(`oplenario.legislativo.logic` já está `:require`d neste namespace — nenhum require novo.)

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (4 testes).

- [ ] **Step 5: Rodar a suíte inteira do módulo (garantir que `colunas` +lock_version não quebrou `buscar`/`listar-por-estado`)**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.legislativo`
Expected: PASS em tudo.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/db/proposicao.clj \
        apps/backend/test/integration/oplenario/legislativo/proposicao_editar_db_test.clj
git commit -m "feat(legislativo): db/proposicao editar! (CAS, guard nao-terminal)"
```

---

## Task 5: `wire/in/proposicao.clj` — `CriarProposicao` / `EditarProposicao`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/wire/in/proposicao.clj` (hoje stub vazio)
- Test: `apps/backend/test/unit/oplenario/legislativo/wire_in_proposicao_test.clj` (novo)

**Interfaces:**
- Produces: `oplenario.legislativo.wire.in.proposicao/CriarProposicao`, `EditarProposicao` (Malli, `:closed true`).

- [ ] **Step 1: Escrever o teste de unidade (falhando)**

Criar `apps/backend/test/unit/oplenario/legislativo/wire_in_proposicao_test.clj`:
```clojure
(ns oplenario.legislativo.wire-in-proposicao-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.wire.in.proposicao :as wire]))

(deftest criar-proposicao-minima-valida
  (is (m/validate wire/CriarProposicao {:tipo "projeto_lei" :ano 2026 :ementa "X"})))

(deftest criar-proposicao-tipo-desconhecido-invalido
  (is (not (m/validate wire/CriarProposicao {:tipo "decreto_alienigena" :ano 2026 :ementa "X"}))))

(deftest criar-proposicao-campo-extra-invalido
  (is (not (m/validate wire/CriarProposicao {:tipo "projeto_lei" :ano 2026 :ementa "X" :campo-fantasma 1}))))

(deftest editar-proposicao-exige-lock-version
  (is (not (m/validate wire/EditarProposicao {:ementa "X"})))
  (is (m/validate wire/EditarProposicao {:lock-version 0})))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.wire-in-proposicao-test`
Expected: FAIL (`CriarProposicao`/`EditarProposicao` unbound).

- [ ] **Step 3: Implementar os schemas**

Substituir o conteúdo de `apps/backend/src/oplenario/legislativo/wire/in/proposicao.clj`:
```clojure
(ns oplenario.legislativo.wire.in.proposicao
  "Representacao EXTERNA de ENTRADA da proposicao (§22.10 wire/in, ADR-0001, Onda B Slice 2) — os corpos de
  POST/PATCH. `:closed true` recusa campo extra; tenant/autor NAO vem do corpo (vem do ator resolvido na
  auth); o `id` (PATCH) vem do path. Enums saem de legislativo.logic (fonte unica; espelham os CHECK das
  migrations 20260620000013/20260620000015)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def CriarProposicao
  "Corpo de POST /legislativo/proposicoes. `texto` e' OPCIONAL (corpo integral markdown, inline <=32KB —
  overflow p/ objeto_store fica de carry, spec §6)."
  [:map {:closed true}
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe (km/enum-de logic/autor-tipos)]]
   [:autor-id {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   [:texto {:optional true} [:maybe :string]]])

(def EditarProposicao
  "Corpo de PATCH /legislativo/proposicoes/:id. PATCH parcial: so' os campos presentes mudam.
  `lock-version` e' obrigatorio (CAS). Se `texto` presente, promove uma nova versao (origem 'edicao')."
  [:map {:closed true}
   [:lock-version :int]
   [:ementa {:optional true} [:maybe :string]]
   [:autor-tipo {:optional true} [:maybe (km/enum-de logic/autor-tipos)]]
   [:autor-id {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   [:texto {:optional true} [:maybe :string]]])
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (4 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/wire/in/proposicao.clj \
        apps/backend/test/unit/oplenario/legislativo/wire_in_proposicao_test.clj
git commit -m "feat(legislativo): wire/in CriarProposicao/EditarProposicao"
```

---

## Task 6: `wire/out/proposicao.clj` — `ProposicaoDetalheOut`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/wire/out/proposicao.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/wire_out_proposicao_test.clj` (novo)

**Interfaces:**
- Produces: `oplenario.legislativo.wire.out.proposicao/ProposicaoDetalheOut` (Malli, `:closed true`).

- [ ] **Step 1: Escrever o teste de unidade (falhando)**

Criar `apps/backend/test/unit/oplenario/legislativo/wire_out_proposicao_test.clj`:
```clojure
(ns oplenario.legislativo.wire-out-proposicao-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.wire.out.proposicao :as wire]))

(def ^:private minima
  {:id "u" :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:x" :ementa "X" :estado "protocolada"
   :lock-version 0 :atualizado-em "2026-01-01T00:00:00Z"})

(deftest detalhe-minimo-valido
  (is (m/validate wire/ProposicaoDetalheOut minima)))

(deftest detalhe-com-texto-valido
  (is (m/validate wire/ProposicaoDetalheOut (assoc minima :texto "## Art. 1o"))))

(deftest detalhe-sem-lock-version-invalido
  (is (not (m/validate wire/ProposicaoDetalheOut (dissoc minima :lock-version)))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.wire-out-proposicao-test`
Expected: FAIL (`ProposicaoDetalheOut` unbound).

- [ ] **Step 3: Implementar o schema**

Adicionar ao fim de `apps/backend/src/oplenario/legislativo/wire/out/proposicao.clj`:
```clojure
(def ProposicaoDetalheOut
  "GET /legislativo/proposicoes/:id (Onda B Slice 2) — a proposicao inteira (nao o resumo estreito da
  lista) + o texto vigente inline, p/ o form de edicao pre-encher (e a resposta do POST de criacao, com
  o numero oficial + lock-version iniciais)."
  [:map {:closed true}
   [:id :string]
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   [:estado :string]
   [:lock-version :int]
   [:atualizado-em :string]
   [:texto {:optional true} [:maybe :string]]])
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (3 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/wire/out/proposicao.clj \
        apps/backend/test/unit/oplenario/legislativo/wire_out_proposicao_test.clj
git commit -m "feat(legislativo): wire/out ProposicaoDetalheOut"
```

---

## Task 7: `adapters/in/proposicao.clj` — coerção de corpo (criar/editar)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/adapters/in/proposicao.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_in_test.clj` (estender)

**Interfaces:**
- Consumes: `oplenario.legislativo.wire.in.proposicao/CriarProposicao`, `EditarProposicao` (Task 5).
- Produces: `oplenario.legislativo.adapters.in.proposicao/criar-proposicao->dominio` — `(ator wire-in) ->
  {:id :tipo :ano :ementa :autor-tipo :autor-id :autor-texto :objeto-indicacao :destinatario-id
  :destinatario-texto :tipo-requerimento :categoria-mocao :texto :created-by}`.
- Produces: `oplenario.legislativo.adapters.in.proposicao/editar-proposicao->dominio` — `(ator id wire-in)
  -> {:id :lock-version :ementa :autor-tipo :autor-id :autor-texto :objeto-indicacao :destinatario-id
  :destinatario-texto :tipo-requerimento :categoria-mocao :texto :updated-by}`.

- [ ] **Step 1: Escrever os testes (falhando)**

Adicionar a `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_in_test.clj`:
```clojure
(deftest criar-proposicao->dominio-injeta-id-e-created-by
  (let [ator {:identidade-id (random-uuid)}
        m (adapters/criar-proposicao->dominio ator {"tipo" "projeto_lei" "ano" 2026 "ementa" "X"})]
    (is (some? (:id m)))
    (is (= (:identidade-id ator) (:created-by m)))
    (is (= "projeto_lei" (:tipo m)))))

(deftest criar-proposicao->dominio-corpo-invalido-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalido"
                        (adapters/criar-proposicao->dominio {:identidade-id (random-uuid)} {"tipo" "lixo"}))))

(deftest editar-proposicao->dominio-usa-id-do-path-e-updated-by-do-ator
  (let [ator {:identidade-id (random-uuid)} id (random-uuid)
        m (adapters/editar-proposicao->dominio ator id {"lock-version" 0 "ementa" "Y"})]
    (is (= id (:id m)))
    (is (= (:identidade-id ator) (:updated-by m)))
    (is (= "Y" (:ementa m)))))
```
(ajustar o `:require` do arquivo de teste para `[oplenario.legislativo.adapters.in.proposicao :as adapters]`
se ainda não existir esse alias.)

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.proposicao-adapters-in-test`
Expected: FAIL (`criar-proposicao->dominio`/`editar-proposicao->dominio` unbound).

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/adapters/in/proposicao.clj`, ajustar o `:require`/`:import` do
topo do arquivo para incluir Malli + o wire/in + `UUID`:
```clojure
(:require [clojure.string :as str]
          [malli.core :as m]
          [malli.error :as me]
          [oplenario.legislativo.wire.in.proposicao :as wire])
(:import (java.util UUID))
```

E adicionar ao fim do arquivo:
```clojure
;; ---------- Onda B Slice 2: criar/editar (corpo JSON, nao query-params) ----------

(def ^:private campos-criar
  ["tipo" "ano" "ementa" "autor-tipo" "autor-id" "autor-texto" "objeto-indicacao" "destinatario-id"
   "destinatario-texto" "tipo-requerimento" "categoria-mocao" "texto"])
(def ^:private campos-editar
  ["lock-version" "ementa" "autor-tipo" "autor-id" "autor-texto" "objeto-indicacao" "destinatario-id"
   "destinatario-texto" "tipo-requerimento" "categoria-mocao" "texto"])

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->uuid? [s campo] (when (some? s) (->uuid s campo)))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn criar-proposicao->dominio
  "Corpo (wire/in.CriarProposicao) + `ator` -> mapa de dominio p/ Repo/protocolar!. Gera `:id` e
  `:created-by`; `ente-id` vem do ator (o controller injeta)."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-criar)]
    (validar! wire/CriarProposicao m "corpo de criar proposicao invalido")
    {:id (random-uuid) :tipo (:tipo m) :ano (:ano m) :ementa (:ementa m)
     :autor-tipo (:autor-tipo m) :autor-id (->uuid? (:autor-id m) :autor-id) :autor-texto (:autor-texto m)
     :objeto-indicacao (:objeto-indicacao m)
     :destinatario-id (->uuid? (:destinatario-id m) :destinatario-id)
     :destinatario-texto (:destinatario-texto m) :tipo-requerimento (:tipo-requerimento m)
     :categoria-mocao (:categoria-mocao m) :texto (:texto m) :created-by (:identidade-id ator)}))

(defn editar-proposicao->dominio
  "Corpo (wire/in.EditarProposicao) + `ator` + `id` (path, ja' UUID) -> mapa de dominio p/
  Repo/editar-proposicao!."
  [ator id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-editar)]
    (validar! wire/EditarProposicao m "corpo de editar proposicao invalido")
    {:id id :lock-version (:lock-version m) :ementa (:ementa m) :autor-tipo (:autor-tipo m)
     :autor-id (->uuid? (:autor-id m) :autor-id) :autor-texto (:autor-texto m)
     :objeto-indicacao (:objeto-indicacao m)
     :destinatario-id (->uuid? (:destinatario-id m) :destinatario-id)
     :destinatario-texto (:destinatario-texto m) :tipo-requerimento (:tipo-requerimento m)
     :categoria-mocao (:categoria-mocao m) :texto (:texto m) :updated-by (:identidade-id ator)}))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/adapters/in/proposicao.clj \
        apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_in_test.clj
git commit -m "feat(legislativo): adapters/in criar/editar proposicao->dominio"
```

---

## Task 8: `adapters/out/proposicao.clj` — `detalhe->wire`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/adapters/out/proposicao.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_out_test.clj` (estender)

**Interfaces:**
- Consumes: `oplenario.legislativo.wire.out.proposicao/ProposicaoDetalheOut` (Task 6).
- Produces: `oplenario.legislativo.adapters.out.proposicao/detalhe->wire` — `(linha texto-inline-ou-nil) ->
  ProposicaoDetalheOut`.

- [ ] **Step 1: Escrever o teste (falhando)**

Adicionar a `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_out_test.clj`:
```clojure
(deftest detalhe->wire-projeta-e-inclui-texto
  (let [linha {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:x"
               :ementa "X" :estado "protocolada" :lock-version 0
               :atualizado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")}
        out (adapters/detalhe->wire linha "## Art. 1o")]
    (is (= "## Art. 1o" (:texto out)))
    (is (string? (:id out)))))

(deftest detalhe->wire-texto-nil-quando-sem-versao-vigente
  (let [linha {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:x"
               :ementa "X" :estado "protocolada" :lock-version 0
               :atualizado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")}]
    (is (nil? (:texto (adapters/detalhe->wire linha nil))))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.proposicao-adapters-out-test`
Expected: FAIL (`detalhe->wire` unbound).

- [ ] **Step 3: Implementar**

Adicionar ao fim de `apps/backend/src/oplenario/legislativo/adapters/out/proposicao.clj`:
```clojure
(defn detalhe->wire
  "Proposicao (dominio, kebab) + texto vigente inline opcional (string ou nil) -> ProposicaoDetalheOut."
  [linha texto]
  (validado wire/ProposicaoDetalheOut
            {:id (->str (:id linha)) :tipo (:tipo linha) :ano (:ano linha) :sequencial (:sequencial linha)
             :urn-lex (:urn-lex linha) :ementa (:ementa linha) :autor-tipo (:autor-tipo linha)
             :autor-id (->str (:autor-id linha)) :autor-texto (:autor-texto linha)
             :objeto-indicacao (:objeto-indicacao linha) :destinatario-id (->str (:destinatario-id linha))
             :destinatario-texto (:destinatario-texto linha) :tipo-requerimento (:tipo-requerimento linha)
             :categoria-mocao (:categoria-mocao linha) :estado (:estado linha)
             :lock-version (:lock-version linha) :atualizado-em (->str (:atualizado-em linha)) :texto texto}
            "detalhe de proposicao"))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/adapters/out/proposicao.clj \
        apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_out_test.clj
git commit -m "feat(legislativo): adapters/out detalhe->wire"
```

---

## Task 9: `components/repositorio.clj` — compor as 3 ações numa tx

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/proposicao_repositorio_test.clj` (novo)

**Interfaces:**
- Consumes: `texto/nova-versao!`, `texto/promover!` (já existentes), `proposicao/editar!` (Task 4),
  `logic/decidir-armazenamento` (já existe).
- Modifies: `protocolar!` (existente) passa a aceitar `:texto` opcional em `p`.
- Produces: `RepoLegislativo` ganha `(editar-proposicao! [this ente-id m])` e
  `(buscar-proposicao-detalhe [this ente-id id])` — `-> {:proposicao ... :texto ...}` (`:texto` = a linha
  inteira de `texto/vigente`, ou nil).

- [ ] **Step 1: Escrever o teste de integração (falhando)**

Criar `apps/backend/test/integration/oplenario/legislativo/proposicao_repositorio_test.clj`:
```clojure
(ns oplenario.legislativo.proposicao-repositorio-test
  "Onda B Slice 2 — prova a COMPOSICAO numa unica tx: protocolar!+texto, editar-proposicao!+texto,
  buscar-proposicao-detalhe. Repo real (Postgres+bus), sem HTTP — mesmo padrao de
  votacao_eventos_repo_test.clj (`->RepoLegislativoPg` construido direto com o Component de datasource
  JA STARTADO + `outbox/bus`, sem passar pelo `repositorio`/Stuart Sierra `using` — esse fio so' e'
  montado pelo `oplenario.sistema` em producao)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(deftest protocolar-com-texto-cria-e-promove-versao-na-mesma-tx
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :texto "## Art. 1o"})
        {:keys [proposicao texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (= "vigente" (:estado-versao texto)))
    (is (= "## Art. 1o" (:texto-inline texto)))
    (is (= (:id r) (:id proposicao)))))

(deftest protocolar-sem-texto-nao-cria-versao
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X"})
        {:keys [texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (nil? texto))))

(deftest protocolar-com-texto-grande-demais-lanca
  (let [ente (random-uuid) grande (apply str (repeat 40000 "a"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"objeto_store"
                          (repo/protocolar! *repo* ente {:id (random-uuid) :tipo "projeto_lei" :ano 2026
                                                          :uf "CE" :municipio-nome "Fortaleza" :ementa "X"
                                                          :texto grande})))))

(deftest editar-proposicao-com-texto-promove-nova-versao-origem-edicao
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :texto "## Art. 1o"})]
    (repo/editar-proposicao! *repo* ente {:id (:id r) :lock-version 0 :ementa "Y" :texto "## Art. 1o (rev)"
                                          :updated-by (random-uuid)})
    (let [{:keys [proposicao texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
      (is (= "Y" (:ementa proposicao)))
      (is (= "## Art. 1o (rev)" (:texto-inline texto)))
      (is (= "edicao" (:origem-versao texto))))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.legislativo.proposicao-repositorio-test`
Expected: FAIL (`editar-proposicao!`/`buscar-proposicao-detalhe` unbound; `protocolar!` ignora `:texto`).

- [ ] **Step 3: Adicionar `logic` ao `:require` do namespace**

No topo de `apps/backend/src/oplenario/legislativo/components/repositorio.clj`, adicionar
`[oplenario.legislativo.logic :as logic]` à lista de `:require` (ordem alfabética com os demais).

- [ ] **Step 4: Estender `protocolar!` para compor o texto inicial**

Substituir a implementação atual de `protocolar!` no `defrecord RepoLegislativoPg`:
```clojure
  (protocolar! [this ente-id p]
    (transacao this ente-id
      (fn [tx]
        (when-let [corpo (:texto p)]
          (when (= :objeto-store (logic/decidir-armazenamento corpo))
            (throw (ex-info "texto excede o limite inline (32KB); objeto_store fora do escopo desta fatia"
                            {:tipo :validacao/invalido :campos [:texto]}))))
        (let [r (proposicao/protocolar! tx p)]
          (when-let [corpo (:texto p)]
            (let [versao-id (random-uuid)]
              (texto/nova-versao! tx {:id versao-id :ente-id ente-id :proposicao-id (:id r)
                                       :origem-versao "protocolo" :formato "markdown"
                                       :texto-inline corpo :created-by (:created-by p)})
              (texto/promover! tx {:ente-id ente-id :proposicao-id (:id r) :versao-id versao-id
                                    :updated-by (:created-by p) :lock-version 0})))
          (producers/emitir-protocolada! bus tx ente-id
            {:proposicao-id (:id r) :tipo (:tipo p) :ano (:ano p) :sequencial (:sequencial r)
             :urn-lex (:urn-lex r) :ementa (:ementa p) :estado "protocolada"
             :autor-tipo (:autor-tipo p) :autor-texto (:autor-texto p)})
          r))))
```

- [ ] **Step 5: Adicionar os 2 métodos novos ao protocolo `RepoLegislativo`**

No `defprotocol RepoLegislativo`, logo após `(mudar-estado-proposicao! [this ente-id m])`:
```clojure
  (editar-proposicao! [this ente-id m]
    "PATCH parcial (CAS) + promove nova versao 'edicao' se :texto presente, 1 tx.")
  (buscar-proposicao-detalhe [this ente-id id]
    "{:proposicao ... :texto (a linha de texto/vigente, ou nil)}, uma leitura.")
```

- [ ] **Step 6: Implementar os 2 métodos no `defrecord`**

Logo após a implementação de `mudar-estado-proposicao!`:
```clojure
  (editar-proposicao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (when-let [corpo (:texto m)]
          (when (= :objeto-store (logic/decidir-armazenamento corpo))
            (throw (ex-info "texto excede o limite inline (32KB); objeto_store fora do escopo desta fatia"
                            {:tipo :validacao/invalido :campos [:texto]}))))
        (let [r (proposicao/editar! tx (assoc m :ente-id ente-id))]
          (when-let [corpo (:texto m)]
            (let [versao-id (random-uuid)]
              (texto/nova-versao! tx {:id versao-id :ente-id ente-id :proposicao-id (:id m)
                                       :origem-versao "edicao" :formato "markdown"
                                       :texto-inline corpo :created-by (:updated-by m)})
              (texto/promover! tx {:ente-id ente-id :proposicao-id (:id m) :versao-id versao-id
                                    :updated-by (:updated-by m) :lock-version 0})))
          r))))
  (buscar-proposicao-detalhe [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        {:proposicao (proposicao/buscar tx ente-id id)
         :texto (texto/vigente tx ente-id id)})))
```

- [ ] **Step 7: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (4 testes).

- [ ] **Step 8: Rodar a suíte inteira do módulo legislativo (garantir que estender `protocolar!` não quebrou nenhum caller existente — Slice 1, votação, F3.x)**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.legislativo`
Expected: PASS em tudo.

- [ ] **Step 9: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/components/repositorio.clj \
        apps/backend/test/integration/oplenario/legislativo/proposicao_repositorio_test.clj
git commit -m "feat(legislativo): Repo compoe criar/editar proposicao com texto, 1 tx"
```

---

## Task 10: `controllers.clj` — `criar-proposicao` / `editar-proposicao` / `buscar-proposicao-ficha`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/controllers.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/controllers_proposicao_test.clj` (novo)

**Interfaces:**
- Consumes: `repo/protocolar!`, `repo/editar-proposicao!`, `repo/buscar-proposicao-detalhe` (Task 9).
- Produces: `oplenario.legislativo.controllers/criar-proposicao` — `(repo-legislativo resolver-municipio
  ente-id m) -> {:id :sequencial :urn-lex}`.
- Produces: `oplenario.legislativo.controllers/editar-proposicao` — `(repo-legislativo ente-id m) -> {:id}`.
- Produces: `oplenario.legislativo.controllers/buscar-proposicao-ficha` — `(repo-legislativo ente-id id) ->
  {:proposicao ... :texto (string ou nil)} | nil`.

- [ ] **Step 1: Escrever os testes (falhando) com um Repo fake**

Criar `apps/backend/test/unit/oplenario/legislativo/controllers_proposicao_test.clj`:
```clojure
(ns oplenario.legislativo.controllers-proposicao-test
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]))

(defn- fake-repo [& {:keys [protocolar editar detalhe]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (protocolar! [_ _ente-id p] (protocolar p))
    (editar-proposicao! [_ _ente-id m] (editar m))
    (buscar-proposicao-detalhe [_ _ente-id id] (detalhe id))))

(deftest criar-proposicao-mescla-uf-municipio-do-resolver
  (let [recebido (atom nil)
        repo (fake-repo :protocolar (fn [p] (reset! recebido p) {:id (:id p) :sequencial 1 :urn-lex "urn:x"}))
        resolver (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})]
    (controllers/criar-proposicao repo resolver (random-uuid) {:id (random-uuid) :tipo "projeto_lei"})
    (is (= "CE" (:uf @recebido)))
    (is (= "Fortaleza" (:municipio-nome @recebido)))))

(deftest buscar-proposicao-ficha-nil-quando-nao-existe
  (let [repo (fake-repo :detalhe (fn [_id] {:proposicao nil :texto nil}))]
    (is (nil? (controllers/buscar-proposicao-ficha repo (random-uuid) (random-uuid))))))

(deftest buscar-proposicao-ficha-extrai-texto-inline
  (let [repo (fake-repo :detalhe (fn [_id] {:proposicao {:id "p"} :texto {:texto-inline "## Art. 1o"}}))]
    (is (= "## Art. 1o" (:texto (controllers/buscar-proposicao-ficha repo (random-uuid) (random-uuid)))))))

(deftest editar-proposicao-repassa-ao-repo
  (let [repo (fake-repo :editar (fn [m] {:id (:id m)}))
        id (random-uuid)]
    (is (= {:id id} (controllers/editar-proposicao repo (random-uuid) {:id id})))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.controllers-proposicao-test`
Expected: FAIL (`criar-proposicao`/`editar-proposicao`/`buscar-proposicao-ficha` unbound).

- [ ] **Step 3: Implementar**

Adicionar a `apps/backend/src/oplenario/legislativo/controllers.clj`, após `listar-proposicoes`:
```clojure
(defn criar-proposicao
  "Onda B Slice 2 — protocola uma proposicao nova (authz: so' o gate grosso da rota, papel 'secretario',
  mesmo contrato de listar-proposicoes). `resolver-municipio` (injetado pelo host, cross-modulo p/
  cadastros) resolve {:uf :municipio-nome} do ente — precondicao de protocolar! (eixo H). Ente sem perfil
  cadastrado (resolver devolve nil) e' erro de PROVISIONAMENTO, nao de cliente: propaga sem catch (-> 500),
  nunca mascarado como 400."
  [repo-legislativo resolver-municipio ente-id m]
  (let [{:keys [uf municipio-nome]} (resolver-municipio ente-id)]
    (repo/protocolar! repo-legislativo ente-id (merge m {:uf uf :municipio-nome municipio-nome}))))

(defn buscar-proposicao-ficha
  "Onda B Slice 2 — detalhe (proposicao + texto vigente inline) p/ a tela de edicao pre-encher. nil se a
  proposicao nao existe no tenant (-> 404 na borda)."
  [repo-legislativo ente-id id]
  (let [{:keys [proposicao texto]} (repo/buscar-proposicao-detalhe repo-legislativo ente-id id)]
    (when proposicao {:proposicao proposicao :texto (:texto-inline texto)})))

(defn editar-proposicao
  "Onda B Slice 2 — edita metadados e/ou promove nova versao de texto ('edicao'). Mesmo gate grosso; `m`
  ja' vem coagido pelo adapters/in."
  [repo-legislativo ente-id m]
  (repo/editar-proposicao! repo-legislativo ente-id m))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (4 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/controllers.clj \
        apps/backend/test/unit/oplenario/legislativo/controllers_proposicao_test.clj
git commit -m "feat(legislativo): controllers criar/editar/buscar-ficha proposicao"
```

---

## Task 11: HTTP — 3 rotas novas + injeção do `resolver-municipio` (host)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/proposicao_escrita_http_in_test.clj` (novo)

**Interfaces:**
- Consumes: `controllers/criar-proposicao`, `controllers/editar-proposicao`,
  `controllers/buscar-proposicao-ficha` (Task 10); `adapters-in-votacao/id-param->uuid` (já existe, reuso
  cross-arquivo dentro do mesmo módulo).
- Produces: `legislativo-http/rotas` ganha a chave opcional `:resolver-municipio` no mapa de opts.
- Produces: 3 rotas — `POST /legislativo/proposicoes` (201), `GET /legislativo/proposicoes/:id` (200/404),
  `PATCH /legislativo/proposicoes/:id` (200/404).

- [ ] **Step 1: Escrever os testes HTTP (falhando)**

Criar `apps/backend/test/integration/oplenario/legislativo/proposicao_escrita_http_in_test.clj` (mesmo
padrão DB-free de `proposicao_http_in_test.clj`, Repo fake via `reify`):
```clojure
(ns oplenario.legislativo.proposicao-escrita-http-in-test
  "Onda B Slice 2 — as 3 rotas novas de escrita: POST criar, GET detalhe, PATCH editar. DB-free (Repo FAKE)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas]))

(defn- detalhe-canonico [ente id]
  {:id id :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 1
   :urn-lex "urn:lex:x" :ementa "X" :estado "protocolada" :lock-version 0
   :atualizado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")})

(defn- fake-repo-legislativo [{:keys [protocolar editar detalhe]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (protocolar! [_ _ente-id p] (protocolar p))
    (editar-proposicao! [_ _ente-id m] (editar m))
    (buscar-proposicao-detalhe [_ _ente-id id] (detalhe id))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l
                                   :repo-cadastros nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok) "content-type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest criar-proposicao-201
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:protocolar (fn [_p] {:id id :sequencial 1 :urn-lex "urn:x"})
                                      :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:tipo "projeto_lei" :ano 2026 :ementa "X"}))]
    (is (= 201 (:status r)))
    (is (= "protocolada" (:estado (ler-json r))))))

(deftest criar-proposicao-corpo-invalido-400
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:tipo "lixo"}))]
    (is (= 400 (:status r)))))

(deftest criar-proposicao-sem-papel-403
  (let [repo (fake-repo-legislativo {})
        r (pt/response-for (service-fn #{"vereador"} repo)
                           :post "/legislativo/proposicoes"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:tipo "projeto_lei" :ano 2026 :ementa "X"}))]
    (is (= 403 (:status r)))))

(deftest detalhe-proposicao-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))))

(deftest detalhe-proposicao-inexistente-404
  (let [repo (fake-repo-legislativo {:detalhe (fn [_id] {:proposicao nil :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :get (str "/legislativo/proposicoes/" (random-uuid))
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)))))

(deftest editar-proposicao-200
  (let [ente (random-uuid) id (random-uuid)
        repo (fake-repo-legislativo {:editar (fn [m] {:id (:id m)})
                                      :detalhe (fn [_id] {:proposicao (detalhe-canonico ente id) :texto nil})})
        r (pt/response-for (service-fn #{"secretario"} repo)
                           :patch (str "/legislativo/proposicoes/" id)
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:lock-version 0 :ementa "Y"}))]
    (is (= 200 (:status r)))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test --focus oplenario.legislativo.proposicao-escrita-http-in-test`
Expected: FAIL (rotas 404 — não registradas ainda; `rotas/montar` pode reclamar de `:repo-cadastros` — checar
o `defn montar` em `rotas.clj` e ajustar o fixture se necessário).

- [ ] **Step 3: Implementar os 3 handlers e estender `rotas`**

Em `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`, após `listar-proposicoes-handler`:
```clojure
(defn- criar-proposicao-handler
  "POST /legislativo/proposicoes. Cria + relê o detalhe (o Repo devolve so' {:id :sequencial :urn-lex})."
  [repo-leg resolver-municipio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in-proposicao/criar-proposicao->dominio ator (:json-params req))]
      (controllers/criar-proposicao repo-leg resolver-municipio ente-id m)
      (let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id (:id m))]
        (http/json-resposta 201 (adapters-out-proposicao/detalhe->wire proposicao texto))))))

(defn- detalhe-proposicao-handler
  "GET /legislativo/proposicoes/:id."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-proposicao/detalhe->wire proposicao texto))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- editar-proposicao-handler
  "PATCH /legislativo/proposicoes/:id."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          m (adapters-in-proposicao/editar-proposicao->dominio ator id (:json-params req))]
      (controllers/editar-proposicao repo-leg ente-id m)
      (let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-proposicao/detalhe->wire proposicao texto))))))
```

Atualizar a assinatura e o corpo de `rotas` para aceitar `resolver-municipio` e somar as 3 rotas:
```clojure
(defn rotas
  [{:keys [auth repo-legislativo consultar-sessao resolver-municipio]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/sessoes/:id/votacoes" :post
       [auth papel it/corpo-json (abrir-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/abrir-votacao]
      ["/sessoes/:id/votacoes/:votacao-id/votos" :post
       [auth papel it/corpo-json (voto-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/registrar-voto]
      ["/sessoes/:id/votacoes/:votacao-id/encerramento" :post
       [auth papel it/corpo-json (encerrar-handler repo-legislativo consultar-sessao)]
       :route-name :legislativo/encerrar-votacao]
      ["/legislativo/proposicoes" :get [auth papel (listar-proposicoes-handler repo-legislativo)]
       :route-name :legislativo/listar-proposicoes]
      ["/legislativo/proposicoes" :post
       [auth papel it/corpo-json (criar-proposicao-handler repo-legislativo resolver-municipio)]
       :route-name :legislativo/criar-proposicao]
      ["/legislativo/proposicoes/:id" :get [auth papel (detalhe-proposicao-handler repo-legislativo)]
       :route-name :legislativo/detalhe-proposicao]
      ["/legislativo/proposicoes/:id" :patch
       [auth papel it/corpo-json (editar-proposicao-handler repo-legislativo)]
       :route-name :legislativo/editar-proposicao]}))
```

- [ ] **Step 4: Injetar `resolver-municipio` no host**

Em `apps/backend/src/oplenario/rotas.clj`, no `let` de `montar`, após a definição de `membros-da-casa`:
```clojure
        ;; Onda B Slice 2: uf/nome-do-municipio do ente, p/ o legislativo computar a URN em protocolar! —
        ;; mesma inversao de dependencia de consultar-sessao/membros-da-casa/info-ente (§22.10).
        resolver-municipio (fn [ente-id] (repo-cadastros-comp/uf-e-municipio repo-cadastros ente-id))
```
E, na chamada de `legislativo-http/rotas`, somar a chave:
```clojure
        (into (legislativo-http/rotas {:auth auth :repo-legislativo repo-legislativo
                                       :consultar-sessao consultar-sessao
                                       :resolver-municipio resolver-municipio}))
```

- [ ] **Step 5: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (6 testes). Se `rotas/montar` reclamar de `:repo-cadastros nil` no fixture do teste (por
`repo-cadastros-comp/uf-e-municipio` ser chamado eagerly na construção do `let`), ajustar `resolver-municipio`
para so' resolver o Repo dentro do corpo da closure (já é o caso — `(fn [ente-id] ...)` não chama nada na
definição) ou passar um `repo-cadastros` fake mínimo no teste.

- [ ] **Step 6: Rodar a suíte inteira do backend (garantir zero regressão cross-módulo — `rotas.clj` é código do host)**

Run: `cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test`
Expected: PASS (só a falha pré-existente conhecida do `outbox-relay-test`, se o container `oplenario-app-1`
estiver up durante a suíte — ver gotcha em `oplenario-fe-execucao`).

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/diplomat/http/in.clj apps/backend/src/oplenario/rotas.clj \
        apps/backend/test/integration/oplenario/legislativo/proposicao_escrita_http_in_test.clj
git commit -m "feat(legislativo): rotas HTTP criar/detalhe/editar proposicao + resolver-municipio no host"
```

---

## Task 12: Review `ecc` do backend (clojure + database) + correções

**Files:** todos os modificados/criados nas Tasks 2–11.

- [ ] **Step 1: Rodar os dois revisores em paralelo**

Invocar (via Agent tool) `ecc:clojure-review` e `ecc:database-reviewer` sobre o diff da branch
(`git diff main...fe-8-editor-proposicao`), com foco em: (a) o guard fail-closed de `editar!`/CAS; (b) a
composição em 1 tx de `protocolar!`/`editar-proposicao!`; (c) o CHECK constraint da migration 0054 (nome
correto, herança em partições); (d) o resolver `uf-e-municipio` (isolamento RLS, nil-safety); (e) authz
grossa das 3 rotas novas.

- [ ] **Step 2: Aplicar as correções encontradas (CRÍTICO/MAJOR obrigatórias; MENOR a critério)**

Para cada achado aplicado: editar o arquivo, rodar o teste focado daquele arquivo, e só então seguir pro
próximo achado (nunca acumular mudanças não verificadas).

- [ ] **Step 3: Rodar a suíte inteira + clj-kondo de novo**

Run:
```bash
cd apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test
clojure -Sdeps '{:aliases {:kondo {:extra-deps {clj-kondo/clj-kondo {:mvn/version "2026.05.25"}} :main-opts ["-m" "clj-kondo.main"]}}}' -M:kondo --lint src test
```
Expected: suíte verde (exceto o flaky conhecido do outbox); `clj-kondo` sem erros de import-lint.

- [ ] **Step 4: Commit das correções**

```bash
git add -A
git commit -m "fix(legislativo): correcoes do review ecc clojure+database (Onda B Slice 2)"
```
(Só commitar se houve mudança — pular este step se o review não achou nada a corrigir.)

---

## Task 13: Codegen — `ProposicaoDetalheOut` no `contrato-legislativo.gen.ts`

**Files:**
- Modify: `apps/backend/src/oplenario/codegen/gerar_legislativo.clj`
- Modify (gerado, não editar a mão): `apps/frontend/src/lib/contrato-legislativo.gen.ts`
- Test: `apps/backend/test/unit/oplenario/codegen/gerar_legislativo_test.clj` (estender se existir)

**Interfaces:**
- Consumes: `oplenario.legislativo.wire.out.proposicao/ProposicaoDetalheOut` (Task 6).
- Produces: `ProposicaoDetalheOut` (interface TS) em `contrato-legislativo.gen.ts`.

- [ ] **Step 1: Escrever/estender o teste do manifesto (falhando)**

Se `apps/backend/test/unit/oplenario/codegen/gerar_legislativo_test.clj` já existir, adicionar:
```clojure
(deftest manifesto-inclui-proposicao-detalhe-out
  (is (some #(= "ProposicaoDetalheOut" (first %)) gerar-legislativo/manifesto)))
```
Se não existir, criar o arquivo com esse teste + `(:require [clojure.test :refer [deftest is]]
[oplenario.codegen.gerar-legislativo :as gerar-legislativo])`.

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.codegen.gerar-legislativo-test`
Expected: FAIL (manifesto não contém `ProposicaoDetalheOut`).

- [ ] **Step 3: Atualizar o manifesto**

Em `apps/backend/src/oplenario/codegen/gerar_legislativo.clj`:
```clojure
(def manifesto
  [["ProposicaoResumoOut" proposicao/ProposicaoResumoOut]
   ["ListaProposicoesOut" proposicao/ListaProposicoesOut]
   ["ProposicaoDetalheOut" proposicao/ProposicaoDetalheOut]])
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Regenerar o arquivo TS**

Run:
```bash
cd apps/backend && clojure -M -m oplenario.codegen.gerar-legislativo ../frontend/src/lib/contrato-legislativo.gen.ts
```
Expected: `[oplenario] tipos TS de legislativo gerados em ../frontend/src/lib/contrato-legislativo.gen.ts ( 3 interfaces)`.

- [ ] **Step 6: Conferir visualmente o arquivo gerado**

Read `apps/frontend/src/lib/contrato-legislativo.gen.ts` e confirmar que `ProposicaoDetalheOut` apareceu com
os campos esperados (incluindo `texto?: string | null` e `lockVersion: number`).

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/oplenario/codegen/gerar_legislativo.clj \
        apps/backend/test/unit/oplenario/codegen/gerar_legislativo_test.clj \
        apps/frontend/src/lib/contrato-legislativo.gen.ts
git commit -m "feat(codegen): ProposicaoDetalheOut no contrato-legislativo.gen.ts"
```

---

## Task 14: Frontend — fix do carry de navegação (`?token=` em `<Link>`)

**Files:**
- Create: `apps/frontend/src/lib/nav.ts`
- Modify: `apps/frontend/src/app/(interno)/topo.tsx`
- Test: `apps/frontend/src/lib/nav.test.ts` (novo)

**Interfaces:**
- Produces: `oplenario.nav/comToken` — `(href: string, token: string | null) => string` — anexa
  `?token=<token>` (URL-encoded) ao `href` se `token` não for nulo; devolve `href` intocado se `token` for
  nulo (produção real, sem token via querystring).

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/frontend/src/lib/nav.test.ts`:
```typescript
import { describe, expect, it } from "vitest";
import { comToken } from "./nav";

describe("comToken", () => {
  it("anexa o token como querystring quando presente", () => {
    expect(comToken("/proposicoes", "abc123")).toBe("/proposicoes?token=abc123");
  });

  it("preserva o href intocado quando o token e' nulo", () => {
    expect(comToken("/proposicoes", null)).toBe("/proposicoes");
  });

  it("URL-encoda o token (claims JSON de dev tem caracteres especiais)", () => {
    expect(comToken("/proposicoes", '{"a":1}')).toBe(`/proposicoes?token=${encodeURIComponent('{"a":1}')}`);
  });
});
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx vitest run src/lib/nav.test.ts`
Expected: FAIL (`comToken` não existe — módulo `./nav` não encontrado).

- [ ] **Step 3: Implementar**

Criar `apps/frontend/src/lib/nav.ts`:
```typescript
// Preserva o ?token= dev entre navegacoes internas (<Link>). Sem isto, qualquer <Link> perde o token de
// dev (useAuth le' de useSearchParams() a cada pagina) — carry da Slice 1, agora corrigido porque esta
// fatia introduz o primeiro loop real de navegacao ida-e-volta (lista -> editor -> lista).
export function comToken(href: string, token: string | null): string {
  if (!token) return href;
  return `${href}?token=${encodeURIComponent(token)}`;
}
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (3 testes).

- [ ] **Step 5: Usar `comToken` em `TopoInterno`**

Em `apps/frontend/src/app/(interno)/topo.tsx`, importar `useAuth`/`comToken` e aplicar aos `href`s de nav:
```tsx
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
// ...
export function TopoInterno({ area, ator }: { area: string; ator: { nome: string; papel: string } }) {
  const { tema, alternar } = useTema();
  const { token } = useAuth();
  // ...
  {DESTINOS_NAV.map((d) => (
    <Link
      key={d.href}
      href={comToken(d.href, token)}
      aria-current={d.rotulo === area ? "page" : undefined}
    >
      {d.rotulo}
    </Link>
  ))}
```

- [ ] **Step 6: Rodar os testes de `topo.tsx` (se existirem) para garantir que nada quebrou**

Run: `docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx vitest run src/app/\(interno\)/topo.test.tsx`
Expected: PASS (ou "no test files found" se não existir teste dedicado — nesse caso seguir).

- [ ] **Step 7: Commit**

```bash
git add apps/frontend/src/lib/nav.ts apps/frontend/src/lib/nav.test.ts apps/frontend/src/app/\(interno\)/topo.tsx
git commit -m "fix(fe): preserva ?token= dev em navegacao interna via <Link> (comToken)"
```

---

## Task 15: Frontend — `use-proposicao-detalhe.ts` (GET single)

**Files:**
- Create: `apps/frontend/src/lib/use-proposicao-detalhe.ts`
- Test: `apps/frontend/src/lib/use-proposicao-detalhe.test.ts`

**Interfaces:**
- Consumes: `ProposicaoDetalheOut` (Task 13), `camelizarChaves` (`boundary.ts`, já existe).
- Produces: `useProposicaoDetalhe(token: string | null, id: string | null) -> { dados:
  ProposicaoDetalheOut | null, estado: "carregando" | "pronto" | "erro" }`. `id === null` (fluxo de criar,
  sem detalhe a buscar) devolve `{ dados: null, estado: "pronto" }` sem chamar fetch.

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/frontend/src/lib/use-proposicao-detalhe.test.ts`:
```typescript
import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useProposicaoDetalhe } from "./use-proposicao-detalhe";

const respostaFake = {
  id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 1, "urn-lex": "urn:x", ementa: "X",
  estado: "protocolada", "lock-version": 0, "atualizado-em": "2026-01-01T00:00:00Z",
};

describe("useProposicaoDetalhe", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/proposicoes/:id e cameliza", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.urnLex).toBe("urn:x");
  });

  it("id nulo -> 'pronto' sem chamar fetch (fluxo de criar)", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe(null, "1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx vitest run src/lib/use-proposicao-detalhe.test.ts`
Expected: FAIL (módulo não existe).

- [ ] **Step 3: Implementar**

Criar `apps/frontend/src/lib/use-proposicao-detalhe.ts`:
```typescript
"use client";

// Hook de detalhe de uma proposicao (Onda B Slice 2) — GET /api/legislativo/proposicoes/:id. `id === null`
// e' o fluxo de CRIAR (nao ha' nada pra' buscar); devolve "pronto"/dados-nulo sem chamar fetch, mesmo
// idioma de useProposicoes (guard `vivo` contra unmount).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ProposicaoDetalheOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

export function useProposicaoDetalhe(token: string | null, id: string | null) {
  const [dados, setDados] = useState<ProposicaoDetalheOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");

  useEffect(() => {
    if (!id) return;
    if (!token) return;
    let vivo = true;
    setEstado("carregando");
    (async () => {
      try {
        const r = await fetch(`/api/legislativo/proposicoes/${id}`, {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as ProposicaoDetalheOut);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, id]);

  if (!id) return { dados: null, estado: "pronto" as Estado };
  if (!token) return { dados: null, estado: "erro" as Estado };
  return { dados, estado };
}
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (4 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/use-proposicao-detalhe.ts apps/frontend/src/lib/use-proposicao-detalhe.test.ts
git commit -m "feat(fe): hook use-proposicao-detalhe (GET single)"
```

---

## Task 16: Frontend — hooks de mutação `use-criar-proposicao` / `use-editar-proposicao`

**Files:**
- Create: `apps/frontend/src/lib/use-criar-proposicao.ts`
- Create: `apps/frontend/src/lib/use-editar-proposicao.ts`
- Test: `apps/frontend/src/lib/use-criar-proposicao.test.ts`
- Test: `apps/frontend/src/lib/use-editar-proposicao.test.ts`

**Interfaces:**
- Produces: `useCriarProposicao(token: string | null) -> { criar: (corpo: CriarProposicaoIn) =>
  Promise<ProposicaoDetalheOut>, estado: "ocioso" | "enviando" | "erro", erro: string | null }`. `criar`
  lança se `!token` ou se a resposta não for `ok` (mensagem extraída do corpo de erro quando presente).
- Produces: `useEditarProposicao(token: string | null, id: string) -> { editar: (corpo:
  EditarProposicaoIn) => Promise<ProposicaoDetalheOut>, estado: "ocioso" | "enviando" | "erro", erro:
  string | null }`. Mesmo contrato, método PATCH.
- **Primeiro par de hooks de mutação do app** — não existe nenhum precedente de POST/PATCH no frontend.

- [ ] **Step 1: Escrever os testes (falhando)**

Criar `apps/frontend/src/lib/use-criar-proposicao.test.ts`:
```typescript
import { describe, expect, it, vi, afterEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useCriarProposicao } from "./use-criar-proposicao";

const respostaFake = {
  id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 1, "urn-lex": "urn:x", ementa: "X",
  estado: "protocolada", "lock-version": 0, "atualizado-em": "2026-01-01T00:00:00Z",
};

describe("useCriarProposicao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POST /api/legislativo/proposicoes e devolve o detalhe camelizado", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao("tok"));
    let devolvido: unknown;
    await act(async () => {
      devolvido = await result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" });
    });
    expect((devolvido as { urnLex: string }).urnLex).toBe("urn:x");
    expect(result.current.estado).toBe("ocioso");
  });

  it("envia o corpo em snake/kebab (o backend espera kebab)", async () => {
    let corpoCapturado = "";
    global.fetch = vi.fn(async (_url: string, opts: RequestInit) => {
      corpoCapturado = opts.body as string;
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao("tok"));
    await act(async () => {
      await result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" });
    });
    expect(JSON.parse(corpoCapturado)).toEqual({ tipo: "projeto_lei", ano: 2026, ementa: "X" });
  });

  it("erro do backend -> estado 'erro' + rejeita a promise", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 400, json: async () => ({ erro: "invalido" }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao("tok"));
    await expect(result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" })).rejects.toThrow();
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> rejeita sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao(null));
    await expect(result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
```

Criar `apps/frontend/src/lib/use-editar-proposicao.test.ts` (mesma forma, método PATCH e `id` na URL):
```typescript
import { describe, expect, it, vi, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useEditarProposicao } from "./use-editar-proposicao";

const respostaFake = {
  id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 1, "urn-lex": "urn:x", ementa: "Y",
  estado: "protocolada", "lock-version": 1, "atualizado-em": "2026-01-01T00:00:00Z",
};

describe("useEditarProposicao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("PATCH /api/legislativo/proposicoes/:id", async () => {
    let urlCapturada = "";
    let metodoCapturado = "";
    global.fetch = vi.fn(async (url: string, opts: RequestInit) => {
      urlCapturada = url;
      metodoCapturado = opts.method ?? "";
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarProposicao("tok", "1"));
    await act(async () => {
      await result.current.editar({ lockVersion: 0, ementa: "Y" });
    });
    expect(urlCapturada).toBe("/api/legislativo/proposicoes/1");
    expect(metodoCapturado).toBe("PATCH");
  });

  it("erro do backend -> estado 'erro' + rejeita", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 409, json: async () => ({ erro: "conflito" }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarProposicao("tok", "1"));
    await expect(result.current.editar({ lockVersion: 0 })).rejects.toThrow();
  });
});
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx vitest run src/lib/use-criar-proposicao.test.ts src/lib/use-editar-proposicao.test.ts`
Expected: FAIL (módulos não existem).

- [ ] **Step 3: Implementar**

Criar `apps/frontend/src/lib/use-criar-proposicao.ts`:
```typescript
"use client";

// Onda B Slice 2 — PRIMEIRO hook de mutacao do app (nenhum POST/PATCH existia antes). Envia o corpo em
// kebab-case (o backend espera; nao ha' boundary de escrita ainda — o corpo e' escrito diretamente nas
// chaves que o wire/in espera, ja' que os campos aqui sao poucos e triviais de nomear a mao). Guard `vivo`
// contra unmount, mesmo idioma dos hooks de leitura (useProposicoes/useProposicaoDetalhe).

import { useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ProposicaoDetalheOut } from "./contrato-legislativo.gen";

export type CriarProposicaoIn = {
  tipo: string;
  ano: number;
  ementa: string;
  autorTipo?: string;
  autorId?: string;
  autorTexto?: string;
  objetoIndicacao?: string;
  destinatarioId?: string;
  destinatarioTexto?: string;
  tipoRequerimento?: string;
  categoriaMocao?: string;
  texto?: string;
};

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: CriarProposicaoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useCriarProposicao(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);

  async function criar(corpo: CriarProposicaoIn): Promise<ProposicaoDetalheOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    setEstado("enviando");
    setErro(null);
    try {
      const r = await fetch("/api/legislativo/proposicoes", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao protocolar (status ${r.status})`;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as ProposicaoDetalheOut;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current) setEstado("erro");
      throw e;
    }
  }

  return { criar, estado, erro };
}
```

Criar `apps/frontend/src/lib/use-editar-proposicao.ts` (mesmo idioma, PATCH + `id` fixo):
```typescript
"use client";

// Onda B Slice 2 — segundo hook de mutacao (irmao de use-criar-proposicao). `id` e' fixo por instancia
// (a pagina de edicao ja sabe qual proposicao esta' editando).

import { useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ProposicaoDetalheOut } from "./contrato-legislativo.gen";

export type EditarProposicaoIn = {
  lockVersion: number;
  ementa?: string;
  autorTipo?: string;
  autorId?: string;
  autorTexto?: string;
  objetoIndicacao?: string;
  destinatarioId?: string;
  destinatarioTexto?: string;
  tipoRequerimento?: string;
  categoriaMocao?: string;
  texto?: string;
};

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: EditarProposicaoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useEditarProposicao(token: string | null, id: string) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);

  async function editar(corpo: EditarProposicaoIn): Promise<ProposicaoDetalheOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    setEstado("enviando");
    setErro(null);
    try {
      const r = await fetch(`/api/legislativo/proposicoes/${id}`, {
        method: "PATCH",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao salvar (status ${r.status})`;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as ProposicaoDetalheOut;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current) setEstado("erro");
      throw e;
    }
  }

  return { editar, estado, erro };
}
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (6 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/use-criar-proposicao.ts apps/frontend/src/lib/use-criar-proposicao.test.ts \
        apps/frontend/src/lib/use-editar-proposicao.ts apps/frontend/src/lib/use-editar-proposicao.test.ts
git commit -m "feat(fe): hooks de mutacao use-criar-proposicao/use-editar-proposicao"
```

---

## Task 17: Frontend — `FormularioProposicao` (componente compartilhado)

**Files:**
- Create: `apps/frontend/src/app/(interno)/editor-proposicao/formulario-proposicao.tsx`
- Create: `apps/frontend/src/app/(interno)/editor-proposicao/formulario-proposicao.css`
- Test: `apps/frontend/src/app/(interno)/editor-proposicao/formulario-proposicao.test.tsx`

**Interfaces:**
- Produces: `<FormularioProposicao valorInicial={...} aoSubmeter={...} enviando={boolean} erro={string |
  null} rotuloAcaoPrimaria={string} bloquearIdentidade={boolean} />` — ficha técnica (espécie/autor/campos
  condicionais por tipo/ementa) + textarea markdown com helpers de inserção + botão primário único (rótulo
  variável por tela). `bloquearIdentidade` (default `false`) desabilita `Espécie`+`Ano` — `tipo`/`ano`/
  `sequencial`/`urn-lex` são imutáveis após o protocolo (trigger `trg_proposicoes_imut_identidade`, eixo H);
  a tela de criar usa `false` (Task 18), a de editar usa `true` (Task 19).

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/frontend/src/app/(interno)/editor-proposicao/formulario-proposicao.test.tsx`:
```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { FormularioProposicao } from "./formulario-proposicao";

describe("FormularioProposicao", () => {
  it("renderiza os campos base e o botao com o rotulo passado", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    expect(screen.getByLabelText(/espécie/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/ementa/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Protocolar" })).toBeInTheDocument();
  });

  it("mostra o campo condicional 'objeto da indicação' so' quando a especie e' indicacao", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    expect(screen.queryByLabelText(/objeto da indicação/i)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/espécie/i), { target: { value: "indicacao" } });
    expect(screen.getByLabelText(/objeto da indicação/i)).toBeInTheDocument();
  });

  it("helper de inserção 'Art. Nº' insere o snippet no textarea", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    const textarea = screen.getByLabelText(/texto da proposição/i) as HTMLTextAreaElement;
    fireEvent.click(screen.getByRole("button", { name: /inserir artigo/i }));
    expect(textarea.value).toContain("Art. ");
  });

  it("desabilita o botao primario quando 'enviando'", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando erro={null} rotuloAcaoPrimaria="Protocolar" />);
    expect(screen.getByRole("button", { name: "Protocolar" })).toBeDisabled();
  });

  it("bloquearIdentidade desabilita Espécie e Ano (imutáveis pós-protocolo)", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro={null} rotuloAcaoPrimaria="Salvar alterações" bloquearIdentidade />);
    expect(screen.getByLabelText(/espécie/i)).toBeDisabled();
    expect(screen.getByLabelText(/^ano$/i)).toBeDisabled();
  });

  it("mostra a mensagem de erro quando presente", () => {
    render(<FormularioProposicao aoSubmeter={vi.fn()} enviando={false} erro="falha ao protocolar" rotuloAcaoPrimaria="Protocolar" />);
    expect(screen.getByRole("alert")).toHaveTextContent("falha ao protocolar");
  });

  it("chama aoSubmeter com o corpo preenchido", () => {
    const aoSubmeter = vi.fn();
    render(<FormularioProposicao aoSubmeter={aoSubmeter} enviando={false} erro={null} rotuloAcaoPrimaria="Protocolar" />);
    fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "Cria o Programa X" } });
    fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));
    expect(aoSubmeter).toHaveBeenCalledWith(expect.objectContaining({ ementa: "Cria o Programa X" }));
  });
});
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx vitest run "src/app/(interno)/editor-proposicao/formulario-proposicao.test.tsx"`
Expected: FAIL (componente não existe).

- [ ] **Step 3: Implementar**

Criar `apps/frontend/src/app/(interno)/editor-proposicao/formulario-proposicao.tsx`:
```tsx
"use client";

// Ficha tecnica + ilha-papel do editor-proposicao (Onda B Slice 2) — porte de
// produto/design-system/o-plenario/telas/editor-proposicao.html, SEM o rail .copiloto (Track IA e'
// satelite) e SEM editor estruturado por artigo/inciso (textarea markdown com a convencao leve
// `## Art. Nº`, arquitetura/22-4-dados-legislativo.md eixo B, + helpers de insercao de snippet).
// Uma acao primaria so' (rotulo variavel: "Protocolar" na criacao, "Salvar alterações" na edicao) — nao
// existe "Salvar rascunho" batendo no backend (spec §2: criar = protocolar! imediato).

import { useRef, useState } from "react";
import "./formulario-proposicao.css";

const ESPECIES = [
  { valor: "projeto_lei", rotulo: "Projeto de Lei" },
  { valor: "projeto_lei_complementar", rotulo: "Projeto de Lei Complementar" },
  { valor: "projeto_resolucao", rotulo: "Projeto de Resolução" },
  { valor: "projeto_decreto_legislativo", rotulo: "Decreto Legislativo" },
  { valor: "proposta_emenda_lom", rotulo: "Emenda à LOM" },
  { valor: "requerimento", rotulo: "Requerimento" },
  { valor: "indicacao", rotulo: "Indicação" },
  { valor: "mocao", rotulo: "Moção" },
];

const AUTOR_TIPOS = [
  { valor: "vereador", rotulo: "Vereador" },
  { valor: "mesa", rotulo: "Mesa Diretora" },
  { valor: "comissao", rotulo: "Comissão" },
  { valor: "executivo", rotulo: "Poder Executivo" },
  { valor: "cidadao", rotulo: "Iniciativa popular" },
];

export type ValoresFormulario = {
  tipo: string;
  ano: number;
  ementa: string;
  autorTipo?: string;
  autorTexto?: string;
  objetoIndicacao?: string;
  tipoRequerimento?: string;
  categoriaMocao?: string;
  texto?: string;
};

const VAZIO: ValoresFormulario = { tipo: "projeto_lei", ano: new Date().getFullYear(), ementa: "" };

export function FormularioProposicao({
  valorInicial,
  aoSubmeter,
  enviando,
  erro,
  rotuloAcaoPrimaria,
  bloquearIdentidade = false,
}: {
  valorInicial?: ValoresFormulario;
  aoSubmeter: (valores: ValoresFormulario) => void;
  enviando: boolean;
  erro: string | null;
  rotuloAcaoPrimaria: string;
  bloquearIdentidade?: boolean;
}) {
  const [valores, setValores] = useState<ValoresFormulario>(valorInicial ?? VAZIO);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  function inserirNoCursor(snippet: string) {
    const el = textareaRef.current;
    if (!el) return;
    const inicio = el.selectionStart ?? valores.texto?.length ?? 0;
    const fim = el.selectionEnd ?? inicio;
    const atual = valores.texto ?? "";
    const novo = atual.slice(0, inicio) + snippet + atual.slice(fim);
    setValores((v) => ({ ...v, texto: novo }));
  }

  return (
    <form
      className="formulario-proposicao"
      onSubmit={(e) => {
        e.preventDefault();
        aoSubmeter(valores);
      }}
    >
      {erro && (
        <p role="alert" className="form-erro">
          {erro}
        </p>
      )}

      <div className="doc-ficha" role="group" aria-label="Ficha técnica da proposição">
        <div className="ficha-campo">
          <label htmlFor="f-especie">Espécie</label>
          <select
            id="f-especie"
            value={valores.tipo}
            disabled={bloquearIdentidade}
            onChange={(e) => setValores((v) => ({ ...v, tipo: e.target.value }))}
          >
            {ESPECIES.map((e) => (
              <option key={e.valor} value={e.valor}>
                {e.rotulo}
              </option>
            ))}
          </select>
        </div>
        <div className="ficha-campo">
          <label htmlFor="f-ano">Ano</label>
          <input
            id="f-ano"
            type="number"
            value={valores.ano}
            disabled={bloquearIdentidade}
            onChange={(e) => setValores((v) => ({ ...v, ano: Number(e.target.value) }))}
          />
        </div>
        <div className="ficha-campo">
          <label htmlFor="f-autor-tipo">Autor</label>
          <select
            id="f-autor-tipo"
            value={valores.autorTipo ?? ""}
            onChange={(e) => setValores((v) => ({ ...v, autorTipo: e.target.value || undefined }))}
          >
            <option value="">Não informado</option>
            {AUTOR_TIPOS.map((a) => (
              <option key={a.valor} value={a.valor}>
                {a.rotulo}
              </option>
            ))}
          </select>
        </div>
        {valores.autorTipo && (
          <div className="ficha-campo">
            <label htmlFor="f-autor-texto">Nome do autor</label>
            <input
              id="f-autor-texto"
              type="text"
              value={valores.autorTexto ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, autorTexto: e.target.value }))}
            />
          </div>
        )}
        {valores.tipo === "indicacao" && (
          <div className="ficha-campo">
            <label htmlFor="f-objeto-indicacao">Objeto da indicação</label>
            <input
              id="f-objeto-indicacao"
              type="text"
              value={valores.objetoIndicacao ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, objetoIndicacao: e.target.value }))}
            />
          </div>
        )}
        {valores.tipo === "requerimento" && (
          <div className="ficha-campo">
            <label htmlFor="f-tipo-requerimento">Tipo do requerimento</label>
            <input
              id="f-tipo-requerimento"
              type="text"
              value={valores.tipoRequerimento ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, tipoRequerimento: e.target.value }))}
            />
          </div>
        )}
        {valores.tipo === "mocao" && (
          <div className="ficha-campo">
            <label htmlFor="f-categoria-mocao">Categoria da moção</label>
            <input
              id="f-categoria-mocao"
              type="text"
              value={valores.categoriaMocao ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, categoriaMocao: e.target.value }))}
            />
          </div>
        )}
      </div>

      <div className="campo-ementa">
        <label htmlFor="f-ementa">Ementa</label>
        <textarea
          id="f-ementa"
          rows={2}
          value={valores.ementa}
          onChange={(e) => setValores((v) => ({ ...v, ementa: e.target.value }))}
        />
      </div>

      <div className="ferramentas" role="toolbar" aria-label="Ferramentas de redação">
        <button type="button" onClick={() => inserirNoCursor("\n\nArt. Nº ")}>
          Inserir artigo
        </button>
        <button type="button" onClick={() => inserirNoCursor("\nI — ")}>
          Inciso
        </button>
        <button type="button" onClick={() => inserirNoCursor("\n§ ")}>
          § Parágrafo
        </button>
      </div>

      <div className="campo-texto">
        <label htmlFor="f-texto">Texto da proposição</label>
        <textarea
          id="f-texto"
          ref={textareaRef}
          rows={16}
          placeholder="## Art. 1º ..."
          value={valores.texto ?? ""}
          onChange={(e) => setValores((v) => ({ ...v, texto: e.target.value }))}
        />
      </div>

      <div className="comando">
        <button type="submit" className="btn btn-primaria" disabled={enviando}>
          {rotuloAcaoPrimaria}
        </button>
      </div>
    </form>
  );
}
```

Criar `apps/frontend/src/app/(interno)/editor-proposicao/formulario-proposicao.css` — CSS mínimo
reaproveitando os tokens/chassi já providos globalmente (`.ficha-campo`/`.doc-ficha` inspirados no mockup;
`.campo-ementa`/`.campo-texto`/`.ferramentas`/`.comando`/`.form-erro` novos, page-scoped, mesma disciplina
de `proposicoes.css` — sem promover ao chassi nesta fatia, gatilho de 2º-uso):
```css
.doc-ficha { display: flex; gap: 0.8rem 1.5rem; flex-wrap: wrap; align-items: flex-end; margin: 1rem 0 0; padding: 0.95rem 1.1rem; background: var(--surface); border: 1px solid var(--linha); border-radius: var(--raio); }
.ficha-campo { display: flex; flex-direction: column; gap: 0.35rem; min-width: 160px; }
.ficha-campo label { font-family: var(--mono); font-size: var(--t-12); letter-spacing: .05em; text-transform: uppercase; color: var(--texto-2); }
.ficha-campo select, .ficha-campo input { font-family: var(--corpo); font-size: var(--t-14); color: var(--texto); background: var(--surface-2); border: 1px solid var(--linha); border-radius: var(--raio-sm); padding: 0.5rem 0.7rem; min-height: 42px; }
.campo-ementa, .campo-texto { display: flex; flex-direction: column; gap: 0.4rem; margin: 1.2rem 0; }
.campo-ementa label, .campo-texto label { font-family: var(--mono); font-size: var(--t-12); letter-spacing: .05em; text-transform: uppercase; color: var(--texto-2); }
.campo-ementa textarea, .campo-texto textarea { font-family: var(--corpo); font-size: var(--t-14); color: var(--texto); background: var(--surface); border: 1px solid var(--linha); border-radius: var(--raio-sm); padding: 0.8rem; line-height: 1.6; }
.ferramentas { display: flex; gap: 0.4rem; flex-wrap: wrap; margin: 0 0 0.5rem; }
.ferramentas button { font-size: var(--t-13); font-weight: 600; color: var(--texto-2); background: var(--surface); border: 1px solid var(--linha); border-radius: var(--raio-sm); padding: 0.4rem 0.7rem; min-height: 38px; cursor: pointer; }
.ferramentas button:hover { color: var(--texto); border-color: var(--texto-2); }
.comando { display: flex; justify-content: flex-end; margin: 1.5rem 0; }
.form-erro { color: var(--erro-texto, #B3261E); background: var(--surface-2); border: 1px solid var(--linha); border-radius: var(--raio-sm); padding: 0.7rem 1rem; margin: 0 0 1rem; }
```
(Conferir se `--erro-texto` já existe em `tokens.css`; se não, usar um token AA já existente equivalente —
checar `GUIDELINES-CHECKLIST.md` antes de cravar uma cor nova.)

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (6 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/app/\(interno\)/editor-proposicao/formulario-proposicao.tsx \
        apps/frontend/src/app/\(interno\)/editor-proposicao/formulario-proposicao.css \
        apps/frontend/src/app/\(interno\)/editor-proposicao/formulario-proposicao.test.tsx
git commit -m "feat(fe): FormularioProposicao (ficha tecnica + textarea + helpers de insercao)"
```

---

## Task 18: Frontend — página `/editor-proposicao` (criar)

**Files:**
- Create: `apps/frontend/src/app/(interno)/editor-proposicao/page.tsx`
- Test: manual (E2E na Task 22) + os testes unitários das Tasks 15–17 já cobrem a lógica.

**Interfaces:**
- Consumes: `useAuth`, `useCriarProposicao` (Task 16), `<FormularioProposicao>` (Task 17), `<TopoInterno>`.

- [ ] **Step 1: Implementar a página**

Criar `apps/frontend/src/app/(interno)/editor-proposicao/page.tsx`:
```tsx
"use client";

// Onda B Slice 2 — criar uma proposicao (protocolar! imediato: uma acao primaria so', "Protocolar", spec
// §2 — nao existe estado de rascunho de backend). Apos sucesso, redireciona a /proposicoes preservando o
// ?token= dev (comToken, Task 14).

import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useCriarProposicao } from "@/lib/use-criar-proposicao";
import { comToken } from "@/lib/nav";
import { FormularioProposicao, type ValoresFormulario } from "./formulario-proposicao";
import { TopoInterno } from "../topo";

export default function PaginaCriarProposicao() {
  const { token } = useAuth();
  const { criar, estado, erro } = useCriarProposicao(token);
  const router = useRouter();

  async function aoSubmeter(valores: ValoresFormulario) {
    try {
      await criar(valores);
      router.push(comToken("/proposicoes", token));
    } catch {
      // erro ja' refletido em `erro`/`estado` pelo hook — nada mais a fazer aqui.
    }
  }

  return (
    <>
      <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pagina-cab">
          <div>
            <span className="eyebrow">Editor de proposição</span>
            <h1>Nova proposição</h1>
          </div>
        </div>
        <FormularioProposicao
          aoSubmeter={aoSubmeter}
          enviando={estado === "enviando"}
          erro={erro}
          rotuloAcaoPrimaria="Protocolar"
        />
      </main>
    </>
  );
}
```

- [ ] **Step 2: Verificar tsc/eslint**

Run:
```bash
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx tsc --noEmit
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx eslint "src/app/(interno)/editor-proposicao/page.tsx"
```
Expected: sem erros.

- [ ] **Step 3: Commit**

```bash
git add apps/frontend/src/app/\(interno\)/editor-proposicao/page.tsx
git commit -m "feat(fe): pagina /editor-proposicao (criar)"
```

---

## Task 19: Frontend — página `/editor-proposicao/[id]` (editar)

**Files:**
- Create: `apps/frontend/src/app/(interno)/editor-proposicao/[id]/page.tsx`

**Interfaces:**
- Consumes: `useAuth`, `useProposicaoDetalhe` (Task 15), `useEditarProposicao` (Task 16),
  `<FormularioProposicao>` (Task 17).

- [ ] **Step 1: Implementar a página**

Criar `apps/frontend/src/app/(interno)/editor-proposicao/[id]/page.tsx`:
```tsx
"use client";

// Onda B Slice 2 — editar uma proposicao existente (metadados e/ou texto; "Salvar alterações" = editar!
// + promove nova versao 'edicao' se o texto mudou, spec §2). Carrega o detalhe (use-proposicao-detalhe),
// pre-enche o form, e envia so' o que mudou junto com o lock-version corrente (CAS).

import { use } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useProposicaoDetalhe } from "@/lib/use-proposicao-detalhe";
import { useEditarProposicao } from "@/lib/use-editar-proposicao";
import { comToken } from "@/lib/nav";
import { FormularioProposicao, type ValoresFormulario } from "../formulario-proposicao";
import { TopoInterno } from "../../topo";

export default function PaginaEditarProposicao({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { token } = useAuth();
  const { dados, estado: estadoDetalhe } = useProposicaoDetalhe(token, id);
  const { editar, estado: estadoEnvio, erro } = useEditarProposicao(token, id);
  const router = useRouter();

  async function aoSubmeter(valores: ValoresFormulario) {
    if (!dados) return;
    try {
      await editar({ lockVersion: dados.lockVersion, ...valores });
      router.push(comToken("/proposicoes", token));
    } catch {
      // erro ja' refletido pelo hook.
    }
  }

  if (estadoDetalhe === "carregando") {
    return (
      <>
        <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
        <main className="envelope">
          <p role="status">Carregando…</p>
        </main>
      </>
    );
  }

  if (estadoDetalhe === "erro" || !dados) {
    return (
      <>
        <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
        <main className="tela-estado">
          <h1>Não foi possível carregar esta proposição</h1>
        </main>
      </>
    );
  }

  return (
    <>
      <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pagina-cab">
          <div>
            <span className="eyebrow">Editor de proposição</span>
            <h1>{dados.ementa}</h1>
          </div>
        </div>
        <FormularioProposicao
          valorInicial={{
            tipo: dados.tipo,
            ano: dados.ano,
            ementa: dados.ementa,
            autorTipo: dados.autorTipo ?? undefined,
            autorTexto: dados.autorTexto ?? undefined,
            objetoIndicacao: dados.objetoIndicacao ?? undefined,
            tipoRequerimento: dados.tipoRequerimento ?? undefined,
            categoriaMocao: dados.categoriaMocao ?? undefined,
            texto: dados.texto ?? undefined,
          }}
          aoSubmeter={aoSubmeter}
          enviando={estadoEnvio === "enviando"}
          erro={erro}
          rotuloAcaoPrimaria="Salvar alterações"
          bloquearIdentidade
        />
      </main>
    </>
  );
}
```

`bloquearIdentidade` (implementado na Task 17, aplicado aqui) desabilita `Espécie`/`Ano` — imutáveis pós-
protocolo pelo trigger `trg_proposicoes_imut_identidade` (eixo H).

- [ ] **Step 2: Verificar tsc/eslint**

Run:
```bash
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx tsc --noEmit
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx eslint "src/app/(interno)/editor-proposicao/[id]/page.tsx"
```
Expected: sem erros.

- [ ] **Step 3: Commit**

```bash
git add apps/frontend/src/app/\(interno\)/editor-proposicao/\[id\]/page.tsx
git commit -m "feat(fe): pagina /editor-proposicao/[id] (editar, bloqueia identidade imutavel)"
```

---

## Task 20: Frontend — habilitar "Nova proposição" + "Editar" em `/proposicoes`

**Files:**
- Modify: `apps/frontend/src/app/(interno)/proposicoes/page.tsx`
- Test: adicionar a um `page.test.tsx` se existir, senão cobrir via os testes já existentes de render (ou
  criar `apps/frontend/src/app/(interno)/proposicoes/page.test.tsx` mínimo se nenhum existir).

**Interfaces:**
- Consumes: `comToken` (Task 14), rota `/editor-proposicao` (Task 18) e `/editor-proposicao/[id]` (Task 19).

- [ ] **Step 1: Escrever/estender o teste (falhando)**

Se não existir `apps/frontend/src/app/(interno)/proposicoes/page.test.tsx`, criar um mínimo cobrindo só o
que esta task muda:
```tsx
import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import PaginaProposicoes from "./page";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

describe("PaginaProposicoes — pontos de entrada da Slice 2", () => {
  afterEach(() => vi.restoreAllMocks());

  it("renderiza o link 'Nova proposição' apontando para /editor-proposicao", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens: [], total: 0, pagina: 1, "tamanho-pagina": 20 }) }) as Response) as unknown as typeof fetch;
    render(<PaginaProposicoes />);
    const link = await screen.findByRole("link", { name: /nova proposição/i });
    expect(link).toHaveAttribute("href", expect.stringContaining("/editor-proposicao"));
  });
});
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx vitest run "src/app/(interno)/proposicoes/page.test.tsx"`
Expected: FAIL (link "Nova proposição" não existe).

- [ ] **Step 3: Implementar**

Em `apps/frontend/src/app/(interno)/proposicoes/page.tsx`:
- Importar `Link` (`next/link`), `comToken` (`@/lib/nav`).
- No `<div className="pagina-cab">`, adicionar o link de "Nova proposição" ao lado do `<h1>Proposições</h1>`:
```tsx
<Link href={comToken("/editor-proposicao", token)} className="btn btn-primaria">
  Nova proposição
</Link>
```
- Na tabela, adicionar uma coluna "Ações" com um link "Editar" por linha:
```tsx
<th scope="col">Ações</th>
{/* ... */}
<td>
  <Link href={comToken(`/editor-proposicao/${linha.id}`, token)} aria-label={`Editar ${linha.numero}`}>
    Editar
  </Link>
</td>
```
(Atualizar o comentário de cabeçalho do arquivo, que hoje documenta explicitamente que estes 2 pontos de
entrada NÃO existem — remover essa observação já que esta fatia os habilita.)

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Rodar a suíte inteira do frontend (garantir zero regressão)**

Run: `docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npm test`
Expected: PASS em tudo.

- [ ] **Step 6: Commit**

```bash
git add apps/frontend/src/app/\(interno\)/proposicoes/page.tsx \
        apps/frontend/src/app/\(interno\)/proposicoes/page.test.tsx
git commit -m "feat(fe): habilita 'Nova proposição' e 'Editar' por linha em /proposicoes"
```

---

## Task 21: Review `ecc` do frontend (react + security) + correções

**Files:** todos os modificados/criados nas Tasks 14–20.

- [ ] **Step 1: Rodar os dois revisores em paralelo**

Invocar `ecc:react-review` e `ecc:security-review` sobre o diff da branch, com foco em: (a) os 2 hooks de
mutação novos (guard de unmount, dupla-submissão, mensagens de erro sem vazar detalhe interno); (b) o
`comToken` (encoding correto, sem XSS via `href`); (c) a acessibilidade do formulário (labels, `role="alert"`
no erro, foco após erro); (d) o helper de inserção no textarea (não quebra seleção/undo do navegador de
forma surpreendente).

- [ ] **Step 2: Aplicar as correções encontradas**

Mesmo processo da Task 12 — editar, rodar o teste focado, só então seguir.

- [ ] **Step 3: Rodar tsc/eslint/build + a suíte inteira**

Run:
```bash
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx tsc --noEmit
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx eslint src
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npm test
docker compose -f /Users/daoudatraore/oplenario/apps/backend/docker-compose.yml exec frontend npx next build
```
Expected: tudo limpo/verde.

- [ ] **Step 4: Commit das correções**

```bash
git add -A
git commit -m "fix(fe): correcoes do review ecc react+security (Onda B Slice 2)"
```
(Pular se o review não achou nada a corrigir.)

---

## Task 22: Revisão final da branch + E2E manual em Docker + gate de merge

**Files:** nenhum novo — verificação.

- [ ] **Step 1: Subir a stack completa e semear**

Run:
```bash
cd /Users/daoudatraore/oplenario/apps/backend && docker compose up -d --build
```
Confirmar `oplenario-app-1`/`oplenario-frontend-1` saudáveis; usar o ente de demo já semeado
(`6e946df5-3c63-4e78-824d-10bc9b965816`) ou rodar o seed novamente se o volume tiver sido resetado.

- [ ] **Step 2: E2E — protocolar do zero**

Abrir `http://localhost:3000/proposicoes?token=<claims-dev>`, clicar "Nova proposição", preencher
espécie+ementa+texto curto, clicar "Protocolar", confirmar redirect para `/proposicoes` **com o `?token=`
preservado na URL** (prova o fix da Task 14) e que a nova proposição aparece na lista com número oficial.

- [ ] **Step 3: E2E — editar uma existente**

Na lista, clicar "Editar" numa proposição semeada, mudar a ementa e o texto, "Salvar alterações", confirmar
redirect e que a mudança persiste (reabrir a ficha/recarregar a lista).

- [ ] **Step 4: AA nos 2 temas**

Alternar tema claro/escuro na tela do editor; medir contraste dos campos/botão primário/mensagem de erro
contra `GUIDELINES-CHECKLIST.md` (mesma disciplina de toda fatia anterior — medir em pixel composto, não
de memória).

- [ ] **Step 5: Rodar as duas suítes completas uma última vez, do zero**

Run:
```bash
cd /Users/daoudatraore/oplenario/apps/backend && DATABASE_URL="jdbc:postgresql://localhost:5544/oplenario" DB_USER=oplenario_pool DB_PASSWORD=oplenario_dev_pool clojure -M:test
docker compose exec frontend npm test
```
Expected: verde (exceto o flaky conhecido do outbox se `oplenario-app-1` estiver up).

- [ ] **Step 6: Apresentar o gate de merge ao Daouda**

Resumir: commits da branch, testes/reviews passados, carries documentados (spec §6), e perguntar
explicitamente se aprova `git merge --no-ff fe-8-editor-proposicao` → `main` (mesmo protocolo de toda fatia
anterior — [[oplenario-git-branch-por-frente]], não mergear sem esse gate).
