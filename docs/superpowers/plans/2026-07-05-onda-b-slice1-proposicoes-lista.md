# Onda B · Slice 1 · Proposições (lista) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dar ao servidor uma lista real, filtrável, buscável, ordenável e paginada das proposições do seu ente — a primeira vertical de leitura do fluxo diário do servidor (Marco MFE-2), só leitura.

**Architecture:** Nova borda HTTP de leitura em `legislativo` (silhueta ADR-0001: `wire/out` → `adapters/in`/`adapters/out` → `controllers` → `diplomat/http/in`) consultando diretamente `legislativo.proposicoes` (fonte da verdade, sem migration nova). Frontend: nova página `(interno)/proposicoes` no App Shell existente, reaproveitando o view-model de tramitação (`derivarTramitacao`) já construído na Onda A2.

**Tech Stack:** Clojure/Pedestal/HoneySQL/next.jdbc/Malli (backend); Next.js 16/React 19/TypeScript/vitest (frontend).

## Global Constraints

- **Espec aprovada:** `docs/superpowers/specs/2026-07-05-onda-b-slice1-proposicoes-lista-design.md` — qualquer dúvida de escopo, essa é a autoridade.
- **Só leitura nesta fatia.** Nenhuma rota de escrita (protocolar/arquivar/distribuir/exportar). Ações de escrita da tela-fonte ficam desabilitadas/`EmBreve` no FE.
- **Papel de autorização: `"secretario"`** — mesmo gate grosso usado por TODAS as leituras internas hoje (`/paineis/mesa`, `/paineis/tramitacao`, `/paineis/pendencias`, `/paineis/sli/sessoes`). Não inventar papel novo.
- **Sem migration nova.** `legislativo.proposicoes` já existe, já indexada por `(ente_id, estado)`.
- **Query nova direto em `legislativo.proposicoes`** (fonte da verdade) — não reaproveita `paineis.tramitacao` (read-model de outro módulo, decisão do brainstorm 05/07/2026).
- **Wire kebab-case.** `jsonista` serializa keywords Clojure verbatim (kebab); o FE cameliza no boundary (`camelizarChaves`, `src/lib/boundary.ts`) — nunca inventar outro formato.
- **`ORDER BY` nunca por string crua do usuário** — allowlist validado na borda (`adapters/in`) E lookup seguro no `db` (defesa em profundidade).
- **Reaproveitar, não reinventar:** `derivarTramitacao`/`EstagioTramitacao`/`descreverFaixa` (`src/lib/tramitacao-vista.ts`) para o chip de situação e o mini-azulejo — já existem e são testados.
- **TDD red→green em toda task de código** — escrever o teste, rodar e ver falhar, implementar o mínimo, rodar e ver passar, commitar.
- **Reviews `ecc`** (clojure+database no backend; react+security no frontend) antes do merge final — tasks dedicadas no fim de cada metade do plano.

---

## Task 1: Branch da fatia

**Files:** nenhum arquivo de código — só controle de versão.

- [ ] **Step 1: Confirmar árvore de trabalho limpa**

Run: `git -C /Users/daoudatraore/oplenario status`
Expected: `nothing to commit, working tree clean` (branch `main`, sem alterações pendentes).

- [ ] **Step 2: Criar e mudar para a branch da fatia**

Run:
```bash
git -C /Users/daoudatraore/oplenario checkout -b fe-7-proposicoes-lista
```
Expected: `Switched to a new branch 'fe-7-proposicoes-lista'`.

---

## Task 2: Backend — `db/proposicao.clj` (listar + contar)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/db/proposicao.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/proposicao_listagem_db_test.clj` (novo)

**Interfaces:**
- Produces: `oplenario.legislativo.db.proposicao/listar` — `(tx ente-id filtro) -> [linha-kebab...]`, onde `filtro` é `{:busca :tipo :estado :autor-id :ano :pagina :tamanho :ordenar-por :ordenar-dir}` (todas as chaves de filtro-de-conteúdo opcionais/nil; `:pagina`/`:tamanho` inteiros positivos; `:ordenar-por` string em `#{"atualizado_em" "sequencial" "ano"}`; `:ordenar-dir` string em `#{"asc" "desc"}`).
- Produces: `oplenario.legislativo.db.proposicao/contar` — `(tx ente-id filtro) -> int` (mesmo filtro de conteúdo, ignora paginação/ordenação).

- [ ] **Step 1: Escrever o teste de integração (falhando)**

Criar `apps/backend/test/integration/oplenario/legislativo/proposicao_listagem_db_test.clj`:

```clojure
(ns oplenario.legislativo.proposicao-listagem-db-test
  "INTEGRACAO (PG real): Onda B Slice 1 — a nova query de leitura filtravel/paginada/ordenavel sobre
  `legislativo.proposicoes` (fonte da verdade, NAO read-model). Prova filtros combinaveis, paginacao
  offset/limit com desempate estavel, ordenacao pelo allowlist e isolamento por ente_id (RLS)."
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

(def ^:private filtro-base
  {:busca nil :tipo nil :estado nil :autor-id nil :ano nil
   :pagina 1 :tamanho 20 :ordenar-por "atualizado_em" :ordenar-dir "desc"})

(defn- protocolar!
  [tx ente & {:keys [tipo ano ementa autor-id autor-texto]
              :or {tipo "projeto_lei" ano 2026 ementa "Dispoe sobre X"}}]
  (:id (prop/protocolar! tx (cond-> {:id (random-uuid) :ente-id ente :tipo tipo :ano ano
                                     :uf "CE" :municipio-nome "Fortaleza" :ementa ementa}
                              autor-id     (assoc :autor-id autor-id :autor-tipo "vereador")
                              autor-texto  (assoc :autor-texto autor-texto)))))

(deftest lista-so-do-proprio-ente
  (let [e1 (random-uuid) e2 (random-uuid)]
    (tenancy/com-tenant* *ds* e1 (fn [tx] (protocolar! tx e1)))
    (tenancy/com-tenant* *ds* e2 (fn [tx] (protocolar! tx e2)))
    (tenancy/com-tenant* *ds* e1
      (fn [tx]
        (is (= 1 (count (prop/listar tx e1 filtro-base))) "so' enxerga a proposicao do proprio ente (RLS)")
        (is (= 1 (prop/contar tx e1 filtro-base)))))))

(deftest filtro-por-tipo-estado-ano-autor-combinaveis
  (let [ente (random-uuid) autor (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente :tipo "projeto_lei" :ano 2026 :ementa "Hortas comunitarias" :autor-id autor :autor-texto "Helena Matos")
        (protocolar! tx ente :tipo "requerimento" :ano 2026 :ementa "Informacoes sobre iluminacao")
        (protocolar! tx ente :tipo "projeto_lei" :ano 2025 :ementa "Outra materia de 2025")
        (is (= 2 (prop/contar tx ente (assoc filtro-base :tipo "projeto_lei"))) "filtro por tipo")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :tipo "projeto_lei" :ano 2026))) "tipo+ano combinados")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :autor-id autor))) "filtro por autor-id")
        (is (= 1 (count (prop/listar tx ente (assoc filtro-base :tipo "requerimento")))))))))

(deftest busca-textual-ilike-case-insensitive-em-ementa-e-urn
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente :ementa "Cria o Programa Municipal de Hortas Comunitarias")
        (protocolar! tx ente :ementa "Dispoe sobre acessibilidade")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :busca "hortas"))) "case-insensitive, substring")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :busca "HORTAS"))))
        (is (= 0 (prop/contar tx ente (assoc filtro-base :busca "inexistente"))))))))

(deftest paginacao-offset-limit-com-desempate-estavel
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (dotimes [n 5] (protocolar! tx ente :ementa (str "Materia " n)))
        (let [total (prop/contar tx ente filtro-base)
              pagina1 (prop/listar tx ente (assoc filtro-base :pagina 1 :tamanho 2))
              pagina2 (prop/listar tx ente (assoc filtro-base :pagina 2 :tamanho 2))
              pagina3 (prop/listar tx ente (assoc filtro-base :pagina 3 :tamanho 2))]
          (is (= 5 total))
          (is (= 2 (count pagina1))) (is (= 2 (count pagina2))) (is (= 1 (count pagina3)))
          (is (empty? (clojure.set/intersection (set (map :id pagina1)) (set (map :id pagina2))))
              "paginas nao se sobrepoem")
          (is (= 5 (count (distinct (map :id (concat pagina1 pagina2 pagina3)))))
              "as 3 paginas cobrem as 5 linhas sem duplicar/pular"))))))

(deftest pagina-alem-do-total-devolve-vazio
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente)
        (is (= [] (prop/listar tx ente (assoc filtro-base :pagina 99 :tamanho 20))))))))

(deftest ordenacao-por-ano-allowlist
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente :ano 2024 :ementa "Mais antiga")
        (protocolar! tx ente :ano 2026 :ementa "Mais nova")
        (let [asc (prop/listar tx ente (assoc filtro-base :ordenar-por "ano" :ordenar-dir "asc"))]
          (is (= [2024 2026] (mapv :ano asc))))
        (let [desc (prop/listar tx ente (assoc filtro-base :ordenar-por "ano" :ordenar-dir "desc"))]
          (is (= [2026 2024] (mapv :ano desc))))))))
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-listagem-db-test`
Expected: FAIL — `listar`/`contar` ainda não existem em `oplenario.legislativo.db.proposicao` (erro de resolução de símbolo).

- [ ] **Step 3: Implementar `listar` + `contar`**

Editar `apps/backend/src/oplenario/legislativo/db/proposicao.clj` — inserir estas definições logo APÓS `listar-por-estado` (linha 55) e ANTES de `mudar-estado!` (linha 57):

```clojure
;; ---------- Onda B Slice 1: lista filtravel/ordenavel/paginada do servidor ----------

(def ^:private colunas-ordenacao
  "Allowlist string(querystring) -> coluna HoneySQL (defesa-em-profundidade: adapters/in ja' rejeitou
  qualquer string fora deste vocabulario -> 400; aqui NUNCA se interpola a string do usuario direto no SQL,
  so' se faz o lookup seguro — default 'atualizado_em' se a chave nao bater por algum motivo)."
  {"atualizado_em" :atualizado_em "sequencial" :sequencial "ano" :ano})

(defn- where-listagem
  [ente-id {:keys [busca tipo estado autor-id ano]}]
  (cond-> [[:= :ente_id ente-id]]
    tipo     (conj [:= :tipo tipo])
    estado   (conj [:= :estado estado])
    autor-id (conj [:= :autor_id autor-id])
    ano      (conj [:= :ano ano])
    busca    (conj [:or [:ilike :ementa (str "%" busca "%")] [:ilike :urn_lex (str "%" busca "%")]])))

(defn listar
  "Onda B Slice 1 — lista filtravel/ordenavel/paginada do servidor (fonte da verdade, NAO read-model
  assincrono). Filtro OPCIONAL e combinavel (chave ausente/nil nao filtra); `busca` e' ILIKE substring
  case-insensitive em ementa+urn_lex (sem indice novo — volume por-tenant limitado, hash-particionado; vira
  carry de indice trigram se aparecer lentidao real). Desempate ESTAVEL sempre por :id (mesma disciplina de
  transparencia/db/norma/listar — paginacao sem desempate fixo pode duplicar/pular linha entre paginas em
  empate de `ordenar-por`)."
  [tx ente-id {:keys [pagina tamanho ordenar-por ordenar-dir] :as filtro}]
  {:pre [(some? ente-id) (pos-int? pagina) (pos-int? tamanho)]}
  (let [col (get colunas-ordenacao ordenar-por :atualizado_em)
        dir (if (= "asc" ordenar-dir) :asc :desc)]
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format {:select colunas :from [:legislativo.proposicoes]
                    :where (into [:and] (where-listagem ente-id filtro))
                    :order-by [[col dir] [:id :asc]]
                    :limit tamanho
                    :offset (* (dec pagina) tamanho)})))))

(defn contar
  "Total de linhas do MESMO filtro de conteudo de `listar` (ignora pagina/tamanho/ordenacao — so' a
  paginacao do wire/out precisa do total)."
  [tx ente-id filtro]
  {:pre [(some? ente-id)]}
  (:total
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :total]] :from [:legislativo.proposicoes]
                   :where (into [:and] (where-listagem ente-id filtro))})))))
```

- [ ] **Step 4: Rodar o teste e ver passar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-listagem-db-test`
Expected: PASS — todos os `deftest` verdes.

- [ ] **Step 5: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/backend/src/oplenario/legislativo/db/proposicao.clj apps/backend/test/integration/oplenario/legislativo/proposicao_listagem_db_test.clj
git commit -m "feat(be): query filtravel/paginada de proposicoes (legislativo.db.proposicao/listar+contar)"
```

---

## Task 3: Backend — `wire/out` + `adapters/out` de proposição

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/wire/out/proposicao.clj`
- Modify: `apps/backend/src/oplenario/legislativo/adapters/out/proposicao.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_out_test.clj` (novo)

**Interfaces:**
- Consumes: linhas kebab de `db/proposicao.clj:listar` (`{:id :tipo :ano :sequencial :urn-lex :ementa :autor-tipo :autor-texto :estado :atualizado-em ...}`, mais colunas não usadas nesta projeção).
- Produces: `oplenario.legislativo.adapters.out.proposicao/listar->wire` — `({:itens [...] :total :pagina :tamanho-pagina}) -> ListaProposicoesOut` (mapa validado contra o wire/out).

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_out_test.clj`:

```clojure
(ns oplenario.legislativo.proposicao-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out da lista de proposicoes (Onda B Slice 1). Prova a forma
  (so' os campos de ProposicaoResumoOut, uuid/instant como string) e a validacao de contrato."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.proposicao :as adapters]
            [oplenario.legislativo.wire.out.proposicao :as wire]))

(defn- linha-canonica [ente]
  {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 42
   :urn-lex "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42"
   :ementa "Cria o Programa Municipal de Hortas Comunitarias"
   :autor-tipo "vereador" :autor-texto "Helena Matos" :estado "em_comissoes"
   :atualizado-em (java.time.Instant/parse "2026-05-21T10:00:00Z")
   :atributos-especificos nil :texto-vigente-versao-id nil})

(deftest projeta-lista-com-total-pagina-tamanho
  (let [ente (random-uuid)
        out (adapters/listar->wire {:itens [(linha-canonica ente)] :total 1284 :pagina 1 :tamanho-pagina 20})]
    (is (m/validate wire/ListaProposicoesOut out) "a projecao satisfaz ListaProposicoesOut")
    (is (= 1284 (:total out))) (is (= 1 (:pagina out))) (is (= 20 (:tamanho-pagina out)))
    (let [item (first (:itens out))]
      (is (string? (:id item)) "id vira string")
      (is (= "projeto_lei" (:tipo item)))
      (is (= "em_comissoes" (:estado item)))
      (is (string? (:atualizado-em item)) "instant vira string")
      (is (not (contains? item :ente-id)) "ente-id (tenant) nao vaza")
      (is (not (contains? item :atributos-especificos)) "atributos_especificos nao vaza")
      (is (not (contains? item :texto-vigente-versao-id)) "texto-vigente-versao-id nao vaza"))))

(deftest lista-vazia-projeta-itens-vazio
  (let [out (adapters/listar->wire {:itens [] :total 0 :pagina 1 :tamanho-pagina 20})]
    (is (m/validate wire/ListaProposicoesOut out))
    (is (= [] (:itens out)))))

(deftest autor-nulo-fica-nil-no-wire
  (let [ente (random-uuid)
        linha (assoc (linha-canonica ente) :autor-tipo nil :autor-texto nil)
        out (adapters/listar->wire {:itens [linha] :total 1 :pagina 1 :tamanho-pagina 20})]
    (is (m/validate wire/ListaProposicoesOut out))
    (is (nil? (:autor-tipo (first (:itens out)))))))
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-adapters-out-test`
Expected: FAIL — `wire/ListaProposicoesOut` e `adapters/listar->wire` ainda não existem.

- [ ] **Step 3: Implementar o wire/out**

Escrever `apps/backend/src/oplenario/legislativo/wire/out/proposicao.clj` (substitui o stub inteiro):

```clojure
(ns oplenario.legislativo.wire.out.proposicao
  "Representacao EXTERNA de SAIDA da leitura de proposicoes (§22.10 wire/out, ADR-0001, Onda B Slice 1) — o
  contrato de GET /legislativo/proposicoes, do qual o Eixo 8 gera os tipos TS. `estado` fica :string (nao
  enum fechado): a maquina fina de tramitacao e' TEMPLATE-DRIVEN por camara (F3.3) — mesmo racional de
  paineis/wire/out/tramitacao e de tramitacao-vista.ts no FE."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def ProposicaoResumoOut
  "Uma linha da lista (nao a proposicao inteira — sem atributos_especificos/texto_vigente_versao_id/
  lock_version/created_by/updated_by)."
  [:map {:closed true}
   [:id :string]
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   [:atualizado-em :string]])

(def ListaProposicoesOut
  [:map {:closed true}
   [:itens [:vector ProposicaoResumoOut]]
   [:total :int]
   [:pagina :int]
   [:tamanho-pagina :int]])
```

- [ ] **Step 4: Implementar o adapters/out**

Escrever `apps/backend/src/oplenario/legislativo/adapters/out/proposicao.clj` (substitui o stub inteiro):

```clojure
(ns oplenario.legislativo.adapters.out.proposicao
  "Gate de SAIDA `models -> wire/out` da leitura de proposicoes (§22.10 adapters/out, ADR-0001, Onda B Slice
  1). Projeta cada linha (kebab, uuid/instant) para ProposicaoResumoOut — nunca vaza
  atributos_especificos/texto_vigente_versao_id/lock_version/created_by/updated_by/ente_id. Validado contra
  o contrato (drift de campo = bug de servidor -> 500, nunca resposta malformada que envenena o codegen)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.proposicao :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn- resumo->wire [linha]
  (validado wire/ProposicaoResumoOut
            {:id (->str (:id linha)) :tipo (:tipo linha) :ano (:ano linha) :sequencial (:sequencial linha)
             :urn-lex (:urn-lex linha) :ementa (:ementa linha) :autor-tipo (:autor-tipo linha)
             :autor-texto (:autor-texto linha) :estado (:estado linha)
             :atualizado-em (->str (:atualizado-em linha))}
            "item de proposicao"))

(defn listar->wire
  "{:itens [...] :total :pagina :tamanho-pagina} (dominio) -> ListaProposicoesOut."
  [{:keys [itens total pagina tamanho-pagina]}]
  (validado wire/ListaProposicoesOut
            {:itens (mapv resumo->wire itens) :total total :pagina pagina :tamanho-pagina tamanho-pagina}
            "lista de proposicoes"))
```

- [ ] **Step 5: Rodar o teste e ver passar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-adapters-out-test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/backend/src/oplenario/legislativo/wire/out/proposicao.clj apps/backend/src/oplenario/legislativo/adapters/out/proposicao.clj apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_out_test.clj
git commit -m "feat(be): contrato de saida ListaProposicoesOut + adapters/out (Onda B Slice 1)"
```

---

## Task 4: Backend — `adapters/in` de proposição (coerção de query-params)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/adapters/in/proposicao.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_in_test.clj` (novo)

**Interfaces:**
- Consumes: `query-params` do Pedestal — mapa `keyword -> String | [String...]` (repetido na URL vira vetor).
- Produces: `oplenario.legislativo.adapters.in.proposicao/listar-proposicoes->dominio` — `(query-params) -> {:busca :tipo :estado :autor-id :ano :pagina :tamanho :ordenar-por :ordenar-dir}` (consumido por `controllers/listar-proposicoes` e por `db/proposicao.clj:listar`/`contar`). Lança `ex-info` com `{:tipo :validacao/invalido}` em qualquer valor malformado (a borda HTTP traduz para 400).

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_in_test.clj`:

```clojure
(ns oplenario.legislativo.proposicao-adapters-in-test
  "UNIT (puro, sem DB) — a coercao dos query-params de GET /legislativo/proposicoes (Onda B Slice 1). Prova
  os defaults, o fail-closed em valor invalido e a tolerancia a ausencia (filtro nil, nao 400)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.adapters.in.proposicao :as adapters]))

(defn- invalido? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

(deftest sem-query-params-usa-defaults
  (let [f (adapters/listar-proposicoes->dominio {})]
    (is (nil? (:busca f))) (is (nil? (:tipo f))) (is (nil? (:estado f)))
    (is (nil? (:autor-id f))) (is (nil? (:ano f)))
    (is (= 1 (:pagina f))) (is (= 20 (:tamanho f)))
    (is (= "atualizado_em" (:ordenar-por f))) (is (= "desc" (:ordenar-dir f)))))

(deftest busca-trimada-e-blank-vira-nil
  (is (= "hortas" (:busca (adapters/listar-proposicoes->dominio {:busca "  hortas  "}))))
  (is (nil? (:busca (adapters/listar-proposicoes->dominio {:busca "   "})))))

(deftest busca-grande-demais-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:busca (apply str (repeat 201 "a"))}))))

(deftest tipo-e-estado-passam-direto
  (let [f (adapters/listar-proposicoes->dominio {:tipo "projeto_lei" :estado "em_comissoes"})]
    (is (= "projeto_lei" (:tipo f))) (is (= "em_comissoes" (:estado f)))))

(deftest autor-id-uuid-valido-coage
  (let [id (random-uuid)
        f (adapters/listar-proposicoes->dominio {:autor-id (str id)})]
    (is (= id (:autor-id f)))))

(deftest autor-id-invalido-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:autor-id "nao-e-uuid"}))))

(deftest ano-invalido-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:ano "vinte-e-vinte-seis"}))))

(deftest ano-valido-coage-inteiro
  (is (= 2026 (:ano (adapters/listar-proposicoes->dominio {:ano "2026"})))))

(deftest tamanho-fora-da-faixa-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:tamanho "0"})))
  (is (invalido? #(adapters/listar-proposicoes->dominio {:tamanho "101"})))
  (is (invalido? #(adapters/listar-proposicoes->dominio {:tamanho "abc"}))))

(deftest tamanho-no-limite-aceita
  (is (= 1 (:tamanho (adapters/listar-proposicoes->dominio {:tamanho "1"}))))
  (is (= 100 (:tamanho (adapters/listar-proposicoes->dominio {:tamanho "100"})))))

(deftest pagina-invalida-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:pagina "0"})))
  (is (invalido? #(adapters/listar-proposicoes->dominio {:pagina "-1"}))))

(deftest ordenar-por-fora-do-allowlist-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:ordenar-por "senha"}))))

(deftest ordenar-dir-fora-do-allowlist-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:ordenar-dir "lateral"}))))

(deftest parametro-repetido-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:busca ["a" "b"]}))))
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-adapters-in-test`
Expected: FAIL — `listar-proposicoes->dominio` ainda não existe.

- [ ] **Step 3: Implementar**

Escrever `apps/backend/src/oplenario/legislativo/adapters/in/proposicao.clj` (substitui o stub inteiro):

```clojure
(ns oplenario.legislativo.adapters.in.proposicao
  "Gate de ENTRADA `wire/in -> models` da leitura de proposicoes (§22.10 adapters/in, ADR-0001, Onda B Slice
  1). Chamado SO pelo diplomat/. Coage os query-params de GET /legislativo/proposicoes: cada filtro de
  CONTEUDO e' OPCIONAL e TOLERANTE ao valor (ex.: tipo/estado desconhecidos so' nao casam nenhuma linha, nao
  sao 400 — mesmo racional de transparencia/adapters/in/portal/filtro-legislacao); ja' pagina/tamanho/
  ordenacao tem DEFAULT quando AUSENTES mas REJEITAM (400) quando PRESENTES e invalidos — nunca absorvidos
  em silencio, porque mudam o contrato de paginacao que o FE depende."
  (:require [clojure.string :as str])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- ->single
  "Um query-param do Pedestal e' String (uma ocorrencia) ou VETOR (repetido na URL) — repetido e' AMBIGUO
  p/ um filtro escalar -> 400 (nunca escolhe 'primeiro/ultimo' em silencio). Ausente -> nil."
  [s campo]
  (cond (nil? s) nil (string? s) s :else (invalido! "parametro repetido" {:campo campo})))

(def ^:private texto-max 200)
(def ^:private inteiro-max-chars 11)
(def ^:private tamanho-min 1)
(def ^:private tamanho-max 100)
(def ^:private tamanho-default 20)
(def ^:private pagina-min 1)
(def ^:private pagina-max 100000)
(def ^:private pagina-default 1)
(def ^:private ordenar-por-default "atualizado_em")
(def ^:private ordenar-por-valores #{"atualizado_em" "sequencial" "ano"})
(def ^:private ordenar-dir-default "desc")
(def ^:private ordenar-dir-valores #{"asc" "desc"})

(defn- query-texto [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) texto-max) (invalido! "parametro grande demais" {:campo campo}))
      t)))

(defn- query-inteiro [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) inteiro-max-chars) (invalido! "inteiro grande demais" {:campo campo}))
      (try (Integer/parseInt t)
           (catch NumberFormatException _ (invalido! "inteiro invalido" {:campo campo}))))))

(defn- query-uuid [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (try (UUID/fromString t) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))))

(defn- query-inteiro-em-faixa [s campo minimo maximo default]
  (if-let [n (query-inteiro s campo)]
    (do (when (or (< n minimo) (> n maximo)) (invalido! "fora da faixa permitida" {:campo campo :valor n}))
        n)
    default))

(defn- query-enum [s campo valores default]
  (if-let [t (query-texto s campo)]
    (do (when-not (contains? valores t) (invalido! "valor nao permitido" {:campo campo :valor t}))
        t)
    default))

(defn listar-proposicoes->dominio
  "query-params (mapa keyword->string|vetor do Pedestal) -> filtro+paginacao de dominio p/
  controllers/listar-proposicoes e db/proposicao.clj (listar/contar)."
  [query-params]
  {:busca       (query-texto (:busca query-params) :busca)
   :tipo        (query-texto (:tipo query-params) :tipo)
   :estado      (query-texto (:estado query-params) :estado)
   :autor-id    (query-uuid (:autor-id query-params) :autor-id)
   :ano         (query-inteiro (:ano query-params) :ano)
   :pagina      (query-inteiro-em-faixa (:pagina query-params) :pagina pagina-min pagina-max pagina-default)
   :tamanho     (query-inteiro-em-faixa (:tamanho query-params) :tamanho tamanho-min tamanho-max tamanho-default)
   :ordenar-por (query-enum (:ordenar-por query-params) :ordenar-por ordenar-por-valores ordenar-por-default)
   :ordenar-dir (query-enum (:ordenar-dir query-params) :ordenar-dir ordenar-dir-valores ordenar-dir-default)})
```

- [ ] **Step 4: Rodar o teste e ver passar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-adapters-in-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/backend/src/oplenario/legislativo/adapters/in/proposicao.clj apps/backend/test/unit/oplenario/legislativo/proposicao_adapters_in_test.clj
git commit -m "feat(be): coercao fail-closed dos query-params de listagem de proposicoes"
```

---

## Task 5: Backend — `RepoLegislativo` (protocolo + impl) + `controllers.clj`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/legislativo/controllers.clj`

**Interfaces:**
- Consumes: `oplenario.legislativo.db.proposicao/listar`, `.../contar` (Task 2).
- Produces: protocolo `RepoLegislativo` ganha `listar-proposicoes`/`contar-proposicoes`; `controllers/listar-proposicoes` — `(repo-legislativo ente-id filtro) -> {:itens [...] :total :pagina :tamanho-pagina}` (consumido pelo diplomat na Task 6).

Sem teste dedicado nesta task: `listar-proposicoes`/`contar-proposicoes` do Repo são delegação pura para `transacao` (mesmo padrão não-testado-diretamente de `buscar-proposicao`/`listar-por-estado`, já cobertos pelo teste de `db/proposicao.clj` da Task 2); `controllers/listar-proposicoes` é exercido ponta-a-ponta pelo teste HTTP da Task 6 (mesma convenção da vertical de votação — sem `controllers_test.clj` próprio).

- [ ] **Step 1: Adicionar os métodos ao protocolo `RepoLegislativo`**

Editar `apps/backend/src/oplenario/legislativo/components/repositorio.clj` — logo após a linha `(listar-por-estado [this ente-id estado])` (linha 38), inserir:

```clojure
  (listar-proposicoes [this ente-id filtro] "Onda B Slice 1: leitura filtrada/paginada/ordenada do servidor.")
  (contar-proposicoes [this ente-id filtro] "Total de linhas do mesmo filtro (paginacao).")
```

- [ ] **Step 2: Implementar no `RepoLegislativoPg`**

Na mesma arquivo, logo após `(listar-por-estado [this ente-id estado] (transacao this ente-id #(proposicao/listar-por-estado % ente-id estado)))` (linha 195), inserir:

```clojure
  (listar-proposicoes [this ente-id filtro] (transacao this ente-id #(proposicao/listar % ente-id filtro)))
  (contar-proposicoes [this ente-id filtro] (transacao this ente-id #(proposicao/contar % ente-id filtro)))
```

- [ ] **Step 3: Adicionar `listar-proposicoes` ao `controllers.clj`**

Ler `apps/backend/src/oplenario/legislativo/controllers.clj` para achar o require de `oplenario.legislativo.components.repositorio :as repo` (já existe, usado por `relatores-pendentes`). Adicionar, próximo à função `relatores-pendentes`:

```clojure
(defn listar-proposicoes
  "Onda B Slice 1 — leitura tenant-wide (mesmo contrato de authz de `relatores-pendentes`: sem policy fina
  adicional, so' o gate grosso da rota — papel 'secretario'). `filtro` ja vem coagido pelo adapters/in."
  [repo-legislativo ente-id filtro]
  {:itens (repo/listar-proposicoes repo-legislativo ente-id filtro)
   :total (repo/contar-proposicoes repo-legislativo ente-id filtro)
   :pagina (:pagina filtro)
   :tamanho-pagina (:tamanho filtro)})
```

- [ ] **Step 4: Compilar e rodar a suíte inteira (sanity check — nada quebrou)**

Run: `cd apps/backend && clojure -M:test`
Expected: PASS em todos os testes existentes (nenhum efeito colateral — só adição de método/função nova).

- [ ] **Step 5: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/backend/src/oplenario/legislativo/components/repositorio.clj apps/backend/src/oplenario/legislativo/controllers.clj
git commit -m "feat(be): RepoLegislativo.listar-proposicoes + controllers/listar-proposicoes"
```

---

## Task 6: Backend — borda HTTP `GET /legislativo/proposicoes`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/proposicao_http_in_test.clj` (novo)

**Interfaces:**
- Consumes: `adapters-in-proposicao/listar-proposicoes->dominio` (Task 4), `adapters-out-proposicao/listar->wire` (Task 3), `controllers/listar-proposicoes` (Task 5).
- Produces: rota Pedestal `GET /legislativo/proposicoes` registrada no fragmento `rotas` já existente do `legislativo` (nenhuma mudança em `oplenario.rotas` — o host já injeta `repo-legislativo` neste fragmento).

- [ ] **Step 1: Escrever o teste HTTP (falhando)**

Criar `apps/backend/test/integration/oplenario/legislativo/proposicao_http_in_test.clj`:

```clojure
(ns oplenario.legislativo.proposicao-http-in-test
  "Onda B Slice 1 (borda HTTP do legislativo) — a vertical de leitura GET /legislativo/proposicoes: prova a
  silhueta de borda end-to-end (adapters/in -> controller -> repo -> adapters/out -> wire/out) + a authz
  grossa (papel 'secretario') + 401. DB-free: RepoLegislativo FAKE (reify, so' os 2 metodos exercidos) +
  idp-dev real (mesmo precedente de tramitacao-http-in-test/votacao-http-in-test)."
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

(defn- item-canonico [ente]
  {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 42
   :urn-lex "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42"
   :ementa "Cria o Programa Municipal de Hortas Comunitarias"
   :autor-tipo "vereador" :autor-texto "Helena Matos" :estado "em_comissoes"
   :atualizado-em (java.time.Instant/parse "2026-05-21T10:00:00Z")})

(defn- fake-repo-legislativo
  "RepoLegislativo fake: `listar-proposicoes`/`contar-proposicoes` devolvem `itens`/`total`. Impl parcial
  proposital (so' os metodos exercidos). `filtros-recebidos` (atom) captura o filtro que o controller
  repassou ao Repo — prova que a coercao da borda chegou intacta."
  [itens total filtros-recebidos]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (listar-proposicoes [_ _ente-id filtro] (reset! filtros-recebidos filtro) itens)
    (contar-proposicoes [_ _ente-id _filtro] total)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest listar-proposicoes-200
  (let [ente (random-uuid)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [(item-canonico ente)] 1284 (atom nil)))
                           :get "/legislativo/proposicoes" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1284 (:total body))) (is (= 1 (:pagina body))) (is (= 20 (:tamanho-pagina body)))
    (let [i (first (:itens body))]
      (is (= "projeto_lei" (:tipo i)))
      (is (string? (:id i)) "id como string")
      (is (not (contains? i :ente-id)) "ente-id (tenant) nao vaza"))))

(deftest listar-proposicoes-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [] (:itens (ler-json r))))))

(deftest listar-proposicoes-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest listar-proposicoes-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest listar-proposicoes-tamanho-invalido-400
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes?tamanho=999"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)) "tamanho fora da faixa -> 400 na borda, nunca 500")))

(deftest listar-proposicoes-ordenar-por-invalido-400
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 (atom nil)))
                           :get "/legislativo/proposicoes?ordenar-por=senha"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)) "ordenar-por fora do allowlist -> 400, nunca interpola no SQL")))

(deftest listar-proposicoes-repassa-filtro-da-query-ao-repo
  (let [ente (random-uuid) filtros (atom nil)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-legislativo [] 0 filtros))
                           :get "/legislativo/proposicoes?tipo=projeto_lei&ano=2026"
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= "projeto_lei" (:tipo @filtros)))
    (is (= 2026 (:ano @filtros)))))
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-http-in-test`
Expected: FAIL — a rota `/legislativo/proposicoes` ainda não existe (404 em vez de 200/400/etc.), e `listar-proposicoes`/`contar-proposicoes` ainda não estão no protocolo fake (na verdade já estão, da Task 5 — o que falta é a rota).

- [ ] **Step 3: Implementar o handler + a rota**

Editar `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`:

No bloco `:require`, adicionar as duas novas dependências:

```clojure
            [oplenario.legislativo.adapters.in.proposicao :as adapters-in-proposicao]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-out-proposicao]
```

Logo antes de `(defn rotas` (linha 51 do arquivo atual), adicionar o handler:

```clojure
(defn- listar-proposicoes-handler
  "GET /legislativo/proposicoes(?busca=&tipo=&estado=&autor-id=&ano=&pagina=&tamanho=&ordenar-por=&ordenar-dir=).
  Leitura tenant-wide (mesmo contrato de authz de /paineis/*, Onda B Slice 1): adapters/in coage os filtros
  (fail-closed -> 400); o controller le' do tenant do ator; adapters/out projeta+valida."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          filtro (adapters-in-proposicao/listar-proposicoes->dominio (:query-params req))]
      (http/json-resposta 200 (adapters-out-proposicao/listar->wire
                                (controllers/listar-proposicoes repo-leg ente-id filtro))))))
```

Dentro de `(defn rotas ...)`, no `let [papel (it/exige-papel "secretario")]`, adicionar uma nova entrada ao `#{...}` (mantendo as 3 já existentes da votação):

```clojure
      ["/legislativo/proposicoes" :get [auth papel (listar-proposicoes-handler repo-legislativo)]
       :route-name :legislativo/listar-proposicoes]
```

(O parâmetro `repo-legislativo` já chega desestruturado em `[{:keys [auth repo-legislativo consultar-sessao]}]` — nenhuma mudança na assinatura de `rotas` é necessária.)

- [ ] **Step 4: Rodar o teste e ver passar**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.proposicao-http-in-test`
Expected: PASS em todos os `deftest`.

- [ ] **Step 5: Rodar a suíte inteira do backend**

Run: `cd apps/backend && clojure -M:test`
Expected: PASS — nenhuma regressão nas verticais de votação/paineis/etc.

- [ ] **Step 6: Lint (clj-kondo)**

Run: `cd apps/backend && clj-kondo --lint src test`
Expected: 0 erros/warnings novos (os `#_{:clj-kondo/ignore [:missing-protocol-method]}` já suprimem o esperado nos reifies parciais).

- [ ] **Step 7: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/backend/src/oplenario/legislativo/diplomat/http/in.clj apps/backend/test/integration/oplenario/legislativo/proposicao_http_in_test.clj
git commit -m "feat(be): borda HTTP GET /legislativo/proposicoes (Onda B Slice 1)"
```

---

## Task 7: Backend — review `ecc` (clojure + database)

**Files:** nenhum arquivo novo — aplica os achados dos revisores nos arquivos das Tasks 2–6.

- [ ] **Step 1: Rodar o revisor clojure**

Invocar o agente `ecc:clojure-reviewer` (ou `Skill clojure-review`) sobre o diff da branch `fe-7-proposicoes-lista` (arquivos tocados: `db/proposicao.clj`, `wire/out/proposicao.clj`, `adapters/out/proposicao.clj`, `adapters/in/proposicao.clj`, `components/repositorio.clj`, `controllers.clj`, `diplomat/http/in.clj`, e os 4 arquivos de teste novos).

- [ ] **Step 2: Rodar o revisor database**

Invocar o agente `ecc:database-reviewer` focado em `db/proposicao.clj` (`listar`/`contar`) — atenção especial ao `WHERE`/`ORDER BY` dinâmico (nunca coluna vinda do usuário sem allowlist — já mitigado na Task 2/4, mas o revisor confirma) e ao plano de execução do `ILIKE` sem índice novo (aceitável nesta fatia, mas o revisor deve concordar ou apontar um risco concreto).

- [ ] **Step 3: Aplicar os achados CRÍTICOS/MAJOR**

Corrigir cada achado confirmado; para cada fix, rodar a suíte relevante (`clojure -M:test -n <ns-do-teste-afetado>`) antes de seguir para o próximo.

- [ ] **Step 4: Rodar a suíte inteira + lint de novo**

Run: `cd apps/backend && clojure -M:test && clj-kondo --lint src test`
Expected: PASS, 0 erros.

- [ ] **Step 5: Commit dos fixes (se houver)**

```bash
cd /Users/daoudatraore/oplenario
git add -A apps/backend
git commit -m "fix(be): incorpora review ecc (clojure+database) da listagem de proposicoes"
```

Se não houver achados CRÍTICOS/MAJOR, pular o commit (nada a registrar) e seguir para a Task 8.

---

## Task 8: Frontend — codegen `contrato-legislativo.gen.ts`

**Files:**
- Create: `apps/backend/src/oplenario/codegen/gerar_legislativo.clj`
- Create: `apps/frontend/src/lib/contrato-legislativo.gen.ts` (gerado, não editado à mão)

**Interfaces:**
- Produces: interfaces TS `ProposicaoResumoOut`/`ListaProposicoesOut` (camelCase), consumidas por `use-proposicoes.ts` (Task 10).

- [ ] **Step 1: Escrever o entrypoint de codegen**

Criar `apps/backend/src/oplenario/codegen/gerar_legislativo.clj` (espelha `gerar_portal.clj`):

```clojure
(ns oplenario.codegen.gerar-legislativo
  "Entrypoint do codegen Malli->TS da leitura interna de proposicoes (Onda B Slice 1, Eixo 8). Espelha
  oplenario.codegen.gerar-portal (mesmo racional/ferramenta, manifesto proprio) — schema-fonte e' o
  wire/out da rota GET /legislativo/proposicoes (papel 'secretario'). Roda via:
    clojure -M -m oplenario.codegen.gerar-legislativo [caminho-de-saida]
  Default = target/generated-ts/contrato-legislativo.gen.ts."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.legislativo.wire.out.proposicao :as proposicao]))

(def manifesto
  [["ProposicaoResumoOut" proposicao/ProposicaoResumoOut]
   ["ListaProposicoesOut" proposicao/ListaProposicoesOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-legislativo.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de legislativo gerados em" caminho "(" (count manifesto) "interfaces)")))
```

- [ ] **Step 2: Rodar o gerador direto para o destino no frontend**

Run:
```bash
cd apps/backend
clojure -M -m oplenario.codegen.gerar-legislativo ../frontend/src/lib/contrato-legislativo.gen.ts
```
Expected: `[oplenario] tipos TS de legislativo gerados em ../frontend/src/lib/contrato-legislativo.gen.ts ( 2 interfaces)`, e o arquivo `apps/frontend/src/lib/contrato-legislativo.gen.ts` existe, começando com o comentário `// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.` e contendo `export interface ProposicaoResumoOut { ... }` (campos camelCase: `id`, `tipo`, `ano`, `sequencial`, `urnLex`, `ementa`, `autorTipo?`, `autorTexto?`, `estado`, `atualizadoEm`) e `export interface ListaProposicoesOut { itens: ProposicaoResumoOut[]; total: number; pagina: number; tamanhoPagina: number; }`.

- [ ] **Step 3: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/backend/src/oplenario/codegen/gerar_legislativo.clj apps/frontend/src/lib/contrato-legislativo.gen.ts
git commit -m "feat(codegen): gera contrato-legislativo.gen.ts (ProposicaoResumoOut/ListaProposicoesOut)"
```

---

## Task 9: Frontend — `AzulejoMini` (variante compacta do AzulejoFaixa)

**Files:**
- Create: `apps/frontend/src/lib/charts/azulejo-mini.tsx`
- Test: `apps/frontend/src/lib/charts/azulejo-mini.test.tsx`

**Interfaces:**
- Consumes: `EstagioTramitacao[]` (`apps/frontend/src/lib/tramitacao-vista.ts`, já existe).
- Produces: `AzulejoMini({ estagios, rotuloAria }) -> JSX` — SVG compacto (blocos pequenos, sem rótulo textual por estágio), `role="img"` + `aria-label`.

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/frontend/src/lib/charts/azulejo-mini.test.tsx`:

```tsx
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AzulejoMini } from "./azulejo-mini";
import type { EstagioTramitacao } from "../tramitacao-vista";

// Onda B Slice 1 — variante compacta do AzulejoFaixa para a coluna "Situação" da tabela de proposições
// (produto/design-system/o-plenario/telas/proposicoes.html, .azulejo-mini). Mesma entrada
// (EstagioTramitacao[]) do AzulejoFaixa já testado — só troca o SVG por blocos pequenos sem rótulo textual
// por estágio (a acessibilidade vem do aria-label, não de <text> por bloco).

const estagios: EstagioTramitacao[] = [
  { rotulo: "Protocolo", situacao: "concluido" },
  { rotulo: "Comissões", situacao: "concluido" },
  { rotulo: "1º turno", situacao: "ativo" },
  { rotulo: "2º turno", situacao: "pendente" },
  { rotulo: "Sanção", situacao: "pendente" },
];

describe("AzulejoMini", () => {
  afterEach(() => cleanup());

  it("tem role=img com aria-label descritivo obrigatório", () => {
    render(<AzulejoMini estagios={estagios} rotuloAria="Tramitação: em 1º turno." />);
    expect(screen.getByRole("img", { name: /em 1º turno/ })).toBeTruthy();
  });

  it("renderiza um bloco por estágio", () => {
    const { container } = render(<AzulejoMini estagios={estagios} rotuloAria="Tramitação" />);
    expect(container.querySelectorAll(".azulejo-mini-bloco").length).toBe(5);
  });

  it("marca o estágio ativo e os pendentes com as classes correspondentes", () => {
    const { container } = render(<AzulejoMini estagios={estagios} rotuloAria="Tramitação" />);
    const blocos = container.querySelectorAll(".azulejo-mini-bloco");
    expect(blocos[2].getAttribute("class")).toContain("azulejo-mini-bloco--ativo");
    expect(blocos[3].getAttribute("class")).toContain("azulejo-mini-bloco--pendente");
    expect(blocos[0].getAttribute("class")).toContain("azulejo-mini-bloco--concluido");
  });
});
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/frontend && npx vitest run src/lib/charts/azulejo-mini.test.tsx`
Expected: FAIL — `./azulejo-mini` não existe.

- [ ] **Step 3: Implementar**

Criar `apps/frontend/src/lib/charts/azulejo-mini.tsx`:

```tsx
// AzulejoMini — variante compacta do AzulejoFaixa (Onda B Slice 1) para a coluna "Situação" da tabela de
// proposições (.azulejo-mini na tela-fonte, 13x13px por bloco). Mesma entrada (EstagioTramitacao[]) e mesma
// semântica visual do AzulejoFaixa (concluído = sólido; ativo = marca menor com acento; pendente =
// tracejado) — aqui SEM <text> por estágio (a tabela não tem espaço para rótulos; a acessibilidade vem do
// aria-label via descreverFaixa, chamado pelo caller).

import type { EstagioTramitacao } from "../tramitacao-vista";

const LADO = 13;
const GAP = 2;

export function AzulejoMini({
  estagios,
  rotuloAria,
}: {
  estagios: EstagioTramitacao[];
  rotuloAria: string;
}) {
  const largura = estagios.length * LADO + (estagios.length - 1) * GAP;
  return (
    <svg
      className="azulejo-mini"
      width={largura}
      height={LADO}
      viewBox={`0 0 ${largura} ${LADO}`}
      role="img"
      aria-label={rotuloAria}
    >
      {estagios.map((estagio, i) => (
        <rect
          key={estagio.rotulo}
          x={i * (LADO + GAP)}
          y={0}
          width={LADO}
          height={LADO}
          rx={3}
          className={`azulejo-mini-bloco azulejo-mini-bloco--${estagio.situacao}`}
        />
      ))}
    </svg>
  );
}
```

- [ ] **Step 4: Rodar o teste e ver passar**

Run: `cd apps/frontend && npx vitest run src/lib/charts/azulejo-mini.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/frontend/src/lib/charts/azulejo-mini.tsx apps/frontend/src/lib/charts/azulejo-mini.test.tsx
git commit -m "feat(fe): AzulejoMini — variante compacta da faixa de tramitacao p/ tabela"
```

---

## Task 10: Frontend — `proposicoes-vista.ts` (view-model puro)

**Files:**
- Create: `apps/frontend/src/lib/proposicoes-vista.ts`
- Test: `apps/frontend/src/lib/proposicoes-vista.test.ts`

**Interfaces:**
- Consumes: `ProposicaoResumoOut[]` camelizado (de `contrato-legislativo.gen.ts` via `camelizarChaves`), `derivarTramitacao`/`EstagioTramitacao` (`./tramitacao-vista`).
- Produces: `derivarProposicoesVista(itens: ProposicaoResumoOut[]) -> LinhaProposicaoVista[]`, onde `LinhaProposicaoVista = { id: string; numero: string; especie: string; ementa: string; autor: string; situacao: { rotulo: string; estagios: EstagioTramitacao[] }; atualizadoEm: string }`. `numero` = `"<sigla> <sequencial>/<ano>"` (ex.: `"PL 42/2026"`); `autor` = `autorTexto` ou `"—"` quando ausente.

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/frontend/src/lib/proposicoes-vista.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { derivarProposicoesVista } from "./proposicoes-vista";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";

const base: ProposicaoResumoOut = {
  id: "11111111-1111-1111-1111-111111111111",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 42,
  urnLex: "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42",
  ementa: "Cria o Programa Municipal de Hortas Comunitárias",
  autorTipo: "vereador",
  autorTexto: "Helena Matos",
  estado: "em_comissoes",
  atualizadoEm: "2026-05-21T10:00:00Z",
};

describe("derivarProposicoesVista", () => {
  it("monta o número no formato SIGLA sequencial/ano", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.numero).toBe("PL 42/2026");
  });

  it("mapeia a espécie para um rótulo legível", () => {
    const [linha] = derivarProposicoesVista([{ ...base, tipo: "requerimento" }]);
    expect(linha.especie).toBe("Requerimento");
  });

  it("autor ausente vira travessão, não string vazia/undefined", () => {
    const [linha] = derivarProposicoesVista([{ ...base, autorTexto: undefined, autorTipo: undefined }]);
    expect(linha.autor).toBe("—");
  });

  it("reaproveita derivarTramitacao para a situação (estado conhecido)", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.situacao.rotulo).toBe("Em comissões");
    expect(linha.situacao.estagios.length).toBeGreaterThan(0);
  });

  it("estado desconhecido degrada honesto (fail-closed), nunca lança", () => {
    const [linha] = derivarProposicoesVista([{ ...base, estado: "estado_customizado_do_tenant" }]);
    expect(linha.situacao.rotulo).toBe("estado_customizado_do_tenant");
  });

  it("lista vazia vira lista vazia", () => {
    expect(derivarProposicoesVista([])).toEqual([]);
  });
});
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/frontend && npx vitest run src/lib/proposicoes-vista.test.ts`
Expected: FAIL — `./proposicoes-vista` não existe.

- [ ] **Step 3: Implementar**

Criar `apps/frontend/src/lib/proposicoes-vista.ts`:

```ts
// View-model puro da lista de proposições (Onda B Slice 1) — traduz ProposicaoResumoOut (wire, camelizado)
// para o que a tabela mostra. Reaproveita derivarTramitacao (já construído na A2 para este mesmo `estado`
// livre/template-driven) — nenhum vocabulário novo de estado é inventado aqui.

import { derivarTramitacao, type EstagioTramitacao } from "./tramitacao-vista";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";

const SIGLA_POR_TIPO: Record<string, string> = {
  projeto_lei: "PL",
  projeto_lei_complementar: "PLC",
  projeto_resolucao: "PR",
  projeto_decreto_legislativo: "PDL",
  proposta_emenda_lom: "PELOM",
  indicacao: "IND",
  requerimento: "REQ",
  mocao: "MOÇ",
};

const ESPECIE_POR_TIPO: Record<string, string> = {
  projeto_lei: "Projeto de Lei",
  projeto_lei_complementar: "Projeto de Lei Complementar",
  projeto_resolucao: "Projeto de Resolução",
  projeto_decreto_legislativo: "Decreto Legislativo",
  proposta_emenda_lom: "Emenda à LOM",
  indicacao: "Indicação",
  requerimento: "Requerimento",
  mocao: "Moção",
};

export type LinhaProposicaoVista = {
  id: string;
  numero: string;
  especie: string;
  ementa: string;
  autor: string;
  situacao: { rotulo: string; estagios: EstagioTramitacao[] };
  atualizadoEm: string;
};

export function derivarProposicoesVista(itens: ProposicaoResumoOut[]): LinhaProposicaoVista[] {
  return itens.map((item) => {
    const sigla = SIGLA_POR_TIPO[item.tipo] ?? item.tipo;
    const { estagios, rotuloSituacao } = derivarTramitacao(item.estado);
    return {
      id: item.id,
      numero: `${sigla} ${item.sequencial}/${item.ano}`,
      especie: ESPECIE_POR_TIPO[item.tipo] ?? item.tipo,
      ementa: item.ementa,
      autor: item.autorTexto ?? "—",
      situacao: { rotulo: rotuloSituacao, estagios },
      atualizadoEm: item.atualizadoEm,
    };
  });
}
```

- [ ] **Step 4: Rodar o teste e ver passar**

Run: `cd apps/frontend && npx vitest run src/lib/proposicoes-vista.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/frontend/src/lib/proposicoes-vista.ts apps/frontend/src/lib/proposicoes-vista.test.ts
git commit -m "feat(fe): view-model puro proposicoes-vista (reaproveita derivarTramitacao)"
```

---

## Task 11: Frontend — `use-proposicoes.ts` (hook)

**Files:**
- Create: `apps/frontend/src/lib/use-proposicoes.ts`
- Test: `apps/frontend/src/lib/use-proposicoes.test.ts`

**Interfaces:**
- Consumes: `camelizarChaves` (`./boundary`), `ListaProposicoesOut`/`ProposicaoResumoOut` (`./contrato-legislativo.gen`).
- Produces: `useProposicoes(token: string | null, filtros: FiltrosProposicoes) -> { dados: ListaProposicoesOut | null; estado: "carregando" | "pronto" | "erro" }`, onde `FiltrosProposicoes = { busca?: string; tipo?: string; estado?: string; ano?: number; pagina: number; tamanho: number; ordenarPor: string; ordenarDir: "asc" | "desc" }`. Refetch em qualquer mudança de `filtros` (identidade por valor via `JSON.stringify` no array de dependências — mesmo padrão simples já aceitável no projeto para hooks pequenos).

- [ ] **Step 1: Escrever o teste (falhando)**

Criar `apps/frontend/src/lib/use-proposicoes.test.ts`:

```ts
import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useProposicoes } from "./use-proposicoes";

const filtrosBase = { pagina: 1, tamanho: 20, ordenarPor: "atualizado_em", ordenarDir: "desc" as const };

const respostaFake = {
  itens: [{ id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x", ementa: "X", estado: "protocolada", "atualizado-em": "2026-05-21T10:00:00Z" }],
  total: 1, pagina: 1, "tamanho-pagina": 20,
};

describe("useProposicoes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/proposicoes e cameliza o payload", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicoes("tok", filtrosBase));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.total).toBe(1);
    expect(result.current.dados?.itens[0].urnLex).toBe("urn:x");
    expect(result.current.dados?.tamanhoPagina).toBe(20);
  });

  it("monta a querystring a partir dos filtros", async () => {
    let urlCapturada = "";
    global.fetch = vi.fn(async (url: string) => {
      urlCapturada = url;
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useProposicoes("tok", { ...filtrosBase, busca: "hortas", tipo: "projeto_lei", pagina: 2 }));
    await waitFor(() => expect(urlCapturada).toContain("busca=hortas"));
    expect(urlCapturada).toContain("tipo=projeto_lei");
    expect(urlCapturada).toContain("pagina=2");
  });

  it("falha de rede -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicoes("tok", filtrosBase));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicoes(null, filtrosBase));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("refetch quando os filtros mudam (ex.: página)", async () => {
    let chamadas = 0;
    global.fetch = vi.fn(async () => {
      chamadas++;
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    const { rerender } = renderHook(({ filtros }) => useProposicoes("tok", filtros), {
      initialProps: { filtros: filtrosBase },
    });
    await waitFor(() => expect(chamadas).toBe(1));
    rerender({ filtros: { ...filtrosBase, pagina: 2 } });
    await waitFor(() => expect(chamadas).toBe(2));
  });
});
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/frontend && npx vitest run src/lib/use-proposicoes.test.ts`
Expected: FAIL — `./use-proposicoes` não existe.

- [ ] **Step 3: Implementar**

Criar `apps/frontend/src/lib/use-proposicoes.ts`:

```ts
"use client";

// Hook da lista de proposições (Onda B Slice 1): busca GET /api/legislativo/proposicoes com os filtros
// atuais (querystring), cameliza o payload no boundary (mesmo padrão de use-mesa.ts) e refaz a busca a
// cada mudança de filtro (busca/tipo/estado/ano/página/ordenação).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ListaProposicoesOut } from "./contrato-legislativo.gen";

export type FiltrosProposicoes = {
  busca?: string;
  tipo?: string;
  estado?: string;
  ano?: number;
  pagina: number;
  tamanho: number;
  ordenarPor: string;
  ordenarDir: "asc" | "desc";
};

type Estado = "carregando" | "pronto" | "erro";

function montarQuerystring(filtros: FiltrosProposicoes): string {
  const params = new URLSearchParams();
  if (filtros.busca) params.set("busca", filtros.busca);
  if (filtros.tipo) params.set("tipo", filtros.tipo);
  if (filtros.estado) params.set("estado", filtros.estado);
  if (filtros.ano !== undefined) params.set("ano", String(filtros.ano));
  params.set("pagina", String(filtros.pagina));
  params.set("tamanho", String(filtros.tamanho));
  params.set("ordenar-por", filtros.ordenarPor);
  params.set("ordenar-dir", filtros.ordenarDir);
  return params.toString();
}

export function useProposicoes(token: string | null, filtros: FiltrosProposicoes) {
  const [dados, setDados] = useState<ListaProposicoesOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const chave = JSON.stringify(filtros);

  useEffect(() => {
    if (!token) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    setEstado("carregando");
    (async () => {
      try {
        const r = await fetch(`/api/legislativo/proposicoes?${montarQuerystring(filtros)}`, {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as ListaProposicoesOut);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `chave` já resume `filtros` por valor
  }, [token, chave]);

  if (!token) {
    return { dados: null, estado: "erro" as Estado };
  }
  return { dados, estado };
}
```

- [ ] **Step 4: Rodar o teste e ver passar**

Run: `cd apps/frontend && npx vitest run src/lib/use-proposicoes.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add apps/frontend/src/lib/use-proposicoes.ts apps/frontend/src/lib/use-proposicoes.test.ts
git commit -m "feat(fe): hook use-proposicoes (fetch autenticado + refetch por filtro)"
```

---

## Task 12: Frontend — nav no `TopoInterno`

**Files:**
- Modify: `apps/frontend/src/app/(interno)/topo.tsx`
- Modify: `apps/frontend/src/app/(interno)/topo.css`
- Modify: `apps/frontend/src/app/(interno)/topo.test.tsx`

**Interfaces:**
- Produces: `TopoInterno` ganha uma prop opcional `area` já existente (sem mudança de assinatura) + um `<nav>` fixo de 2 itens (Painéis da Mesa ⇄ Proposições), com `aria-current="page"` no item cuja `area` bate com o rótulo.

Esta é a **primeira navegação real** do App Shell interno (hoje só existe "Painéis da Mesa" isolada) — os rótulos de destino são fixos (não uma lista dinâmica) porque só há 2 páginas internas nesta fatia.

- [ ] **Step 1: Atualizar o teste existente (vai falhar até o Step 3)**

Editar `apps/frontend/src/app/(interno)/topo.test.tsx` — adicionar ao final do `it` existente (depois da linha `expect(screen.getByText("Presidente da Mesa")).toBeTruthy();`):

```tsx
    expect(screen.getByRole("link", { name: "Painéis da Mesa" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Proposições" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Painéis da Mesa" }).getAttribute("aria-current")).toBe("page");
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `cd apps/frontend && npx vitest run "src/app/(interno)/topo.test.tsx"`
Expected: FAIL — os links de nav ainda não existem.

- [ ] **Step 3: Implementar o nav**

Editar `apps/frontend/src/app/(interno)/topo.tsx` — adicionar o import do `Link` do Next e o array de destinos antes do componente:

```tsx
import Link from "next/link";
```

Adicionar logo antes de `export function TopoInterno`:

```tsx
const DESTINOS_NAV = [
  { rotulo: "Painéis da Mesa", href: "/paineis/mesa" },
  { rotulo: "Proposições", href: "/proposicoes" },
];
```

Dentro do `<header className="topo">`, logo após a `<div className="topo-sep" aria-hidden="true" />` e o `<span className="area-tag">{area}</span>` existentes, inserir o `<nav>`:

```tsx
        <nav className="nav-interna" aria-label="Navegação interna">
          {DESTINOS_NAV.map((d) => (
            <Link
              key={d.href}
              href={d.href}
              aria-current={d.rotulo === area ? "page" : undefined}
            >
              {d.rotulo}
            </Link>
          ))}
        </nav>
```

- [ ] **Step 4: Portar o CSS do nav (verbatim, mesma disciplina do resto do `topo.css`)**

Editar `apps/frontend/src/app/(interno)/topo.css` — adicionar ao final:

```css
.nav-interna { display: flex; gap: 1rem; margin-left: 0.5rem; }
.nav-interna a { font-family: var(--mono); font-size: var(--t-13); font-weight: 500; letter-spacing: .02em; color: var(--texto-2); text-decoration: none; padding: 0.3rem 0; border-bottom: 2px solid transparent; }
.nav-interna a:hover { color: var(--texto); }
.nav-interna a[aria-current="page"] { color: var(--marca); border-bottom-color: var(--marca); font-weight: 600; }
```

- [ ] **Step 5: Rodar o teste e ver passar**

Run: `cd apps/frontend && npx vitest run "src/app/(interno)/topo.test.tsx"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add "apps/frontend/src/app/(interno)/topo.tsx" "apps/frontend/src/app/(interno)/topo.css" "apps/frontend/src/app/(interno)/topo.test.tsx"
git commit -m "feat(fe): primeira navegacao real do App Shell interno (Paineis da Mesa <-> Proposicoes)"
```

---

## Task 13: Frontend — página `(interno)/proposicoes`

**Files:**
- Create: `apps/frontend/src/app/(interno)/proposicoes/proposicoes.css`
- Create: `apps/frontend/src/app/(interno)/proposicoes/page.tsx`
- Test: `apps/frontend/src/app/(interno)/proposicoes/page.test.tsx`

**Interfaces:**
- Consumes: `useAuth` (`@/lib/auth`), `useProposicoes`/`FiltrosProposicoes` (`@/lib/use-proposicoes`), `derivarProposicoesVista` (`@/lib/proposicoes-vista`), `AzulejoMini` (`@/lib/charts/azulejo-mini`), `descreverFaixa` (`@/lib/tramitacao-vista`), `TopoInterno` (`../topo`).

- [ ] **Step 1: Portar o CSS da tela-fonte (verbatim)**

Criar `apps/frontend/src/app/(interno)/proposicoes/proposicoes.css` — copiar VERBATIM as regras de `.pagina-cab`, `.filtros`, `.filtros-linha`, `.busca`, `.faceta`, `.tabela-wrap`, `table.tabela` (e os seletores `thead`/`tbody` dentro dela), `.num`, `.especie`, `.ementa`, `.autor`, `.atualizada`, `.sit`, `.chip` (+ `.chip-tram`/`.chip-aguarda`/`.chip-aprovada`/`.chip-arquivada`), `.paginacao`, `.pag-nav`, `.vazio` e o bloco `@media (max-width: 860px)` de `produto/design-system/o-plenario/telas/proposicoes.html` (linhas 25–147), **omitindo**: `.busca-ia`/`.busca-aviso` (busca inteligente é permanentemente "off" nesta fatia — não precisa do CSS do estado ligado), `.demo-strip` (scaffolding de design, não é chrome de produto), `.col-sel`/`.lin-acoes`/`.ico-btn`/`.comando`/`.sel-conta` (seleção em massa e ação por linha ficam fora do escopo desta fatia — sem barra de seleção renderizada) e `.azulejo-mini`/`.am*` (o componente `AzulejoMini` desta fatia é um SVG React próprio com suas próprias classes `azulejo-mini-bloco--*`, não o markup inline HTML da tela-fonte — adicionar em vez disso):

```css
.azulejo-mini-bloco--concluido { fill: var(--jade); }
.azulejo-mini-bloco--ativo { fill: color-mix(in srgb, var(--amarelo) 55%, var(--surface-2)); stroke: var(--amarelo); stroke-width: 1; }
.azulejo-mini-bloco--pendente { fill: none; stroke: color-mix(in srgb, var(--texto-2) 40%, transparent); stroke-width: 1; stroke-dasharray: 2 2; }
```

- [ ] **Step 2: Escrever o teste da página (falhando)**

Criar `apps/frontend/src/app/(interno)/proposicoes/page.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaProposicoes from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <PaginaProposicoes />
      </TemaProvider>
    </AuthProvider>,
  );
}

const respostaFake = {
  itens: [
    {
      id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x",
      ementa: "Cria o Programa Municipal de Hortas Comunitárias", "autor-texto": "Helena Matos",
      estado: "em_comissoes", "atualizado-em": "2026-05-21T10:00:00Z",
    },
  ],
  total: 1, pagina: 1, "tamanho-pagina": 20,
};

describe("PaginaProposicoes", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renderiza a tabela com os dados reais após carregar", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("PL 42/2026")).toBeTruthy());
    expect(screen.getByText("Cria o Programa Municipal de Hortas Comunitárias")).toBeTruthy();
    expect(screen.getByText("Helena Matos")).toBeTruthy();
  });

  it("lista vazia mostra o estado 'nenhuma matéria encontrada'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens: [], total: 0, pagina: 1, "tamanho-pagina": 20 }) }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText(/Nenhuma matéria encontrada/)).toBeTruthy());
  });

  it("erro de rede mostra o estado de erro da página", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText(/não foi possível carregar/i)).toBeTruthy());
  });
});
```

- [ ] **Step 3: Rodar o teste e ver falhar**

Run: `cd apps/frontend && npx vitest run "src/app/(interno)/proposicoes/page.test.tsx"`
Expected: FAIL — `./page` não existe.

- [ ] **Step 4: Implementar a página**

Criar `apps/frontend/src/app/(interno)/proposicoes/page.tsx`:

```tsx
"use client";

// Lista de proposições (Onda B Slice 1, §16 arquétipo lista/tabela filtrável) — assembly da rota
// /proposicoes. Une useAuth (App Shell) + useProposicoes (fetch autenticado + refetch por filtro) +
// derivarProposicoesVista (view-model puro) + AzulejoMini/descreverFaixa (assinatura de tramitação, já
// construídos na A2). SÓ LEITURA nesta fatia (spec 2026-07-05): "Nova proposição", "Exportar" e a barra de
// ações em massa da tela-fonte ficam FORA — nenhum botão morto é renderizado (em vez de simular ações sem
// dono, elas simplesmente não aparecem ainda). A ação "abrir ficha" por linha também não existe ainda
// (ficha-materia é uma fatia posterior) — cada linha não é clicável.

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { useProposicoes, type FiltrosProposicoes } from "@/lib/use-proposicoes";
import { derivarProposicoesVista } from "@/lib/proposicoes-vista";
import { AzulejoMini } from "@/lib/charts/azulejo-mini";
import { descreverFaixa } from "@/lib/tramitacao-vista";
import { TopoInterno } from "../topo";
import "./proposicoes.css";

const FILTROS_INICIAIS: FiltrosProposicoes = {
  pagina: 1,
  tamanho: 20,
  ordenarPor: "atualizado_em",
  ordenarDir: "desc",
};

export default function PaginaProposicoes() {
  const { token } = useAuth();
  const [filtros, setFiltros] = useState<FiltrosProposicoes>(FILTROS_INICIAIS);
  const { dados, estado } = useProposicoes(token, filtros);
  const linhas = dados ? derivarProposicoesVista(dados.itens) : [];
  const totalPaginas = dados ? Math.max(1, Math.ceil(dados.total / dados.tamanhoPagina)) : 1;

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar as proposições</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }

  return (
    <>
      <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pagina-cab">
          <div>
            <span className="eyebrow">Acervo legislativo</span>
            <h1>Proposições</h1>
          </div>
          {dados && (
            <span className="conta" aria-live="polite">
              <b>{linhas.length}</b> de {dados.total} matérias
            </span>
          )}
        </div>

        <form className="filtros" role="search" aria-label="Filtrar proposições" onSubmit={(e) => e.preventDefault()}>
          <div className="filtros-linha">
            <div className="busca">
              <label className="sr-only" htmlFor="busca">Buscar por número, ementa ou autor</label>
              <input
                id="busca"
                type="search"
                placeholder="Buscar por número, ementa ou autor…"
                onChange={(e) => setFiltros((f) => ({ ...f, busca: e.target.value || undefined, pagina: 1 }))}
              />
            </div>
            <span className="faceta">
              <label htmlFor="f-tipo">Espécie</label>
              <select
                id="f-tipo"
                onChange={(e) => setFiltros((f) => ({ ...f, tipo: e.target.value || undefined, pagina: 1 }))}
              >
                <option value="">Todas</option>
                <option value="projeto_lei">Projeto de Lei</option>
                <option value="requerimento">Requerimento</option>
                <option value="mocao">Moção</option>
                <option value="indicacao">Indicação</option>
                <option value="projeto_resolucao">Projeto de Resolução</option>
                <option value="projeto_decreto_legislativo">Projeto de Decreto Leg.</option>
              </select>
            </span>
          </div>
        </form>

        {estado === "carregando" && <p role="status">Carregando…</p>}

        {estado === "pronto" && linhas.length === 0 && (
          <div className="vazio">
            <h2>Nenhuma matéria encontrada</h2>
            <p>Nenhuma proposição corresponde aos filtros atuais. Ajuste a busca ou limpe os filtros para ver o acervo completo.</p>
          </div>
        )}

        {estado === "pronto" && linhas.length > 0 && (
          <div className="tabela-wrap">
            <table className="tabela">
              <caption className="sr-only">
                Lista de proposições com número, espécie, ementa, autoria, situação e data de atualização.
              </caption>
              <thead>
                <tr>
                  <th scope="col">Nº / ano</th>
                  <th scope="col">Espécie</th>
                  <th scope="col">Ementa</th>
                  <th scope="col">Autoria</th>
                  <th scope="col">Situação</th>
                  <th scope="col">Atualizada</th>
                </tr>
              </thead>
              <tbody>
                {linhas.map((linha) => (
                  <tr key={linha.id}>
                    <td className="num">{linha.numero}</td>
                    <td className="especie">{linha.especie}</td>
                    <td className="ementa">{linha.ementa}</td>
                    <td className="autor">{linha.autor}</td>
                    <td>
                      <div className="sit">
                        <span className="chip">{linha.situacao.rotulo}</span>
                        <AzulejoMini
                          estagios={linha.situacao.estagios}
                          rotuloAria={descreverFaixa(linha.numero, linha.situacao.estagios)}
                        />
                      </div>
                    </td>
                    <td className="atualizada">{new Date(linha.atualizadoEm).toLocaleDateString("pt-BR")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {dados && dados.total > 0 && (
          <nav className="paginacao" aria-label="Paginação">
            <span className="info">
              Página {dados.pagina} de {totalPaginas}
            </span>
            <div className="pag-nav">
              <button
                type="button"
                disabled={dados.pagina <= 1}
                onClick={() => setFiltros((f) => ({ ...f, pagina: f.pagina - 1 }))}
                aria-label="Página anterior"
              >
                ←
              </button>
              <button
                type="button"
                disabled={dados.pagina >= totalPaginas}
                onClick={() => setFiltros((f) => ({ ...f, pagina: f.pagina + 1 }))}
                aria-label="Próxima página"
              >
                →
              </button>
            </div>
          </nav>
        )}
      </main>
    </>
  );
}
```

- [ ] **Step 5: Rodar o teste e ver passar**

Run: `cd apps/frontend && npx vitest run "src/app/(interno)/proposicoes/page.test.tsx"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/daoudatraore/oplenario
git add "apps/frontend/src/app/(interno)/proposicoes"
git commit -m "feat(fe): pagina /proposicoes (lista filtravel/paginada, so leitura)"
```

---

## Task 14: Verificação manual (prova real) + build

**Files:** nenhum arquivo novo — verificação end-to-end.

- [ ] **Step 1: Subir o ambiente Docker**

Run: `cd apps/backend && docker compose up -d --build`
Expected: containers `postgres`/`app`/`minio`/`valkey` saudáveis (`docker compose ps` mostra `healthy`/`running`).

- [ ] **Step 2: Semear proposições reais**

Run (a partir de `apps/backend`, ajustando `DATABASE_URL`/credenciais conforme `docs/` de rodar local):
```bash
clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed seed-demo/base
```
Expected: seed roda sem erro (ou confirmar que o seed já usado nas fatias anteriores — `apps/backend/demo/seed_demo.clj` — cobre proposições protocoladas; se não cobrir volume suficiente para exercitar paginação, protocolar mais algumas via REPL/script usando `legislativo.db.proposicao/protocolar!` diretamente, só para esta verificação manual).

- [ ] **Step 3: Subir o frontend e abrir no browser**

Run: `cd apps/frontend && docker compose up -d --build` (ou `npm run dev` se a convenção Docker do projeto for só para o backend — confirmar em `docs/` antes; **lembrete do projeto: nunca rodar `npm`/`node` direto no host, tudo em container**).

Abrir `http://localhost:3000/proposicoes?token=<claims-json-dev>` no browser (Claude-in-Chrome ou manual). Verificar:
- A tabela renderiza linhas reais (não mock).
- Digitar na busca filtra a lista (aguardar o refetch).
- Trocar a "Espécie" no select filtra a lista.
- Paginação avança/volta corretamente quando há mais de 20 proposições semeadas.
- A coluna "Situação" mostra o chip + o mini-azulejo coerente com o `estado` da proposição.
- Alternar tema (claro/escuro) mantém a tabela legível (AA).
- Nav do topo alterna entre "Painéis da Mesa" e "Proposições" corretamente.

- [ ] **Step 4: Rodar as suítes completas + build**

Run:
```bash
cd apps/backend && clojure -M:test
cd ../frontend && npx vitest run && npx tsc --noEmit && npx eslint . && npx next build
```
Expected: tudo verde/sem erro.

- [ ] **Step 5: Registrar carries encontrados (se houver) e seguir**

Se a verificação manual revelar algo que não é um bug de código desta fatia (ex.: seed insuficiente, ajuste de UX menor), registrar como carry no commit da Task 16 — não bloquear a fatia por polish fora do escopo aprovado.

---

## Task 15: Frontend — review `ecc` (react + security)

**Files:** nenhum arquivo novo — aplica os achados dos revisores nos arquivos das Tasks 8–13.

- [ ] **Step 1: Rodar o revisor react**

Invocar o agente `ecc:react-reviewer` sobre o diff da branch (arquivos: `azulejo-mini.tsx`, `proposicoes-vista.ts`, `use-proposicoes.ts`, `topo.tsx`, `page.tsx`, `proposicoes.css`, mais os testes).

- [ ] **Step 2: Rodar o revisor security**

Invocar o agente `ecc:security-reviewer` — atenção a: querystring montada em `montarQuerystring` (usa `URLSearchParams`, escapa automaticamente — confirmar que não há concatenação manual de string em nenhum lugar), token dev via `?token=` (já guardado por `AuthProvider`, nenhuma mudança nesta fatia), e o `dangerouslySetInnerHTML`/XSS (não deveria haver nenhum — a `ementa` é renderizada via JSX puro, que escapa por padrão).

- [ ] **Step 3: Aplicar os achados CRÍTICOS/MAJOR**

Corrigir cada achado confirmado; rodar `npx vitest run` após cada fix.

- [ ] **Step 4: Rodar tudo de novo (suíte + tsc + eslint + build)**

Run: `cd apps/frontend && npx vitest run && npx tsc --noEmit && npx eslint . && npx next build`
Expected: PASS, 0 erros.

- [ ] **Step 5: Commit dos fixes (se houver)**

```bash
cd /Users/daoudatraore/oplenario
git add -A apps/frontend
git commit -m "fix(fe): incorpora review ecc (react+security) da lista de proposicoes"
```

Se não houver achados CRÍTICOS/MAJOR, pular o commit e seguir para a Task 16.

---

## Task 16: Fechamento da fatia

**Files:** nenhum arquivo novo.

- [ ] **Step 1: Rodar as duas suítes completas uma última vez**

Run:
```bash
cd apps/backend && clojure -M:test && clj-kondo --lint src test
cd ../frontend && npx vitest run && npx tsc --noEmit && npx eslint . && npx next build
```
Expected: tudo verde.

- [ ] **Step 2: Revisar o diff completo da branch**

Run: `git -C /Users/daoudatraore/oplenario diff main...fe-7-proposicoes-lista --stat`
Expected: só os arquivos das Tasks 1–15 (backend: db/wire/adapters/repositorio/controllers/diplomat/testes; frontend: codegen gerado + lib + topo + página + testes). Nenhuma migration, nenhuma rota de escrita.

- [ ] **Step 3: Apresentar a branch para decisão de merge**

Não mergear sozinho — usar a skill `superpowers:finishing-a-development-branch` para apresentar as opções (merge/PR/manter aberta) ao Daouda, mesma disciplina de `fe-1`…`fe-6` ([[oplenario-git-branch-por-frente]]).

---

## Self-Review (executado antes de entregar este plano)

**1. Cobertura da spec:** §1 escopo (só leitura, resto `EmBreve`/ausente) → Tasks 13 (nenhum botão de escrita renderizado). §2 borda backend → Tasks 2–6. §3 frontend → Tasks 8–13. §4 contrato de dados → Task 3 (wire/out) + Task 8 (codegen). §5 decisões (fonte direta, papel `secretario`, mini-azulejo incluído) → refletidas em Global Constraints e nas Tasks 2/6/9. §6 testes → uma task de teste por camada (2, 3, 4, 6, 9, 10, 11, 12, 13) + Tasks 7/15 (review). §7 carries → mencionados na Task 14 (não bloqueiam).

**2. Placeholder scan:** nenhum "TBD"/"adicionar validação apropriada" — todo código é completo e executável como escrito.

**3. Consistência de tipos:** `FiltrosProposicoes` (Task 11) e o que `page.tsx` (Task 13) usa em `setFiltros` batem campo a campo. `LinhaProposicaoVista` (Task 10) e os campos lidos em `page.tsx` (`linha.numero`/`.especie`/`.ementa`/`.autor`/`.situacao.rotulo`/`.situacao.estagios`/`.atualizadoEm`) batem. `filtro` no backend (`:busca :tipo :estado :autor-id :ano :pagina :tamanho :ordenar-por :ordenar-dir`) é o mesmo shape em `adapters/in` (Task 4), `db` (Task 2) e no fake repo do teste HTTP (Task 6).
