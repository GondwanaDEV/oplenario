# Dashboard da Mesa — Track FE Slice 1 (Onda A1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar `GET /paineis/mesa` enriquecido com 3 read-models novos e baratos (presença agregada,
cumprimento de prazo e-SIC, relatores pendentes) e o Dashboard da Mesa em Next.js consumindo-o — estabelecendo
o App Shell interno, as primitivas de chart honestos e a decisão do codegen Malli→TS que as fatias seguintes
da Track FE reusam.

**Architecture:** Backend Clojure (silhueta ADR-0001 por módulo: wire/in, wire/out, adapters/in, adapters/out,
controllers, diplomat, components) — 3 módulos donos (`cadastros`, `sessoes`, `participacao`, `legislativo`)
expõem cada um sua fatia; `paineis` compõe tudo por inversão de dependência (host injeta as fns, nunca import
cross-módulo, §22.10). Frontend Next.js 16 App Router — App Shell autenticado novo + página `/paineis/mesa`
com view-model puro testável e componentes finos de mapeamento.

**Tech Stack:** Clojure/Pedestal/HoneySQL/next.jdbc/Malli (backend); Next.js 16/React 19/TypeScript/vitest
(frontend); PostgreSQL via `docker compose` (containers locais).

## Global Constraints

- Toda query nova inclui `ente_id` no WHERE mesmo com RLS (defesa + índice — convenção do codebase).
- Nenhum módulo importa outro (§22.10 import-lint); composição cross-módulo só no host (`rotas.clj`/`sistema.clj`)
  por inversão de dependência (mesmo padrão de `consultar-sessao`/`painel-compliance`).
- Silhueta ADR-0001: `wire/in`, `wire/out`, `adapters/in`, `adapters/out`, `controllers`, `diplomat`,
  `components` — sem `port/`, sem ORM.
- Todo `wire/out` é `[:map {:closed true} ...]`, validado com `malli.core/validate` antes de sair (drift de
  campo = bug de servidor → lança `ex-info`, nunca corpo malformado).
- TDD red→green obrigatório em cada task backend; reviews `ecc` clojure-reviewer + database-reviewer +
  security-reviewer antes do merge da fatia (fora do escopo mecânico deste plano — ver nota final).
- Frontend: sem lib de chart nova — primitivas SVG hand-rolled (mesmo padrão do `Hemiciclo` já existente em
  `sessoes/[id]/plenario/page.tsx`).
- Container local: `cd apps/backend && docker compose up -d --build` sobe postgres (`oplenario-postgres-1`,
  porta do `.env`), valkey, minio; o serviço `migrate` aplica migrations antes do `app` subir.
- Modelo/effort ativo verificado nesta sessão: Sonnet 5, xhigh — consistente com a tier "mecânico" de `docs/11`.

---

# PARTE A — Backend (paineis/mesa enriquecido)

## Task A1: `cadastros` — expor `membros-da-casa` no RepoCadastros

**Files:**
- Modify: `apps/backend/src/oplenario/cadastros/components/repositorio.clj`
- Test: `apps/backend/test/integration/oplenario/cadastros/repositorio_membros_test.clj`

**Interfaces:**
- Produces: `RepoCadastros.membros-da-casa [this ente-id data]` → `int` (nº de vereadores com mandato vigente
  em `data`). Consumido pela Task A2 via o host.

- [ ] **Step 1: Escrever o teste de integração (falha)**

```clojure
(ns oplenario.cadastros.repositorio-membros-test
  "INTEGRACAO (PG real) — RepoCadastros/membros-da-casa (novo metodo, FE Onda A1): expoe
  cadastros.relacoes.cadastro/membros-da-casa via o Repo, p/ o host injetar em outros modulos."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(deftest membros-da-casa-conta-mandatos-vigentes
  (let [ente (random-uuid)
        leg  (repo/criar-legislatura! *repo* ente
               {:id (random-uuid) :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        v1 (repo/criar-vereador! *repo* ente {:id (random-uuid) :ente-id ente :nome "A" :identidade-id nil})
        v2 (repo/criar-vereador! *repo* ente {:id (random-uuid) :ente-id ente :nome "B" :identidade-id nil})]
    (repo/criar-mandato! *repo* ente {:id (random-uuid) :ente-id ente :vereador-id (:id v1)
                                      :legislatura-id (:id leg) :estado "vigente"
                                      :vigencia-inicio (LocalDate/of 2025 1 1) :vigencia-fim nil})
    (repo/criar-mandato! *repo* ente {:id (random-uuid) :ente-id ente :vereador-id (:id v2)
                                      :legislatura-id (:id leg) :estado "vigente"
                                      :vigencia-inicio (LocalDate/of 2025 1 1) :vigencia-fim nil})
    (is (= 2 (repo/membros-da-casa *repo* ente (LocalDate/of 2026 7 4))))
    (is (= 0 (repo/membros-da-casa *repo* (random-uuid) (LocalDate/of 2026 7 4))) "RLS: outro ente conta 0")))
```

> Ajuste os mapas de `criar-legislatura!`/`criar-vereador!`/`criar-mandato!` para bater exatamente com os
> parâmetros aceitos por `db/estrutura.clj`/`db/vereador.clj` (confira as chaves com `grep -n "defn inserir"`
> nesses arquivos antes de rodar — os nomes de campo devem casar 1:1).

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/backend && clojure -M:test -n oplenario.cadastros.repositorio-membros-test`
Expected: FAIL — `No implementation of method: :membros-da-casa`

- [ ] **Step 3: Implementar — adicionar o método ao protocolo e ao record**

Em `apps/backend/src/oplenario/cadastros/components/repositorio.clj`, adicionar o require de
`oplenario.cadastros.relacoes.cadastro`, o método ao `defprotocol` (após `membros-da-comissao`) e ao
`defrecord`:

```clojure
(:require [oplenario.cadastros.db.comissao :as comissao]
          [oplenario.cadastros.db.estrutura :as estrutura]
          [oplenario.cadastros.db.vereador :as vereador]
          [oplenario.cadastros.relacoes.cadastro :as rel-cadastro]
          [oplenario.kernel.tenancy :as tenancy])
```

```clojure
  (membros-da-comissao [this ente-id com-id])
  (membros-da-casa [this ente-id data]
    "Nº de vereadores com mandato vigente em `data` (relacao ja usada pelo motor de regras — F2; exposta
     aqui p/ o host injetar em outros modulos via inversao de dependencia, §22.10, FE Onda A1)."))
```

```clojure
  (membros-da-casa [this ente-id data] (transacao this ente-id #(rel-cadastro/membros-da-casa % data))))
```

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/backend && clojure -M:test -n oplenario.cadastros.repositorio-membros-test`
Expected: PASS (2 asserts)

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/cadastros/components/repositorio.clj \
        apps/backend/test/integration/oplenario/cadastros/repositorio_membros_test.clj
git commit -m "feat(cadastros): expõe membros-da-casa no RepoCadastros (FE Onda A1)"
```

---

## Task A2: `sessoes` — presença agregada (read-model barato) + host injeta `membros-da-casa`

**Files:**
- Modify: `apps/backend/src/oplenario/sessoes/db/presenca.clj`
- Modify: `apps/backend/src/oplenario/sessoes/wire/out.clj`
- Modify: `apps/backend/src/oplenario/sessoes/adapters/out/presenca.clj`
- Modify: `apps/backend/src/oplenario/sessoes/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/sessoes/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/sistema.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj`
- Test: `apps/backend/test/integration/oplenario/sessoes/presenca_db_test.clj` (adiciona casos)
- Test: `apps/backend/test/unit/oplenario/sessoes/presenca_resumo_adapters_test.clj`

**Interfaces:**
- Consumes: `RepoCadastros.membros-da-casa` (Task A1).
- Produces: `sessoes.diplomat.http.in/presenca-resumo-wire [repo-sessoes membros-da-casa ente-id]` →
  `PresencaResumoOut` (mapa JSON-serializável). Consumido pela Task A6 (host injeta em `paineis`).

**Decisão de escopo (desvio pontual da spec):** a spec falava em "sessão legislativa corrente"; na
implementação isso exigiria resolver `sessao_legislativa` vigente (não existe helper pronto — só
`legislatura-vigente`, uma granularidade mais grossa, ~4 anos). Para não inventar uma query nova de
"sessão legislativa vigente" fora do escopo barato desta fatia, a janela vira **as últimas 10 sessões
ENCERRADAS do tenant** (sem depender de nenhum conceito de `cadastros` além de `membros-da-casa`) — mais
simples, autocontido, e ainda não é uma janela de calendário arbitrária.

- [ ] **Step 1: Escrever o teste de integração da query (falha)**

Adicionar ao final de `apps/backend/test/integration/oplenario/sessoes/presenca_db_test.clj`:

```clojure
(deftest resumo-presenca-agrega-as-ultimas-10-sessoes-encerradas
  (let [ente (random-uuid)
        v1 (random-uuid) v2 (random-uuid) v3 (random-uuid)
        s1 (random-uuid) s2 (random-uuid)]
    ;; s1: v1+v2 presentes, v3 ausente (2/3); s2: so' v1 presente (1/3)
    (db-sessao/inserir! *ds* {:id s1 :ente-id ente :sessao-legislativa-id (random-uuid)
                              :tipo-sessao "ordinaria" :numero-sequencial 1 :estado "encerrada"
                              :modalidade "presencial" :delibera true :transmite-publica true
                              :gera-ata-regimental true :permite-voto-secreto false
                              :permite-modalidade-remota false :encerrada-em (Instant/parse "2026-07-01T20:00:00Z")})
    (db-presenca/registrar-evento! *ds* {:id (random-uuid) :ente-id ente :sessao-id s1 :vereador-id v1
                                         :tipo "entrada" :modalidade "plenario" :fonte "manual"
                                         :ocorrido-em (Instant/parse "2026-07-01T19:00:00Z")})
    (db-presenca/registrar-evento! *ds* {:id (random-uuid) :ente-id ente :sessao-id s1 :vereador-id v2
                                         :tipo "entrada" :modalidade "plenario" :fonte "manual"
                                         :ocorrido-em (Instant/parse "2026-07-01T19:00:00Z")})
    (db-sessao/inserir! *ds* {:id s2 :ente-id ente :sessao-legislativa-id (random-uuid)
                              :tipo-sessao "ordinaria" :numero-sequencial 2 :estado "encerrada"
                              :modalidade "presencial" :delibera true :transmite-publica true
                              :gera-ata-regimental true :permite-voto-secreto false
                              :permite-modalidade-remota false :encerrada-em (Instant/parse "2026-07-02T20:00:00Z")})
    (db-presenca/registrar-evento! *ds* {:id (random-uuid) :ente-id ente :sessao-id s2 :vereador-id v1
                                         :tipo "entrada" :modalidade "plenario" :fonte "manual"
                                         :ocorrido-em (Instant/parse "2026-07-02T19:00:00Z")})
    (let [r (db-presenca/resumo-presenca *ds* ente 3)]
      (is (= 2 (:sessoes-consideradas r)))
      (is (= 3 (:membros-da-casa r)))
      ;; numerador (2+1)=3, denominador 2*3=6 -> 50%
      (is (= 50 (:media-percentual r))))
    (is (nil? (:media-percentual (db-presenca/resumo-presenca *ds* (random-uuid) 3)))
        "sem sessao encerrada -> media indefinida (nil, nao 0%)")))
```

> Adicione os requires necessários no topo do arquivo (`[oplenario.sessoes.db.sessao :as db-sessao]`,
> `[java.time Instant]`) se ainda não estiverem lá — confira as chaves exatas de `db/sessao.clj:inserir!`
> antes de rodar (mesma checagem de nomes de campo da Task A1).

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/backend && clojure -M:test -n oplenario.sessoes.presenca-db-test`
Expected: FAIL — `resumo-presenca` não existe

- [ ] **Step 3: Implementar a query em `db/presenca.clj`**

Adicionar ao final de `apps/backend/src/oplenario/sessoes/db/presenca.clj`:

```clojure
;; ---------- presenca agregada (read-model barato, FE Onda A1) ----------

(defn- sessoes-encerradas-recentes
  "As `teto` sessoes mais RECENTES do tenant com `estado`='encerrada' e encerrada_em carimbado —
  janela autocontida (nao depende de 'legislativa vigente', que exigiria cruzar cadastros)."
  [tx ente-id teto]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :encerrada_em] :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :estado [:inline "encerrada"]]
                          [:is-not :encerrada_em nil]]
                  :order-by [[:encerrada_em :desc]] :limit teto}))))

(def ^:private positivos (vec (sort logic/tipos-presenca-positiva)))

(defn- presentes-na-sessao
  "Total de vereadores com ULTIMO evento positivo ate' `instante` (qualquer modalidade) — generaliza
  contar-presentes de sessoes/relacoes/presenca (que filtra por modalidade) p/ o agregado cross-sessao."
  [tx sessao-id instante]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:count :*] :n]]
                     :from [[{:select-distinct-on [[:vereador_id] :vereador_id :tipo]
                              :from [:sessoes.presenca_evento]
                              :where [:and [:= :sessao_id sessao-id] [:<= :ocorrido_em instante]]
                              :order-by [[:vereador_id :asc] [:ocorrido_em :desc]
                                         [:fonte_precedencia :desc] [:id :desc]]}
                             :u]]
                     :where [:in :u.tipo positivos]}))
      comum/linha->kebab :n))

(defn resumo-presenca
  "Presenca agregada (F7/FE Onda A1, barata): media de presenca das ultimas `teto` sessoes ENCERRADAS do
  tenant. numerador = soma de presentes por sessao; denominador = (n de sessoes) x `membros-da-casa`
  (resolvido pelo CALLER via cadastros, injecao cross-modulo — este ns nao importa cadastros). Devolve
  {:media-percentual :sessoes-consideradas :membros-da-casa} — media nil se nao houve sessao encerrada
  ainda (0/0 e' indefinido, nao 0%)."
  [tx ente-id membros-da-casa teto]
  (let [sessoes (sessoes-encerradas-recentes tx ente-id teto)
        n-sessoes (count sessoes)
        total-presentes (reduce + 0 (map #(presentes-na-sessao tx (:id %) (:encerrada-em %)) sessoes))]
    {:media-percentual (when (and (pos? n-sessoes) (pos? membros-da-casa))
                         (int (Math/round (* 100.0 (/ total-presentes (* n-sessoes membros-da-casa))))))
     :sessoes-consideradas n-sessoes
     :membros-da-casa membros-da-casa}))
```

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/backend && clojure -M:test -n oplenario.sessoes.presenca-db-test`
Expected: PASS (todos os asserts, incl. os pré-existentes do arquivo)

- [ ] **Step 5: `wire/out` — adicionar `PresencaResumoOut`**

Adicionar ao final de `apps/backend/src/oplenario/sessoes/wire/out.clj`:

```clojure
(def PresencaResumoOut
  "Presenca agregada do tenant (§16.11, FE Onda A1 — card 'o que a Casa entregou'). `media-percentual`
  nil quando nao ha sessao encerrada ainda (0/0 e' indefinido, o FE NAO mostra '0%')."
  [:map {:closed true}
   [:media-percentual [:maybe :int]]
   [:sessoes-consideradas :int]
   [:membros-da-casa :int]])
```

- [ ] **Step 6: `adapters/out` — projetar e validar**

Adicionar ao final de `apps/backend/src/oplenario/sessoes/adapters/out/presenca.clj`:

```clojure
(defn resumo-presenca->wire
  "Resumo cru (kebab, do db) -> PresencaResumoOut (validado)."
  [{:keys [media-percentual sessoes-consideradas membros-da-casa]}]
  (let [out {:media-percentual media-percentual :sessoes-consideradas sessoes-consideradas
             :membros-da-casa membros-da-casa}]
    (when-not (m/validate wire/PresencaResumoOut out)
      (throw (ex-info "resumo de presenca viola o contrato PresencaResumoOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/PresencaResumoOut out))})))
    out))
```

- [ ] **Step 7: Teste unit do adapter (falha → sucesso)**

```clojure
(ns oplenario.sessoes.presenca-resumo-adapters-test
  "UNIT (puro) — sessoes/adapters/out/presenca: resumo-presenca->wire."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.sessoes.adapters.out.presenca :as adapters]
            [oplenario.sessoes.wire.out :as wire]))

(deftest resumo-presenca-valida-contrato
  (let [out (adapters/resumo-presenca->wire {:media-percentual 78 :sessoes-consideradas 10 :membros-da-casa 43})]
    (is (m/validate wire/PresencaResumoOut out))
    (is (= 78 (:media-percentual out)))))

(deftest resumo-presenca-aceita-media-nil
  (let [out (adapters/resumo-presenca->wire {:media-percentual nil :sessoes-consideradas 0 :membros-da-casa 43})]
    (is (m/validate wire/PresencaResumoOut out))
    (is (nil? (:media-percentual out)))))
```

Run: `cd apps/backend && clojure -M:test -n oplenario.sessoes.presenca-resumo-adapters-test` → PASS

- [ ] **Step 8: `RepoSessoes` — expor o método**

Em `apps/backend/src/oplenario/sessoes/components/repositorio.clj`, adicionar ao `defprotocol` (após
`listar-presenca`) e ao `defrecord`:

```clojure
  (resumo-presenca [this ente-id membros-da-casa]
    "Presenca agregada (F7/FE Onda A1) das ultimas 10 sessoes encerradas do tenant.")
```

```clojure
  (resumo-presenca [this ente-id membros-da-casa]
    (transacao this ente-id #(presenca/resumo-presenca % ente-id membros-da-casa 10)))
```

- [ ] **Step 9: `diplomat/http/in` — fn de composição in-process (mirror `painel-wire`)**

Adicionar ao final de `apps/backend/src/oplenario/sessoes/diplomat/http/in.clj` (confira o require de
`adapters-out-presenca` já existente no topo; ajuste o alias se divergir):

```clojure
(defn presenca-resumo-wire
  "Ponto de entrada IN-PROCESS da presenca agregada (FE Onda A1) — o gemeo nao-HTTP p/ a RAIZ DE COMPOSICAO
  (o host) compor o dashboard da Mesa do modulo `paineis`. Passa pelo MESMO gate adapters/out (projeta+valida)
  que uma rota HTTP teria. `membros-da-casa` chega JA RESOLVIDO pelo host (inversao de dependencia sobre
  `cadastros` — `sessoes` nunca importa `cadastros`, §22.10)."
  [repo-sessoes membros-da-casa ente-id]
  (adapters-out-presenca/resumo-presenca->wire
   (repo-sessoes-comp/resumo-presenca repo-sessoes ente-id (membros-da-casa ente-id))))
```

> Ajuste o require/alias de `oplenario.sessoes.components.repositorio` (provavelmente já importado como
> algo como `repositorio`/`repo` no topo do arquivo — use o alias já existente, não crie um segundo).

- [ ] **Step 10: Host — `sistema.clj` injeta `:repo-cadastros` no servidor HTTP**

Em `apps/backend/src/oplenario/sistema.clj`, no `using` de `:servidor-http` (função `sistema-serve`),
adicionar `:repo-cadastros` à lista:

```clojure
         :servidor-http (component/using
                         (http-servidor/servidor-http config rotas/montar)
                         [:idp :repo-identidade :repo-sessoes :repo-legislativo :repo-compliance
                          :repo-participacao :repo-transparencia :repo-paineis :repo-cadastros
                          :canal-store :objeto-store])))
```

- [ ] **Step 11: Host — `rotas.clj` injeta `membros-da-casa` e monta `presenca-resumo`**

Em `apps/backend/src/oplenario/rotas.clj`, adicionar `repo-cadastros` à destructuração de `montar`, um
require de `oplenario.cadastros.components.repositorio`, o closure `membros-da-casa` (mirror
`consultar-sessao`), e passar `presenca-resumo` ao fragmento de `paineis` (a chave só existe de fato depois
da Task A6 — por ora, adicione o closure aqui e deixe-o pronto para uso):

```clojure
(:require [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
          ...)
```

```clojure
  [{:keys [idp repo-identidade repo-sessoes repo-legislativo repo-compliance repo-participacao
           repo-transparencia repo-paineis repo-cadastros canal-store objeto-store painel-compliance]}]
  (let [auth (it/autenticacao idp repo-identidade)
        relogio-participacao (tempo/relogio-sistema)
        consultar-sessao (fn [ente-id sessao-id] (repo-sessoes-comp/buscar-sessao repo-sessoes ente-id sessao-id))
        ;; FE Onda A1: membros-da-casa injetado em sessoes (presenca agregada) — mesma inversao de
        ;; dependencia de consultar-sessao/painel-compliance; ZoneId fixo (fuso civil, mesmo racional de
        ;; participacao/controllers.clj).
        membros-da-casa (fn [ente-id]
                          (repo-cadastros-comp/membros-da-casa repo-cadastros ente-id
                                                                (tempo/hoje (tempo/relogio-sistema)
                                                                            (java.time.ZoneId/of "America/Fortaleza"))))
        presenca-resumo (fn [ente-id] (sessoes-http/presenca-resumo-wire repo-sessoes membros-da-casa ente-id))
        painel-compliance (or painel-compliance
                              (fn [ente-id] (compliance-http/painel-wire repo-compliance ente-id)))]
```

- [ ] **Step 12: Rodar a suíte completa e confirmar verde**

Run: `cd apps/backend && clojure -M:test`
Expected: PASS — nenhuma regressão nas rotas existentes (a assinatura de `montar` ganhou chaves novas,
todas opcionais via destructuração; testes antigos que não passam `repo-cadastros` seguem funcionando pois
`membros-da-casa`/`presenca-resumo` só são chamados quando `paineis-http/rotas` os usar, o que só acontece
na Task A6).

- [ ] **Step 13: Commit**

```bash
git add apps/backend/src/oplenario/sessoes/ apps/backend/src/oplenario/sistema.clj apps/backend/src/oplenario/rotas.clj \
        apps/backend/test/integration/oplenario/sessoes/presenca_db_test.clj \
        apps/backend/test/unit/oplenario/sessoes/presenca_resumo_adapters_test.clj
git commit -m "feat(sessoes): presença agregada (read-model barato, FE Onda A1) + host injeta membros-da-casa"
```

---

## Task A3: `participacao` — e-SIC cumprimento no prazo

**Files:**
- Modify: `apps/backend/src/oplenario/participacao/db/prazo_ativo.clj`
- Create: `apps/backend/src/oplenario/participacao/wire/out/esic_cumprimento.clj`
- Create: `apps/backend/src/oplenario/participacao/adapters/out/esic_cumprimento.clj`
- Modify: `apps/backend/src/oplenario/participacao/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/participacao/diplomat/http/in.clj`
- Test: `apps/backend/test/integration/oplenario/participacao/prazo_ativo_db_test.clj` (adiciona caso)
- Test: `apps/backend/test/unit/oplenario/participacao/esic_cumprimento_adapters_test.clj`

**Interfaces:**
- Produces: `participacao.diplomat.http.in/esic-cumprimento-wire [repo-participacao ente-id]` →
  `EsicCumprimentoOut`. Consumido pela Task A6.

- [ ] **Step 1: Escrever o teste de integração da query (falha)**

Adicionar ao final de `apps/backend/test/integration/oplenario/participacao/prazo_ativo_db_test.clj`:

```clojure
(deftest esic-cumprimento-conta-no-prazo-vs-total
  (let [ente (random-uuid)]
    ;; cumprida NO PRAZO (cumprida_em <= vence_em)
    (db/inserir! *ds* {:id (random-uuid) :ente-id ente :objeto-tipo "pedido_esic" :objeto-id (random-uuid)
                       :vence-em (LocalDate/of 2026 7 20) :estado "pendente"})
    (db/cumprir! *ds* {:ente-id ente :objeto-tipo "pedido_esic"
                       :objeto-id (:objeto-id (last (db/pendentes-vencidas-ate *ds* ente (LocalDate/of 2026 8 1))))
                       :cumprida-em (LocalDate/of 2026 7 18)})
    ;; um 2o pedido: cumprida FORA do prazo
    (let [id2 (random-uuid)]
      (db/inserir! *ds* {:id (random-uuid) :ente-id ente :objeto-tipo "pedido_esic" :objeto-id id2
                         :vence-em (LocalDate/of 2026 7 10) :estado "pendente"})
      (db/cumprir! *ds* {:ente-id ente :objeto-tipo "pedido_esic" :objeto-id id2 :cumprida-em (LocalDate/of 2026 7 15)}))
    ;; um 3o: outro objeto-tipo (recurso_esic) NAO deve contar (metrica e' so' pedido_esic)
    (db/inserir! *ds* {:id (random-uuid) :ente-id ente :objeto-tipo "recurso_esic" :objeto-id (random-uuid)
                       :vence-em (LocalDate/of 2026 7 20) :estado "pendente"})
    (let [r (db/esic-cumprimento *ds* ente)]
      (is (= 2 (:total-encerrados r)) "so' os 2 pedido_esic cumpridos contam (o pendente e' aberto)")
      (is (= 1 (:cumpridos-no-prazo r))))))
```

> Confira o require de `[java.time LocalDate]` e o alias `db` no topo do arquivo (provavelmente já existe
> como `oplenario.participacao.db.prazo-ativo`). Ajuste a chamada de `cumprir!` se a assinatura pedir outro
> shape de argumento — o teste real deve buscar o `id` inserido em vez de "last" heurístico; substitua por
> capturar o retorno de `inserir!` (`{:keys [id]} (db/inserir! ...)`) e usar esse `id` diretamente no
> `cumprir!` (mais robusto que reconsultar via `pendentes-vencidas-ate`).

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/backend && clojure -M:test -n oplenario.participacao.prazo-ativo-db-test`
Expected: FAIL — `esic-cumprimento` não existe

- [ ] **Step 3: Implementar a query em `db/prazo_ativo.clj`**

Adicionar ao final de `apps/backend/src/oplenario/participacao/db/prazo_ativo.clj`:

```clojure
;; ---- e-SIC cumprimento no prazo (read-model barato, FE Onda A1 §16.11) ----

(defn esic-cumprimento
  "Cumprimento de prazo do e-SIC (§16.11 'o que a Casa entregou'): dos pedidos JA ENCERRADOS
  (cumprida|vencida — pendente ainda esta' aberto, nao entra no historico), quantos foram cumpridos
  DENTRO do prazo (cumprida_em <= vence_em). So' `objeto_tipo`='pedido_esic' (a metrica institucional
  e' especificamente sobre o e-SIC, LAI; LGPD/ouvidoria tem prazos proprios sem essa cobranca legal
  identica). `vencida` conta no total-encerrados mas NUNCA no prazo (por definicao)."
  [tx ente-id]
  (let [linha (jdbc/execute-one! tx
                (sql/format {:select [[[:count :*] :total]
                                       [[:count [:case [:and [:= :estado [:inline "cumprida"]]
                                                        [:<= :cumprida_em :vence_em]] 1]] :no-prazo]]
                             :from [:participacao.prazo_ativo]
                             :where [:and [:= :ente_id ente-id] [:= :objeto_tipo [:inline "pedido_esic"]]
                                     [:in :estado [[:inline "cumprida"] [:inline "vencida"]]]]}))]
    {:total-encerrados (:total linha) :cumpridos-no-prazo (:no-prazo linha)}))
```

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/backend && clojure -M:test -n oplenario.participacao.prazo-ativo-db-test`
Expected: PASS

- [ ] **Step 5: `wire/out` — `EsicCumprimentoOut`**

```clojure
(ns oplenario.participacao.wire.out.esic-cumprimento
  "Representacao EXTERNA de SAIDA do cumprimento e-SIC (§22.10 wire/out, ADR-0001, FE Onda A1).")

(def EsicCumprimentoOut
  "Cumprimento de prazo do e-SIC (§16.11 'o que a Casa entregou'). `percentual` nil quando nao ha
  pedido encerrado ainda (0/0 indefinido)."
  [:map {:closed true}
   [:total-encerrados :int]
   [:cumpridos-no-prazo :int]
   [:percentual [:maybe :int]]])
```

- [ ] **Step 6: `adapters/out`**

```clojure
(ns oplenario.participacao.adapters.out.esic-cumprimento
  "Gate de SAIDA `models -> wire/out` do cumprimento e-SIC (§22.10 adapters/out, ADR-0001, FE Onda A1)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.esic-cumprimento :as wire]))

(set! *warn-on-reflection* true)

(defn esic-cumprimento->wire
  "{:total-encerrados :cumpridos-no-prazo} (cru) -> EsicCumprimentoOut (validado). `percentual` DERIVADO
  aqui (arredondado), nil se total-encerrados=0."
  [{:keys [total-encerrados cumpridos-no-prazo]}]
  (let [out {:total-encerrados total-encerrados
             :cumpridos-no-prazo cumpridos-no-prazo
             :percentual (when (pos? total-encerrados)
                          (int (Math/round (* 100.0 (/ cumpridos-no-prazo total-encerrados)))))}]
    (when-not (m/validate wire/EsicCumprimentoOut out)
      (throw (ex-info "cumprimento e-SIC viola o contrato EsicCumprimentoOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/EsicCumprimentoOut out))})))
    out))
```

- [ ] **Step 7: Teste unit do adapter**

```clojure
(ns oplenario.participacao.esic-cumprimento-adapters-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.participacao.adapters.out.esic-cumprimento :as adapters]
            [oplenario.participacao.wire.out.esic-cumprimento :as wire]))

(deftest esic-cumprimento-deriva-percentual
  (let [out (adapters/esic-cumprimento->wire {:total-encerrados 49 :cumpridos-no-prazo 47})]
    (is (m/validate wire/EsicCumprimentoOut out))
    (is (= 96 (:percentual out)))))

(deftest esic-cumprimento-zero-encerrados-percentual-nil
  (let [out (adapters/esic-cumprimento->wire {:total-encerrados 0 :cumpridos-no-prazo 0})]
    (is (m/validate wire/EsicCumprimentoOut out))
    (is (nil? (:percentual out)))))
```

Run: `cd apps/backend && clojure -M:test -n oplenario.participacao.esic-cumprimento-adapters-test` → PASS

- [ ] **Step 8: `RepoParticipacao` — expor o método**

Em `apps/backend/src/oplenario/participacao/components/repositorio.clj`, adicionar ao `defprotocol` e ao
`defrecord` (mesmo padrão `transacao`/`db/prazo-ativo` já usado nos métodos vizinhos):

```clojure
  (esic-cumprimento [this ente-id] "Cumprimento de prazo do e-SIC (FE Onda A1, §16.11).")
```

```clojure
  (esic-cumprimento [this ente-id] (transacao this ente-id #(prazo-ativo/esic-cumprimento % ente-id)))
```

- [ ] **Step 9: `diplomat/http/in` — fn de composição in-process**

Adicionar ao final de `apps/backend/src/oplenario/participacao/diplomat/http/in.clj`:

```clojure
(defn esic-cumprimento-wire
  "Ponto de entrada IN-PROCESS do cumprimento e-SIC (FE Onda A1) — gemeo nao-HTTP p/ o host compor o
  dashboard da Mesa (mirror painel-wire de compliance)."
  [repo-participacao ente-id]
  (adapters-out-esic/esic-cumprimento->wire (repo-participacao-comp/esic-cumprimento repo-participacao ente-id)))
```

> Adicione os requires `[oplenario.participacao.adapters.out.esic-cumprimento :as adapters-out-esic]` e
> `[oplenario.participacao.components.repositorio :as repo-participacao-comp]` no topo (reusar o alias já
> existente para o Repo, se houver — não duplique).

- [ ] **Step 10: Host — `rotas.clj` monta `esic-cumprimento`**

Em `apps/backend/src/oplenario/rotas.clj`, adicionar ao `let` de `montar` (junto dos outros closures):

```clojure
        esic-cumprimento (fn [ente-id] (participacao-http/esic-cumprimento-wire repo-participacao ente-id))
```

- [ ] **Step 11: Rodar a suíte completa**

Run: `cd apps/backend && clojure -M:test`
Expected: PASS, sem regressão.

- [ ] **Step 12: Commit**

```bash
git add apps/backend/src/oplenario/participacao/ apps/backend/src/oplenario/rotas.clj \
        apps/backend/test/integration/oplenario/participacao/prazo_ativo_db_test.clj \
        apps/backend/test/unit/oplenario/participacao/esic_cumprimento_adapters_test.clj
git commit -m "feat(participacao): cumprimento de prazo do e-SIC (read-model barato, FE Onda A1)"
```

---

## Task A4: `legislativo` — fila de relatores pendentes

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/db/parecer.clj`
- Create: `apps/backend/src/oplenario/legislativo/wire/out/relator_pendente.clj`
- Create: `apps/backend/src/oplenario/legislativo/adapters/out/relator_pendente.clj`
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/parecer_db_test.clj` (adiciona caso)
- Test: `apps/backend/test/unit/oplenario/legislativo/relator_pendente_adapters_test.clj`

**Interfaces:**
- Produces: `legislativo.diplomat.http.in/relatores-pendentes-wire [repo-legislativo ente-id]` →
  `RelatoresPendentesOut` (`{:itens [RelatorPendenteOut...]}`). Consumido pela Task A6.

- [ ] **Step 1: Escrever o teste de integração da query (falha)**

Adicionar ao final de `apps/backend/test/integration/oplenario/legislativo/parecer_db_test.clj`:

```clojure
(deftest relatores-pendentes-lista-pareceres-aguardando-designacao
  (let [ente (random-uuid)
        prop (db-prop/protocolar! *ds* {:id (random-uuid) :ente-id ente :tipo "pl" :ano 2026
                                        :uf "CE" :municipio-nome "Fortaleza" :ementa "Arborização viária"
                                        :autor-tipo "vereador" :autor-texto "Fulano" :created-by (random-uuid)})
        tmpl (random-uuid)]
    ;; template_tramitacao precisa existir com estado_inicial='aguardando_designacao' e sujeito='parecer'
    ;; — semeie via a fixture/helper já usada nos outros testes deste arquivo (ex.: `criar-template!`).
    (db-parecer/criar! *ds* {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id (:id prop)
                             :comissao-id (random-uuid) :relator-id nil :voto-relator nil :template-id tmpl
                             :created-by (random-uuid)})
    (let [itens (db-parecer/relatores-pendentes *ds* ente 50)]
      (is (= 1 (count itens)))
      (is (= (:id prop) (:proposicao-id (first itens))))
      (is (= "Arborização viária" (:ementa (first itens)))))))
```

> Reaproveite o helper de criação de `template_tramitacao` já usado nos testes vizinhos deste arquivo
> (procure por `criar-template!`/`template-parecer` em `parecer_db_test.clj` ou `parecer_tramitacao_db_test.clj`
> antes de escrever este teste — não reinvente o fixture).

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.parecer-db-test`
Expected: FAIL — `relatores-pendentes` não existe

- [ ] **Step 3: Implementar a query em `db/parecer.clj`**

Adicionar ao final de `apps/backend/src/oplenario/legislativo/db/parecer.clj`:

```clojure
;; ---- fila de relatores pendentes (read-model barato, FE Onda A1 §16.11) ----

(def ^:private teto-relatores-pendentes 50)

(defn relatores-pendentes
  "Pareceres 'aguardando_designacao' (a designacao de relator ainda nao aconteceu — designar-relator!
  transiciona daqui p/ 'com_relator'), join com a proposicao p/ mostrar ementa/urn-lex (o objeto e'
  SEMPRE 'proposicao' nesta fatia — emenda fica fora, YAGNI). Mais antigo primeiro (fila FIFO)."
  [tx ente-id teto]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:pc.id [:pc.objeto_id :proposicao_id] :p.tipo :p.ano :p.sequencial
                           :p.urn_lex :p.ementa :pc.criado_em]
                  :from [[:legislativo.pareceres :pc]]
                  :join [[:legislativo.proposicoes :p]
                         [:and [:= :p.id :pc.objeto_id] [:= :p.ente_id :pc.ente_id]]]
                  :where [:and [:= :pc.ente_id ente-id] [:= :pc.objeto_tipo [:inline "proposicao"]]
                          [:= :pc.estado [:inline "aguardando_designacao"]]]
                  :order-by [[:pc.criado_em :asc]]
                  :limit teto}))))
```

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.parecer-db-test`
Expected: PASS

- [ ] **Step 5: `wire/out` — `RelatorPendenteOut`/`RelatoresPendentesOut`**

```clojure
(ns oplenario.legislativo.wire.out.relator-pendente
  "Representacao EXTERNA de SAIDA da fila de relatores pendentes (§22.10 wire/out, ADR-0001, FE Onda A1)."
  (:require [oplenario.kernel.malli :as km]))

(def RelatorPendenteOut
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:criado-em :string]])

(def RelatoresPendentesOut
  "A fila 'designar relator' (§16.11 'o que só a Mesa despacha'): pareceres aguardando designação,
  mais antigo primeiro."
  [:map {:closed true}
   [:itens [:sequential RelatorPendenteOut]]])
```

> `km` fica sem uso neste arquivo (nenhum enum aqui) — remova o require se o linter reclamar, ou troque por
> `[:map {:closed true} ...]` puro sem o alias.

- [ ] **Step 6: `adapters/out`**

```clojure
(ns oplenario.legislativo.adapters.out.relator-pendente
  "Gate de SAIDA `models -> wire/out` da fila de relatores pendentes (§22.10 adapters/out, ADR-0001,
  FE Onda A1)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.relator-pendente :as wire]))

(set! *warn-on-reflection* true)

(defn- item->wire [{:keys [id proposicao-id tipo ano sequencial urn-lex ementa criado-em]}]
  {:id (str id) :proposicao-id (str proposicao-id) :tipo tipo :ano ano :sequencial sequencial
   :urn-lex urn-lex :ementa ementa :criado-em (str criado-em)})

(defn relatores-pendentes->wire
  "Linhas cruas (kebab, do db) -> RelatoresPendentesOut (validado)."
  [linhas]
  (let [out {:itens (mapv item->wire linhas)}]
    (when-not (m/validate wire/RelatoresPendentesOut out)
      (throw (ex-info "fila de relatores pendentes viola o contrato RelatoresPendentesOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/RelatoresPendentesOut out))})))
    out))
```

- [ ] **Step 7: Teste unit do adapter**

```clojure
(ns oplenario.legislativo.relator-pendente-adapters-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.relator-pendente :as adapters]
            [oplenario.legislativo.wire.out.relator-pendente :as wire]))

(deftest relatores-pendentes-projeta-e-valida
  (let [linhas [{:id (random-uuid) :proposicao-id (random-uuid) :tipo "pl" :ano 2026 :sequencial 51
                 :urn-lex "urn:lex:..." :ementa "Arborização viária"
                 :criado-em (java.time.Instant/parse "2026-07-01T12:00:00Z")}]
        out (adapters/relatores-pendentes->wire linhas)]
    (is (m/validate wire/RelatoresPendentesOut out))
    (is (= 1 (count (:itens out))))
    (is (string? (:proposicao-id (first (:itens out)))))))

(deftest relatores-pendentes-vazio
  (is (= {:itens []} (adapters/relatores-pendentes->wire []))))
```

Run: `cd apps/backend && clojure -M:test -n oplenario.legislativo.relator-pendente-adapters-test` → PASS

- [ ] **Step 8: `RepoLegislativo` — expor o método**

Em `apps/backend/src/oplenario/legislativo/components/repositorio.clj`, adicionar ao `defprotocol` e ao
`defrecord`:

```clojure
  (relatores-pendentes [this ente-id] "Fila de pareceres aguardando designacao de relator (FE Onda A1).")
```

```clojure
  (relatores-pendentes [this ente-id] (transacao this ente-id #(db-parecer/relatores-pendentes % ente-id 50)))
```

> Confira o alias usado para `legislativo.db.parecer` neste arquivo (`db-parecer` ou outro) e reuse-o.

- [ ] **Step 9: `diplomat/http/in` — fn de composição in-process**

Adicionar ao final de `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`:

```clojure
(defn relatores-pendentes-wire
  "Ponto de entrada IN-PROCESS da fila de relatores pendentes (FE Onda A1) — gemeo nao-HTTP p/ o host
  compor o dashboard da Mesa (mirror painel-wire de compliance)."
  [repo-legislativo ente-id]
  (adapters-out-relator/relatores-pendentes->wire (controllers/relatores-pendentes repo-legislativo ente-id)))
```

> Se `legislativo/controllers.clj` ainda não tiver uma fn `relatores-pendentes` que delega ao Repo (mirror
> `paineis.controllers/dashboard-mesa`), adicione-a lá primeiro:
> `(defn relatores-pendentes [repo-legislativo ente-id] (repo/relatores-pendentes repo-legislativo ente-id))`
> — e adicione o require de `adapters-out-relator` (`oplenario.legislativo.adapters.out.relator-pendente`)
> no topo de `diplomat/http/in.clj`.

- [ ] **Step 10: Host — `rotas.clj` monta `relatores-pendentes`**

```clojure
        relatores-pendentes (fn [ente-id] (legislativo-http/relatores-pendentes-wire repo-legislativo ente-id))
```

- [ ] **Step 11: Rodar a suíte completa**

Run: `cd apps/backend && clojure -M:test`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/ apps/backend/src/oplenario/rotas.clj \
        apps/backend/test/integration/oplenario/legislativo/parecer_db_test.clj \
        apps/backend/test/unit/oplenario/legislativo/relator_pendente_adapters_test.clj
git commit -m "feat(legislativo): fila de relatores pendentes (read-model barato, FE Onda A1)"
```

---

## Task A5: `paineis` — estender `MesaOut` com os 3 cards novos

**Files:**
- Modify: `apps/backend/src/oplenario/paineis/wire/out/mesa.clj`
- Modify: `apps/backend/src/oplenario/paineis/adapters/out/mesa.clj`
- Modify: `apps/backend/test/unit/oplenario/paineis/mesa_adapters_test.clj`

**Interfaces:**
- Consumes: os 3 cards já vêm PRONTOS (wire, opacos) de fora — este task só embute e valida, não chama os
  fns injetados (isso é a Task A6).
- Produces: `MesaOut` com `:presenca-resumo`, `:esic-cumprimento`, `:relatores-pendentes`;
  `mesa->wire` ganha 3 parâmetros novos.

- [ ] **Step 1: Atualizar o teste unit (falha) — estender `mesa_adapters_test.clj`**

Editar `apps/backend/test/unit/oplenario/paineis/mesa_adapters_test.clj`: toda chamada a `mesa/mesa->wire`
ganha 3 argumentos novos. Substituir o topo do arquivo (após os requires) por:

```clojure
(def ^:private presenca-fake {:media-percentual 78 :sessoes-consideradas 10 :membros-da-casa 43})
(def ^:private esic-fake {:total-encerrados 49 :cumpridos-no-prazo 47 :percentual 96})
(def ^:private relatores-fake {:itens []})
```

E trocar TODA chamada `(mesa/mesa->wire (rollups-fake) card-compliance-fake)` por
`(mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)` em cada
`deftest` do arquivo (7 ocorrências). Adicionar ao final do arquivo:

```clojure
(deftest cards-novos-embutidos-opacos-verbatim
  (let [out (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (= presenca-fake (:presenca-resumo out)))
    (is (= esic-fake (:esic-cumprimento out)))
    (is (= relatores-fake (:relatores-pendentes out)))))

(deftest lacunas-so-lista-o-que-genuinamente-falta
  (let [{:keys [lacunas]} (mesa/mesa->wire (rollups-fake) card-compliance-fake presenca-fake esic-fake relatores-fake)]
    (is (= #{"ciencia_convocacao" "assinatura_autografo" "incidente_grant_lgpd"} (set lacunas))
        "presenca_agregada/engajamento_cidadao SAIRAM da lista (agora materializados)")))
```

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/backend && clojure -M:test -n oplenario.paineis.mesa-adapters-test`
Expected: FAIL — arity mismatch em `mesa->wire`

- [ ] **Step 3: `wire/out/mesa.clj` — 3 chaves novas + `lacunas` atualizada**

Editar `apps/backend/src/oplenario/paineis/wire/out/mesa.clj`: adicionar 3 `def`s antes de `MesaOut` e
estender o `[:map ...]`:

```clojure
(def PresencaResumoOut
  "Espelha oplenario.sessoes.wire.out/PresencaResumoOut — reexportado aqui p/ o codegen gerar o campo
  tipado (paineis nao importa sessoes; a IGUALDADE ESTRUTURAL do schema e' o que o codegen casa por
  referencia, nao um import Clojure)."
  [:map {:closed true}
   [:media-percentual [:maybe :int]]
   [:sessoes-consideradas :int]
   [:membros-da-casa :int]])

(def EsicCumprimentoOut
  "Espelha oplenario.participacao.wire.out.esic-cumprimento/EsicCumprimentoOut."
  [:map {:closed true}
   [:total-encerrados :int]
   [:cumpridos-no-prazo :int]
   [:percentual [:maybe :int]]])

(def RelatorPendenteOut
  "Espelha oplenario.legislativo.wire.out.relator-pendente/RelatorPendenteOut."
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:criado-em :string]])

(def RelatoresPendentesOut
  [:map {:closed true}
   [:itens [:sequential RelatorPendenteOut]]])
```

E trocar o `def MesaOut`:

```clojure
(def MesaOut
  "O dashboard institucional da Mesa (resposta de GET /paineis/mesa, §16.11 item 11.4): o card de compliance
  do TCE (opaco) + os tres rollups do paineis + os 3 cards novos da FE Onda A1 (presenca-resumo/
  esic-cumprimento/relatores-pendentes, cada um embutido opaco — mesmo racional de compliance-tce, cada
  fonte E' o dono, paineis nao redeclara/reprojeta) + `lacunas` (so' o que genuinamente falta ainda)."
  [:map {:closed true}
   [:compliance-tce :map]
   [:tramitacao TramitacaoResumoOut]
   [:pendencias PendenciasResumoOut]
   [:sessoes SessoesResumoOut]
   [:presenca-resumo PresencaResumoOut]
   [:esic-cumprimento EsicCumprimentoOut]
   [:relatores-pendentes RelatoresPendentesOut]
   [:lacunas [:sequential :string]]])
```

> Nota: os `def`s `PresencaResumoOut`/`EsicCumprimentoOut`/`RelatorPendenteOut` aqui são cópias estruturais
> (mesma forma Malli, sem import cross-módulo — §22.10) das versões-fonte de `sessoes`/`participacao`/
> `legislativo`. Isso é DUPLICAÇÃO DELIBERADA de forma (não de dado): cada schema tem UM dono que o
> valida na fonte; `paineis` só precisa da MESMA FORMA para o codegen conseguir referenciar por igualdade
> estrutural (Task A7). Se um dia divergirem, o teste de validação (`m/validate`) no `mesa->wire` pega o
> drift imediatamente (o card real, vindo do módulo dono, deixaria de bater com a cópia aqui).

- [ ] **Step 4: `adapters/out/mesa.clj` — compor os 3 cards + atualizar `lacunas`**

Editar `apps/backend/src/oplenario/paineis/adapters/out/mesa.clj`:

```clojure
(def ^:private lacunas-conhecidas
  "Facetas do dashboard institucional da Mesa (§16.11 item 11.4) que EXIGEM nova modelagem de dominio
  (nao materializaveis por query barata) — expostas honestamente p/ o FE rotular sem sugerir cobertura
  inexistente. presenca_agregada/engajamento_cidadao SAIRAM desta lista na FE Onda A1 (materializados)."
  ["ciencia_convocacao" "assinatura_autografo" "incidente_grant_lgpd"])
```

```clojure
(defn mesa->wire
  "Rollups internos + os 4 cards opacos (compliance/presenca/esic/relatores) -> MesaOut (validada)."
  [rollups compliance-card presenca-card esic-card relatores-card]
  (let [out {:compliance-tce compliance-card
             :tramitacao (tramitacao->wire (:tramitacao rollups))
             :pendencias (pendencias->wire (:pendencias rollups))
             :sessoes    (sessoes->wire (:sessoes rollups))
             :presenca-resumo presenca-card
             :esic-cumprimento esic-card
             :relatores-pendentes relatores-card
             :lacunas    lacunas-conhecidas}]
    (when-not (m/validate wire/MesaOut out)
      (throw (ex-info "projecao do dashboard da Mesa viola o contrato MesaOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MesaOut out))})))
    out))
```

- [ ] **Step 5: Rodar e confirmar sucesso**

Run: `cd apps/backend && clojure -M:test -n oplenario.paineis.mesa-adapters-test`
Expected: PASS (9 testes)

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/paineis/wire/out/mesa.clj apps/backend/src/oplenario/paineis/adapters/out/mesa.clj \
        apps/backend/test/unit/oplenario/paineis/mesa_adapters_test.clj
git commit -m "feat(paineis): MesaOut ganha presenca-resumo/esic-cumprimento/relatores-pendentes"
```

---

## Task A6: `paineis` — diplomat/http/in + host injeta os 3 fns novos

**Files:**
- Modify: `apps/backend/src/oplenario/paineis/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj`
- Modify: `apps/backend/test/integration/oplenario/paineis/mesa_http_in_test.clj`

**Interfaces:**
- Consumes: `presenca-resumo`, `esic-cumprimento`, `relatores-pendentes` (closures montados nas Tasks
  A2/A3/A4, Steps 11/10/10 de `rotas.clj`).

- [ ] **Step 1: Atualizar os testes de `mesa_http_in_test.clj` (falha)**

Editar `apps/backend/test/integration/oplenario/paineis/mesa_http_in_test.clj`: adicionar os 3 fakes e
passá-los em toda chamada a `service-fn`/`service-fn-default`. Adicionar após `card-compliance-fake`:

```clojure
(def ^:private presenca-fake {:media-percentual 78 :sessoes-consideradas 10 :membros-da-casa 43})
(def ^:private esic-fake {:total-encerrados 49 :cumpridos-no-prazo 47 :percentual 96})
(def ^:private relatores-fake {:itens []})
```

Atualizar `service-fn` para aceitar e passar os 3 novos closures:

```clojure
(defn- service-fn [papeis repo-p painel-compliance presenca-resumo esic-cumprimento relatores-pendentes]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p
                                   :painel-compliance painel-compliance
                                   :presenca-resumo presenca-resumo
                                   :esic-cumprimento esic-cumprimento
                                   :relatores-pendentes relatores-pendentes})
                    it/globais)
      ph/create-server ::ph/service-fn))
```

E em CADA `deftest` que chama `(service-fn #{"secretario"} ... painel-compliance)`, adicionar os 3 novos
argumentos (ex.: `(constantly presenca-fake) (constantly esic-fake) (constantly relatores-fake)`). Adicionar
ao final do arquivo:

```clojure
(deftest mesa-200-compoe-os-3-cards-novos
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) (constantly card-compliance-fake)
                                       (constantly presenca-fake) (constantly esic-fake) (constantly relatores-fake))
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 78 (get-in body [:presenca-resumo :media-percentual])))
    (is (= 96 (get-in body [:esic-cumprimento :percentual])))
    (is (= [] (get-in body [:relatores-pendentes :itens])))
    (is (not (contains? (set (:lacunas body)) "presenca_agregada")))))
```

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/backend && clojure -M:test -n oplenario.paineis.mesa-http-in-test`
Expected: FAIL — arity de `service-fn`/`montar` incompatível

- [ ] **Step 3: `diplomat/http/in.clj` — `mesa-handler` chama os 3 fns novas com degradação por card**

Editar `apps/backend/src/oplenario/paineis/diplomat/http/in.clj`:

```clojure
(def ^:private card-generico-indisponivel
  "Sentinela GENERICO de degradacao por card (mesmo racional de card-compliance-indisponivel, agora
  reusado pelos 3 cards novos — todos seguem a mesma disciplina de tolerancia)."
  {:indisponivel true})

(defn- card-seguro
  "Chama `f` (a fn injetada pelo host); em falha, loga e devolve o sentinel — NUNCA derruba a pagina
  inteira por causa de UM card cross-modulo fragil (mesmo racional do card de compliance)."
  [rotulo f ente-id]
  (try
    (f ente-id)
    (catch Throwable e
      (log/warn e (str "paineis: leitura de " rotulo " falhou no dashboard da Mesa — card degradado")
                {:ente-id ente-id})
      card-generico-indisponivel)))

(defn- mesa-handler
  [repo-paineis painel-compliance presenca-resumo esic-cumprimento relatores-pendentes]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          rollups (controllers/dashboard-mesa repo-paineis (:ator req))
          compliance-card (card-seguro "compliance" painel-compliance ente-id)
          presenca-card (card-seguro "presenca-resumo" presenca-resumo ente-id)
          esic-card (card-seguro "esic-cumprimento" esic-cumprimento ente-id)
          relatores-card (card-seguro "relatores-pendentes" relatores-pendentes ente-id)]
      (http/json-resposta 200 (adapters-out-mesa/mesa->wire rollups compliance-card
                                                            presenca-card esic-card relatores-card)))))
```

> Isto SUBSTITUI o `mesa-handler` existente (que só tinha `compliance-card` via `try/catch` inline) — remova
> o `card-compliance-indisponivel` antigo (renomeado para o genérico `card-generico-indisponivel` acima) e
> ajuste qualquer outra referência a ele no arquivo.

Atualizar `rotas`:

```clojure
(defn rotas
  [{:keys [auth repo-paineis painel-compliance presenca-resumo esic-cumprimento relatores-pendentes]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/paineis/pendencias" :get [auth papel (pendencias-handler repo-paineis)]
       :route-name :paineis/pendencias]
      ["/paineis/tramitacao" :get [auth papel (tramitacao-handler repo-paineis)]
       :route-name :paineis/tramitacao]
      ["/paineis/sli/sessoes" :get [auth papel (sli-sessoes-handler repo-paineis)]
       :route-name :paineis/sli-sessoes]
      ["/paineis/mesa" :get [auth papel (mesa-handler repo-paineis painel-compliance
                                                       presenca-resumo esic-cumprimento relatores-pendentes)]
       :route-name :paineis/mesa]}))
```

- [ ] **Step 4: Host — `rotas.clj` passa os 3 fns ao fragmento de `paineis`**

Editar a chamada de `paineis-http/rotas` em `apps/backend/src/oplenario/rotas.clj`:

```clojure
        (into (paineis-http/rotas {:auth auth :repo-paineis repo-paineis
                                   :painel-compliance painel-compliance
                                   :presenca-resumo presenca-resumo
                                   :esic-cumprimento esic-cumprimento
                                   :relatores-pendentes relatores-pendentes}))
```

- [ ] **Step 5: Rodar e confirmar sucesso**

Run: `cd apps/backend && clojure -M:test -n oplenario.paineis.mesa-http-in-test`
Expected: PASS (todos os testes do arquivo, incl. o novo)

- [ ] **Step 6: Rodar a suíte inteira + lint**

Run: `cd apps/backend && clojure -M:test && clj-kondo --lint src test`
Expected: 0 falhas, 0 erros de lint.

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/oplenario/paineis/diplomat/http/in.clj apps/backend/src/oplenario/rotas.clj \
        apps/backend/test/integration/oplenario/paineis/mesa_http_in_test.clj
git commit -m "feat(paineis): GET /paineis/mesa compõe os 3 cards novos com degradação por card"
```

---

## Task A7: Codegen Malli→TS — referência nomeada + `:sequential` + manifesto do MesaOut

**Files:**
- Modify: `apps/backend/src/oplenario/codegen/malli_ts.clj`
- Modify: `apps/backend/src/oplenario/codegen/gerar.clj`
- Modify: `apps/backend/test/unit/oplenario/codegen/malli_ts_test.clj`

**Interfaces:**
- Produces: `apps/frontend/src/lib/contrato-mesa.gen.ts` (arquivo gerado, consumido pela Task B4/B5).

- [ ] **Step 1: Escrever os testes novos do codegen (falha)**

Adicionar ao final de `apps/backend/test/unit/oplenario/codegen/malli_ts_test.clj`:

```clojure
(deftest referencia-nomeada-nao-inlina-record-generico
  ;; um :map ANINHADO cuja forma bate EXATAMENTE com um schema ja' nomeado no manifesto -> emite o NOME
  ;; da interface (referencia), nao "Record<string, unknown>".
  (let [interno [:map {:closed true} [:x :int]]
        externo [:map {:closed true} [:campo interno]]
        out (ts/interface-ts {interno "Interno"} "Externo" externo)]
    (is (str/includes? out "campo: Interno;") "schema nomeado no manifesto vira referencia de tipo")))

(deftest sequential-vira-array-ts
  (let [item [:map {:closed true} [:id :string]]
        pai  [:map {:closed true} [:itens [:sequential item]]]
        out (ts/interface-ts {item "Item"} "Pai" pai)]
    (is (str/includes? out "itens: Item[];"))))

(deftest interface-ts-2-aridade-preserva-comportamento-antigo
  ;; backward-compat: chamada sem o mapa de referencias segue inlinando maps aninhados como antes.
  (let [interno [:map {:closed true} [:x :int]]
        externo [:map {:closed true} [:campo interno]]]
    (is (str/includes? (ts/interface-ts "Externo" externo) "campo: Record<string, unknown>;"))))
```

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/backend && clojure -M:test -n oplenario.codegen.malli-ts-test`
Expected: FAIL — `interface-ts` 3-aridade não existe ainda; `:sequential` cai em `"unknown"`

- [ ] **Step 3: Implementar — reescrever `malli_ts.clj`**

Substituir o conteúdo de `apps/backend/src/oplenario/codegen/malli_ts.clj` a partir da linha 16 (mantendo
o `ns` e `camel`):

```clojure
(declare ts-tipo)

(defn- ts-enum [membros] (str/join " | " (map #(str \" % \") membros)))

(defn- ts-tipo
  "`nome-por-schema` = {schema-VALOR -> \"NomeDaInterface\"} (igualdade estrutural, nao identidade —
  schemas Malli sao dados literais). Um :map/[:map ...] cuja forma bate EXATAMENTE com uma entrada do
  manifesto emite o NOME (referencia de tipo), preservando a estrutura entre modulos sem reprojetar; o
  que nao bate cai em 'Record<string, unknown>' (mapa opaco, mesmo comportamento do 1o corte)."
  [nome-por-schema forma]
  (cond
    (keyword? forma) (case forma
                       (:uuid :string) "string"
                       :int "number"
                       (:double :number) "number"
                       :boolean "boolean"
                       :map "Record<string, unknown>"
                       "unknown")
    (vector? forma)
    (if-let [nome (get nome-por-schema forma)]
      nome
      (case (first forma)
        :re "string"
        :enum (ts-enum (rest forma))
        :maybe (str (ts-tipo nome-por-schema (second forma)) " | null")
        :sequential (str (ts-tipo nome-por-schema (second forma)) "[]")
        :fn "string"                              ; LocalDate/Instant -> ISO string
        :map "Record<string, unknown>"           ; map aninhado anonimo, sem entrada no manifesto
        "unknown"))
    :else "unknown"))

(defn- entradas-de
  "Pula o :map e o mapa de opts de nivel ([:map {:closed true} & entradas])."
  [[_map maybe-opts & resto]]
  (if (map? maybe-opts) resto (cons maybe-opts resto)))

(defn- campo-ts [nome-por-schema [k & r]]
  (let [opts   (when (map? (first r)) (first r))
        schema (if opts (second r) (first r))]
    (str "  " (camel k) (when (:optional opts) "?") ": " (ts-tipo nome-por-schema schema) ";")))

(defn interface-ts
  "Uma [:map ...] Malli -> `export interface <Nome> { ... }`. Aridade 2 (sem mapa de referencias) preserva
  o comportamento do 1o corte (tudo aninhado vira Record<string, unknown>); aridade 3 permite referencias
  nomeadas entre interfaces do MESMO manifesto (`gerar` monta o mapa automaticamente)."
  ([nome schema] (interface-ts {} nome schema))
  ([nome-por-schema nome schema]
   (str "export interface " nome " {\n"
        (str/join "\n" (map (partial campo-ts nome-por-schema) (entradas-de schema)))
        "\n}\n")))

(defn gerar
  "specs = mapa ordenado {\"Nome\" schema}. Devolve o conteudo .ts (banner + interfaces), com referencias
  nomeadas resolvidas por igualdade estrutural entre as entradas de `specs`."
  [specs]
  (let [nome-por-schema (into {} (map (fn [[nome schema]] [schema nome]) specs))]
    (str "// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.\n\n"
         (str/join "\n" (map (fn [[nome schema]] (interface-ts nome-por-schema nome schema)) specs)))))
```

- [ ] **Step 4: Rodar e confirmar sucesso dos testes novos**

Run: `cd apps/backend && clojure -M:test -n oplenario.codegen.malli-ts-test`
Expected: os 3 testes novos PASSAM; os testes antigos do arquivo (`interface-simples-uuid-e-string`,
`enum-vira-uniao-de-literais-ordenada`, `data-vira-string-e-optional-maybe`) continuam passando (2-aridade
inalterada). O teste `gerar-tudo-emite-todas-as-interfaces-com-banner` ainda não roda contra o manifesto
novo (isso é o próximo passo) — ele deve seguir verde neste ponto pois o manifesto de `gerar.clj` não mudou
ainda.

- [ ] **Step 5: Estender o manifesto em `gerar.clj`**

Editar `apps/backend/src/oplenario/codegen/gerar.clj`:

```clojure
(ns oplenario.codegen.gerar
  "Entrypoint do codegen Malli->TS (FE0 + FE Onda A1). Host-level: declara o MANIFESTO de models/wire a
  exportar e escreve o .ts. Roda via:
    clojure -M -m oplenario.codegen.gerar [caminho-de-saida]
  Default = target/generated-ts/oplenario-tipos.ts."
  (:require [clojure.java.io :as io]
            [oplenario.cadastros.models.cadastro :as cad]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.legislativo.wire.out.relator-pendente :as leg-wire]
            [oplenario.paineis.wire.out.mesa :as mesa-wire]
            [oplenario.participacao.wire.out.esic-cumprimento :as part-wire]
            [oplenario.sessoes.wire.out :as sess-wire]))

(def manifesto
  "Os tipos exportados como TS do front. Ordem deterministica; as entradas de MesaOut (Tramitacao/
  Pendencias/Sessoes/Presenca/Esic/RelatorPendente/RelatoresPendentes) vem ANTES de MesaOut no mapa p/ a
  referencia nomeada (Task codegen) casar por igualdade estrutural nos campos aninhados."
  [["Ente" cad/Ente]
   ["Legislatura" cad/Legislatura]
   ["Vereador" cad/Vereador]
   ["Mandato" cad/Mandato]
   ["Comissao" cad/Comissao]
   ["ComissaoMembro" cad/ComissaoMembro]
   ;; FE Onda A1 — dashboard da Mesa
   ["TramitacaoResumoOut" mesa-wire/TramitacaoResumoOut]
   ["PendenciasResumoOut" mesa-wire/PendenciasResumoOut]
   ["SessoesResumoOut" mesa-wire/SessoesResumoOut]
   ["PresencaResumoOut" sess-wire/PresencaResumoOut]
   ["EsicCumprimentoOut" part-wire/EsicCumprimentoOut]
   ["RelatorPendenteOut" leg-wire/RelatorPendenteOut]
   ["RelatoresPendentesOut" mesa-wire/RelatoresPendentesOut]
   ["MesaOut" mesa-wire/MesaOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/oplenario-tipos.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS gerados em" caminho "(" (count manifesto) "interfaces)")))
```

> Nota: `mesa-wire/PresencaResumoOut`/`EsicCumprimentoOut`/`RelatorPendenteOut` são as CÓPIAS estruturais em
> `paineis/wire/out/mesa.clj` (Task A5) — use ESSAS no manifesto (não as de `sessoes`/`participacao`/
> `legislativo`) para a referência nomeada bater exatamente com os campos aninhados de `MesaOut` (que
> literalmente contém essas cópias, não as originais — igualdade estrutural, não identidade de módulo).
> Ajuste os requires acima para `oplenario.paineis.wire.out.mesa` como fonte de todos os 4 tipos
> auxiliares + `MesaOut`, e REMOVA os requires de `sessoes.wire.out`/`participacao.wire.out.esic-cumprimento`/
> `legislativo.wire.out.relator-pendente` deste arquivo (não são necessários se tudo vem da cópia em `mesa.clj`).

- [ ] **Step 6: Corrigir a asserção pré-existente que quebraria com o novo manifesto**

Em `apps/backend/test/unit/oplenario/codegen/malli_ts_test.clj`, a assertão
`(is (not (str/includes? out "unknown")) ...)` do teste `gerar-tudo-emite-todas-as-interfaces-com-banner`
vai falhar com o manifesto novo — `MesaOut.compliance-tce` é `:map` (mapa opaco de propósito), que agora
emite `"Record<string, unknown>"`. A intenção original do teste era "nenhum campo caiu no FALLBACK bare
`unknown`" (falha de cobertura do codegen), não "a substring 'unknown' nunca aparece" — o teste estava
escrito de forma ampla demais. Trocar essa linha por:

```clojure
    (is (not (re-find #": unknown;" out)) "nenhum campo caiu no fallback bare 'unknown' (Record<string, unknown> é tipo válido, não fallback)")
```

E atualizar a lista de interfaces esperadas no mesmo teste para incluir as novas:

```clojure
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["Ente" "Legislatura" "Vereador" "Mandato" "Comissao" "ComissaoMembro"
                 "TramitacaoResumoOut" "PendenciasResumoOut" "SessoesResumoOut" "PresencaResumoOut"
                 "EsicCumprimentoOut" "RelatorPendenteOut" "RelatoresPendentesOut" "MesaOut"])
        "todas as interfaces do manifesto presentes")
```

Adicionar um teste novo garantindo que `MesaOut` referencia (não inlina) os tipos aninhados:

```clojure
(deftest mesa-out-referencia-os-tipos-aninhados-por-nome
  (let [out (gerar/gerar-tudo)]
    (is (str/includes? out "tramitacao: TramitacaoResumoOut;"))
    (is (str/includes? out "presencaResumo: PresencaResumoOut;"))
    (is (str/includes? out "esicCumprimento: EsicCumprimentoOut;"))
    (is (str/includes? out "relatoresPendentes: RelatoresPendentesOut;"))
    (is (str/includes? out "complianceTce: Record<string, unknown>;") "card opaco por design")))
```

- [ ] **Step 7: Rodar e confirmar sucesso**

Run: `cd apps/backend && clojure -M:test -n oplenario.codegen.malli-ts-test`
Expected: PASS (todos os testes do arquivo)

- [ ] **Step 8: Gerar o arquivo TS de fato, direto para o frontend**

Run: `cd apps/backend && clojure -M -m oplenario.codegen.gerar ../frontend/src/lib/contrato-mesa.gen.ts`
Expected: imprime `[oplenario] tipos TS gerados em ../frontend/src/lib/contrato-mesa.gen.ts ( 14 interfaces )`

- [ ] **Step 9: Rodar a suíte inteira + lint**

Run: `cd apps/backend && clojure -M:test && clj-kondo --lint src test`
Expected: 0 falhas, 0 erros.

- [ ] **Step 10: Commit**

```bash
git add apps/backend/src/oplenario/codegen/ apps/backend/test/unit/oplenario/codegen/malli_ts_test.clj \
        apps/frontend/src/lib/contrato-mesa.gen.ts
git commit -m "feat(codegen): referência nomeada + :sequential no malli-ts; gera contrato-mesa.gen.ts"
```

**PARTE A concluída aqui.** Antes de seguir para a PARTE B: rode `docker compose up -d --build` em
`apps/backend/` (reconstrói o `app` com as mudanças), confirme `GET /paineis/mesa` autenticado devolve as
3 chaves novas via curl manual, e considere abrir/mergear a branch backend (`ecc` review clojure+database+
security) antes de começar o frontend — a Parte B assume o contrato REAL já em `main` ou pelo menos rodando
localmente.

---

# PARTE B — Frontend (App Shell + Dashboard da Mesa)

## Task B1: `AuthContext` — extrair o padrão de token dev da página do plenário

**Files:**
- Create: `apps/frontend/src/lib/auth.tsx`
- Create: `apps/frontend/src/lib/auth.test.tsx`
- Modify: `apps/frontend/src/app/sessoes/[id]/plenario/page.tsx` (passa a consumir `useAuth`)

**Interfaces:**
- Produces: `AuthProvider` (componente), `useAuth(): { token: string | null }` — mesma regra de guard de
  produção já usada (`?token=` e `NEXT_PUBLIC_DEV_TOKEN` só fora de `NODE_ENV=production`).

- [ ] **Step 1: Escrever o teste (falha)**

```tsx
// apps/frontend/src/lib/auth.test.tsx
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { AuthProvider, useAuth } from "./auth";

function Sonda() {
  const { token } = useAuth();
  return <div data-testid="token">{token ?? "sem-token"}</div>;
}

describe("AuthProvider/useAuth", () => {
  const originalEnv = process.env.NODE_ENV;
  afterEach(() => {
    Object.defineProperty(process.env, "NODE_ENV", { value: originalEnv, configurable: true });
    vi.unstubAllEnvs();
  });

  it("lê o token da querystring fora de produção", () => {
    Object.defineProperty(process.env, "NODE_ENV", { value: "test", configurable: true });
    render(
      <AuthProvider tokenQuery='{"sub":"u"}'>
        <Sonda />
      </AuthProvider>
    );
    expect(screen.getByTestId("token").textContent).toBe('{"sub":"u"}');
  });

  it("bloqueia token via querystring em produção (lança)", () => {
    Object.defineProperty(process.env, "NODE_ENV", { value: "production", configurable: true });
    expect(() =>
      render(
        <AuthProvider tokenQuery='{"sub":"u"}'>
          <Sonda />
        </AuthProvider>
      )
    ).toThrow(/produção/);
  });

  it("sem token nenhum -> null", () => {
    Object.defineProperty(process.env, "NODE_ENV", { value: "test", configurable: true });
    render(
      <AuthProvider tokenQuery={null}>
        <Sonda />
      </AuthProvider>
    );
    expect(screen.getByTestId("token").textContent).toBe("sem-token");
  });
});
```

> Este teste usa `@testing-library/react` — se ainda não estiver instalado, rode
> `cd apps/frontend && npm install -D @testing-library/react @testing-library/dom jsdom` e adicione
> `test: { environment: "jsdom" }` ao `vite.config.ts`/`vitest.config.ts` (crie um `vitest.config.ts` mínimo
> se o projeto hoje roda vitest só com config default via `vitest run`).

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/frontend && npm test -- auth.test.tsx`
Expected: FAIL — `./auth` não existe

- [ ] **Step 3: Implementar `auth.tsx`**

```tsx
"use client";

// AuthContext: extrai o padrão de token dev (?token=/NEXT_PUBLIC_DEV_TOKEN) que antes vivia inline em
// sessoes/[id]/plenario/page.tsx — App Shell (FE Onda A1) precisa do MESMO guard em qualquer página
// interna nova, não só no plenário. Em produção o token via querystring É PROIBIDO (authn real = sessão
// Keycloak, carry F1.4); o guard lança DEPOIS de todos os hooks (ordem estável).

import { createContext, useContext, type ReactNode } from "react";

const AuthCtx = createContext<{ token: string | null } | null>(null);

export function AuthProvider({
  children,
  tokenQuery,
}: {
  children: ReactNode;
  tokenQuery: string | null;
}) {
  const tokenInProd = process.env.NODE_ENV === "production" && !!tokenQuery;
  const token = tokenInProd
    ? null
    : tokenQuery ?? (process.env.NODE_ENV !== "production" ? process.env.NEXT_PUBLIC_DEV_TOKEN ?? null : null);

  if (tokenInProd) {
    throw new Error("token via querystring desabilitado em produção (authn = sessão Keycloak, carry F1.4).");
  }
  return <AuthCtx.Provider value={{ token }}>{children}</AuthCtx.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error("useAuth fora de AuthProvider");
  return ctx;
}
```

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/frontend && npm test -- auth.test.tsx`
Expected: PASS (3 testes)

- [ ] **Step 5: Migrar `sessoes/[id]/plenario/page.tsx` para consumir `useAuth`**

Em `apps/frontend/src/app/sessoes/[id]/plenario/page.tsx`, trocar a leitura inline de token (linhas 46-61,
o bloco `tokenQuery`/`tokenInProd`/`token`/o `throw`) por: envolver o retorno do componente com
`<AuthProvider tokenQuery={search.get("token")}>` e usar `useAuth().token` em vez da variável local `token`.
Isso é um refactor comportamento-preservando (mesmo guard, mesma lógica) — rode a suíte de testes do
plenário depois (`npm test -- plenario`) para confirmar zero regressão.

- [ ] **Step 6: Rodar toda a suíte FE**

Run: `cd apps/frontend && npm test && npm run lint && npm run build`
Expected: tudo verde.

- [ ] **Step 7: Commit**

```bash
git add apps/frontend/src/lib/auth.tsx apps/frontend/src/lib/auth.test.tsx \
        apps/frontend/src/app/sessoes/\[id\]/plenario/page.tsx
git commit -m "refactor(fe): extrai AuthContext do padrão de token dev (base do App Shell)"
```

---

## Task B2: App Shell interno — layout raiz autenticado

**Files:**
- Create: `apps/frontend/src/app/(interno)/layout.tsx`
- Create: `apps/frontend/src/app/(interno)/topo.tsx`
- Create: `apps/frontend/src/app/(interno)/topo.css`
- Create: `apps/frontend/src/app/(interno)/topo.test.tsx`

**Interfaces:**
- Produces: `<TopoInterno ator={...} />` (componente); o `layout.tsx` do grupo de rotas `(interno)` envolve
  qualquer página nova sob ele com `AuthProvider` + `TemaProvider` + a barra `.topo`.

- [ ] **Step 1: Escrever o teste do `Topo` (falha)**

```tsx
// apps/frontend/src/app/(interno)/topo.test.tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TopoInterno } from "./topo";

describe("TopoInterno", () => {
  it("mostra o rótulo da área e o nome do ator", () => {
    render(<TopoInterno area="Painéis da Mesa" ator={{ nome: "Sérgio Lopes", papel: "Presidente da Mesa" }} />);
    expect(screen.getByText("Painéis da Mesa")).toBeTruthy();
    expect(screen.getByText("Sérgio Lopes")).toBeTruthy();
    expect(screen.getByText("Presidente da Mesa")).toBeTruthy();
  });
});
```

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/frontend && npm test -- topo.test.tsx`
Expected: FAIL — `./topo` não existe

- [ ] **Step 3: Implementar `topo.tsx` (porta da `.topo` do chassi, seção institucional)**

```tsx
"use client";

// Barra institucional do App Shell interno (FE Onda A1) — porta ../sistema/chassi.css .topo (mesma
// marca+área-tag+tema-btn já usados em sessoes/[id]/plenario/page.tsx), agora compartilhada por QUALQUER
// página autenticada nova. `area` = o rótulo da seção atual (ex. "Painéis da Mesa"); `ator` = quem está
// logado (nome+papel — vem do JWT decodificado, injetado pelo caller).

import { useTema } from "@/lib/tema";

export function TopoInterno({ area, ator }: { area: string; ator: { nome: string; papel: string } }) {
  const { tema, alternar } = useTema();
  return (
    <header className="topo">
      <div className="envelope topo-grade">
        <div className="marca">
          <Brasao />
          <div>
            <p className="marca-nome">O&nbsp;Plenário</p>
            <p className="marca-orgao">Câmara Municipal</p>
          </div>
        </div>
        <div className="topo-sep" aria-hidden="true" />
        <span className="area-tag">{area}</span>
        <div className="topo-dir">
          <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
            {tema === "escuro" ? "☾" : "☀"}
            <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
          </button>
          <div className="quem-mesa">
            <span className="avatar" aria-hidden="true">
              {ator.nome.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase()}
            </span>
            <span className="quem">
              <b>{ator.nome}</b>
              <span>{ator.papel}</span>
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 40 40" role="img" aria-label="O Plenário">
      <circle cx="20" cy="20" r="19" fill="#FBF8F0" stroke="#E0D7BF" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#0C5340" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#1E5FA8" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#D9542B" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#E8B23A" />
    </svg>
  );
}
```

> `Brasao` duplica o SVG já presente em `sessoes/[id]/plenario/page.tsx` — deliberado (2ª ocorrência ainda
> não justifica extrair p/ um módulo compartilhado sozinha; promover ao chassi quando a 3ª tela precisar,
> mesma disciplina de `PADROES-DE-COMPOSICAO.md` do design-system). `.area-tag`/`.quem-mesa`/`.avatar` já
> existem em `chassi.css` (portado verbatim) — confirme que `apps/frontend/src/app/chassi.css` já cobre
> essas classes (foram vistas em `paineis-mesa.html`); se chassi.css do FE estiver desatualizado em relação
> à cópia mais recente do design-system, copie as regras que faltarem de
> `produto/design-system/o-plenario/sistema/chassi.css` (verbatim, mesma disciplina do FE.1).

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/frontend && npm test -- topo.test.tsx`
Expected: PASS

- [ ] **Step 5: Implementar o `layout.tsx` do grupo `(interno)`**

```tsx
// apps/frontend/src/app/(interno)/layout.tsx
// Layout raiz do App Shell interno (FE Onda A1) — qualquer página sob app/(interno)/ ganha AuthProvider +
// TemaProvider automaticamente. O grupo de rotas (interno) não aparece na URL (convenção Next.js App
// Router); /sessoes/[id]/plenario fica FORA deste grupo por enquanto (migrá-la é carry — spec §7).

import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

export default function LayoutInterno({
  children,
  searchParams,
}: {
  children: React.ReactNode;
  searchParams?: { token?: string };
}) {
  return (
    <AuthProvider tokenQuery={searchParams?.token ?? null}>
      <TemaProvider>{children}</TemaProvider>
    </AuthProvider>
  );
}
```

> Next.js 16 App Router: `layout.tsx` não recebe `searchParams` diretamente em todas as versões — se o
> build reclamar, mova a leitura do `?token=` para dentro de cada `page.tsx` do grupo via `useSearchParams()`
> (client component) e passe como prop ao invólucro, IGUAL ao padrão já usado em
> `sessoes/[id]/plenario/page.tsx`. Confirme com `npm run build` no Step 6 abaixo qual variante compila.

- [ ] **Step 6: Build de sanidade**

Run: `cd apps/frontend && npm run build`
Expected: build limpo (ajuste o Step 5 conforme a nota acima se houver erro de tipo em `searchParams`).

- [ ] **Step 7: Commit**

```bash
git add "apps/frontend/src/app/(interno)/"
git commit -m "feat(fe): App Shell interno — layout raiz autenticado + barra institucional compartilhada"
```

---

## Task B3: Primitivas de chart honestos (SVG hand-rolled)

**Files:**
- Create: `apps/frontend/src/lib/charts/anel-prazo.tsx`
- Create: `apps/frontend/src/lib/charts/barra-segmentada.tsx`
- Create: `apps/frontend/src/lib/charts/tabuleiro-estagios.tsx`
- Create: `apps/frontend/src/lib/charts/anel-prazo.test.tsx`
- Create: `apps/frontend/src/lib/charts/barra-segmentada.test.tsx`

**Interfaces:**
- Produces: `<AnelPrazo diasRestantes={n} diasTotal={n} rotulo={string} />`,
  `<BarraSegmentada segmentos={{rotulo,n,cor}[]} />`, `<TabuleiroEstagios estagios={{rotulo,n}[]} />`.

- [ ] **Step 1: Escrever os testes da geometria pura (falha)**

```tsx
// apps/frontend/src/lib/charts/anel-prazo.test.tsx
import { describe, expect, it } from "vitest";
import { arcoDashoffset } from "./anel-prazo";

describe("arcoDashoffset", () => {
  it("0 dias restantes de um total -> offset = 0 (arco cheio)", () => {
    expect(arcoDashoffset(0, 14, 40)).toBeCloseTo(0, 1);
  });
  it("todo o prazo restante -> offset = circunferencia (arco vazio)", () => {
    const circ = 2 * Math.PI * 40;
    expect(arcoDashoffset(14, 14, 40)).toBeCloseTo(circ, 1);
  });
  it("metade do prazo -> offset = metade da circunferencia", () => {
    const circ = 2 * Math.PI * 40;
    expect(arcoDashoffset(7, 14, 40)).toBeCloseTo(circ / 2, 1);
  });
  it("diasTotal 0 -> nao divide por zero (offset 0, trata como vencido/cheio)", () => {
    expect(arcoDashoffset(0, 0, 40)).toBe(0);
  });
});
```

```tsx
// apps/frontend/src/lib/charts/barra-segmentada.test.tsx
import { describe, expect, it } from "vitest";
import { largurasPercentuais } from "./barra-segmentada";

describe("largurasPercentuais", () => {
  it("distribui proporcional ao total", () => {
    const r = largurasPercentuais([{ rotulo: "a", n: 1 }, { rotulo: "b", n: 3 }]);
    expect(r[0].percentual).toBeCloseTo(25, 1);
    expect(r[1].percentual).toBeCloseTo(75, 1);
  });
  it("total 0 -> todos 0% (sem NaN)", () => {
    const r = largurasPercentuais([{ rotulo: "a", n: 0 }]);
    expect(r[0].percentual).toBe(0);
  });
});
```

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/frontend && npm test -- anel-prazo.test.tsx barra-segmentada.test.tsx`
Expected: FAIL — módulos não existem

- [ ] **Step 3: Implementar `anel-prazo.tsx`**

```tsx
// apps/frontend/src/lib/charts/anel-prazo.tsx
// Anel de prazo (countdown circular) — porta o SVG hand-rolled de paineis-mesa.html (.prazo-anel/.anel-c),
// mesma disciplina do Hemiciclo já existente em sessoes/[id]/plenario (zero lib de chart, skill dataviz:
// sem donut decorativo — este anel É informativo, mede prazo real).

export function arcoDashoffset(diasRestantes: number, diasTotal: number, raio: number): number {
  const circunferencia = 2 * Math.PI * raio;
  if (diasTotal <= 0) return 0;
  const fracaoRestante = Math.max(0, Math.min(1, diasRestantes / diasTotal));
  return circunferencia * fracaoRestante;
}

export function AnelPrazo({
  diasRestantes,
  diasTotal,
  rotulo,
  tamanho = 64,
}: {
  diasRestantes: number;
  diasTotal: number;
  rotulo: string;
  tamanho?: number;
}) {
  const raio = tamanho / 2 - 4;
  const circunferencia = 2 * Math.PI * raio;
  const offset = arcoDashoffset(diasRestantes, diasTotal, raio);
  const centro = tamanho / 2;
  return (
    <div className="anel-c" role="img" aria-label={`${rotulo}: faltam ${diasRestantes} dias.`}>
      <svg viewBox={`0 0 ${tamanho} ${tamanho}`} style={{ transform: "rotate(-90deg)", display: "block" }}>
        <circle className="trilho" cx={centro} cy={centro} r={raio} fill="none" strokeWidth={6} />
        <circle
          className="arco"
          cx={centro}
          cy={centro}
          r={raio}
          fill="none"
          strokeWidth={6}
          strokeLinecap="round"
          strokeDasharray={circunferencia}
          strokeDashoffset={offset}
        />
      </svg>
      <div className="centro">
        <span className="d">{diasRestantes}</span>
        <span className="u">dias</span>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Implementar `barra-segmentada.tsx`**

```tsx
// apps/frontend/src/lib/charts/barra-segmentada.tsx
// Barra de distribuição segmentada — porta .barra-dist de paineis-mesa.html. Composição honesta da carga
// (skill dataviz): larguras proporcionais REAIS, nunca estilizadas p/ parecerem mais uniformes.

export function largurasPercentuais<T extends { n: number }>(segmentos: T[]): (T & { percentual: number })[] {
  const total = segmentos.reduce((acc, s) => acc + s.n, 0);
  return segmentos.map((s) => ({ ...s, percentual: total > 0 ? (100 * s.n) / total : 0 }));
}

export function BarraSegmentada({
  segmentos,
  rotuloGeral,
}: {
  segmentos: { rotulo: string; n: number; cor: string }[];
  rotuloGeral: string;
}) {
  const comPercentual = largurasPercentuais(segmentos);
  const descricao = segmentos.map((s) => `${s.rotulo} ${s.n}`).join(", ");
  return (
    <div className="distribuicao">
      <p className="rotulo-d">
        <span>{rotuloGeral}</span>
      </p>
      <div className="barra-dist" role="img" aria-label={`${rotuloGeral}: ${descricao}.`}>
        {comPercentual.map((s) => (
          <span key={s.rotulo} style={{ width: `${s.percentual}%`, background: s.cor }}>
            {s.percentual > 8 ? s.n : ""}
          </span>
        ))}
      </div>
      <div className="dist-legenda">
        {segmentos.map((s) => (
          <span key={s.rotulo}>
            <i style={{ background: s.cor }} aria-hidden="true" />
            {s.rotulo} · {s.n}
          </span>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Implementar `tabuleiro-estagios.tsx`**

```tsx
// apps/frontend/src/lib/charts/tabuleiro-estagios.tsx
// Tabuleiro de estágios (board de N colunas) — porta .pipeline-board de paineis-mesa.html. SEM itens de
// proposição individuais aqui (essa versão só tem contagem — a versão com itens reais vem de
// /paineis/tramitacao, componente PipelineLegislativo na Task B7).

export function TabuleiroEstagios({ estagios }: { estagios: { rotulo: string; n: number }[] }) {
  return (
    <div className="pipeline-board">
      {estagios.map((e) => (
        <section className="estagio" key={e.rotulo} aria-label={`${e.rotulo}: ${e.n}`}>
          <div className="estagio-cab">
            <div className="meta">
              <span className="nome">{e.rotulo}</span>
              <span className="n">{e.n}</span>
            </div>
          </div>
        </section>
      ))}
    </div>
  );
}
```

- [ ] **Step 6: Rodar e confirmar sucesso**

Run: `cd apps/frontend && npm test -- anel-prazo.test.tsx barra-segmentada.test.tsx`
Expected: PASS (8 testes)

- [ ] **Step 7: `tsc`/`eslint`/`build`**

Run: `cd apps/frontend && npm run lint && npm run build`
Expected: limpo.

- [ ] **Step 8: Commit**

```bash
git add apps/frontend/src/lib/charts/
git commit -m "feat(fe): primitivas de chart honestos (anel de prazo, barra segmentada, tabuleiro de estágios)"
```

---

## Task B4: `use-mesa` — hook de busca (4 requests em paralelo)

**Files:**
- Create: `apps/frontend/src/lib/use-mesa.ts`
- Create: `apps/frontend/src/lib/use-mesa.test.ts`

**Interfaces:**
- Consumes: `contrato-mesa.gen.ts` (Task A7 — `MesaOut`, `RelatoresPendentesOut`), tipos hand-rolled novos
  para `/paineis/tramitacao` (`ItemBoardOut`) e `/paineis/pendencias` (`PendenciaOut`) e
  `/paineis/sli/sessoes` (`SliSessaoOut`) — ver Step 3.
- Produces: `useMesa(token): { mesa, tramitacaoItens, pendenciasItens, sliSessoes, estado }` onde `estado`
  é `"carregando" | "pronto" | "erro"`; cada campo de detalhe é `T[] | null` (null = aquela chamada
  específica falhou, degrada para a versão "só contagem" já presente em `mesa`).

- [ ] **Step 1: Escrever o teste (falha)**

```tsx
// apps/frontend/src/lib/use-mesa.test.ts
import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useMesa } from "./use-mesa";

const mesaFake = {
  complianceTce: { resumo: {}, emAberto: [], remessasRecentes: [] },
  tramitacao: { total: 2, porEstado: [{ estado: "protocolada", n: 2 }] },
  pendencias: { abertas: 0, vencidas: 0, pendentes: 0 },
  sessoes: { emCurso: 0, naoRealizadas: 0, porSituacao: [] },
  presencaResumo: { mediaPercentual: 78, sessoesConsideradas: 10, membrosDaCasa: 43 },
  esicCumprimento: { totalEncerrados: 49, cumpridosNoPrazo: 47, percentual: 96 },
  relatoresPendentes: { itens: [] },
  lacunas: ["ciencia_convocacao", "assinatura_autografo", "incidente_grant_lgpd"],
};

describe("useMesa", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca as 4 rotas em paralelo e monta o estado 'pronto'", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const corpo = url.includes("/paineis/mesa")
        ? mesaFake
        : url.includes("/paineis/tramitacao")
          ? { itens: [] }
          : url.includes("/paineis/pendencias")
            ? { pendencias: [] }
            : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.mesa?.tramitacao.total).toBe(2);
    expect(result.current.tramitacaoItens).toEqual([]);
  });

  it("chamada principal falha -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("chamada de detalhe (tramitação) falha isoladamente -> mesa continua 'pronto', detalhe vira null", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url.includes("/paineis/tramitacao")) return { ok: false, status: 500 } as Response;
      const corpo = url.includes("/paineis/mesa")
        ? mesaFake
        : url.includes("/paineis/pendencias")
          ? { pendencias: [] }
          : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.tramitacaoItens).toBeNull();
    expect(result.current.mesa?.tramitacao.total).toBe(2);
  });
});
```

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/frontend && npm test -- use-mesa.test.ts`
Expected: FAIL — `./use-mesa` não existe

- [ ] **Step 3: Implementar `use-mesa.ts`**

```tsx
"use client";

// Hook do Dashboard da Mesa (FE Onda A1): busca GET /api/paineis/mesa (o request principal, 1 chamada) +
// as 3 chamadas "enriquecidas" (tramitação/pendências/sli-sessões, em paralelo) — decisão confirmada: 1
// request pro hero (MesaOut já vem com os 4 cards) + 3 chamadas de detalhe para itens reais (Fork 1-A da
// composição + decisão "B enriquecida" do brainstorm). Falha na chamada PRINCIPAL = página inteira em
// erro; falha isolada de UMA chamada de detalhe = aquele detalhe cai para null (a seção correspondente usa
// a versão "só contagem" já presente em `mesa`, nunca deriva pra erro de página).

import { useEffect, useState } from "react";
import type { MesaOut, RelatoresPendentesOut } from "./contrato-mesa.gen";

export interface ItemBoardOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
  transicionouEm: string;
}
export interface PendenciaOut {
  objetoTipo: string;
  objetoId: string;
  protocolo: string;
  venceEm: string;
  estado: string;
}
export interface SliSessaoOut {
  sessaoId: string;
  estadoAtual: string;
  situacao: string;
  agendadaPara?: string | null;
  abertaEm?: string | null;
  encerradaEm?: string | null;
  duracaoSegundos?: number | null;
}

type Estado = "carregando" | "pronto" | "erro";

async function buscarOuNull<T>(url: string, token: string): Promise<T | null> {
  try {
    const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, cache: "no-store" });
    if (!r.ok) return null;
    return (await r.json()) as T;
  } catch {
    return null;
  }
}

export function useMesa(token: string | null) {
  const [mesa, setMesa] = useState<MesaOut | null>(null);
  const [tramitacaoItens, setTramitacaoItens] = useState<ItemBoardOut[] | null>(null);
  const [pendenciasItens, setPendenciasItens] = useState<PendenciaOut[] | null>(null);
  const [sliSessoes, setSliSessoes] = useState<SliSessaoOut[] | null>(null);
  const [relatoresPendentes, setRelatoresPendentes] = useState<RelatoresPendentesOut["itens"] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (!token) {
      setEstado("erro");
      return;
    }
    let vivo = true;
    (async () => {
      const principal = await buscarOuNull<MesaOut>("/api/paineis/mesa", token);
      if (!vivo) return;
      if (!principal) {
        setEstado("erro");
        return;
      }
      setMesa(principal);
      setRelatoresPendentes(principal.relatoresPendentes.itens);

      const [tramitacao, pendencias, sli] = await Promise.all([
        buscarOuNull<{ itens: ItemBoardOut[] }>("/api/paineis/tramitacao", token),
        buscarOuNull<{ pendencias: PendenciaOut[] }>("/api/paineis/pendencias", token),
        buscarOuNull<{ sessoes: SliSessaoOut[] }>("/api/paineis/sli/sessoes", token),
      ]);
      if (!vivo) return;
      setTramitacaoItens(tramitacao ? tramitacao.itens : null);
      setPendenciasItens(pendencias ? pendencias.pendencias : null);
      setSliSessoes(sli ? sli.sessoes : null);
      setEstado("pronto");
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  return { mesa, tramitacaoItens, pendenciasItens, sliSessoes, relatoresPendentes, estado };
}
```

> `contrato-mesa.gen.ts` (Task A7) exporta interfaces em camelCase (o codegen já converte kebab→camel) —
> os campos acima (`ItemBoardOut`/`PendenciaOut`/`SliSessaoOut`) são HAND-ROLLED (não fazem parte do
> manifesto de A7, que só cobre `MesaOut` e seus aninhados) porque `/paineis/tramitacao`,
> `/paineis/pendencias` e `/paineis/sli/sessoes` são contratos PRÓPRIOS não tocados nesta fatia — mesma
> disciplina hand-rolled do `contrato.ts` já existente (kebab→camel manual, fiel ao wire).

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/frontend && npm test -- use-mesa.test.ts`
Expected: PASS (3 testes)

- [ ] **Step 5: `tsc`/`eslint`**

Run: `cd apps/frontend && npm run lint`
Expected: limpo (confira se `MesaOut`/`RelatoresPendentesOut` realmente existem com esses nomes exatos em
`contrato-mesa.gen.ts` — gerado na Task A7 Step 8; ajuste os nomes de import se o codegen tiver emitido
algo ligeiramente diferente).

- [ ] **Step 6: Commit**

```bash
git add apps/frontend/src/lib/use-mesa.ts apps/frontend/src/lib/use-mesa.test.ts
git commit -m "feat(fe): use-mesa — busca GET /paineis/mesa + 3 detalhes em paralelo, degradação por card"
```

---

## Task B5: `mesa-vista` — view-model puro (o coração testável)

**Files:**
- Create: `apps/frontend/src/lib/mesa-vista.ts`
- Create: `apps/frontend/src/lib/mesa-vista.test.ts`

**Interfaces:**
- Consumes: o retorno de `useMesa` (Task B4).
- Produces: `derivarMesaVista(dados): MesaVista` — um objeto plano com uma chave por seção da página, cada
  uma `{ estado: "disponivel" | "indisponivel" | "em-breve", ... }`.

- [ ] **Step 1: Escrever os testes (falha)**

```tsx
// apps/frontend/src/lib/mesa-vista.test.ts
import { describe, expect, it } from "vitest";
import { derivarMesaVista } from "./mesa-vista";

const mesaBase = {
  complianceTce: { resumo: { pendente: 2, cumprida: 9, vencida: 0, dispensada: 1, cancelada: 0 }, emAberto: [], remessasRecentes: [] },
  tramitacao: { total: 47, porEstado: [{ estado: "protocolada", n: 12 }] },
  pendencias: { abertas: 8, vencidas: 1, pendentes: 7 },
  sessoes: { emCurso: 0, naoRealizadas: 1, porSituacao: [] },
  presencaResumo: { mediaPercentual: 78, sessoesConsideradas: 10, membrosDaCasa: 43 },
  esicCumprimento: { totalEncerrados: 49, cumpridosNoPrazo: 47, percentual: 96 },
  relatoresPendentes: { itens: [] },
  lacunas: ["ciencia_convocacao", "assinatura_autografo", "incidente_grant_lgpd"],
};

describe("derivarMesaVista", () => {
  it("compliance indisponivel -> saude.estado = indisponivel", () => {
    const v = derivarMesaVista({
      mesa: { ...mesaBase, complianceTce: { indisponivel: true } },
      tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [],
    });
    expect(v.saude.estado).toBe("indisponivel");
  });

  it("compliance ok -> saude.estado = disponivel com o resumo", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.saude.estado).toBe("disponivel");
    expect(v.saude.resumo?.cumprida).toBe(9);
  });

  it("tramitacaoItens null (chamada de detalhe falhou) -> pipeline degrada pra só-contagem", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: null, pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.pipeline.estado).toBe("disponivel");
    expect(v.pipeline.comItens).toBe(false);
    expect(v.pipeline.porEstado).toEqual(mesaBase.tramitacao.porEstado);
  });

  it("tramitacaoItens presente -> pipeline com itens reais", () => {
    const itens = [{ proposicaoId: "1", tipo: "pl", ano: 2026, sequencial: 1, urnLex: "u", ementa: "e", estado: "protocolada", transicionouEm: "2026-07-01T00:00:00Z" }];
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: itens, pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.pipeline.comItens).toBe(true);
    expect(v.pipeline.itens).toHaveLength(1);
  });

  it("as 3 secoes caras ficam em-breve por construção (nunca dependem de dado)", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.proximaSessaoCiencia.estado).toBe("em-breve");
    expect(v.despachos.autografo.estado).toBe("em-breve");
    expect(v.lenteJuridico.estado).toBe("em-breve");
  });

  it("orgulho combina presenca+esic+total-tramitacao; transmissao ao vivo em-breve", () => {
    const v = derivarMesaVista({ mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [], relatoresPendentes: [] });
    expect(v.orgulho.presencaMedia).toBe(78);
    expect(v.orgulho.esicPercentual).toBe(96);
    expect(v.orgulho.totalTramitacao).toBe(47);
    expect(v.orgulho.transmissaoAoVivo.estado).toBe("em-breve");
  });

  it("despachos.relator com itens reais quando relatoresPendentes vem preenchido", () => {
    const v = derivarMesaVista({
      mesa: mesaBase, tramitacaoItens: [], pendenciasItens: [], sliSessoes: [],
      relatoresPendentes: [{ id: "1", proposicaoId: "p1", tipo: "pl", ano: 2026, sequencial: 51, urnLex: "u", ementa: "Arborização", criadoEm: "2026-07-01T00:00:00Z" }],
    });
    expect(v.despachos.relator.estado).toBe("disponivel");
    expect(v.despachos.relator.itens).toHaveLength(1);
  });
});
```

- [ ] **Step 2: Rodar e confirmar falha**

Run: `cd apps/frontend && npm test -- mesa-vista.test.ts`
Expected: FAIL — `./mesa-vista` não existe

- [ ] **Step 3: Implementar `mesa-vista.ts`**

```tsx
// apps/frontend/src/lib/mesa-vista.ts
// View-model PURO do Dashboard da Mesa (FE Onda A1) — traduz o retorno de useMesa em "o que cada seção da
// página mostra", incluindo os 3 estados por seção (disponivel/indisponivel/em-breve). Nenhum componente
// React sabe interpretar MesaOut diretamente; eles só leem daqui (mesmo padrão de placar-vista.ts).

import type { ItemBoardOut, PendenciaOut, SliSessaoOut } from "./use-mesa";
import type { MesaOut, RelatoresPendentesOut } from "./contrato-mesa.gen";

type CardOpaco = { indisponivel: true } | Record<string, unknown>;
function indisponivel(card: CardOpaco): card is { indisponivel: true } {
  return "indisponivel" in card && card.indisponivel === true;
}

export interface MesaVistaInput {
  mesa: MesaOut | (Omit<MesaOut, "complianceTce"> & { complianceTce: CardOpaco }) | null;
  tramitacaoItens: ItemBoardOut[] | null;
  pendenciasItens: PendenciaOut[] | null;
  sliSessoes: SliSessaoOut[] | null;
  relatoresPendentes: RelatoresPendentesOut["itens"] | null;
}

export function derivarMesaVista(input: MesaVistaInput) {
  const { mesa, tramitacaoItens, pendenciasItens, sliSessoes, relatoresPendentes } = input;
  if (!mesa) {
    return {
      saude: { estado: "indisponivel" as const },
      oQueVence: { estado: "indisponivel" as const, itens: [] },
      pipeline: { estado: "indisponivel" as const, comItens: false, porEstado: [], itens: [] },
      despachos: {
        relator: { estado: "indisponivel" as const, itens: [] },
        distribuicao: { estado: "em-breve" as const },
        autografo: { estado: "em-breve" as const },
        ata: { estado: "em-breve" as const },
      },
      orgulho: { estado: "indisponivel" as const, presencaMedia: null, esicPercentual: null, totalTramitacao: null,
                 transmissaoAoVivo: { estado: "em-breve" as const } },
      proximaSessaoCiencia: { estado: "em-breve" as const },
      lenteJuridico: { estado: "em-breve" as const },
    };
  }

  const complianceOk = !indisponivel(mesa.complianceTce);
  const compliance = complianceOk ? (mesa.complianceTce as { resumo: Record<string, number>; emAberto: unknown[] }) : null;

  return {
    saude: complianceOk
      ? { estado: "disponivel" as const, resumo: compliance!.resumo, emAberto: compliance!.emAberto }
      : { estado: "indisponivel" as const },

    oQueVence: complianceOk
      ? {
          estado: "disponivel" as const,
          itens: [
            ...(compliance!.emAberto as { venceEm: string }[]).map((i) => ({ ...i, origem: "compliance" as const })),
            ...(pendenciasItens ?? []).map((i) => ({ ...i, origem: "pendencia" as const })),
          ].sort((a, b) => a.venceEm.localeCompare(b.venceEm)),
        }
      : { estado: "indisponivel" as const, itens: [] },

    pipeline:
      tramitacaoItens !== null
        ? { estado: "disponivel" as const, comItens: true, porEstado: mesa.tramitacao.porEstado, itens: tramitacaoItens }
        : { estado: "disponivel" as const, comItens: false, porEstado: mesa.tramitacao.porEstado, itens: [] },

    despachos: {
      relator:
        relatoresPendentes !== null
          ? { estado: "disponivel" as const, itens: relatoresPendentes }
          : { estado: "indisponivel" as const, itens: [] },
      distribuicao: { estado: "em-breve" as const },
      autografo: { estado: "em-breve" as const },
      ata: { estado: "em-breve" as const },
    },

    orgulho: {
      estado: "disponivel" as const,
      presencaMedia: mesa.presencaResumo.mediaPercentual,
      esicPercentual: mesa.esicCumprimento.percentual,
      totalTramitacao: mesa.tramitacao.total,
      transmissaoAoVivo: { estado: "em-breve" as const },
    },

    proximaSessaoCiencia: { estado: "em-breve" as const },
    lenteJuridico: { estado: "em-breve" as const },

    _sliSessoes: sliSessoes,
  };
}

export type MesaVista = ReturnType<typeof derivarMesaVista>;
```

- [ ] **Step 4: Rodar e confirmar sucesso**

Run: `cd apps/frontend && npm test -- mesa-vista.test.ts`
Expected: PASS (7 testes)

- [ ] **Step 5: `tsc`/`eslint`**

Run: `cd apps/frontend && npm run lint`
Expected: limpo (ajuste os tipos de `CardOpaco`/união se o `tsc` reclamar de estreitamento — o guard
`indisponivel()` é um type predicate, deve satisfazer o narrowing).

- [ ] **Step 6: Commit**

```bash
git add apps/frontend/src/lib/mesa-vista.ts apps/frontend/src/lib/mesa-vista.test.ts
git commit -m "feat(fe): mesa-vista — view-model puro com os 3 estados por seção (disponível/indisponível/em-breve)"
```

---

## Task B6: Componentes — `SaudeInstitucional` + `OQueVence`

**Files:**
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/saude-institucional.tsx`
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/o-que-vence.tsx`
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/mesa.css`

**Interfaces:**
- Consumes: `MesaVista["saude"]`, `MesaVista["oQueVence"]` (Task B5); `AnelPrazo` (Task B3).
- Produces: `<SaudeInstitucional vista={...} />`, `<OQueVence vista={...} />`.

- [ ] **Step 1: Implementar `saude-institucional.tsx`**

```tsx
"use client";

// Hero "saúde institucional" — porta .saude/.placar de paineis-mesa.html. Mapeamento fino do view-model
// (mesa-vista.ts); sem lógica própria aqui.

import type { MesaVista } from "@/lib/mesa-vista";

export function SaudeInstitucional({ vista }: { vista: MesaVista["saude"] }) {
  if (vista.estado === "indisponivel") {
    return (
      <section className="saude saude-indisponivel" aria-labelledby="saude-titulo">
        <div className="saude-corpo">
          <p className="eyebrow">Saúde institucional · prestação de contas da Mesa</p>
          <h1 id="saude-titulo">Painel de compliance indisponível no momento.</h1>
          <p className="saude-sub">Os demais painéis abaixo seguem carregando normalmente.</p>
        </div>
      </section>
    );
  }
  const { resumo } = vista;
  const emDia = resumo.cumprida + resumo.dispensada + resumo.cancelada;
  const vencidas = resumo.vencida;
  return (
    <section className="saude" aria-labelledby="saude-titulo">
      <div className="saude-corpo">
        <p className="eyebrow">Saúde institucional · prestação de contas da Mesa</p>
        <h1 id="saude-titulo">
          {vencidas === 0 ? (
            <>A Casa está em dia com o <em>TCE-CE</em>.</>
          ) : (
            <>{vencidas} obrigação(ões) venceu(ram) o prazo no <em>TCE-CE</em>.</>
          )}
        </h1>
        <div className="placar">
          <div className="ob-col ob-dia">
            <p className="rotulo">Em dia</p>
            <p className="n">{emDia}</p>
            <p className="est">conformes</p>
          </div>
          <div className="ob-col ob-vencer">
            <p className="rotulo">Pendentes</p>
            <p className="n">{resumo.pendente}</p>
          </div>
          <div className="ob-col ob-risco">
            <p className="rotulo">Vencidas</p>
            <p className="n">{vencidas}</p>
          </div>
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 2: Implementar `o-que-vence.tsx`**

```tsx
"use client";

// "O que vence" — porta .prazos de paineis-mesa.html, agora unindo obrigações de compliance (em-aberto)
// com pendências de atendimento ao cidadão (e-SIC/LGPD/ouvidoria), ordenado por vence-em. Usa AnelPrazo.

import { AnelPrazo } from "@/lib/charts/anel-prazo";
import type { MesaVista } from "@/lib/mesa-vista";

function diasAte(dataIso: string): number {
  const alvo = new Date(dataIso).getTime();
  const hoje = new Date().getTime();
  return Math.max(0, Math.ceil((alvo - hoje) / (1000 * 60 * 60 * 24)));
}

export function OQueVence({ vista }: { vista: MesaVista["oQueVence"] }) {
  if (vista.estado === "indisponivel") {
    return (
      <section className="bloco" aria-labelledby="prazos-titulo">
        <div className="bloco-cabeca"><h2 id="prazos-titulo">O que vence</h2></div>
        <div className="bloco-corpo"><p>Indisponível no momento.</p></div>
      </section>
    );
  }
  return (
    <section className="bloco" aria-labelledby="prazos-titulo">
      <div className="bloco-cabeca">
        <h2 id="prazos-titulo">O que vence</h2>
        <span className="selo-n mono">{vista.itens.length} itens</span>
      </div>
      <div className="bloco-corpo">
        <ul className="prazos">
          {vista.itens.map((item, i) => {
            const dias = diasAte(item.venceEm);
            const rotulo =
              item.origem === "compliance"
                ? `Obrigação TCE · ${(item as { templateChave: string }).templateChave}`
                : `${(item as { objetoTipo: string }).objetoTipo} · ${(item as { protocolo: string }).protocolo}`;
            return (
              <li key={i} className="prazo-item">
                <AnelPrazo diasRestantes={dias} diasTotal={30} rotulo={rotulo} />
                <div className="prazo-obj">
                  <b>{rotulo}</b>
                  <span className="quando">vence em {dias} dia(s)</span>
                </div>
              </li>
            );
          })}
        </ul>
        {vista.itens.length === 0 && <p>Nenhum prazo em aberto.</p>}
      </div>
    </section>
  );
}
```

> `templateChave` em `compliance`'s `emAberto` e `objetoTipo`/`protocolo` em `pendencia` vêm do
> `ObrigacaoEmAbertoOut`/`PendenciaOut` — confira que `mesa-vista.ts` (Task B5) preserva essas chaves ao
> espalhar (`...i`) e ajuste os nomes de campo se o codegen/hand-roll tiver emitido algo diferente
> (ex.: `template-chave` vs `templateChave` — o codegen já converte kebab→camel, então `templateChave` é o
> esperado).

- [ ] **Step 3: CSS — portar as classes usadas destas 2 seções de `paineis-mesa.html`**

Copiar de `produto/design-system/o-plenario/telas/paineis-mesa.html` (as regras `.saude*`, `.placar*`,
`.ob-col*`, `.prazos*`, `.prazo-item*`, `.anel-c*`) para `apps/frontend/src/app/(interno)/paineis/mesa/mesa.css`
VERBATIM (mesma disciplina de FE.1 — copiar, não reinventar). Importar `./mesa.css` no `page.tsx` (Task B9).

- [ ] **Step 4: Teste manual visual (sem asserção automatizada nesta task — cobre na B10)**

Nenhum teste automatizado aqui além dos já cobertos por `mesa-vista.test.ts` (a lógica de estados já está
testada; estes 2 arquivos são mapeamento puro sem lógica nova). Rode `npm run lint` para garantir tipos.

- [ ] **Step 5: `tsc`/`eslint`/`build`**

Run: `cd apps/frontend && npm run lint && npm run build`

- [ ] **Step 6: Commit**

```bash
git add "apps/frontend/src/app/(interno)/paineis/mesa/saude-institucional.tsx" \
        "apps/frontend/src/app/(interno)/paineis/mesa/o-que-vence.tsx" \
        "apps/frontend/src/app/(interno)/paineis/mesa/mesa.css"
git commit -m "feat(fe): componentes SaudeInstitucional + OQueVence do Dashboard da Mesa"
```

---

## Task B7: Componentes — `PipelineLegislativo` + `DespachosDaMesa`

**Files:**
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/pipeline-legislativo.tsx`
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/despachos-da-mesa.tsx`

**Interfaces:**
- Consumes: `MesaVista["pipeline"]`, `MesaVista["despachos"]`; `BarraSegmentada`/`TabuleiroEstagios` (Task B3).

- [ ] **Step 1: Implementar `pipeline-legislativo.tsx`**

```tsx
"use client";

// "Onde está cada proposição" — porta .pipeline-board de paineis-mesa.html. Com itens reais quando
// tramitacaoItens veio (comItens=true); degrada pra só-contagem (TabuleiroEstagios) quando a chamada de
// detalhe falhou — nunca deriva pra estado de erro de página inteira.

import { BarraSegmentada } from "@/lib/charts/barra-segmentada";
import { TabuleiroEstagios } from "@/lib/charts/tabuleiro-estagios";
import type { MesaVista } from "@/lib/mesa-vista";

const CORES_ESTAGIO: Record<string, string> = {
  protocolada: "#0C5340",
  em_comissao: "#1E5FA8",
  primeiro_turno: "#D9542B",
  segundo_turno: "#16785C",
  sancao: "#E8B23A",
};

export function PipelineLegislativo({ vista }: { vista: MesaVista["pipeline"] }) {
  if (vista.estado === "indisponivel") {
    return (
      <section className="bloco" aria-labelledby="pipeline-titulo">
        <div className="bloco-cabeca"><h2 id="pipeline-titulo">Onde está cada proposição</h2></div>
        <div className="bloco-corpo"><p>Indisponível no momento.</p></div>
      </section>
    );
  }
  const segmentos = vista.porEstado.map((e) => ({ rotulo: e.estado, n: e.n, cor: CORES_ESTAGIO[e.estado] ?? "#888" }));
  return (
    <section className="bloco" aria-labelledby="pipeline-titulo">
      <div className="bloco-cabeca">
        <h2 id="pipeline-titulo">Onde está cada proposição</h2>
      </div>
      <div className="bloco-corpo">
        {vista.comItens ? (
          <div className="pipeline-board">
            {vista.porEstado.map((e) => (
              <section className="estagio" key={e.estado} aria-label={`${e.estado}: ${e.n}`}>
                <div className="estagio-cab">
                  <div className="meta">
                    <span className="nome">{e.estado}</span>
                    <span className="n">{e.n}</span>
                  </div>
                </div>
                <ul className="estagio-lista">
                  {vista.itens
                    .filter((it) => it.estado === e.estado)
                    .slice(0, 3)
                    .map((it) => (
                      <li key={it.proposicaoId}>
                        <span className="ref">{it.tipo.toUpperCase()} {it.sequencial}/{it.ano}</span>
                        <span className="tit">{it.ementa}</span>
                      </li>
                    ))}
                </ul>
              </section>
            ))}
          </div>
        ) : (
          <TabuleiroEstagios estagios={vista.porEstado.map((e) => ({ rotulo: e.estado, n: e.n }))} />
        )}
        <BarraSegmentada segmentos={segmentos} rotuloGeral={`Carga por estágio · ${vista.porEstado.reduce((a, e) => a + e.n, 0)} proposições ativas`} />
      </div>
    </section>
  );
}
```

- [ ] **Step 2: Implementar `despachos-da-mesa.tsx`**

```tsx
"use client";

// "O que só a Mesa despacha" — porta .fila de paineis-mesa.html. Só "designar relator" tem dado real
// (fila.relator); os demais (distribuição/autógrafo/ata) ficam em-breve por construção (spec §7 — nenhum
// tem rota hoje) e NÃO viram bullet points inventados: aparecem como uma nota honesta, não itens fake.

import type { MesaVista } from "@/lib/mesa-vista";

export function DespachosDaMesa({ vista }: { vista: MesaVista["despachos"] }) {
  const relatorItens = vista.relator.estado === "disponivel" ? vista.relator.itens : [];
  return (
    <section className="bloco" aria-labelledby="fila-titulo">
      <div className="bloco-cabeca">
        <h2 id="fila-titulo">O que só a Mesa despacha</h2>
        <span className="selo-n mono">{relatorItens.length} item(ns)</span>
      </div>
      <div className="bloco-corpo">
        {vista.relator.estado === "indisponivel" && <p>Fila de relatores indisponível no momento.</p>}
        <ol className="fila">
          {relatorItens.map((it) => (
            <li key={it.id} className="fila-item">
              <div className="fila-txt">
                <span className="tag-p">Designar relator</span>
                <b>{it.tipo.toUpperCase()} {it.sequencial}/{it.ano}</b>
                <span className="de">{it.ementa}</span>
              </div>
            </li>
          ))}
        </ol>
        {relatorItens.length === 0 && vista.relator.estado === "disponivel" && (
          <p>Nenhum parecer aguardando designação de relator.</p>
        )}
        <p className="nota-gap">
          Despachar distribuição, assinar autógrafo e revisar ata seguem fora deste painel por ora — sem
          rota de backend ainda (carry documentado no spec).
        </p>
      </div>
    </section>
  );
}
```

- [ ] **Step 3: `tsc`/`eslint`/`build`**

Run: `cd apps/frontend && npm run lint && npm run build`

- [ ] **Step 4: Commit**

```bash
git add "apps/frontend/src/app/(interno)/paineis/mesa/pipeline-legislativo.tsx" \
        "apps/frontend/src/app/(interno)/paineis/mesa/despachos-da-mesa.tsx"
git commit -m "feat(fe): componentes PipelineLegislativo + DespachosDaMesa do Dashboard da Mesa"
```

---

## Task B8: Componentes — `OrgulhoInstitucional` + `ProximaSessaoRail` + `LenteJuridico`

**Files:**
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/orgulho-institucional.tsx`
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/proxima-sessao-rail.tsx`
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/lente-juridico.tsx`

**Interfaces:**
- Consumes: `MesaVista["orgulho"]`, `MesaVista["proximaSessaoCiencia"]`, `MesaVista["lenteJuridico"]`,
  `MesaVista["_sliSessoes"]`.

- [ ] **Step 1: Implementar `orgulho-institucional.tsx`**

```tsx
"use client";

// "O que a Casa entregou" — porta .orgulho-corpo de paineis-mesa.html. presencaMedia/esicPercentual/
// totalTramitacao são reais; transmissaoAoVivo fica em-breve (nenhuma rota rastreia "transmitida" hoje).

import type { MesaVista } from "@/lib/mesa-vista";

export function OrgulhoInstitucional({ vista }: { vista: MesaVista["orgulho"] }) {
  return (
    <section className="bloco" aria-labelledby="orgulho-titulo">
      <div className="bloco-cabeca"><h2 id="orgulho-titulo">O que a Casa entregou</h2></div>
      <div className="bloco-corpo">
        <div className="orgulho-corpo">
          <div className="org-stat">
            <p className="n">{vista.totalTramitacao ?? "—"}</p>
            <p className="rot">Proposições em tramitação</p>
          </div>
          <div className="org-stat">
            <p className="n">{vista.presencaMedia !== null ? `${vista.presencaMedia}%` : "—"}</p>
            <p className="rot">Presença média nas sessões</p>
          </div>
          <div className="org-stat">
            <p className="n">{vista.esicPercentual !== null ? `${vista.esicPercentual}%` : "—"}</p>
            <p className="rot">Pedidos de informação respondidos no prazo (LAI)</p>
          </div>
          <div className="org-stat">
            <p className="rot">Sessões transmitidas ao vivo — em breve.</p>
          </div>
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 2: Implementar `proxima-sessao-rail.tsx`**

```tsx
"use client";

// "Próxima sessão" — porta .rail de paineis-mesa.html. Mostra a próxima sessão AGENDADA (de sliSessoes,
// já disponível) sem quórum-de-ciência/checklist (carry — ciência de convocação não existe no domínio
// ainda, spec §7).

import type { SliSessaoOut } from "@/lib/use-mesa";

export function ProximaSessaoRail({ sliSessoes }: { sliSessoes: SliSessaoOut[] | null }) {
  const proxima = (sliSessoes ?? []).find((s) => s.situacao === "agendada");
  return (
    <aside className="rail" aria-label="Próxima sessão">
      <section className="bloco" aria-labelledby="proxima-titulo">
        <div className="bloco-cabeca"><h2 id="proxima-titulo">Próxima sessão</h2></div>
        <div className="bloco-corpo">
          {proxima ? (
            <>
              <p>Agendada para {proxima.agendadaPara ? new Date(proxima.agendadaPara).toLocaleString("pt-BR") : "data a definir"}.</p>
              <p className="nota-gap">Quórum de ciência e checklist de prontidão seguem em breve (carry — ciência de convocação ainda não existe no domínio).</p>
            </>
          ) : (
            <p>Nenhuma sessão agendada.</p>
          )}
        </div>
      </section>
    </aside>
  );
}
```

- [ ] **Step 3: Implementar `lente-juridico.tsx`**

```tsx
"use client";

// Recorte "lente jurídico" — em-breve por construção (spec §7: incidente LGPD + grant de acesso de
// suporte não existem no domínio; domínio de segurança inteiro novo, fora do escopo desta fatia).

export function LenteJuridico() {
  return (
    <section className="bloco" aria-labelledby="juridico-titulo">
      <div className="bloco-cabeca"><h2 id="juridico-titulo">Recorte jurídico</h2></div>
      <div className="bloco-corpo">
        <p className="nota-gap">
          Incidentes LGPD e grants de acesso de suporte seguem em breve — domínio de segurança ainda não
          modelado (carry documentado no spec desta fatia).
        </p>
      </div>
    </section>
  );
}
```

- [ ] **Step 4: `tsc`/`eslint`/`build`**

Run: `cd apps/frontend && npm run lint && npm run build`

- [ ] **Step 5: Commit**

```bash
git add "apps/frontend/src/app/(interno)/paineis/mesa/orgulho-institucional.tsx" \
        "apps/frontend/src/app/(interno)/paineis/mesa/proxima-sessao-rail.tsx" \
        "apps/frontend/src/app/(interno)/paineis/mesa/lente-juridico.tsx"
git commit -m "feat(fe): componentes OrgulhoInstitucional + ProximaSessaoRail + LenteJuridico (em-breve honesto)"
```

---

## Task B9: `page.tsx` — assembly da rota `/paineis/mesa`

**Files:**
- Create: `apps/frontend/src/app/(interno)/paineis/mesa/page.tsx`

**Interfaces:**
- Consumes: `useAuth` (B1), `useMesa` (B4), `derivarMesaVista` (B5), todos os componentes de B6/B7/B8.

- [ ] **Step 1: Implementar `page.tsx`**

```tsx
"use client";

import { useAuth } from "@/lib/auth";
import { useMesa } from "@/lib/use-mesa";
import { derivarMesaVista } from "@/lib/mesa-vista";
import { TopoInterno } from "../../topo";
import { SaudeInstitucional } from "./saude-institucional";
import { OQueVence } from "./o-que-vence";
import { PipelineLegislativo } from "./pipeline-legislativo";
import { DespachosDaMesa } from "./despachos-da-mesa";
import { OrgulhoInstitucional } from "./orgulho-institucional";
import { ProximaSessaoRail } from "./proxima-sessao-rail";
import { LenteJuridico } from "./lente-juridico";
import "./mesa.css";

export default function PaginaDashboardMesa() {
  const { token } = useAuth();
  const { mesa, tramitacaoItens, pendenciasItens, sliSessoes, relatoresPendentes, estado } = useMesa(token);
  const vista = derivarMesaVista({ mesa, tramitacaoItens, pendenciasItens, sliSessoes, relatoresPendentes });

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar o Dashboard da Mesa</h1>
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
  return (
    <>
      <TopoInterno area="Painéis da Mesa" ator={{ nome: "Sérgio Lopes", papel: "Presidente da Mesa" }} />
      <main className="envelope">
        <SaudeInstitucional vista={vista.saude} />
        <div className="cockpit">
          <div className="coluna">
            <OQueVence vista={vista.oQueVence} />
            <DespachosDaMesa vista={vista.despachos} />
          </div>
          <ProximaSessaoRail sliSessoes={sliSessoes} />
        </div>
        <PipelineLegislativo vista={vista.pipeline} />
        <OrgulhoInstitucional vista={vista.orgulho} />
        <LenteJuridico />
      </main>
    </>
  );
}
```

> `ator` fixo ("Sérgio Lopes"/"Presidente da Mesa") é PLACEHOLDER de exibição — não há rota de identidade
> do ator logado nesta fatia (o token dev não carrega nome/papel humano, só claims técnicas). Isso é
> aceitável para o dashboard read-only desta fatia (não é dado de negócio, é UI de sessão); vira carry para
> quando a Onda D (auth real) entrar. Documente esse carry no commit.

- [ ] **Step 2: Rodar toda a suíte + build**

Run: `cd apps/frontend && npm test && npm run lint && npm run build`
Expected: tudo verde.

- [ ] **Step 3: Commit**

```bash
git add "apps/frontend/src/app/(interno)/paineis/mesa/page.tsx"
git commit -m "feat(fe): monta a página /paineis/mesa (Dashboard da Mesa) — App Shell + view-model + seções"
```

---

## Task B10: Paridade visual + `GUIDELINES-CHECKLIST.md`

**Files:**
- Modify: `apps/frontend/src/app/(interno)/paineis/mesa/mesa.css` (completar o CSS restante)
- No new source files — task de verificação visual.

- [ ] **Step 1: Subir o app localmente**

Run:
```bash
cd apps/backend && docker compose up -d --build
cd ../frontend && npm run dev
```

- [ ] **Step 2: Semear dados de demo**

Run (a partir de `apps/backend`):
```bash
clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed seed-demo/base
clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed seed-demo/eventos
```

- [ ] **Step 3: Abrir `http://localhost:3000/paineis/mesa?token=<claims-json>` nos 2 temas**

Comparar lado a lado com `produto/design-system/o-plenario/telas/paineis-mesa.html` aberto no browser —
seção por seção (hero, o-que-vence, pipeline, despachos, orgulho, rail, lente jurídico).

- [ ] **Step 4: Rodar o `GUIDELINES-CHECKLIST.md`**

Abrir `produto/design-system/o-plenario/GUIDELINES-CHECKLIST.md` e percorrer o checklist (§5.1 contraste
AA medido em pixel composto, um tema por vez com flush) contra a página nova. Corrigir qualquer violação
diretamente em `mesa.css`/nos componentes antes de prosseguir.

- [ ] **Step 5: Ajustar `mesa.css` com o restante das regras portadas**

Complete `apps/frontend/src/app/(interno)/paineis/mesa/mesa.css` com as regras de `.cockpit`, `.bloco`,
`.fila*`, `.orgulho-corpo`, `.rail`, `.pipeline-board`, `.estagio*`, `.nota-gap` — copiadas verbatim de
`paineis-mesa.html` (as que ainda não tiverem sido portadas na Task B6).

- [ ] **Step 6: Commit**

```bash
git add "apps/frontend/src/app/(interno)/paineis/mesa/mesa.css"
git commit -m "style(fe): completa a paridade visual do Dashboard da Mesa (2 temas, GUIDELINES-CHECKLIST)"
```

---

## Task B11: E2E vivo (docker) + fechamento da fatia

**Files:** nenhum arquivo novo — verificação end-to-end.

- [ ] **Step 1: Confirmar containers e app rodando**

Run: `docker ps` — confirme `oplenario-postgres-1`/`oplenario-valkey-1`/`oplenario-minio-1`/`oplenario-app-1`
todos `Up`. Se `oplenario-app-1` estiver `Exited` (visto no início desta sessão), rode
`cd apps/backend && docker compose up -d --build app`.

- [ ] **Step 2: E2E via Playwright (screenshot dos 2 temas)**

Reusar o padrão já provado em FE.1 (E2E do plenário) — navegar para `/paineis/mesa?token=...`, aguardar o
estado `pronto`, capturar screenshot em `data-tema="claro"` e `data-tema="escuro"`, e verificar
visualmente que: (a) o hero mostra números reais (não placeholders), (b) a fila de relator aparece quando
há parecer aguardando designação (do seed), (c) as seções `em-breve` mostram a nota honesta, não erro.

- [ ] **Step 3: Confirmar degradação por card manualmente**

Derrubar temporariamente uma das 3 chamadas de detalhe (ex.: parar o container do backend brevemente
durante o carregamento, ou usar devtools pra bloquear a request de `/paineis/tramitacao`) e confirmar que a
seção de pipeline cai pra "só contagem" sem quebrar a página.

- [ ] **Step 4: Rodar a suíte completa (backend + frontend) uma última vez**

Run:
```bash
cd apps/backend && clojure -M:test && clj-kondo --lint src test
cd ../frontend && npm test && npm run lint && npm run build
```
Expected: tudo verde, 0 falhas, 0 erros de lint.

- [ ] **Step 5: Abrir para review `ecc`**

Fora do escopo mecânico deste plano (subagentes de review): antes do merge, rodar
`ecc:clojure-review` no diff do backend, `ecc:react-review` + `ecc:security-review` no diff do frontend —
mesma disciplina de toda fatia anterior da Track FE. Aplicar os achados MAJOR/CRÍTICO antes de mergear.

- [ ] **Step 6: Merge**

Após reviews aplicados: `git merge --no-ff` da branch desta fatia em `main` (ou abrir PR, conforme
preferência do Daouda no momento) — fecha o Slice 1 = Onda A1, estabelece MFE-1 parcialmente (falta A2 —
Portal do cidadão — para o marco completo).

---

## Self-Review (contra a spec `2026-07-04-dashboard-mesa-slice1-design.md`)

**Cobertura da spec:**
- §2.1 (backend, 3 read-models + composição) → Tasks A1-A6. ✓
- §2.2 (App Shell) → Tasks B1-B2. ✓
- §2.3 (1 request + 3 enriquecidas) → Task B4. ✓
- §2.4 (codegen nos contratos novos) → Task A7. ✓
- §3 (componentes) → Tasks B3, B6-B9. ✓
- §4 (fluxo de dado) → Task B4 (degradação por chamada). ✓
- §5 (erros/estados vazios) → Task B5 (os 3 estados), B6-B8 (renderização). ✓
- §6 (testes) → TDD em toda task; B10 (paridade visual); B11 (e2e). ✓
- §7 (fora de escopo) → B8 (`LenteJuridico`/`ProximaSessaoRail` em-breve), B7 (`DespachosDaMesa` parcial). ✓

**Desvio registrado (não é gap, é decisão tomada durante o planejamento):** a janela de presença agregada
(Task A2) usa "últimas 10 sessões encerradas" em vez de "sessão legislativa corrente" (a spec usava essa
frase) — a implementação revelou que resolver "sessão legislativa vigente" exigiria uma query nova em
`cadastros` sem precedente pronto (só existe `legislatura-vigente`, uma granularidade mais grossa); a troca
mantém a mesma garantia de honestidade (nada arbitrário por calendário) com um custo de implementação menor.

**Placeholders:** nenhum "TBD"/"implementar depois" — os poucos pontos marcados como "ajuste conforme o
arquivo real" (nomes de campo/alias em arquivos que o executor vai abrir) são apontamentos de verificação,
não lacunas de decisão.
