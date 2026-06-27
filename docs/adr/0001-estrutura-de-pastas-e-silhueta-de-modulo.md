# ADR-0001 — Estrutura de pastas e silhueta de módulo

- **Status:** Aceito · 2026-06-26
- **Decisor:** Daouda Traore (CTO)
- **Fonte canônica:** §22.10 do `documento-mestre-camaras.md` (SSOT). Esta ADR **consolida e fixa** a
  forma vigente após os refactors `refactor(silhueta)` (`34dfeac` dissolução de `port/`; `0fa7d98`
  `schema/`→`wire/in`+`wire/out`). Em conflito, a SSOT (§22.10 + esta ADR) prevalece sobre memória de chat.
- **Aplica-se a:** todo código novo do backend (`backend/`) durante toda a vida da plataforma.

## Contexto

O backend é um **monólito modular** (§22.2): um módulo por *bounded context*, isolado por **schema
Postgres homônimo**, comunicando-se **só por HTTP ou eventos** — nunca import direto. O padrão por
módulo é **ports & adapters, versão Nubank (Diplomat Architecture)**. Sem uma forma de pasta única e
**verificada por máquina**, cada módulo driftaria a estrutura, reintroduzindo acoplamento (a "bola de
pelo" que o §22.2 alerta) e quebrando a fronteira core↔apresentação (Inv. 5). Esta ADR crava a forma e
**como ela é garantida** ao longo do desenvolvimento.

## Decisão

### 1. Camadas do workspace (`~/oplenario/`)
| Pasta | Papel |
|---|---|
| `documento-mestre-camaras.md` + `arquitetura/` | **SSOT** — decisões consolidadas (§1–24); `arquitetura/` = §22 densa. Prevalece. |
| `docs/` (+ `docs/adr/`) | discovery: rascunhos por eixo, plano de execução, **ADRs**. |
| `produto/` | produto/comercial: PRD, features, NFRs, GTM + `design-system/`. |
| `backend/` | engenharia (o monólito Clojure). |
| `frontend/` | (futuro — Track FE0: Next self-host + o TS gerado). |

### 2. Backend — raiz de namespace e host
- Raiz de namespace **`oplenario.*`** mantida (`backend/src/oplenario/...`). O `oplenario` sob `src/` é
  o segmento-raiz do namespace (convenção Clojure anti-colisão), **não** repetição da pasta do workspace.
- `kernel/` = compartilhado **puro**; `motor/` = lib da DSL de regras (§22.7); `codegen/` = build tool;
  `{main,sistema,http,config,migracao}.clj` = host/composição. **`kernel` e `motor` NUNCA importam um módulo.**

### 3. Silhueta de um módulo (`backend/src/oplenario/<ctx>/`)
1 módulo = 1 *bounded context* = 1 schema Postgres. Camadas (pasta quando há ≥1 ns por agregado; arquivo até crescer):

| Camada | Papel |
|---|---|
| **`wire/in`** · **`wire/out`** | representação **EXTERNA** (contrato de borda, Malli). `in` = entrada (request / evento consumido); `out` = saída (resposta / evento emitido) — **`wire/out` gera os tipos TS** do front (Eixo 8). |
| **`models/`** | representação **INTERNA** (domínio), Malli. |
| **`adapters/`** | o **gate** `wire↔models`, **sempre atravessado**: valida, traduz, filtra. |
| **`db/`** | persistência: **funções** sobre a `tx` do tenant (next.jdbc + HoneySQL, **schema-qualified**). |
| **`events/`** | eventos publicados/consumidos (nome + schema Malli do payload). |
| **`relacoes/`** | funções de relação que o ctx é dono (§22.5.3) → registry do motor (DSL/authz). |
| `logic` | núcleo **puro** (regras, máquinas de estado; zero I/O). |
| `controllers` | orquestração **impura** (coordena logic + db + components). |
| **`diplomat/`** | fronteira de IO por **DIREÇÃO**: `http/in` (server, rotas-dado Pedestal) · `http/out` (client p/ outro módulo — **protocolo + impl co-localizados**) · `consumers` (inbound eventos) · `producers` (outbound eventos). |
| **`components`** | Stuart Sierra (`Lifecycle` + `using`). **Todo recurso externo é um Component com `defprotocol` + `defrecord` co-localizados** (datasource, cache, objeto_store, idp, inferência, http-clients, serializadores/transportes) → trocável por config, fakeável em teste. |

### 4. NÃO existe pasta `port/`
O protocolo de uma dependência de saída **mora junto de quem o implementa**: no `diplomat/http/out`
(dep de outro módulo) ou em `components/` (recurso/estratégia). O conceito de *port* (protocolo)
permanece — é o que torna o recurso um Component trocável; o que sai é a **pasta** redundante.

### 5. NÃO existe `schema/`
A camada de representação externa é **`wire/in` + `wire/out`** (não `schema/`, não `wire`-único).
*(Cuidado: "schema" ainda é termo válido para **schema Postgres** — `schema-por-módulo` — que é outra coisa.)*

### 6. Comunicação inter-módulo
**Só** HTTP (`diplomat/http/out` → `diplomat/http/in`) **ou** eventos (`producers` → outbox → `consumers`).
**Proibido:** import de namespace de outro módulo; FK/JOIN cross-schema (refs supratenant/polimórficas
cruzam por **guard de serviço**).

### 7. Dados — **sem ORM**
`next.jdbc` (execução) + **HoneySQL** (queries, schema-qualified). ORM é vetado: esconde o SQL e
brigaria com o controle explícito de conexão/role/GUC da RLS (`kernel/tenancy/com-tenant*`).

## Como é GARANTIDO (enforcement) durante o desenvolvimento

A forma não depende de disciplina humana — é **verificada por máquina, falha o build**:

1. **`estrutura-lint`** (`backend/test/unit/oplenario/estrutura_lint_test.clj`): varre `src/` e **falha**
   se reaparecer uma pasta `port/` ou `schema/` (decisões 4 e 5). Tem teste-de-dentes (prova que detecta).
2. **`import-lint`** (`arquitetura_test.clj`, já existente): clj-kondo sobre a matriz §22.10 — módulo
   nunca importa outro módulo; `kernel`/`motor` nunca importam módulo. Falha o build em violação.
3. **`migracoes-lint`**: `timestamptz` sempre (companheiro da convenção de tipos).
4. **CI** (`.github/workflows/ci.yml`) roda `clojure -M:test` → os lints acima barram o merge.
5. **CLAUDE.md** (handoff lido toda sessão) aponta para esta ADR como autoridade da estrutura.
6. **Review `ecc`** (clojure/database/security) antes de cada commit de fase, com esta ADR como régua.

## Consequências

- **Positivas:** estrutura previsível ("onde está X" é sempre o mesmo lugar); fronteiras preservadas;
  recursos trocáveis/fakeáveis (Component); regressão estrutural é erro de build, não revisão manual.
- **Custo:** a forma é prescritiva — um módulo novo copia o template (`legislativo/`) e não inventa pasta.
- **Materialização gradual:** `cadastros`/`identidade` (F1) ainda não têm `wire/`/`diplomat/` (nascem na
  F3, com endpoint). Os módulos-stub já estão na forma nova; a lint impede recriar as antigas.

## Alternativas consideradas e descartadas

- **`port/` como pasta separada** (forma original do §22.10) — redundante: o protocolo já vive com o
  Component; manter a pasta dobrava a navegação. **Descartado** (refactor `34dfeac`).
- **`schema/` único / `wire/` único** — perde a distinção entrada↔saída que o front e o versionamento de
  contrato pedem. **Descartado** em favor de `wire/in` + `wire/out` (`0fa7d98`).
- **ORM (Toucan2 etc.)** — esconde o SQL, incompatível com a disciplina de RLS/tenant. **Descartado.**
- **`db` sob `diplomat`** (Nubank estrito) — o §22.10 manteve `db/` no topo de propósito (testa contra
  Postgres real, sem fake-DB). **Mantido** como divergência consciente.
- **Largar a raiz `oplenario.*`** — namespaces de 1 segmento colidem com libs. **Descartado.**

---
*Próximas decisões estruturais entram como ADRs novas (`docs/adr/NNNN-*.md`) — ver `docs/adr/README.md`.*
