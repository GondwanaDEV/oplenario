# Onda C · Slice C4 — Assinatura em 2 toques (feature 7.3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O vereador-relator assina um parecer de comissão do próprio celular em 2 toques (revisar → confirmar biometria mock), produzindo uma assinatura real (stub `'STUB-ICP-v0'`) gravada em `parecer_texto_versao` e visível via as leituras já existentes.

**Architecture:** "Assinar = emitir" — `Repo/emitir-parecer!` ganha uma porta `AssinadorICP` e assina os bytes do texto-inline sempre que promove um rascunho a vigente na mesma chamada; os campos de assinatura viajam pela mesma versão de texto (append-only). Uma borda nova `/meu/pareceres/:id`(GET)/`/meu/pareceres/:id/emissao`(POST), gate `papel-vereador` + guard de posse (`relator-do-parecer?`), dá ao vereador acesso ao MESMO fluxo que o editor desktop (`secretario`) já usa — sem duplicar a lógica de negócio.

**Tech Stack:** Clojure/Pedestal/HoneySQL/next.jdbc (backend, `apps/backend`); Next.js/React/TypeScript (frontend, `apps/frontend`); Postgres (migrations).

## Global Constraints

- Spec de origem: `docs/superpowers/specs/2026-07-11-onda-c-slice-c4-assinatura-design.md` (commit `ba2060e`) — qualquer ambiguidade resolve-se por ela.
- Assinatura só é gravada quando HÁ um rascunho sendo promovido nesta chamada de `emitir-parecer!` — nunca resigna uma versão vigente pré-existente.
- Assina os bytes UTF-8 do `texto-inline` da versão promovida (não há `conteudo-uri`/objeto_store em pareceres nesta fatia).
- `assinado-por` = o mesmo `updated-by` (identidade-id do ator autenticado) que já flui em `emitir-parecer!` — nenhum campo novo no corpo HTTP.
- Sem Component/wiring novo em `sistema.clj`: a porta `AssinadorICP` é construída inline (`assinador-icp/assinador-stub`) pelo diplomat, mesmo padrão de `gerar-artefato-publicacao!`.
- Fora de escopo: biometria/WebAuthn real, ICP-Brasil real, retrofit do editor desktop, sistema de audit-log genérico (feature 1.6).
- Todas as tasks: TDD red→green, revisão `ecc` por camada (clojure-reviewer+database-reviewer+security-reviewer no backend; react-reviewer+security-reviewer no frontend), branch dedicada `fe-18-assinatura-parecer`, commit por task.

---

## Task 1: Migration — colunas de assinatura em `parecer_texto_versao`

**Files:**
- Create: `apps/backend/resources/migrations/20260620000057-legislativo-parecer-texto-assinatura.up.sql`
- Create: `apps/backend/resources/migrations/20260620000057-legislativo-parecer-texto-assinatura.down.sql`

**Interfaces:**
- Produces: 4 colunas nullable em `legislativo.parecer_texto_versao` — `assinatura_algoritmo text`, `assinatura_b64 text`, `assinado_por uuid`, `assinado_em timestamptz`.

- [ ] **Step 1: Escrever a migration up**

`apps/backend/resources/migrations/20260620000057-legislativo-parecer-texto-assinatura.up.sql`:
```sql
-- Onda C Slice C4 (feature 7.3, assinatura em 2 toques) — assinatura sobre a versao de texto do parecer
-- que vira vigente. Mesmo padrao de legislativo.artefato_publicacao (mig 0046): assinatura DESTACADA
-- (algoritmo+b64) + quem+quando. NULLABLE (versoes anteriores a esta fatia nunca sao assinadas
-- retroativamente) e so' preenchida quando ha' um rascunho sendo promovido na MESMA chamada de
-- emitir-parecer! (Repo/emitir-parecer!, legislativo/components/repositorio.clj) — nao cobre a coluna
-- na trigger de imutabilidade (trg_parecer_texto_conteudo_imutavel, mig 0020): [GAP] nao ha' CHECK/trigger
-- que impeca sobrescrever um valor ja' setado — nenhum caminho de codigo hoje o faz (so' `promover!` grava
-- estes campos, uma unica vez por versao, no momento em que ela se torna vigente).
ALTER TABLE legislativo.parecer_texto_versao
  ADD COLUMN IF NOT EXISTS assinatura_algoritmo text,
  ADD COLUMN IF NOT EXISTS assinatura_b64 text,
  ADD COLUMN IF NOT EXISTS assinado_por uuid,
  ADD COLUMN IF NOT EXISTS assinado_em timestamptz;
```

- [ ] **Step 2: Escrever a migration down**

`apps/backend/resources/migrations/20260620000057-legislativo-parecer-texto-assinatura.down.sql`:
```sql
ALTER TABLE legislativo.parecer_texto_versao
  DROP COLUMN IF EXISTS assinatura_algoritmo,
  DROP COLUMN IF EXISTS assinatura_b64,
  DROP COLUMN IF EXISTS assinado_por,
  DROP COLUMN IF EXISTS assinado_em;
```

- [ ] **Step 3: Aplicar e verificar**

Run: `cd apps/backend && docker compose up -d --build` (roda `migrate` antes do `app`, mesma disciplina de sempre — ver memória `oplenario-rodar-local`).
Expected: log do serviço `migrate` mostra `20260620000057-legislativo-parecer-texto-assinatura` aplicada sem erro.

- [ ] **Step 4: Commit**

```bash
git checkout -b fe-18-assinatura-parecer
git add apps/backend/resources/migrations/20260620000057-legislativo-parecer-texto-assinatura.up.sql \
        apps/backend/resources/migrations/20260620000057-legislativo-parecer-texto-assinatura.down.sql
git commit -m "feat(be): migration — colunas de assinatura em parecer_texto_versao (Onda C4)"
```

---

## Task 2: `db/parecer-texto-versao` — `promover!` grava a assinatura

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/db/parecer_texto_versao.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/parecer_texto_voto_db_test.clj`

**Interfaces:**
- Consumes: migration da Task 1 (colunas existem).
- Produces: `promover!` aceita `:assinatura-algoritmo`/`:assinatura-b64`/`:assinado-por` opcionais no mapa de args; quando presentes, grava-os + `assinado_em = now()` na MESMA UPDATE que marca a versão `vigente`. `colunas` (privado) devolve os 4 campos novos em toda leitura (`buscar`/`vigente`/`versoes-do-parecer`/`rascunho-mais-recente`).

- [ ] **Step 1: Escrever o teste falhando**

Adicionar em `apps/backend/test/integration/oplenario/legislativo/parecer_texto_voto_db_test.clj` (depois de `promover-vigente-supersede-e-reaponta-pointer`):
```clojure
(deftest promover-com-assinatura-grava-os-4-campos
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              v1   (nova-versao! tx ente pcid {})]
          (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v1) :updated-by nil :lock-version 0
                              :assinatura-algoritmo "STUB-ICP-v0" :assinatura-b64 "YWJj"
                              :assinado-por (random-uuid)})
          (let [r (ptxt/buscar tx ente (:id v1))]
            (is (= "STUB-ICP-v0" (:assinatura-algoritmo r)))
            (is (= "YWJj" (:assinatura-b64 r)))
            (is (some? (:assinado-por r)))
            (is (some? (:assinado-em r)) "assinado-em preenchido pelo now() do banco")))))))

(deftest promover-sem-assinatura-nao-grava-nada
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              v1   (nova-versao! tx ente pcid {})]
          (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v1) :updated-by nil :lock-version 0})
          (let [r (ptxt/buscar tx ente (:id v1))]
            (is (nil? (:assinatura-algoritmo r)))
            (is (nil? (:assinado-em r)))))))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.parecer-texto-voto-db-test`
Expected: FAIL — `promover-com-assinatura-grava-os-4-campos` não vê os campos (não existem no `colunas`/UPDATE ainda); `promover-sem-assinatura-nao-grava-nada` passa já (nada mudou), mas roda junto.

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/db/parecer_texto_versao.clj`, atualizar `colunas` e `promover!`:
```clojure
(def ^:private colunas
  [:id :ente_id :parecer_id :numero_versao :origem_versao :origem_ref :origem_tipo :estado_versao
   :formato :texto_inline :conteudo_uri :hash_conteudo :lock_version
   :assinatura_algoritmo :assinatura_b64 :assinado_por :assinado_em])

(defn promover!
  "Promove `versao-id` a 'vigente' (ato auditado): supersede a vigente anterior do parecer, marca a alvo
  como vigente (CAS por lock-version, com parecer_id no WHERE p/ a versao TER de pertencer a este parecer)
  e reaponta pareceres.texto_vigente_versao_id — na MESMA tx. Lanca em conflito de versao OU versao
  inexistente neste parecer OU parecer filtrado. Onda C4: quando `assinatura-algoritmo` vem preenchido
  (o caller ja assinou os bytes do texto-inline — Repo/emitir-parecer!), grava a assinatura NA MESMA
  UPDATE que marca vigente (uma escrita, nao duas); `assinado-em` e' sempre `now()` do banco, nunca vem
  do app (mesmo padrao de artefato_publicacao)."
  [tx {:keys [ente-id parecer-id versao-id updated-by lock-version
              assinatura-algoritmo assinatura-b64 assinado-por]}]
  (let [{:keys [estado]} (comum/linha->kebab
                          (jdbc/execute-one! tx
                            (sql/format {:select [:estado] :from [:legislativo.pareceres]
                                         :where [:and [:= :ente_id ente-id] [:= :id parecer-id]]})))]
    (when (contains? logic/estados-parecer-terminais estado)
      (throw (ex-info "promover!: parecer em estado terminal — texto imutavel"
                      {:parecer-id parecer-id :estado estado}))))
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.parecer_texto_versao
                 :set {:estado_versao "superada" :updated_by updated-by :atualizado_em [:now]
                       :lock_version [:+ :lock_version 1]}
                 :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]
                         [:= :estado_versao "vigente"] [:<> :id versao-id]]}))
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.parecer_texto_versao
                         :set (cond-> {:estado_versao "vigente" :updated_by updated-by :atualizado_em [:now]
                                       :lock_version [:+ :lock_version 1]}
                                assinatura-algoritmo
                                (assoc :assinatura_algoritmo assinatura-algoritmo
                                       :assinatura_b64 assinatura-b64
                                       :assinado_por assinado-por
                                       :assinado_em [:now]))
                         :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]
                                 [:= :id versao-id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "promover!: conflito de lock_version ou versao inexistente neste parecer"
                      {:versao-id versao-id :parecer-id parecer-id :lock-version lock-version})))
    (let [rp (jdbc/execute-one! tx
               (sql/format {:update :legislativo.pareceres
                            :set {:texto_vigente_versao_id versao-id :atualizado_em [:now]
                                  :lock_version [:+ :lock_version 1]}
                            :where [:and [:= :ente_id ente-id] [:= :id parecer-id]]}))]
      (when (zero? (:next.jdbc/update-count rp 0))
        (throw (ex-info "promover!: parecer inexistente ou filtrado (pointer nao reapontado)"
                        {:parecer-id parecer-id :ente-id ente-id}))))
    {:versao-id versao-id :estado "vigente"}))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (todos os testes do namespace, incluindo os 2 novos e os 3 já existentes de `promover!`).

- [ ] **Step 5: Revisão `ecc` (database-reviewer)**

Rodar `/code-review` (ou invocar o agente `ecc:database-reviewer`) sobre o diff deste arquivo. Aplicar achados CRÍTICO/MAJOR antes de prosseguir.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/db/parecer_texto_versao.clj \
        apps/backend/test/integration/oplenario/legislativo/parecer_texto_voto_db_test.clj
git commit -m "feat(be): promover! grava assinatura na mesma UPDATE (Onda C4)"
```

---

## Task 3: `models/parecer-texto-versao` — schema Malli dos campos de assinatura

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/models/parecer_texto_versao.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/parecer_texto_voto_db_test.clj` (já valida `m/validate mod-txt/ParecerTextoVersao` em `nova-versao-numera-local-e-conforma` — vai continuar passando; o novo teste abaixo cobre o formato assinado)

**Interfaces:**
- Consumes: nada novo (schema puro).
- Produces: `ParecerTextoVersao` aceita os 4 campos opcionais — qualquer código que valide uma versão assinada contra este schema (ex.: futuro teste de `promover!`) não quebra.

- [ ] **Step 1: Escrever o teste falhando**

Adicionar em `apps/backend/test/integration/oplenario/legislativo/parecer_texto_voto_db_test.clj`:
```clojure
(deftest versao-assinada-bate-o-model
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              v1   (nova-versao! tx ente pcid {})]
          (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v1) :updated-by nil :lock-version 0
                              :assinatura-algoritmo "STUB-ICP-v0" :assinatura-b64 "YWJj"
                              :assinado-por (random-uuid)})
          (is (m/validate mod-txt/ParecerTextoVersao (ptxt/buscar tx ente (:id v1)))
              "versao assinada bate o model interno (:closed true recusaria campo desconhecido)"))))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.parecer-texto-voto-db-test`
Expected: FAIL — `m/validate` recusa (schema `:closed true` não conhece os 4 campos novos que `buscar` agora devolve).

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/models/parecer_texto_versao.clj`:
```clojure
(ns oplenario.legislativo.models.parecer-texto-versao
  "Representacao INTERNA (dominio) da versao de texto do PARECER — Malli (§22.10 models/, eixo F / F3.6b).
  MESMA estrategia do eixo B (models/texto-versao): conteudo append-only hibrido inline/URI, `estado-versao`
  e' a mutacao controlada (promocao). Enums de legislativo.logic (os CHECK da migration 0020 espelham).
  Onda C4: 4 campos opcionais de assinatura (`assinatura-algoritmo`/`assinatura-b64`/`assinado-por`/
  `assinado-em`) — NULL ate' a versao virar vigente por uma chamada de emitir-parecer! que assina (Repo);
  versoes anteriores a esta fatia ficam sem assinatura pra sempre (nunca retroagimos)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def ParecerTextoVersao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:parecer-id :uuid]
   [:numero-versao :int]
   [:origem-versao (enum-de logic/origens-parecer-versao)]
   [:origem-ref {:optional true} [:maybe :uuid]]
   [:origem-tipo {:optional true} [:maybe :string]]
   [:estado-versao (enum-de logic/estados-versao)]
   [:formato :string]
   ;; XOR no banco: exatamente um de inline/uri (o caller resolve via decidir-armazenamento)
   [:texto-inline {:optional true} [:maybe :string]]
   [:conteudo-uri {:optional true} [:maybe :string]]
   [:hash-conteudo {:optional true} [:maybe :string]]
   ;; concorrencia: exposto p/ o CAS de promover!
   [:lock-version :int]
   ;; Onda C4 — assinatura (feature 7.3): NULL ate' esta versao virar vigente por uma emissao que assina.
   [:assinatura-algoritmo {:optional true} [:maybe :string]]
   [:assinatura-b64 {:optional true} [:maybe :string]]
   [:assinado-por {:optional true} [:maybe :uuid]]
   [:assinado-em {:optional true} [:maybe km/Instante]]])
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/models/parecer_texto_versao.clj \
        apps/backend/test/integration/oplenario/legislativo/parecer_texto_voto_db_test.clj
git commit -m "feat(be): campos de assinatura no model ParecerTextoVersao (Onda C4)"
```

---

## Task 4: `Repo/emitir-parecer!` assina quando promove um rascunho

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj:413-457` (bloco `emitir-parecer!`)
- Test: `apps/backend/test/integration/oplenario/legislativo/parecer_repo_test.clj`

**Interfaces:**
- Consumes: `parecer-texto/promover!` (Task 2), `oplenario.legislativo.components.assinador-icp/assinar` (porta já existente).
- Produces: `Repo/emitir-parecer!` aceita `:assinador` (protocolo `AssinadorICP`) no mapa de args; quando há rascunho a promover nesta chamada, assina `(.getBytes (:texto-inline rascunho) "UTF-8")` e passa `:assinatura-algoritmo`/`:assinatura-b64`/`:assinado-por` pro `promover!`. `assinado-por` = o `updated-by` recebido (não um campo novo).

- [ ] **Step 1: Escrever o teste falhando**

Adicionar em `apps/backend/test/integration/oplenario/legislativo/parecer_repo_test.clj` (require novo: `[oplenario.legislativo.components.assinador-icp :as assinador-icp]` e `[oplenario.legislativo.db.parecer-texto-versao :as ptxt]`):
```clojure
(deftest emitir-parecer-assina-quando-ha-rascunho
  (let [ente (random-uuid)
        tid  (montar-template-parecer! ente)
        pid  (protocolar! ente)
        {pcid :id} (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao"
                                                       :objeto-id pid :comissao-id (random-uuid) :template-id tid})
        relator (random-uuid)]
    (repo/nova-versao-parecer! *repo* ente {:id (random-uuid) :parecer-id pcid :origem-versao "redacao"
                                            :texto-inline "## Parecer\nFavoravel." :created-by relator})
    (repo/emitir-parecer! *repo* ente *registro*
      {:parecer-id pcid :template-id tid :gatilho "designar" :voto-relator "favoravel"
       :lock-version 0 :updated-by relator :agora data :contexto {}
       :assinador (assinador-icp/assinador-stub)})
    (let [vigente (ptxt/vigente *ds* ente pcid)]
      (is (= "STUB-ICP-v0" (:assinatura-algoritmo vigente)) "assinada com o algoritmo stub")
      (is (some? (:assinatura-b64 vigente)))
      (is (= relator (:assinado-por vigente)) "assinado-por = o mesmo updated-by, nao um campo novo")
      (is (some? (:assinado-em vigente))))))

(deftest emitir-parecer-sem-rascunho-nao-reassina-vigente
  (let [ente (random-uuid)
        tid  (montar-template-parecer! ente)
        pid  (protocolar! ente)
        {pcid :id} (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao"
                                                       :objeto-id pid :comissao-id (random-uuid) :template-id tid})
        relator (random-uuid)]
    (repo/nova-versao-parecer! *repo* ente {:id (random-uuid) :parecer-id pcid :origem-versao "redacao"
                                            :texto-inline "## Parecer\nFavoravel." :created-by relator})
    (repo/emitir-parecer! *repo* ente *registro*
      {:parecer-id pcid :template-id tid :gatilho "designar" :voto-relator "favoravel"
       :lock-version 0 :updated-by relator :agora data :contexto {}
       :assinador (assinador-icp/assinador-stub)})
    (let [vigente-1 (ptxt/vigente *ds* ente pcid)
          {:keys [lock-version]} (repo/buscar-proposicao *repo* ente pid) ;; no-op leitura p/ nao quebrar se import mudar
          parecer-atual (:parecer (repo/buscar-parecer-para-editor *repo* ente pcid))]
      ;; 2a chamada de emitir-parecer! (retry do gatilho "bloquear", guard=falso -> nao transiciona) SEM
      ;; novo rascunho: o vigente ja existente NAO deve ser reassinado (mesmo assinatura-b64/assinado-em).
      (repo/emitir-parecer! *repo* ente *registro*
        {:parecer-id pcid :template-id tid :gatilho "bloquear" :voto-relator "favoravel"
         :lock-version (:lock-version parecer-atual) :updated-by relator :agora data :contexto {}
         :assinador (assinador-icp/assinador-stub)})
      (let [vigente-2 (ptxt/vigente *ds* ente pcid)]
        (is (= (:assinatura-b64 vigente-1) (:assinatura-b64 vigente-2)) "mesma assinatura — nao reassinou")
        (is (= (:assinado-em vigente-1) (:assinado-em vigente-2)) "mesmo carimbo — nao regravou")))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.parecer-repo-test`
Expected: FAIL — `emitir-parecer!` ainda não aceita/usa `:assinador`; `ptxt/vigente` devolve `assinatura-algoritmo` nil.

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/components/repositorio.clj`, dentro do `defrecord` (bloco `emitir-parecer!`, atualmente linhas ~413-457), trocar a destructuring e o bloco de promoção:
```clojure
  (emitir-parecer! [this ente-id registro {:keys [parecer-id template-id gatilho voto-relator updated-by agora
                                                   contexto lock-version assinador]}]
    (transacao this ente-id
      (fn [tx]
        (let [atual (parecer/buscar tx ente-id parecer-id)]
          (when (nil? atual)
            (throw (ex-info "emitir-parecer!: parecer inexistente no tenant"
                            {:parecer-id parecer-id :ente-id ente-id})))
          (when (not= lock-version (:lock-version atual))
            (throw (ex-info "conflito de escrita (lock_version desatualizado) ou parecer inexistente"
                            {:id parecer-id :lock-version lock-version}))))
        (let [rascunho (parecer-texto/rascunho-mais-recente tx ente-id parecer-id)]
          (when (and (nil? rascunho) (nil? (parecer-texto/vigente tx ente-id parecer-id)))
            (throw (ex-info "emitir-parecer!: nenhum conteudo de texto para emitir"
                            {:tipo :validacao/invalido :parecer-id parecer-id})))
          ;; Onda C4 (feature 7.3): so' assina quando HA rascunho sendo promovido AGORA — nunca reassina
          ;; uma versao ja vigente de uma chamada anterior (spec §3, "sem rascunho, so' vigente").
          (when rascunho
            (let [{:keys [algoritmo assinatura-b64]}
                  (assinador-icp/assinar assinador (.getBytes ^String (:texto-inline rascunho) "UTF-8"))]
              (parecer-texto/promover! tx {:ente-id ente-id :parecer-id parecer-id :versao-id (:id rascunho)
                                           :updated-by updated-by :lock-version (:lock-version rascunho)
                                           :assinatura-algoritmo algoritmo :assinatura-b64 assinatura-b64
                                           :assinado-por updated-by}))))
        (let [{:keys [lock-version]} (parecer/buscar tx ente-id parecer-id)]
          (parecer/registrar-voto-relator! tx {:id parecer-id :ente-id ente-id :voto-relator voto-relator
                                               :updated-by updated-by :lock-version lock-version}))
        (let [r (parecer-tram/transicionar-parecer! tx {:registro registro :ente-id ente-id :parecer-id parecer-id
                                                         :template-id template-id :gatilho gatilho :agora agora
                                                         :contexto contexto :updated-by updated-by})]
          (when (:transicionou? r)
            (producers/emitir-transicionou-parecer! bus tx ente-id
              {:parecer-id parecer-id :template-id template-id
               :objeto-tipo (:objeto-tipo r) :objeto-id (:objeto-id r)
               :de (:de r) :para (:para r) :gatilho gatilho :transicao-id (:transicao-id r)})))
        (parecer/buscar tx ente-id parecer-id))))
```
(o require de `assinador-icp` já existe no topo do namespace — usado por `gerar-artefato-publicacao!`.)

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS — os 2 testes novos + os testes de `emitir-parecer!` pré-existentes (regressão do voto/transição não deve quebrar).

- [ ] **Step 5: Revisão `ecc` (clojure-reviewer + security-reviewer)**

Rodar `/code-review` sobre o diff. Atenção especial ao guard "só assina quando há rascunho" e ao `updated-by` como única fonte de `assinado-por` (nunca aceitar um `assinado-por` do request).

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/components/repositorio.clj \
        apps/backend/test/integration/oplenario/legislativo/parecer_repo_test.clj
git commit -m "feat(be): emitir-parecer! assina o texto promovido (Onda C4, feature 7.3)"
```

---

## Task 5: Guard de posse `relator-do-parecer?` (Repo + db)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/db/meu_painel.clj`
- Modify: `apps/backend/src/oplenario/legislativo/components/repositorio.clj` (protocolo `RepoLegislativo` + implementação)
- Test: `apps/backend/test/integration/oplenario/legislativo/parecer_repo_test.clj`

**Interfaces:**
- Produces: `db/meu-painel/relator-do-parecer?` `[tx ente-id vereador-id parecer-id] -> boolean`; `Repo/relator-do-parecer?` `[this ente-id vereador-id parecer-id] -> boolean` (novo método do protocolo `RepoLegislativo`).

- [ ] **Step 1: Escrever o teste falhando**

Adicionar em `apps/backend/test/integration/oplenario/legislativo/parecer_repo_test.clj`:
```clojure
(deftest relator-do-parecer-so-o-relator-designado
  (let [ente (random-uuid)
        tid  (montar-template-parecer! ente)
        pid  (protocolar! ente)
        relator (random-uuid)
        outro   (random-uuid)
        {pcid :id} (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao"
                                                       :objeto-id pid :comissao-id (random-uuid) :template-id tid
                                                       :relator-id relator})]
    (is (true? (repo/relator-do-parecer? *repo* ente relator pcid)))
    (is (false? (repo/relator-do-parecer? *repo* ente outro pcid)) "outro vereador nao e' o relator")
    (is (false? (repo/relator-do-parecer? *repo* ente relator (random-uuid))) "parecer inexistente -> false")))
```
(`db/parecer.clj/criar!` já destructura `:relator-id` direto no mapa de criação — `repo/iniciar-parecer!` repassa `m` inteiro, então passar `:relator-id relator` na chamada acima é suficiente; não precisa de uma chamada separada a `designar-relator!`.)

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.parecer-repo-test`
Expected: FAIL — `relator-do-parecer?` não existe no protocolo/Repo.

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/db/meu_painel.clj`, adicionar:
```clojure
(defn relator-do-parecer?
  "Onda C4 (feature 7.3) — ownership guard: `vereador-id` e' de fato o relator do parecer `parecer-id`
  neste ente? Mesmo racional de `parecer-elegivel-para-ciencia?` (legislativo/db/parecer.clj) — guard
  FINAL antes de qualquer leitura/escrita vereador-scoped sobre um parecer que pode nao ser seu."
  [tx ente-id vereador-id parecer-id]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [:id] :from [:legislativo.pareceres]
                        :where [:and [:= :ente_id ente-id] [:= :id parecer-id] [:= :relator_id vereador-id]]}))))
```

Em `apps/backend/src/oplenario/legislativo/components/repositorio.clj`: adicionar ao `defprotocol RepoLegislativo` (perto de `meu-painel`/`acusar-ciencia!`):
```clojure
  (relator-do-parecer? [this ente-id vereador-id parecer-id]
    "Onda C4 — o vereador `vereador-id` e' o relator do parecer `parecer-id` neste ente?")
```
E na implementação do `defrecord` (perto de `(meu-painel [this ente-id vereador-id] ...)`):
```clojure
  (relator-do-parecer? [this ente-id vereador-id parecer-id]
    (transacao this ente-id #(meu-painel-db/relator-do-parecer? % ente-id vereador-id parecer-id)))
```
(o require `[oplenario.legislativo.db.meu-painel :as meu-painel-db]` já existe no topo do namespace.)

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/db/meu_painel.clj \
        apps/backend/src/oplenario/legislativo/components/repositorio.clj \
        apps/backend/test/integration/oplenario/legislativo/parecer_repo_test.clj
git commit -m "feat(be): guard relator-do-parecer? (posse, Onda C4)"
```

---

## Task 6: Controllers — `assinador` em `emitir-parecer` + `meu-parecer-editor`/`meu-emitir-parecer`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/controllers.clj:190-197`
- Test: `apps/backend/test/unit/oplenario/legislativo/meu_voto_controller_test.clj` — NÃO usar este arquivo; criar `apps/backend/test/unit/oplenario/legislativo/parecer_controllers_test.clj` novo (unit, Repo fake via `reify`).

**Interfaces:**
- Consumes: `Repo/emitir-parecer!` (assinador via args map), `Repo/relator-do-parecer?` (Task 5), `Repo/buscar-parecer-para-editor` (já existente).
- Produces: `controllers/emitir-parecer` ganha parâmetro `assinador` (posicional); `controllers/meu-parecer-editor` `[repo resolver-vereador ator id] -> {:parecer ... :objeto ... :texto-rascunho ... :texto-vigente ...} | nil`; `controllers/meu-emitir-parecer` `[repo registro assinador resolver-vereador ator id m] -> parecer | nil`.

- [ ] **Step 1: Escrever o teste falhando**

Criar `apps/backend/test/unit/oplenario/legislativo/parecer_controllers_test.clj`:
```clojure
(ns oplenario.legislativo.parecer-controllers-test
  "Unit (Repo FAKE) — Onda C4: meu-parecer-editor/meu-emitir-parecer respeitam o guard de posse
  (relator-do-parecer?) ANTES de ler/escrever; emitir-parecer repassa o assinador pro Repo."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]))

(defn- fake-repo [{:keys [relator? parecer emitido]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (relator-do-parecer? [_ _ente-id _vereador-id _parecer-id] relator?)
    (buscar-parecer-para-editor [_ _ente-id _id] parecer)
    (emitir-parecer! [_ _ente-id _registro m] (reset! emitido m) parecer)))

(deftest meu-parecer-editor-nil-quando-nao-e-o-relator
  (let [repo (fake-repo {:relator? false :parecer {:parecer {:id "x"}}})]
    (is (nil? (controllers/meu-parecer-editor repo (fn [_ _] (random-uuid))
                                              {:ente-id (random-uuid) :identidade-id (random-uuid)} (random-uuid))))))

(deftest meu-parecer-editor-nil-quando-ator-sem-vinculo
  (let [repo (fake-repo {:relator? true :parecer {:parecer {:id "x"}}})]
    (is (nil? (controllers/meu-parecer-editor repo (fn [_ _] nil)
                                              {:ente-id (random-uuid) :identidade-id (random-uuid)} (random-uuid))))))

(deftest meu-parecer-editor-devolve-quando-e-o-relator
  (let [repo (fake-repo {:relator? true :parecer {:parecer {:id "x"}}})]
    (is (= {:parecer {:id "x"}}
           (controllers/meu-parecer-editor repo (fn [_ _] (random-uuid))
                                           {:ente-id (random-uuid) :identidade-id (random-uuid)} (random-uuid))))))

(deftest meu-emitir-parecer-nao-chama-o-repo-quando-nao-e-o-relator
  (let [emitido (atom :nao-chamado)
        repo (fake-repo {:relator? false :parecer {:parecer {:id "x"}} :emitido emitido})]
    (is (nil? (controllers/meu-emitir-parecer repo :registro-fake :assinador-fake (fn [_ _] (random-uuid))
                                               {:ente-id (random-uuid) :identidade-id (random-uuid)}
                                               (random-uuid) {:voto-relator "favoravel"})))
    (is (= :nao-chamado @emitido) "emitir-parecer! NUNCA chamado sem posse")))

(deftest emitir-parecer-repassa-o-assinador-pro-repo
  (let [emitido (atom nil)
        repo (fake-repo {:parecer {:id "x"} :emitido emitido})]
    (controllers/emitir-parecer repo :registro-fake :assinador-fake (random-uuid) {:voto-relator "favoravel"})
    (is (= :assinador-fake (:assinador @emitido)))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.parecer-controllers-test`
Expected: FAIL — `meu-parecer-editor`/`meu-emitir-parecer` não existem; `emitir-parecer` não aceita `assinador`.

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/controllers.clj`, substituir o bloco atual de `emitir-parecer` (linhas 190-197) e adicionar as 2 funções novas logo depois:
```clojure
(defn emitir-parecer
  "Onda B Slice 5 (+Onda C4: assinatura) — promove o rascunho a vigente (se houver, assinando-o —
  Repo/emitir-parecer!) + registra o voto do relator + tenta transicionar (gatilho recebido, best-effort),
  1 tx. `registro` (RegistroFatos do motor, injetado pelo host) e' o mesmo que `transicionar-parecer!` ja
  recebe. `assinador` (porta AssinadorICP, construida pelo diplomat — mesmo padrao de
  gerar-artefato-publicacao!) e' repassado pro Repo dentro do mapa de args, nunca guardado aqui."
  [repo-legislativo registro assinador ente-id m]
  (repo/emitir-parecer! repo-legislativo ente-id registro (assoc m :assinador assinador)))

(defn meu-parecer-editor
  "Onda C4 (feature 7.3) — leitura do parecer p/ o vereador-relator (mesmo agregado do editor desktop),
  GATE DE POSSE: so' devolve se o vereador ATOR e' de fato o relator deste parecer. Anti-forja: vereador-id
  SEMPRE resolvido do proprio ator (mesmo contrato de meu-painel/acusar-ciencia), nunca de path/corpo. nil
  (ator sem cadastro vinculado OU nao e' o relator OU parecer inexistente) -> nil — a borda traduz -> 404,
  sem distinguir motivo (mesmo contrato de parecer-elegivel-para-ciencia?)."
  [repo-legislativo resolver-vereador ator id]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (repo/relator-do-parecer? repo-legislativo (:ente-id ator) vereador-id id)
      (repo/buscar-parecer-para-editor repo-legislativo (:ente-id ator) id))))

(defn meu-emitir-parecer
  "Onda C4 — 'assinar em 2 toques': o vereador-relator emite (=assina) o PROPRIO parecer. MESMO gate de
  posse de meu-parecer-editor, ANTES de delegar pro Repo (que faz promover+voto+transicao+assinatura) —
  posse negada NUNCA chega a chamar emitir-parecer! (nil -> a borda traduz -> 404)."
  [repo-legislativo registro assinador resolver-vereador ator id m]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (repo/relator-do-parecer? repo-legislativo (:ente-id ator) vereador-id id)
      (repo/emitir-parecer! repo-legislativo (:ente-id ator) registro (assoc m :assinador assinador)))))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

Depois, rodar a suíte inteira do backend para confirmar que o handler existente (`emitir-parecer-handler`, ainda não atualizado — Task 8) vai quebrar de propósito até a próxima task: `cd apps/backend && clojure -M:test` — aceitar falha no diplomat aqui é esperado (fica vermelho até a Task 8); não commitar se `parecer-controllers-test` estiver verde e o resto pré-existente também.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/controllers.clj \
        apps/backend/test/unit/oplenario/legislativo/parecer_controllers_test.clj
git commit -m "feat(be): controllers meu-parecer-editor/meu-emitir-parecer + assinador em emitir-parecer (Onda C4)"
```

---

## Task 7: `wire/out`/`adapters/out` — campos de assinatura em `ParecerEditorOut`

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/wire/out/parecer.clj`
- Modify: `apps/backend/src/oplenario/legislativo/adapters/out/parecer.clj`
- Test: `apps/backend/test/unit/oplenario/legislativo/parecer_adapters_out_test.clj`

**Interfaces:**
- Produces: `ParecerEditorOut` ganha `assinatura-algoritmo`/`assinado-por`/`assinado-em` (opcionais, `[:maybe :string]`) — **não** expõe `assinatura-b64` (sem uso de UI, evita inchar payload). `editor->wire` lê esses campos de `texto-vigente` (nunca de `texto-rascunho` — um rascunho, por definição, ainda não foi assinado).

- [ ] **Step 1: Escrever o teste falhando**

Adicionar em `apps/backend/test/unit/oplenario/legislativo/parecer_adapters_out_test.clj` (mirror dos testes existentes de `editor->wire` já no arquivo — usar o mesmo shape de fixture de `parecer`/`texto-vigente` que os testes vizinhos já constroem):
```clojure
(deftest editor->wire-inclui-assinatura-do-texto-vigente
  (let [saida (adapters-out/editor->wire
                {:parecer {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (random-uuid)
                           :comissao-id (random-uuid) :estado "apresentado" :template-id (random-uuid)
                           :lock-version 1 :criado-em (java.time.Instant/now)}
                 :objeto nil :texto-rascunho nil
                 :texto-vigente {:texto-inline "## Relatório\n\nX\n\n## Análise\n\nY" :numero-versao 1
                                 :assinatura-algoritmo "STUB-ICP-v0" :assinado-por (random-uuid)
                                 :assinado-em (java.time.Instant/now)}})]
    (is (= "STUB-ICP-v0" (:assinatura-algoritmo saida)))
    (is (some? (:assinado-por saida)))
    (is (some? (:assinado-em saida)))))

(deftest editor->wire-assinatura-nil-quando-fonte-e-rascunho
  (let [saida (adapters-out/editor->wire
                {:parecer {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (random-uuid)
                           :comissao-id (random-uuid) :estado "com_relator" :template-id (random-uuid)
                           :lock-version 1 :criado-em (java.time.Instant/now)}
                 :objeto nil
                 :texto-rascunho {:texto-inline "## Relatório\n\nX" :numero-versao 2}
                 :texto-vigente nil})]
    (is (nil? (:assinatura-algoritmo saida)) "rascunho em edicao nunca esta assinado")))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.parecer-adapters-out-test`
Expected: FAIL — `ParecerEditorOut` não tem as chaves; `editor->wire` não as popula.

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/wire/out/parecer.clj`, adicionar ao final de `ParecerEditorOut`:
```clojure
   [:texto-estado (km/enum-de #{"rascunho" "vigente" "vazio"})]
   [:texto-numero-versao {:optional true} [:maybe :int]]
   ;; Onda C4 (feature 7.3) — assinatura da versao VIGENTE (nunca do rascunho, que ainda nao foi assinado).
   ;; `assinatura-b64` NAO exposta (sem uso de UI; o valor bruto so' interessa ao backend/prova).
   [:assinatura-algoritmo {:optional true} [:maybe :string]]
   [:assinado-por {:optional true} [:maybe :string]]
   [:assinado-em {:optional true} [:maybe :string]]])
```
Em `apps/backend/src/oplenario/legislativo/adapters/out/parecer.clj`, na função `editor->wire`, acrescentar as 3 chaves no mapa validado:
```clojure
    (validado wire/ParecerEditorOut
              {:id (->str (:id parecer)) :objeto-tipo (:objeto-tipo parecer)
               :objeto-id (->str (:objeto-id parecer)) :comissao-id (->str (:comissao-id parecer))
               :relator-id (->str (:relator-id parecer)) :voto-relator (:voto-relator parecer)
               :estado (:estado parecer) :template-id (->str (:template-id parecer))
               :lock-version (:lock-version parecer) :criado-em (->str (:criado-em parecer))
               :objeto (when objeto (objeto->wire objeto))
               :relatorio relatorio :analise analise
               :texto-estado texto-estado
               :texto-numero-versao (:numero-versao fonte)
               :assinatura-algoritmo (:assinatura-algoritmo texto-vigente)
               :assinado-por (->str (:assinado-por texto-vigente))
               :assinado-em (->str (:assinado-em texto-vigente))}
              "editor de parecer")))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/wire/out/parecer.clj \
        apps/backend/src/oplenario/legislativo/adapters/out/parecer.clj \
        apps/backend/test/unit/oplenario/legislativo/parecer_adapters_out_test.clj
git commit -m "feat(be): ParecerEditorOut expoe a assinatura da versao vigente (Onda C4)"
```

---

## Task 8: Diplomat — rotas `/meu/pareceres/:id` (GET) e `/meu/pareceres/:id/emissao` (POST)

**Files:**
- Modify: `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`
- Test: `apps/backend/test/integration/oplenario/legislativo/meu_painel_http_in_test.clj` — NÃO usar; criar `apps/backend/test/integration/oplenario/legislativo/meu_parecer_http_in_test.clj` novo (mirror do `fake-repo`/`service-fn` de `meu_painel_http_in_test.clj`).

**Interfaces:**
- Consumes: `controllers/meu-parecer-editor`/`controllers/meu-emitir-parecer` (Task 6), `assinador-icp/assinador-stub` (existente).
- Produces: rota `GET /meu/pareceres/:id` (papel `vereador`) e `POST /meu/pareceres/:id/emissao` (papel `vereador`); a rota existente `POST /legislativo/pareceres/:id/emissao` (papel `secretario`) passa a assinar também (constrói `assinador-icp/assinador-stub` inline).

- [ ] **Step 1: Escrever o teste falhando**

Criar `apps/backend/test/integration/oplenario/legislativo/meu_parecer_http_in_test.clj`:
```clojure
(ns oplenario.legislativo.meu-parecer-http-in-test
  "Onda C4 (feature 7.3) — a borda HTTP /meu/pareceres: GET (leitura p/ assinar) + POST .../emissao
  (assinar=emitir). DB-free (Repo FAKE, mesmo racional de meu-painel-http-in-test): a logica de
  posse/assinatura ja tem cobertura de integracao real em parecer-repo-test/parecer-controllers-test. Foco
  AQUI e' a borda: gate de papel 'vereador' + o contrato 404-sem-distinguir-motivo quando o parecer nao e'
  do vereador ATOR."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(defn- fake-repo-legislativo [{:keys [relator? parecer emitido]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (relator-do-parecer? [_ _ente-id _vereador-id _parecer-id] relator?)
    (buscar-parecer-para-editor [_ _ente-id _id] parecer)
    (emitir-parecer! [_ _ente-id _registro m] (when emitido (reset! emitido m)) (:parecer parecer))))

(defn- fake-repo-identidade [] 
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id] {:vinculo-ativo {:id (random-uuid) :tipo "vereador"} :papeis #{"vereador"}})))

(defn- fake-repo-cadastros [resolver]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ ente-id identidade-id]
      (when-let [v (resolver ente-id identidade-id)] {:id v}))))

(defn- service-fn [repo-l resolver-vereador]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade)
                                   :repo-legislativo repo-l
                                   :repo-cadastros (fake-repo-cadastros resolver-vereador)
                                   :registro :registro-fake
                                   :relogio (constantly (Instant/parse "2026-07-11T12:00:00Z"))})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id] (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest get-meu-parecer-404-quando-nao-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        svc (service-fn (fake-repo-legislativo {:relator? false :parecer nil}) (fn [_ _] vereador))
        r (pt/response-for svc :get (str "/meu/pareceres/" pid) :headers (com-bearer (token ente identidade)))]
    (is (= 404 (:status r)))))

(deftest get-meu-parecer-200-quando-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        parecer {:parecer {:id pid :objeto-tipo "proposicao" :objeto-id (random-uuid) :comissao-id (random-uuid)
                           :estado "com_relator" :template-id (random-uuid) :lock-version 0
                           :criado-em (Instant/now)}
                 :objeto nil :texto-rascunho {:texto-inline "## Relatório\n\nX" :numero-versao 1} :texto-vigente nil}
        svc (service-fn (fake-repo-legislativo {:relator? true :parecer parecer}) (fn [_ _] vereador))
        r (pt/response-for svc :get (str "/meu/pareceres/" pid) :headers (com-bearer (token ente identidade)))]
    (is (= 200 (:status r)))
    (is (= (str pid) (:id (ler-json r))))))

(deftest post-meu-emissao-404-quando-nao-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        svc (service-fn (fake-repo-legislativo {:relator? false :parecer nil}) (fn [_ _] vereador))
        r (pt/response-for svc :post (str "/meu/pareceres/" pid "/emissao")
            :headers (com-bearer (token ente identidade))
            :body (json/write-value-as-string {:voto-relator "favoravel" :lock-version 0}))]
    (is (= 404 (:status r)))))

(deftest post-meu-emissao-200-e-assina-quando-e-o-relator
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid) pid (random-uuid)
        emitido (atom nil)
        parecer {:parecer {:id pid :objeto-tipo "proposicao" :objeto-id (random-uuid) :comissao-id (random-uuid)
                           :estado "com_relator" :template-id (random-uuid) :lock-version 0
                           :criado-em (Instant/now)}
                 :objeto nil :texto-rascunho {:texto-inline "## Relatório\n\nX" :numero-versao 1} :texto-vigente nil}
        svc (service-fn (fake-repo-legislativo {:relator? true :parecer parecer :emitido emitido}) (fn [_ _] vereador))
        r (pt/response-for svc :post (str "/meu/pareceres/" pid "/emissao")
            :headers (com-bearer (token ente identidade))
            :body (json/write-value-as-string {:voto-relator "favoravel" :lock-version 0}))]
    (is (= 200 (:status r)))
    (is (some? (:assinador @emitido)) "o handler construiu e repassou o assinador-stub")))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && clojure -M:test --focus oplenario.legislativo.meu-parecer-http-in-test`
Expected: FAIL — as rotas `/meu/pareceres/:id`(...) não existem ainda (404 genérico do Pedestal, não o 404 tratado do handler; ou erro de rota não encontrada).

- [ ] **Step 3: Implementar**

Em `apps/backend/src/oplenario/legislativo/diplomat/http/in.clj`: adicionar o require de `assinador-icp` e os 2 novos handlers logo após `emitir-parecer-handler` (~linha 208), e atualizar o handler existente pra passar o assinador:
```clojure
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
```
```clojure
(defn- emitir-parecer-handler
  "POST /legislativo/pareceres/:id/emissao. Onda C4: constroi o assinador STUB inline (mesmo padrao de
  gerar-artefato-publicacao!) — a assinatura acontece dentro de Repo/emitir-parecer!, nao aqui."
  [repo-leg registro relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          agora (tempo/hoje relogio zona-civil)]
      (if-let [{:keys [parecer]} (controllers/buscar-parecer-editor repo-leg ente-id id)]
        (let [m (adapters-in-parecer/emitir->dominio ator id (:template-id parecer) agora (:json-params req))]
          (controllers/emitir-parecer repo-leg registro (assinador-icp/assinador-stub) ente-id m)
          (http/json-resposta 200 (adapters-out-parecer/editor->wire
                                     (controllers/buscar-parecer-editor repo-leg ente-id id))))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

(defn- meu-parecer-editor-handler
  "GET /meu/pareceres/:id (Onda C4, feature 7.3). Gate grosso 'vereador' na rota; gate de posse
  (relator-do-parecer?) no controller — 404 sem distinguir 'nao existe' de 'nao e' seu' (mesmo contrato de
  acusar-ciencia-handler)."
  [repo-leg resolver-vereador]
  (fn [req]
    (let [ator (:ator req)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [dados (controllers/meu-parecer-editor repo-leg resolver-vereador ator id)]
        (http/json-resposta 200 (adapters-out-parecer/editor->wire dados))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

(defn- meu-emitir-parecer-handler
  "POST /meu/pareceres/:id/emissao (Onda C4) — 'assinar em 2 toques'. Mesmo gate de posse de
  meu-parecer-editor-handler ANTES de tentar emitir; o TEMPLATE-ID vem do parecer JA' CARREGADO por
  meu-parecer-editor (mesmo pre-check tambem serve de gate 404 — mesmo padrao de emitir-parecer-handler)."
  [repo-leg registro relogio resolver-vereador]
  (fn [req]
    (let [ator (:ator req)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          agora (tempo/hoje relogio zona-civil)]
      (if-let [{:keys [parecer]} (controllers/meu-parecer-editor repo-leg resolver-vereador ator id)]
        (let [m (adapters-in-parecer/emitir->dominio ator id (:template-id parecer) agora (:json-params req))]
          (if (controllers/meu-emitir-parecer repo-leg registro (assinador-icp/assinador-stub)
                                              resolver-vereador ator id m)
            (http/json-resposta 200 (adapters-out-parecer/editor->wire
                                       (controllers/meu-parecer-editor repo-leg resolver-vereador ator id)))
            (http/json-resposta 404 {:erro "parecer nao encontrado"})))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))
```
E em `rotas` (dentro do `#{...}` que já contém `/meu/painel` e `/meu/ciencias`), adicionar:
```clojure
      ["/meu/pareceres/:id" :get [auth papel-vereador (meu-parecer-editor-handler repo-legislativo resolver-vereador)]
       :route-name :legislativo/meu-parecer-editor]
      ["/meu/pareceres/:id/emissao" :post
       [auth papel-vereador it/corpo-json (meu-emitir-parecer-handler repo-legislativo registro relogio resolver-vereador)]
       :route-name :legislativo/meu-emitir-parecer]
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

Depois, rodar a suíte completa do backend: `cd apps/backend && clojure -M:test`. Expected: PASS em tudo (incluindo os testes pré-existentes de `/legislativo/pareceres/:id/emissao`, que agora também assina via o mesmo `assinador-icp/assinador-stub` — nenhum teste antigo deveria depender da ausência de assinatura).

- [ ] **Step 5: Revisão `ecc` (clojure-reviewer + security-reviewer + database-reviewer)**

Rodar `/code-review` sobre o diff completo das Tasks 1-8 (o corte natural de "backend pronto"). Atenção: authz do papel `vereador` + guard de posse compostos corretamente (nenhum caminho de escrita alcança `Repo/emitir-parecer!` sem checar `relator-do-parecer?` antes).

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/oplenario/legislativo/diplomat/http/in.clj \
        apps/backend/test/integration/oplenario/legislativo/meu_parecer_http_in_test.clj
git commit -m "feat(be): rotas /meu/pareceres/:id (GET) e .../emissao (POST) — assinar em 2 toques (Onda C4)"
```

---

## Task 9: Codegen — regenerar `contrato-legislativo.gen.ts`

**Files:**
- Modify (gerado, não editar a mão): `apps/frontend/src/lib/contrato-legislativo.gen.ts`

**Interfaces:**
- Consumes: `wire/out/parecer.ParecerEditorOut` (Task 7).
- Produces: `ParecerEditorOut` (TS) com `assinaturaAlgoritmo?`/`assinadoPor?`/`assinadoEm?`.

- [ ] **Step 1: Regenerar**

Run:
```bash
cd apps/backend && clojure -M -m oplenario.codegen.gerar-legislativo ../frontend/src/lib/contrato-legislativo.gen.ts
```
Expected: `[oplenario] tipos TS de legislativo gerados em ../frontend/src/lib/contrato-legislativo.gen.ts (N interfaces)` (mesmo N de antes — `ParecerEditorOut` já está no manifesto, só ganhou campos).

- [ ] **Step 2: Conferir visualmente**

Read `apps/frontend/src/lib/contrato-legislativo.gen.ts`, confirmar que a interface `ParecerEditorOut` ganhou:
```typescript
export interface ParecerEditorOut {
  // ...campos existentes inalterados...
  assinaturaAlgoritmo?: string | null;
  assinadoPor?: string | null;
  assinadoEm?: string | null;
}
```

- [ ] **Step 3: Commit**

```bash
git add apps/frontend/src/lib/contrato-legislativo.gen.ts
git commit -m "chore(fe): regenera contrato-legislativo.gen.ts (assinatura em ParecerEditorOut, Onda C4)"
```

---

## Task 10: Frontend — hooks `useMeuParecer`/`useMeuEmitirParecer`

**Files:**
- Create: `apps/frontend/src/lib/use-meu-parecer.ts`
- Create: `apps/frontend/src/lib/use-meu-parecer.test.ts`
- Create: `apps/frontend/src/lib/use-meu-emitir-parecer.ts`
- Create: `apps/frontend/src/lib/use-meu-emitir-parecer.test.ts`

**Interfaces:**
- Consumes: `GET /api/meu/pareceres/:id`, `POST /api/meu/pareceres/:id/emissao` (Next rewrite `/api/:path* -> backend`, já configurado em `next.config`).
- Produces: `useMeuParecer(token, id)` — mirror EXATO de `useParecerEditor` (mesmo idioma 3-estados); `useMeuEmitirParecer(token, id)` — mirror EXATO de `useEmitirParecer`, devolvendo `{ emitir, estado, erro }`.

- [ ] **Step 1: Escrever os testes falhando**

`apps/frontend/src/lib/use-meu-parecer.test.ts` — copiar 1:1 os casos de `use-parecer-editor.test.ts` (carregando→pronto, 404→null, troca de `id` reseta, `recarregar()`), trocando só a URL esperada no mock de `fetch` para `/api/meu/pareceres/${id}`.

`apps/frontend/src/lib/use-meu-emitir-parecer.test.ts` — copiar 1:1 os casos de `use-emitir-parecer.test.ts` (envio ok, erro do backend nunca engolido, guard de envio duplo), trocando a URL esperada para `/api/meu/pareceres/${id}/emissao`.

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/frontend && npx vitest run src/lib/use-meu-parecer.test.ts src/lib/use-meu-emitir-parecer.test.ts`
Expected: FAIL — os módulos `./use-meu-parecer`/`./use-meu-emitir-parecer` não existem.

- [ ] **Step 3: Implementar**

`apps/frontend/src/lib/use-meu-parecer.ts` (mirror de `use-parecer-editor.ts`, só a URL muda):
```typescript
"use client";

// Hook de detalhe do parecer p/ o VEREADOR-RELATOR (Onda C4, feature 7.3) — GET /api/meu/pareceres/:id.
// Mirror EXATO de use-parecer-editor.ts (mesmo idioma 3-estados, mesmo contrato de `recarregar()`); a
// UNICA diferenca e' a URL (borda /meu, gate 'vereador' + posse — legislativo/diplomat/http/in.clj).

import { useCallback, useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

async function buscarMeuParecer(token: string, id: string): Promise<ParecerEditorOut | null> {
  const r = await fetch(`/api/meu/pareceres/${encodeURIComponent(id)}`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as ParecerEditorOut;
}

export function useMeuParecer(token: string | null, id: string | null) {
  const [dados, setDados] = useState<ParecerEditorOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);
  const vivoRef = useRef(true);
  const idAtualRef = useRef(id);
  useEffect(() => {
    idAtualRef.current = id;
  }, [id]);

  if (id !== idAnterior) {
    setIdAnterior(id);
    setEstado(id ? "carregando" : "pronto");
  }

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!id) return;
    if (!token) return;
    let vivo = true;
    setEstado("carregando");
    buscarMeuParecer(token, id).then((d) => {
      if (!vivo || !vivoRef.current) return;
      if (idAtualRef.current !== id) return;
      setDados(d);
      setEstado(d ? "pronto" : "erro");
    });
    return () => {
      vivo = false;
    };
  }, [token, id]);

  const recarregar = useCallback(async () => {
    if (!token || !id) return;
    const d = await buscarMeuParecer(token, id);
    if (!vivoRef.current || idAtualRef.current !== id) return;
    setDados(d);
    setEstado(d ? "pronto" : "erro");
  }, [token, id]);

  return { dados, estado, recarregar };
}
```

`apps/frontend/src/lib/use-meu-emitir-parecer.ts` (mirror de `use-emitir-parecer.ts`, só a URL muda):
```typescript
"use client";

// Hook de mutação — POST /api/meu/pareceres/:id/emissao (Onda C4, feature 7.3 — "assinar em 2 toques").
// Mirror EXATO de use-emitir-parecer.ts (mesmo contrato de estado/erro/guard de envio duplo); a UNICA
// diferenca e' a URL (borda /meu, gate 'vereador' + posse).

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

export type EmitirParecerIn = { votoRelator: string; lockVersion: number };

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: EmitirParecerIn): Record<string, unknown> {
  return Object.fromEntries(Object.entries(corpo).map(([k, v]) => [paraKebab(k), v]));
}

export function useMeuEmitirParecer(token: string | null, id: string) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function emitir(corpo: EmitirParecerIn): Promise<ParecerEditorOut> {
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
      const r = await fetch(`/api/meu/pareceres/${id}/emissao`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao assinar parecer (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as ParecerEditorOut;
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

  return { emitir, estado, erro };
}
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/use-meu-parecer.ts apps/frontend/src/lib/use-meu-parecer.test.ts \
        apps/frontend/src/lib/use-meu-emitir-parecer.ts apps/frontend/src/lib/use-meu-emitir-parecer.test.ts
git commit -m "feat(fe): hooks useMeuParecer/useMeuEmitirParecer (Onda C4)"
```

---

## Task 11: Frontend — página `(vereador)/parecer/[id]/assinar` (2 toques)

**Files:**
- Create: `apps/frontend/src/lib/assinatura-vista.ts`
- Create: `apps/frontend/src/lib/assinatura-vista.test.ts`
- Create: `apps/frontend/src/app/(vereador)/parecer/[id]/assinar/page.tsx`
- Create: `apps/frontend/src/app/(vereador)/parecer/[id]/assinar/assinar.css`

**Interfaces:**
- Consumes: `useMeuParecer`, `useMeuEmitirParecer` (Task 10), `useAuth` (existente), `rotularVoto`/`VOTO_OPCOES` (`parecer-vista.ts`, existente).
- Produces: `deriveEstadoAssinatura(dados: ParecerEditorOut | null): "sem-texto" | "pronto-pra-revisar"` (view-model puro, testável sem rede); a página `PaginaAssinarParecer`.

- [ ] **Step 1: Escrever o teste do view-model (falhando)**

`apps/frontend/src/lib/assinatura-vista.ts` ainda não existe — criar o teste primeiro:
```typescript
import { describe, expect, it } from "vitest";
import { deriveEstadoAssinatura } from "./assinatura-vista";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

const base: ParecerEditorOut = {
  id: "p1", objetoTipo: "proposicao", objetoId: "obj1", comissaoId: "c1",
  estado: "com_relator", templateId: "t1", lockVersion: 0, criadoEm: "2026-07-11T00:00:00Z",
  textoEstado: "vazio",
};

describe("deriveEstadoAssinatura", () => {
  it("sem-texto quando textoEstado é vazio", () => {
    expect(deriveEstadoAssinatura(base)).toBe("sem-texto");
  });
  it("pronto-pra-revisar quando há rascunho ou vigente", () => {
    expect(deriveEstadoAssinatura({ ...base, textoEstado: "rascunho", relatorio: "X", analise: "Y" })).toBe(
      "pronto-pra-revisar"
    );
  });
  it("sem-texto quando dados é null", () => {
    expect(deriveEstadoAssinatura(null)).toBe("sem-texto");
  });
});
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/frontend && npx vitest run src/lib/assinatura-vista.test.ts`
Expected: FAIL — módulo não existe.

- [ ] **Step 3: Implementar o view-model**

`apps/frontend/src/lib/assinatura-vista.ts`:
```typescript
// View-model puro da página de assinatura em 2 toques (Onda C4, feature 7.3) — porte de
// produto/design-system/o-plenario/telas/assinatura-2-toques.html. `textoEstado` já vem DERIVADO do
// backend (adapters/out/parecer.clj: rascunho > vigente > vazio) — aqui só traduz pra "dá ou não dá pra
// mostrar a ilha-papel de revisão".

import type { ParecerEditorOut } from "./contrato-legislativo.gen";

export type EstadoAssinatura = "sem-texto" | "pronto-pra-revisar";

export function deriveEstadoAssinatura(dados: ParecerEditorOut | null): EstadoAssinatura {
  if (!dados) return "sem-texto";
  return dados.textoEstado === "vazio" ? "sem-texto" : "pronto-pra-revisar";
}
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Implementar a página**

`apps/frontend/src/app/(vereador)/parecer/[id]/assinar/page.tsx` — porte de `assinatura-2-toques.html` (toque 1 = ilha-papel de revisão + sumário; toque 2 = sheet de confirmação biométrica MOCK, dispara `emitir`):
```typescript
"use client";

// Assinatura em 2 toques do vereador-relator (Onda C4, feature 7.3) — porte 1:1 de
// produto/design-system/o-plenario/telas/assinatura-2-toques.html. Toque 1 abre a ilha-papel (leitura);
// toque 2 abre a folha de confirmação (biometria MOCK LOCAL — sem WebAuthn/gov.br real, fast-follow Onda
// D já decidido no plano da track) e dispara useMeuEmitirParecer.emitir(). Backend assina de verdade
// (Repo/emitir-parecer! + assinador-icp stub) — esta página só orquestra a UI do ritual.

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useMeuParecer } from "@/lib/use-meu-parecer";
import { useMeuEmitirParecer } from "@/lib/use-meu-emitir-parecer";
import { deriveEstadoAssinatura } from "@/lib/assinatura-vista";
import { rotularVoto } from "@/lib/parecer-vista";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import "./assinar.css";

export default function PaginaAssinarParecer() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { token } = useAuth();
  const { dados, estado } = useMeuParecer(token, id);
  const { emitir, estado: estadoEmissao, erro } = useMeuEmitirParecer(token, id);
  const [sheetAberta, setSheetAberta] = useState(false);

  if (estado === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }
  if (estado === "erro" || !dados) {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar este parecer</h1>
        <p>Ele pode não existir, ou você não é o relator designado.</p>
      </main>
    );
  }

  const situacao = deriveEstadoAssinatura(dados);

  async function confirmar() {
    if (!dados) return;
    try {
      await emitir({ votoRelator: dados.votoRelator ?? "favoravel", lockVersion: dados.lockVersion });
      setSheetAberta(false);
      router.push("/vereador");
    } catch {
      // erro já fica exposto via `erro` (useMeuEmitirParecer) — o botão da sheet mostra a mensagem.
    }
  }

  return (
    <div className="app">
      <header className="app-topo">
        <button className="voltar" type="button" aria-label="Voltar" onClick={() => router.back()}>
          ←
        </button>
        <span className="tit">Assinar parecer</span>
      </header>

      <main className="conteudo">
        <div className="toques" aria-hidden="true">
          <span className="toque on">
            <span className="n">1</span>Revisar
          </span>
          <span className="liga" />
          <span className={`toque ${sheetAberta ? "on" : ""}`}>
            <span className="n">2</span>Confirmar
          </span>
        </div>

        {situacao === "sem-texto" ? (
          <p className="vazio">Ainda sem texto pronto para assinar.</p>
        ) : (
          <>
            <section className="papel" aria-label="Documento a assinar">
              <div className="cab">
                <h2>Parecer da Relatoria</h2>
              </div>
              <div className="corpo">
                {dados.objeto && (
                  <>
                    <span className="rot">Matéria</span>
                    <p>{formatarNumeroProposicao(dados.objeto.tipo, dados.objeto.sequencial, dados.objeto.ano)} — {dados.objeto.ementa}</p>
                  </>
                )}
                <span className="rot">Conclusão do relator</span>
                <p>{rotularVoto(dados.votoRelator)}</p>
                <span className="rot">Fundamentação (resumo)</span>
                <p>{dados.relatorio}</p>
              </div>
            </section>

            <div className="sumario">
              <h3>O que você está assinando</h3>
              <p>É <b>ato definitivo</b>: depois de assinado, o parecer é juntado à matéria e vai à pauta.</p>
              <p>A assinatura fica <b>registrada</b>, com data e hora.</p>
            </div>
          </>
        )}
      </main>

      {situacao === "pronto-pra-revisar" && (
        <div className="assinar-bar">
          <div className="assinar-bar-in">
            <button className="btn btn-primaria" type="button" onClick={() => setSheetAberta(true)}>
              Revisar e assinar
            </button>
          </div>
        </div>
      )}

      {sheetAberta && (
        <div className="scrim" role="dialog" aria-modal="true" aria-labelledby="sh-tit">
          <div className="sheet">
            <h3 id="sh-tit">Confirmar assinatura</h3>
            <p className="sub">Use a biometria do aparelho para concluir.</p>
            {erro && (
              <p role="status" className="erro-inline">
                Não foi possível assinar: {erro}
              </p>
            )}
            <div className="acoes">
              <button
                className="btn btn-primaria"
                type="button"
                onClick={confirmar}
                disabled={estadoEmissao === "enviando"}
              >
                Confirmar com a biometria
              </button>
              <button className="btn btn-fantasma" type="button" onClick={() => setSheetAberta(false)}>
                Cancelar
              </button>
            </div>
            <p className="legal">
              Ao confirmar, você assina digitalmente este documento. A assinatura tem validade e não pode
              ser desfeita.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 6: Portar o CSS**

`apps/frontend/src/app/(vereador)/parecer/[id]/assinar/assinar.css` — portar 1:1 o bloco `<style>` de `produto/design-system/o-plenario/telas/assinatura-2-toques.html` (classes `.app`/`.app-topo`/`.toques`/`.toque`/`.papel`/`.sumario`/`.assinar-bar`/`.scrim`/`.sheet`/`.bio`/`.acoes`/`.legal` — já lidas nesta sessão de brainstorming, comparar lado a lado). Mesma disciplina de `vereador-home.css`/`formulario-parecer.css`: reusa os tokens de `chassi.css`/`tokens.css` (já linkados globalmente pelo layout `(vereador)`), não redefine cor/fonte, só o layout específico da tela.

- [ ] **Step 7: Verificar visualmente**

Run: `cd apps/frontend && docker compose up -d --build` (mesma disciplina Docker mandatória do projeto — nunca `npm run dev` solto no host).
Navegar para `http://localhost:3000/vereador/parecer/<id-de-um-parecer-com-rascunho>/assinar` com um dev-token de vereador que seja o relator; conferir os 2 temas (claro/escuro) e comparar lado a lado com `assinatura-2-toques.html` (`GUIDELINES-CHECKLIST.md`, contraste em pixel composto).

- [ ] **Step 8: Revisão `ecc` (react-reviewer + security-reviewer)**

Rodar `/code-review` sobre o diff da página + hooks. Atenção a foco/aria em modal (`scrim`/`sheet`), e a nenhum dado sensível vazando em log de erro.

- [ ] **Step 9: Commit**

```bash
git add apps/frontend/src/lib/assinatura-vista.ts apps/frontend/src/lib/assinatura-vista.test.ts \
        "apps/frontend/src/app/(vereador)/parecer/[id]/assinar/page.tsx" \
        "apps/frontend/src/app/(vereador)/parecer/[id]/assinar/assinar.css"
git commit -m "feat(fe): página de assinatura em 2 toques do parecer (Onda C4, feature 7.3)"
```

---

## Task 12: Frontend — seção "Meus pareceres" na home do vereador

**Files:**
- Modify: `apps/frontend/src/app/(vereador)/vereador/page.tsx`
- Modify: `apps/frontend/src/app/(vereador)/vereador/page.test.tsx`

**Interfaces:**
- Consumes: `HomeVereadorVista.meusPareceres.aguardando` (já computado por `meu-painel-vista.ts`, Onda C1 — hoje não renderizado).
- Produces: seção "Meus pareceres" visível quando `vista.meusPareceres.aguardando.length > 0`, cada item linkando para `/vereador/parecer/${p.id}/assinar`.

- [ ] **Step 1: Escrever o teste falhando**

Em `apps/frontend/src/app/(vereador)/vereador/page.test.tsx`, adicionar um caso que renderiza a página com `useMeuPainel` mockado devolvendo `pareceres: [{ id: "p1", objetoTipo: "proposicao", objetoId: "o1", comissaoId: "c1", estado: "com_relator", votoRelator: null, criadoEm: "2026-07-11T00:00:00Z" }]` e assere:
```typescript
it("mostra a seção Meus pareceres com link para a página de assinatura", async () => {
  // ...render com o mock acima...
  expect(screen.getByText(/Meus pareceres/i)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /assinar/i })).toHaveAttribute("href", "/vereador/parecer/p1/assinar");
});
```
(Seguir o mesmo padrão de mock de `useMeuPainel`/`useAuth`/`useAcusarCiencia` já usado nos testes vizinhos deste arquivo.)

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/frontend && npx vitest run "src/app/(vereador)/vereador/page.test.tsx"`
Expected: FAIL — a seção não existe.

- [ ] **Step 3: Implementar**

Em `apps/frontend/src/app/(vereador)/vereador/page.tsx`: importar `Link` (`next/link`), remover o comentário do carry (linhas 24-27) e adicionar a seção entre "Para sua ciência" e "Suas proposições":
```typescript
import Link from "next/link";
```
```tsx
      {vista.meusPareceres.aguardando.length > 0 && (
        <section aria-label="Meus pareceres">
          <h2 className="secao-tit">Meus pareceres</h2>
          {vista.meusPareceres.aguardando.map((p) => (
            <article className="card" key={p.id}>
              <h3>Parecer em {p.estado.replaceAll("_", " ")}</h3>
              <div className="card-acao">
                <Link className="btn btn-primaria btn-mini" href={`/vereador/parecer/${p.id}/assinar`}>
                  Revisar e assinar
                </Link>
              </div>
            </article>
          ))}
        </section>
      )}
```
E remover o comentário obsoleto (linhas 24-27 do arquivo lido nesta sessão) que documentava a ausência desta seção como carry — não é mais verdade.

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2.
Expected: PASS.

- [ ] **Step 5: Verificar visualmente**

No mesmo servidor Docker da Task 11, conferir a home do vereador (`/vereador`) com um dev-token que seja relator de ao menos 1 parecer não-terminal — confirmar que o card aparece e o link leva pra página de assinatura.

- [ ] **Step 6: Commit**

```bash
git add "apps/frontend/src/app/(vereador)/vereador/page.tsx" \
        "apps/frontend/src/app/(vereador)/vereador/page.test.tsx"
git commit -m "feat(fe): seção Meus pareceres na home do vereador (fecha o carry da Onda C1)"
```

---

## Task 13: Verificação final e2e + paridade visual

**Files:** nenhum arquivo novo — task de verificação.

- [ ] **Step 1: Suíte completa backend**

Run: `cd apps/backend && clojure -M:test`
Expected: 0 falhas (contagem exata deve ser reportada pelo executor — comparar com o baseline pré-Task-1).

- [ ] **Step 2: Suíte completa frontend**

Run: `cd apps/frontend && npx vitest run && npx tsc --noEmit && npx eslint . && npx next build`
Expected: tudo limpo.

- [ ] **Step 3: E2E vivo contra o stack real**

Com `docker compose up -d --build` rodando (backend + frontend + Postgres + MinIO), percorrer manualmente (ou via Playwright): login dev-token de vereador-relator → home do vereador → seção "Meus pareceres" → clicar "Revisar e assinar" → ilha-papel de revisão → "Revisar e assinar" (toque 1) → sheet de confirmação → "Confirmar com a biometria" (toque 2) → parecer emitido e assinado (conferir no editor desktop, `(interno)/parecer/[id]`, papel `secretario`, que os campos de assinatura aparecem na leitura). Repetir nos 2 temas (claro/escuro).

- [ ] **Step 4: Revisão final de branch (4 revisores em paralelo)**

Rodar `/code-review` (ou os agentes `ecc:clojure-reviewer`, `ecc:database-reviewer`, `ecc:security-reviewer`, `ecc:react-reviewer`) sobre o diff completo da branch `fe-18-assinatura-parecer` contra `main`. Aplicar achados CRÍTICO/MAJOR com commits de correção dedicados (nunca `--amend`).

- [ ] **Step 5: Atualizar memória/docs**

Registrar em memória (`oplenario-fe-execucao` ou equivalente) o fechamento da Onda C Slice C4 e o estado da Onda C como um todo (checar se resta alguma fatia antes do próximo macro-passo).
