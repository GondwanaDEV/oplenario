# Perfil Público do Vereador — Implementation Plan (Onda E, fatia 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publicar, no portal público (sem login), a página institucional de um vereador — identidade, números de atuação, matérias de autoria e como votou em votações abertas — servida pelo módulo `transparencia`.

**Architecture:** `transparencia` continua sendo READ-MODEL projetado de eventos, nunca lendo cross-schema. Três elos novos: (1) `proposicao.protocolada` passa a carregar `:autor-id` e a projeção ganha a coluna `autor_id`, criando o elo autoria↔vereador que hoje não existe; (2) duas projeções novas (`transparencia.voto_parlamentar`, `transparencia.presenca_parlamentar`) alimentadas por `voto.registrado` e `presenca.registrada`; (3) a identidade do vereador (nome, mandato, comissões) NÃO é projetada — chega por **inversão de dependência** do host sobre o Repo de `cadastros`, exatamente como `info-ente` já faz em `transparencia/diplomat/http/in.clj:30`.

**Segurança de voto secreto:** `legislativo.events.votacao/VotoRegistradoPayload` é união discriminada por `:modalidade`; o ramo `secreta` é `:closed` e **não admite** `:vereador-id`/`:voto`. A projeção só grava o ramo nominal. Voto secreto é impossível de vazar por construção — não confie em `if` no consumer, confie no schema, e **teste isso explicitamente**.

**Tech Stack:** Clojure (Pedestal, HoneySQL, next.jdbc, Malli, Migratus, Component) · Next.js 16 / React / Vitest · Postgres com RLS FORCE por `ente_id`.

## Global Constraints

- **Docker sem exceção.** Nunca rodar `clj`/`node`/`npx` no host. Backend: container efêmero de Clojure. Frontend: dentro de `oplenario-frontend-1`. Ver memória `oplenario-docker-mandato`.
- **Nunca mutar o mount vivo** `../frontend:/app`. Container efêmero que escreva ali derruba o `next dev`.
- **Parar `oplenario-app-1` antes de rodar a suíte do backend** — o app de pé derruba `outbox-relay-test` por contenção de advisory lock.
- **§22.10 / import-lint:** `transparencia` NUNCA importa `legislativo`, `sessoes` ou `cadastros`. Tipos de evento são **strings literais**, não imports. O `db/` do módulo só é importado pelo seu Repo-Component.
- **Invariante 1 / RLS:** toda tabela nova tem `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `tenant_isolation` com `NULLIF(current_setting('app.ente_id', true), '')::uuid` (fail-closed), e `GRANT` mínimo a `oplenario_app`.
- **Consumer NUNCA lança.** O relay é UM SÓ, compartilhado por todos os módulos; um `throw` bloqueia head-of-line o barramento inteiro. Toda projeção nova é tolerante a gap (log + nil), padrão de `transparencia/components/repositorio.clj`.
- **UUID vindo de payload de evento chega como STRING** (round-trip jsonb). Coagir com `UUID/fromString` — ver `uuid-payload` no mesmo arquivo.
- **TDD estrito:** teste que falha → implementação mínima → verde → commit. Um commit por task.
- **Português sem acento nos comentários/docstrings de código Clojure** (convenção do repo).
- **Honestidade de dado:** nenhuma seção pode fingir acervo completo. Matéria protocolada ANTES desta fatia não tem `autor_id` (a projeção não tem replay — carry documentado na migration 0044). A UI diz isso.
- **Cortado do design, deliberadamente:** "Agenda pública" (calendário não é domínio modelado) e "Contato institucional" (só links estáticos para Protocolo/Ouvidoria — mantém, é texto).

---

### Task 1: Elo de autoria — `:autor-id` no evento e na projeção

O racional escrito hoje em `legislativo/events/proposicao.clj:19` e `transparencia/models/materia.clj` **proíbe** `autor_id` no read-model público. Essa decisão foi tomada antes de existir o requisito "perfil público do vereador". Ela muda aqui, e as docstrings mudam junto — docstring que proíbe o que o código faz é pior que docstring nenhuma.

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/events/proposicao.clj:17-30`
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj:278-281`
- Create: `apps/backend/resources/migrations/20260720000063-transparencia-materia-autor-id.up.sql`
- Create: `apps/backend/resources/migrations/20260720000063-transparencia-materia-autor-id.down.sql`
- Modify: `apps/backend/src/oplenario/transparencia/db/materia.clj`
- Modify: `apps/backend/src/oplenario/transparencia/models/materia.clj`
- Modify: `apps/backend/src/oplenario/transparencia/components/repositorio.clj` (dispatch `proposicao.protocolada`)
- Test: `apps/backend/test/oplenario/transparencia/projecao_test.clj` (arquivo existente — localize-o com `grep -rl "proposicao.protocolada" test/`; se não existir, crie `test/oplenario/transparencia/perfil_test.clj`)

**Interfaces:**
- Consumes: nada.
- Produces: `transparencia.materia.autor_id uuid NULL`; chave `:autor-id` opcional em `ProtocoladaPayload` (string UUID no wire); `db.materia/inserir!` aceita `:autor-id`.

- [ ] **Step 1: Escrever o teste que falha**

No arquivo de teste de projeção, adicionar:

```clojure
(deftest projeta-autor-id-do-protocolo
  (testing "proposicao.protocolada com :autor-id materializa o elo autoria->vereador"
    (let [ente-id   (criar-ente-de-teste!)
          vereador  (random-uuid)
          prop-id   (random-uuid)]
      (jdbc/with-transaction [tx (ds)]
        (repo/projetar-evento! tx
          {:tipo "proposicao.protocolada" :ente-id ente-id
           :payload {:proposicao-id (str prop-id) :tipo "pl" :ano 2026 :sequencial 42
                     :urn-lex "urn:lex:br;fortaleza:camara.municipal:pl:2026;42"
                     :ementa "Hortas comunitarias" :autor-tipo "vereador"
                     :autor-texto "Helena Past" :autor-id (str vereador)
                     :estado "protocolada"}})
        (is (= vereador (:autor-id (db-materia/buscar tx ente-id prop-id))))))))

(deftest projeta-sem-autor-id-legado
  (testing "evento SEM :autor-id (acervo anterior) projeta com autor_id nulo, sem lancar"
    (let [ente-id (criar-ente-de-teste!) prop-id (random-uuid)]
      (jdbc/with-transaction [tx (ds)]
        (repo/projetar-evento! tx
          {:tipo "proposicao.protocolada" :ente-id ente-id
           :payload {:proposicao-id (str prop-id) :tipo "pl" :ano 2026 :sequencial 43
                     :urn-lex "urn:lex:x" :ementa "Legado" :estado "protocolada"}})
        (is (nil? (:autor-id (db-materia/buscar tx ente-id prop-id))))))))
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd /Users/daoudatraore/oplenario/apps/backend && docker compose stop app
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.projecao-test
```
Esperado: FAIL — `:autor-id` não existe na coluna nem no schema.

- [ ] **Step 3: Migration**

`20260720000063-transparencia-materia-autor-id.up.sql`:

```sql
-- Onda E fatia 2: elo AUTORIA -> VEREADOR no read-model publico. REVISITA a decisao original da mig 0044
-- ("sem autor_id interno"), que foi tomada antes de existir o requisito de PERFIL PUBLICO DO VEREADOR: sem
-- este elo, "materias de autoria" so' sairia por casamento de autor_texto (fragil). Nao ha vazamento de PII:
-- `autor_id` e' o UUID do vereador, ator PUBLICO da Casa, ja' exposto na rota publica de perfil desta fatia.
-- NULLABLE por necessidade: a projecao NAO tem replay (carry documentado na 0044) — materia protocolada antes
-- deste deploy fica sem elo para sempre, e a UI DIZ isso em vez de fingir acervo completo.
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS autor_id uuid;
--;;
-- hot-path "materias de autoria deste vereador" (a secao mais pesada do perfil). Parcial: a maioria das
-- linhas legadas e' NULL e nao precisa entrar no indice.
CREATE INDEX IF NOT EXISTS idx_materia_autor
  ON transparencia.materia (ente_id, autor_id, ano DESC, sequencial DESC)
  WHERE autor_id IS NOT NULL;
```

`...down.sql`:

```sql
DROP INDEX IF EXISTS transparencia.idx_materia_autor;
--;;
ALTER TABLE transparencia.materia DROP COLUMN IF EXISTS autor_id;
```

- [ ] **Step 4: Contrato do evento**

Em `legislativo/events/proposicao.clj`, no `ProtocoladaPayload`, adicionar após `:autor-texto`:

```clojure
   [:autor-id {:optional true} [:maybe :string]]
```

E corrigir a docstring — trocar o trecho `SEM autor_id interno (FK do cadastro) — so' o autor_texto de exibicao.` por:

```clojure
  "Payload de `proposicao.protocolada` — snapshot PUBLICO do ato legislativo no protocolo. So dado publico por
  natureza (proposicao e' ato publico). `autor-id` (Onda E fatia 2) e' o UUID do VEREADOR autor — o elo que o
  PERFIL PUBLICO do vereador precisa p/ listar 'materias de autoria' sem casar string de nome. Viaja como
  STRING (jsonb do outbox nao tem modulo UUID); OPCIONAL porque autor pode ser comissao/mesa/executivo/cidadao,
  que nao tem vereador-id. NAO e' PII: vereador e' ator publico da Casa."
```

- [ ] **Step 5: Emitir o campo**

Em `legislativo/components/repositorio.clj`, no `emitir-protocolada!` (linha ~280), adicionar ao mapa:

```clojure
             :autor-id (some-> (:autor-id p) str)
```

`some->` porque `:autor-id` é nulo para autoria não-parlamentar, e `(str nil)` produziria `""` — string vazia falharia o `UUID/fromString` do consumer.

- [ ] **Step 6: Coluna, model e projeção**

Em `transparencia/db/materia.clj`: acrescentar `:autor_id` ao vetor `cols`; acrescentar `autor-id` ao destructuring de `inserir!` e `:autor_id autor-id` ao mapa de `:values`.

Em `transparencia/models/materia.clj`: acrescentar `[:autor-id {:optional true} [:maybe :uuid]]` e substituir na docstring `nunca por 'autor-id' interno` por `mais o 'autor-id' (UUID do vereador autor — ator publico), o elo do perfil publico (Onda E fatia 2)`.

Em `transparencia/components/repositorio.clj`, no dispatch de `"proposicao.protocolada"`, trocar `(uuid-payload [:proposicao-id])` por:

```clojure
    (db-materia/inserir! tx (-> payload (uuid-payload [:proposicao-id :autor-id]) (assoc :ente-id ente-id)))
```

`uuid-payload` já usa `contains?`, então a chave ausente (evento legado) passa intacta.

- [ ] **Step 7: Rodar e ver passar**

```bash
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.projecao-test
```
Esperado: PASS, ambos os testes.

- [ ] **Step 8: Commit**

```bash
git add apps/backend/resources/migrations/20260720000063-* apps/backend/src/oplenario/legislativo/events/proposicao.clj apps/backend/src/oplenario/legislativo/components/repositorio.clj apps/backend/src/oplenario/transparencia apps/backend/test
git commit -m "feat(transparencia): elo autoria->vereador via :autor-id no protocolo"
```

---

### Task 2: Projeções de voto público e presença

**Files:**
- Create: `apps/backend/resources/migrations/20260720000064-transparencia-parlamentar.up.sql` / `.down.sql`
- Create: `apps/backend/src/oplenario/transparencia/db/parlamentar.clj`
- Modify: `apps/backend/src/oplenario/transparencia/components/repositorio.clj` (`projetar-evento!`)
- Modify: `apps/backend/src/oplenario/transparencia/diplomat/consumers.clj` (`tipos-consumidos`)
- Test: mesmo arquivo de teste da Task 1

**Interfaces:**
- Consumes: `projetar-evento!` (Task 1).
- Produces: `db.parlamentar/registrar-voto!`, `db.parlamentar/registrar-presenca!`, tabelas `transparencia.voto_parlamentar` e `transparencia.presenca_parlamentar`.

- [ ] **Step 1: Escrever os testes que falham**

```clojure
(deftest projeta-voto-nominal
  (testing "voto.registrado nominal materializa o voto publico do vereador"
    (let [ente-id (criar-ente-de-teste!) v (random-uuid) votacao (random-uuid) prop (random-uuid)]
      (jdbc/with-transaction [tx (ds)]
        (repo/projetar-evento! tx
          {:tipo "voto.registrado" :ente-id ente-id
           :payload {:modalidade "nominal" :votacao-id (str votacao) :proposicao-id (str prop)
                     :vereador-id (str v) :voto "sim" :ocorrido-em "2026-05-18T14:00:00Z"}})
        (is (= "sim" (:voto (first (db-parlamentar/votos-do-vereador tx ente-id v 10)))))))))

(deftest ignora-voto-secreto
  (testing "voto.registrado SECRETO nao materializa nada — o payload nem carrega identidade"
    (let [ente-id (criar-ente-de-teste!) v (random-uuid)]
      (jdbc/with-transaction [tx (ds)]
        (repo/projetar-evento! tx
          {:tipo "voto.registrado" :ente-id ente-id
           :payload {:modalidade "secreta" :votacao-id (str (random-uuid))
                     :ocorrido-em "2026-05-18T14:00:00Z"}})
        (is (empty? (db-parlamentar/votos-do-vereador tx ente-id v 10)))))))

(deftest projeta-presenca
  (testing "presenca.registrada materializa a presenca do vereador na sessao"
    (let [ente-id (criar-ente-de-teste!) v (random-uuid) s (random-uuid)]
      (jdbc/with-transaction [tx (ds)]
        (repo/projetar-evento! tx
          {:tipo "presenca.registrada" :ente-id ente-id
           :payload {:sessao-id (str s) :vereador-id (str v) :tipo "presente"
                     :modalidade "presencial" :fonte "mesa" :ocorrido-em "2026-05-18T14:00:00Z"}})
        (is (= 1 (:sessoes-presente (db-parlamentar/resumo-presenca tx ente-id v))))))))
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.projecao-test
```
Esperado: FAIL — namespace `db.parlamentar` não existe.

- [ ] **Step 3: Migration**

`20260720000064-transparencia-parlamentar.up.sql`:

```sql
-- Onda E fatia 2: as duas projecoes que o PERFIL PUBLICO do vereador precisa. Read-model puro (§22.10):
-- alimentado SO' por evento (`voto.registrado`, `presenca.registrada`), sem FK/JOIN cross-schema; a verdade
-- continua em legislativo/sessoes.

-- ---------- voto_parlamentar: COMO O VEREADOR VOTOU, so' em votacao ABERTA/NOMINAL. ----------
-- SIGILO: `voto.registrado` e' uniao discriminada por :modalidade e o ramo 'secreta' e' um mapa :closed que
-- NEM ADMITE :vereador-id/:voto (legislativo/events/votacao.clj). Ou seja, esta tabela nao PODE receber voto
-- secreto — a garantia e' de SCHEMA DE EVENTO, nao de um `if` no consumer.
CREATE TABLE IF NOT EXISTS transparencia.voto_parlamentar (
  ente_id       uuid NOT NULL,
  votacao_id    uuid NOT NULL,                    -- ref por VALOR (sem FK cross-schema)
  vereador_id   uuid NOT NULL,
  proposicao_id uuid,                             -- a materia votada (link do portal); nullable p/ robustez
  voto          text NOT NULL,                    -- sim|nao|abstencao (vocabulario de legislativo/logic)
  ocorrido_em   timestamptz NOT NULL,             -- instante no DOMINIO (do evento), nao o de projecao
  projetado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, votacao_id, vereador_id)  -- um voto por (votacao, vereador): ON CONFLICT DO NOTHING
);
--;;
-- hot-path "como votou" do perfil: por vereador, mais recentes primeiro.
CREATE INDEX IF NOT EXISTS idx_voto_parlamentar_vereador
  ON transparencia.voto_parlamentar (ente_id, vereador_id, ocorrido_em DESC);
--;;
ALTER TABLE transparencia.voto_parlamentar ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.voto_parlamentar FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.voto_parlamentar;
--;;
CREATE POLICY tenant_isolation ON transparencia.voto_parlamentar
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT ON transparencia.voto_parlamentar TO oplenario_app;
--;;
-- ---------- presenca_parlamentar: registro OFICIAL de presenca por (sessao, vereador). ----------
-- O evento e' um LOG de entrada/saida (`tipo`); aqui guarda-se o ESTADO ATUAL por sessao (UPSERT), que e' o
-- que a vista publica precisa. Denominador do "% de presenca" sai da PROPRIA tabela (COUNT DISTINCT sessao_id
-- do ente) — sem projetar sessoes, e honesto: so' entram sessoes que TIVERAM chamada.
CREATE TABLE IF NOT EXISTS transparencia.presenca_parlamentar (
  ente_id       uuid NOT NULL,
  sessao_id     uuid NOT NULL,
  vereador_id   uuid NOT NULL,
  tipo          text NOT NULL,                    -- presente|ausente|... (vocabulario de sessoes)
  modalidade    text NOT NULL,
  ocorrido_em   timestamptz NOT NULL,
  projetado_em  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id, sessao_id, vereador_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_presenca_parlamentar_vereador
  ON transparencia.presenca_parlamentar (ente_id, vereador_id);
--;;
ALTER TABLE transparencia.presenca_parlamentar ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE transparencia.presenca_parlamentar FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON transparencia.presenca_parlamentar;
--;;
CREATE POLICY tenant_isolation ON transparencia.presenca_parlamentar
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE ON transparencia.presenca_parlamentar TO oplenario_app;
```

`.down.sql`:

```sql
DROP TABLE IF EXISTS transparencia.presenca_parlamentar;
--;;
DROP TABLE IF EXISTS transparencia.voto_parlamentar;
```

- [ ] **Step 4: `db/parlamentar.clj`**

```clojure
(ns oplenario.transparencia.db.parlamentar
  "Persistencia das projecoes de ATUACAO PARLAMENTAR do portal (Onda E fatia 2, mig 0064) — voto PUBLICO e
  presenca. Funcoes sobre a `tx` corrente (FORCE RLS isola). ESCRITA chamada pelo consumer dentro da tx do
  relay; LEITURA pelo Repo-Component. Voto SECRETO nunca chega aqui: o payload do evento (uniao discriminada
  por :modalidade) nem carrega identidade no ramo secreto."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private teto-votos
  "Teto server-side da secao 'como votou' (anti unbounded-read; mesmo racional dos tetos de materia/comentario)."
  50)

(defn registrar-voto!
  "Projeta um voto NOMINAL. ON CONFLICT DO NOTHING: idempotente sob redrive (a chave e' de negocio, nao a
  idempotency-key do envelope)."
  [tx {:keys [ente-id votacao-id vereador-id proposicao-id voto ocorrido-em]}]
  {:pre [(some? ente-id) (some? votacao-id) (some? vereador-id) (some? voto) (some? ocorrido-em)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :transparencia.voto_parlamentar
                 :values [{:ente_id ente-id :votacao_id votacao-id :vereador_id vereador-id
                           :proposicao_id proposicao-id :voto voto :ocorrido_em ocorrido-em}]
                 :on-conflict [:ente_id :votacao_id :vereador_id]
                 :do-nothing []})))

(defn registrar-presenca!
  "Projeta o ESTADO ATUAL de presenca por (sessao, vereador). UPSERT: o evento e' log de entrada/saida, a
  vista publica quer o ultimo. `ocorrido_em` do DOMINIO decide — um evento fora de ordem no redrive nao
  sobrescreve um mais recente."
  [tx {:keys [ente-id sessao-id vereador-id tipo modalidade ocorrido-em]}]
  {:pre [(some? ente-id) (some? sessao-id) (some? vereador-id) (some? tipo) (some? ocorrido-em)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :transparencia.presenca_parlamentar
                 :values [{:ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                           :tipo tipo :modalidade modalidade :ocorrido_em ocorrido-em}]
                 :on-conflict [:ente_id :sessao_id :vereador_id]
                 :do-update-set {:tipo :excluded.tipo :modalidade :excluded.modalidade
                                 :ocorrido_em :excluded.ocorrido_em}
                 :where [:< :transparencia.presenca_parlamentar.ocorrido_em :excluded.ocorrido_em]})))

(defn votos-do-vereador
  "Secao 'como votou': votos PUBLICOS do vereador, mais recentes primeiro, com a ementa da materia (mesmo
  schema — JOIN permitido, nao e' cross-schema)."
  [tx ente-id vereador-id limite]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:v.votacao_id :v.proposicao_id :v.voto :v.ocorrido_em
                           [:m.tipo :materia_tipo] [:m.ano :materia_ano]
                           [:m.sequencial :materia_sequencial] [:m.ementa :materia_ementa]]
                  :from [[:transparencia.voto_parlamentar :v]]
                  :left-join [[:transparencia.materia :m]
                              [:and [:= :m.ente_id :v.ente_id] [:= :m.proposicao_id :v.proposicao_id]]]
                  :where [:and [:= :v.ente_id ente-id] [:= :v.vereador_id vereador-id]]
                  :order-by [[:v.ocorrido_em :desc]]
                  :limit (or limite teto-votos)}))))

(defn resumo-presenca
  "Numero-card de presenca. Denominador = sessoes do ENTE que tiveram chamada (COUNT DISTINCT sessao_id);
  numerador = as em que este vereador consta 'presente'. Devolve os DOIS numeros — a UI mostra a fracao, nunca
  um percentual sem denominador (um 100% de 1 sessao mente por omissao)."
  [tx ente-id vereador-id]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [[[:count [:distinct :sessao_id]] :sessoes_com_chamada]
                           [[:count [:distinct [:case [:and [:= :vereador_id vereador-id]
                                                            [:= :tipo "presente"]]
                                                :sessao_id :else nil]]]
                            :sessoes_presente]]
                  :from [:transparencia.presenca_parlamentar]
                  :where [:= :ente_id ente-id]}))))
```

- [ ] **Step 5: Dispatch no consumer**

Em `transparencia/components/repositorio.clj`, adicionar ao `case` de `projetar-evento!` (e o require de `db-parlamentar`):

```clojure
    ;; Onda E fatia 2. SIGILO: o ramo 'secreta' de VotoRegistradoPayload e' :closed e nao carrega
    ;; :vereador-id — `when` sobre a presenca da chave, nao sobre a string de modalidade (defesa que nao
    ;; depende do vocabulario permanecer estavel).
    "voto.registrado"
    (when-let [vid (:vereador-id payload)]
      (db-parlamentar/registrar-voto! tx
        {:ente-id ente-id
         :votacao-id (UUID/fromString (:votacao-id payload))
         :vereador-id (UUID/fromString vid)
         :proposicao-id (some-> (:proposicao-id payload) UUID/fromString)
         :voto (:voto payload)
         :ocorrido-em (Instant/parse (:ocorrido-em payload))}))

    "presenca.registrada"
    (db-parlamentar/registrar-presenca! tx
      {:ente-id ente-id
       :sessao-id (UUID/fromString (:sessao-id payload))
       :vereador-id (UUID/fromString (:vereador-id payload))
       :tipo (:tipo payload)
       :modalidade (:modalidade payload)
       :ocorrido-em (Instant/parse (:ocorrido-em payload))})
```

Nota: se `VotoRegistradoPayload` (ramo nominal) não tiver `:proposicao-id` nem `:ocorrido-em`, **leia `legislativo/events/votacao.clj` e use as chaves reais**; se `:ocorrido-em` não existir no payload, adicione-a ao evento seguindo exatamente o racional já escrito em `TransicionouPayload` (tempo de domínio, não de projeção) e ajuste o teste. Não invente chave.

- [ ] **Step 6: Registrar os tipos no bus**

Em `transparencia/diplomat/consumers.clj`, `tipos-consumidos` passa a:

```clojure
  ["proposicao.protocolada" "proposicao.transicionou" "norma.publicada" "artefato.publicacao.gerado"
   "voto.registrado" "presenca.registrada"])
```

- [ ] **Step 7: Rodar e ver passar**

```bash
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.projecao-test
```
Esperado: PASS, três testes novos.

- [ ] **Step 8: Commit**

```bash
git add apps/backend/resources/migrations/20260720000064-* apps/backend/src/oplenario/transparencia apps/backend/test
git commit -m "feat(transparencia): projecoes de voto publico e presenca do vereador"
```

---

### Task 3: Leitura composta do perfil no Repo-Component

**Files:**
- Modify: `apps/backend/src/oplenario/transparencia/db/materia.clj` (query de autoria)
- Modify: `apps/backend/src/oplenario/transparencia/components/repositorio.clj` (protocolo + record)
- Modify: `apps/backend/src/oplenario/transparencia/controllers.clj`
- Test: `apps/backend/test/oplenario/transparencia/perfil_test.clj`

**Interfaces:**
- Consumes: `db.parlamentar/votos-do-vereador`, `db.parlamentar/resumo-presenca` (Task 2); coluna `autor_id` (Task 1).
- Produces: `RepoTransparencia/perfil-parlamentar` → mapa `{:materias [...] :votos [...] :presenca {...} :normas-de-autoria n}`; `controllers/perfil-parlamentar`.

- [ ] **Step 1: Escrever o teste que falha**

```clojure
(deftest perfil-parlamentar-compoe-numa-tx
  (testing "o perfil publico junta autoria, normas, votos e presenca numa unica leitura"
    (let [ente-id (criar-ente-de-teste!) v (random-uuid)]
      (semear-materia-de-autoria! ente-id v {:ano 2026 :sequencial 42})
      (semear-voto-nominal! ente-id v "sim")
      (semear-presenca! ente-id v "presente")
      (let [p (controllers/perfil-parlamentar (repo) ente-id v)]
        (is (= 1 (count (:materias p))))
        (is (= 1 (count (:votos p))))
        (is (= 1 (get-in p [:presenca :sessoes-presente])))))))
```

(As três funções `semear-*!` são helpers locais do arquivo de teste que chamam `repo/projetar-evento!` com os payloads da Task 1/2 — escreva-as no mesmo arquivo, não em fixture compartilhada.)

- [ ] **Step 2: Rodar e ver falhar**

```bash
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.perfil-test
```
Esperado: FAIL — `perfil-parlamentar` não existe.

- [ ] **Step 3: Query de autoria em `db/materia.clj`**

```clojure
(defn listar-por-autor
  "Materias de AUTORIA de um vereador (Onda E fatia 2), mais recentes primeiro. So' materias COM o elo
  `autor_id` — o acervo protocolado antes da mig 0063 nao tem elo e nao aparece aqui (a UI diz isso)."
  [tx ente-id autor-id]
  {:pre [(some? ente-id) (some? autor-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:transparencia.materia]
                  :where [:and [:= :ente_id ente-id] [:= :autor_id autor-id]]
                  :order-by [[:ano :desc] [:sequencial :desc]]
                  :limit teto-listagem}))))

(defn contar-normas-por-autor
  "Numero-card 'viraram lei': materias deste autor que ja' tem norma publicada. JOIN same-schema (permitido)."
  [tx ente-id autor-id]
  {:pre [(some? ente-id) (some? autor-id)]}
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]]
                   :from [[:transparencia.materia :m]]
                   :join [[:transparencia.norma :n]
                          [:and [:= :n.ente_id :m.ente_id] [:= :n.proposicao_id :m.proposicao_id]]]
                   :where [:and [:= :m.ente_id ente-id] [:= :m.autor_id autor-id]]})))))
```

- [ ] **Step 4: Protocolo, record e controller**

No `defprotocol RepoTransparencia`, adicionar:

```clojure
  ;; Onda E fatia 2 — perfil PUBLICO do vereador (leitura composta numa UNICA tx, mesma disciplina de
  ;; ficha-completa-da-proposicao: as quatro leituras veem o MESMO snapshot MVCC).
  (perfil-parlamentar [this ente-id vereador-id]
    "{:materias :normas-de-autoria :votos :presenca} do vereador no read-model publico.")
```

No `defrecord RepoTransparenciaPg`:

```clojure
  (perfil-parlamentar [this ente-id vid]
    (transacao this ente-id
      (fn [tx]
        {:materias           (db-materia/listar-por-autor tx ente-id vid)
         :normas-de-autoria  (db-materia/contar-normas-por-autor tx ente-id vid)
         :votos              (db-parlamentar/votos-do-vereador tx ente-id vid nil)
         :presenca           (db-parlamentar/resumo-presenca tx ente-id vid)})))
```

Em `controllers.clj`:

```clojure
(defn perfil-parlamentar
  "Perfil PUBLICO do vereador no read-model. NAO inclui a identidade (nome/mandato/comissoes) — essa chega
  na BORDA, injetada do host sobre o Repo de cadastros (§22.10: transparencia nunca importa cadastros)."
  [repo ente-id vereador-id]
  (repositorio/perfil-parlamentar repo ente-id vereador-id))
```

- [ ] **Step 5: Rodar e ver passar**

```bash
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.perfil-test
```
Esperado: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/transparencia apps/backend/test
git commit -m "feat(transparencia): leitura composta do perfil parlamentar"
```

---

### Task 4: Rota pública `GET /portal/casa/:ente/vereadores/:vereador_id`

**Files:**
- Create: `apps/backend/src/oplenario/transparencia/wire/out/parlamentar.clj`
- Create: `apps/backend/src/oplenario/transparencia/adapters/out/parlamentar.clj`
- Modify: `apps/backend/src/oplenario/transparencia/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj` (injeção de `ficha-vereador-publica`)
- Test: `apps/backend/test/oplenario/transparencia/http_perfil_test.clj`

**Interfaces:**
- Consumes: `controllers/perfil-parlamentar` (Task 3).
- Produces: rota `:transparencia/perfil-vereador`; schema `wire/out/parlamentar/PerfilVereadorOut`; seam `ficha-vereador-publica` injetado pelo host.

- [ ] **Step 1: Escrever o teste que falha**

```clojure
(deftest perfil-publico-sem-login
  (testing "GET /portal/casa/:ente/vereadores/:id devolve 200 sem token"
    (let [r (http-get (str "/portal/casa/" ente-id "/vereadores/" vereador-id))]
      (is (= 200 (:status r)))
      (is (= "Helena Past" (get-in r [:body :nome-parlamentar])))
      (is (vector? (get-in r [:body :materias]))))))

(deftest perfil-de-vereador-inexistente-404
  (testing "vereador inexistente -> 404, nunca 200 com perfil vazio"
    (is (= 404 (:status (http-get (str "/portal/casa/" ente-id "/vereadores/" (random-uuid))))))))

(deftest perfil-nao-vaza-outro-tenant
  (testing "vereador de OUTRA Casa -> 404 (RLS + escopo do ente do path)"
    (is (= 404 (:status (http-get (str "/portal/casa/" ente-id "/vereadores/" vereador-de-outro-ente)))))))
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.http-perfil-test
```
Esperado: FAIL — rota não existe (404 em todos, inclusive o primeiro).

- [ ] **Step 3: `wire/out/parlamentar.clj`**

Schema Malli `:closed` do contrato de saída. Campos: `:vereador-id`, `:nome-parlamentar`, `:nome-civil`, `:legislatura`, `:cargo-mesa` (opcional), `:comissoes` (vetor de string), `:materias` (vetor de `{:proposicao-id :tipo :ano :sequencial :ementa :estado}`), `:normas-de-autoria` int, `:votos` (vetor de `{:votacao-id :voto :ocorrido-em :materia-rotulo :materia-ementa}`), `:presenca` `{:sessoes-presente :sessoes-com-chamada}`, `:acervo-com-elo-de-autoria-desde` string ISO (a data do deploy da mig 0063 — a UI usa para a nota de honestidade).

Espelhe a forma de `transparencia/wire/out/materia.clj` (mesmo estilo de `km/Instante`, mesma docstring de fronteira). **Nunca exponha** `ente-id`, `identidade-id`, `mandato-id`.

- [ ] **Step 4: `adapters/out/parlamentar.clj`**

`(defn ->wire [ficha perfil] ...)` — funde a ficha de `cadastros` (identidade) com o perfil de `transparencia` (números), valida contra `wire/PerfilVereadorOut` e devolve. Espelhe `cadastros/adapters/out/vereador.clj:62` (`ficha->wire`) para o lado da identidade e `transparencia/adapters/out/materia.clj` para o lado das listas.

- [ ] **Step 5: Handler e rota**

Em `transparencia/diplomat/http/in.clj`:

```clojure
(defn- perfil-vereador-handler
  "GET /portal/casa/:ente/vereadores/:vereador_id — perfil PUBLICO do vereador (Onda E fatia 2, SEM auth).
  Duas fontes fundidas na borda: a IDENTIDADE vem de `ficha-vereador-publica`, INJETADA pelo host sobre o Repo
  de cadastros (inversao de dependencia §22.10, mesmo padrao de `info-ente` — transparencia nunca importa
  cadastros); os NUMEROS vem do read-model proprio. Vereador inexistente (ou de outra Casa) -> 404 ANTES de
  qualquer leitura de perfil: nunca devolver 200 com perfil vazio, que insinuaria um parlamentar sem atuacao."
  [repo-transparencia resolver-ente-publico ficha-vereador-publica]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))
          vid     (adapters-in/vereador-param->uuid (get-in req [:path-params :vereador_id]))]
      (if-let [ficha (ficha-vereador-publica ente-id vid)]
        (http/json-resposta 200
          (adapters-out-parlamentar/->wire ficha (controllers/perfil-parlamentar repo-transparencia ente-id vid)))
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))
```

Na função `rotas`, adicionar `ficha-vereador-publica` ao destructuring e a rota:

```clojure
    ["/portal/casa/:ente/vereadores/:vereador_id" :get
     [(perfil-vereador-handler repo-transparencia resolver-ente-publico ficha-vereador-publica)]
     :route-name :transparencia/perfil-vereador]
```

Adicionar `vereador-param->uuid` em `transparencia/adapters/in/portal.clj`, espelhando `proposicao-param->uuid` (coerção fail-closed → 400).

- [ ] **Step 6: Fiação no host**

Em `apps/backend/src/oplenario/rotas.clj`, onde `info-ente` já é montado sobre o Repo de `cadastros`, montar o irmão:

```clojure
   :ficha-vereador-publica
   (fn [ente-id vereador-id]
     (repo-cadastros/ficha-vereador repo-cadastros ente-id vereador-id (java.time.LocalDate/now)))
```

Use exatamente a mesma forma que a montagem de `info-ente` já usa nesse arquivo (leia-a antes; não copie esta linha às cegas — a aridade de `ficha-vereador` é `[this ente-id id data]`).

- [ ] **Step 7: Rodar e ver passar**

```bash
docker compose run --rm -T app clojure -M:test -v oplenario.transparencia.http-perfil-test
```
Esperado: PASS, três testes.

- [ ] **Step 8: Suíte inteira + lint**

```bash
docker compose run --rm -T app clojure -M:test
docker compose run --rm -T app clojure -M:lint
```
Esperado: 0 falhas; lint no baseline (0 errors / 30 warnings).

- [ ] **Step 9: Commit**

```bash
git add apps/backend/src/oplenario apps/backend/test
git commit -m "feat(transparencia): rota publica do perfil do vereador"
```

---

### Task 5: Contrato TS, vista e hook

**Files:**
- Modify: `apps/frontend/src/lib/contrato-portal.gen.ts` (regenerado)
- Create: `apps/frontend/src/lib/perfil-vereador-vista.ts` + `.test.ts`
- Create: `apps/frontend/src/lib/use-perfil-vereador.ts` + `.test.ts`

**Interfaces:**
- Consumes: `GET /portal/casa/:ente/vereadores/:id` (Task 4).
- Produces: `montarPerfilVereador(dto): PerfilVereadorVista`; `usePerfilVereador(ente, id)`.

- [ ] **Step 1: Regenerar o contrato**

Rode o codegen Malli→TS do repo (o mesmo comando usado nas ondas anteriores — localize-o em `apps/backend/deps.edn` ou `docs/13-plano-track-fe.md`). Confirme que `contrato-portal.gen.ts` ganhou `PerfilVereadorOut`.

- [ ] **Step 2: Escrever os testes de vista que falham**

```ts
describe("montarPerfilVereador", () => {
  it("monta a fracao de presenca sem inventar percentual quando nao ha chamada", () => {
    const v = montarPerfilVereador({ ...base, presenca: { sessoesPresente: 0, sessoesComChamada: 0 } });
    expect(v.presenca.rotulo).toBe("sem registro");
    expect(v.presenca.percentual).toBeNull();
  });

  it("calcula o percentual e mantem o denominador visivel", () => {
    const v = montarPerfilVereador({ ...base, presenca: { sessoesPresente: 24, sessoesComChamada: 25 } });
    expect(v.presenca.percentual).toBe(96);
    expect(v.presenca.rotulo).toBe("24 de 25 sessões");
  });

  it("rotula o voto em linguagem cidada, nao no vocabulario do banco", () => {
    const v = montarPerfilVereador({ ...base, votos: [{ ...voto, voto: "abstencao" }] });
    expect(v.votos[0].rotulo).toBe("Absteve-se");
  });

  it("sinaliza acervo incompleto quando nao ha materia com elo de autoria", () => {
    const v = montarPerfilVereador({ ...base, materias: [] });
    expect(v.avisoAcervoIncompleto).toBe(true);
  });
});
```

- [ ] **Step 3: Rodar e ver falhar**

```bash
docker exec -i oplenario-frontend-1 npx vitest run src/lib/perfil-vereador-vista.test.ts
```
Esperado: FAIL — módulo não existe.

- [ ] **Step 4: Implementar a vista**

`perfil-vereador-vista.ts` — função pura, sem JSX. Mapeia `sim|nao|abstencao` → `A favor|Contra|Absteve-se` (o vocabulário do design), monta o rótulo de matéria (`PL 042/2026`), calcula o percentual **só** quando `sessoesComChamada > 0`, e liga `avisoAcervoIncompleto` quando a lista de autoria está vazia.

- [ ] **Step 5: Rodar e ver passar**

```bash
docker exec -i oplenario-frontend-1 npx vitest run src/lib/perfil-vereador-vista.test.ts
```
Esperado: PASS.

- [ ] **Step 6: Hook**

`use-perfil-vereador.ts`, espelhando `use-ficha-materia.ts` (mesmo `api-fetch`, mesmo shape de `{dados, carregando, erro}`), com teste espelhando `use-ficha-materia.test.ts` — incluindo o caso 404 → estado de erro, não crash.

- [ ] **Step 7: Rodar os dois e commitar**

```bash
docker exec -i oplenario-frontend-1 npx vitest run src/lib/perfil-vereador-vista.test.ts src/lib/use-perfil-vereador.test.ts
git add apps/frontend/src/lib
git commit -m "feat(fe): vista e hook do perfil publico do vereador"
```

---

### Task 6: A tela

**Files:**
- Create: `apps/frontend/src/app/(publico)/portal/casa/[ente]/vereadores/[vereadorId]/page.tsx`
- Create: `apps/frontend/src/app/(publico)/portal/casa/[ente]/vereadores/[vereadorId]/perfil-vereador.css`
- Create: `.../page.test.tsx`
- Reference: `produto/design-system/o-plenario/telas/perfil-vereador-publico.html`

**Interfaces:**
- Consumes: `usePerfilVereador`, `montarPerfilVereador` (Task 5).
- Produces: rota Next `/portal/casa/[ente]/vereadores/[vereadorId]`.

- [ ] **Step 1: Escrever os testes de página que falham**

```tsx
it("mostra o nome e o cargo na Mesa no cabecalho", async () => { /* ... */ });
it("lista as materias de autoria com o rotulo publico", async () => { /* ... */ });
it("mostra a nota de sigilo abaixo da secao de votos", async () => {
  render(<PaginaPerfilVereador {...props} />);
  expect(await screen.findByText(/Apenas votações abertas e nominais são individualizadas/)).toBeInTheDocument();
});
it("avisa quando o acervo de autoria e' incompleto em vez de mostrar zero seco", async () => { /* ... */ });
it("nao renderiza secao de agenda (fora do escopo desta fatia)", async () => {
  render(<PaginaPerfilVereador {...props} />);
  expect(screen.queryByText(/Agenda pública/)).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
docker exec -i oplenario-frontend-1 npx vitest run "src/app/(publico)/portal/casa/[ente]/vereadores"
```
Esperado: FAIL — página não existe.

- [ ] **Step 3: Implementar a página e o CSS**

Portar o design 1:1 nas seções mantidas: cabeçalho com cinta de azulejo, foto/iniciais, tags (partido/bloco/cargo na Mesa/comissões), quatro `num-card`, "Matérias de autoria", "Como votou" com os `vchip`, nota de sigilo, "Contato institucional". **Cortar** "Agenda pública". Reusar `barra-institucional.tsx` e `rodape-institucional.tsx` já existentes em `(publico)/` em vez de recriar o topo do mock.

Atenção ao `GUIDELINES-CHECKLIST.md §5.1`: branco-sobre-telha → `--telha-fundo`; âmbar → `--aviso-texto`. O CSS do mock já usa as variáveis do sistema — copie-as, não invente cor.

- [ ] **Step 4: Rodar e ver passar**

```bash
docker exec -i oplenario-frontend-1 npx vitest run "src/app/(publico)/portal/casa/[ente]/vereadores"
```
Esperado: PASS.

- [ ] **Step 5: Link de entrada**

Adicione o link para o perfil onde a Casa já lista pessoas no portal público. Se hoje **não existe** nenhuma listagem pública de vereadores, **não invente uma rota nova** — registre como carry no relatório final e deixe a página acessível por URL direta. Não estenda o escopo da fatia sem decisão.

- [ ] **Step 6: tsc + eslint + suíte inteira do FE**

```bash
docker exec -i oplenario-frontend-1 npx tsc --noEmit
docker exec -i oplenario-frontend-1 npx eslint src
docker exec -i oplenario-frontend-1 npx vitest run
```
Esperado: 0 erros; suíte no baseline (795+ passando, 0 falhas).

- [ ] **Step 7: Commit**

```bash
git add "apps/frontend/src/app/(publico)/portal/casa/[ente]/vereadores"
git commit -m "feat(fe): tela do perfil publico do vereador"
```

---

### Task 7: Verificação ao vivo e revisão

Teste verde não é prova de que a tela existe. Esta fatia só fecha vista no browser, com dado real.

**Files:** nenhum arquivo de produção; possíveis correções pontuais.

- [ ] **Step 1: Semear e subir**

```bash
cd /Users/daoudatraore/oplenario/apps/backend && docker compose up -d --build
# a suite trunca shared.sequencial de TODOS os entes — o seed precisa rodar DE NOVO depois dela
docker compose run --rm -T app clojure -M -m oplenario.seed-demo/base
```

Se o Turbopack servir snapshot truncado (`Expected '</', got '<eof>'` com arquivo íntegro em disco): `docker restart oplenario-frontend-1`.

- [ ] **Step 2: Provar no browser, nos dois temas**

Abrir `/portal/casa/<ente>/vereadores/<vereador>` **sem sessão autenticada** (janela anônima — a página é pública; se pedir login, é bug de fiação). Conferir: números batem com o seed; matéria de autoria aparece; voto aparece com o rótulo certo; nota de sigilo presente. Repetir em claro e escuro.

- [ ] **Step 3: Medir contraste AA em pixel composto**

Um tema por chamada, com flush entre eles (memória `independent-accessibility-verification`: nunca anotar razão de contraste de memória — medir). Alvo: AA em texto e componentes.

- [ ] **Step 4: Revisão `ecc`**

Despachar em paralelo: `ecc:clojure-reviewer`, `ecc:database-reviewer`, `ecc:security-reviewer`, `ecc:react-reviewer` sobre o diff da branch. O security-reviewer recebe uma pergunta explícita: **"existe algum caminho pelo qual voto secreto chegue a `transparencia.voto_parlamentar` ou à resposta HTTP pública?"**

- [ ] **Step 5: Aplicar achados e commitar**

Um commit por achado aplicado, mensagem nomeando o achado. Achado recusado vira linha no relatório final com o motivo — nunca silêncio.

- [ ] **Step 6: Relatório final**

Reportar: contagem de testes backend/frontend, estado do lint, achados aplicados vs. recusados, carries novos, e o que ficou **fora** da fatia (agenda pública, listagem pública de vereadores se não existir, acervo de autoria pré-mig-0063).

---

## Self-Review

**Cobertura:** cabeçalho institucional (T4 identidade + T6) · números (T3 agregados + T6) · matérias de autoria (T1 elo + T3 query) · como votou (T2 projeção + T3 + T6) · nota de sigilo (T6) · contato institucional (T6, estático). Fora de escopo por decisão registrada: agenda pública.

**Riscos conhecidos, endereçados no plano:**
- Payload de `voto.registrado` pode não ter `:proposicao-id`/`:ocorrido-em` — Task 2 Step 5 manda ler o schema real antes de assumir.
- `ficha-vereador` tem aridade 4 (`[this ente-id id data]`) — Task 4 Step 6 avisa.
- A projeção não tem replay: acervo pré-0063 fica sem elo — tratado como aviso de UI, não escondido.
