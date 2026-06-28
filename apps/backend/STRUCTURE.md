# Monólito modular — O Plenário (backend)

Esqueleto do padrão **ports & adapters (versão Nubank)** consolidado em **§22.10** do documento-mestre.

- `src/oplenario/<ctx>/` — um módulo por bounded context (§22.2). `legislativo/` é o template completo.
  Camadas: `wire/in`·`wire/out`(externo; out→TS) `models/`(interno) `adapters/`(gate) `db/`(funções; next.jdbc+HoneySQL, **sem ORM**)
  `events/` + `logic` `controllers` `relacoes` · `diplomat/`(IO por direção: `http/in` · `http/out` · `consumers` · `producers`)
  · `components`(recursos = `defprotocol`+`defrecord` co-localizados; Stuart Sierra). **Sem pasta `port/`** —
  o protocolo de saída mora no `http/out` (dep de módulo) ou em `components/` (recurso/estratégia).
- `src/oplenario/kernel/` — compartilhado puro (não importa módulo).
- `src/oplenario/motor/` — o motor de regras (§22.7), **biblioteca compartilhada** (kernel/motor nunca
  importam módulo). **Dobrado** do protótipo `../../prototipos/motor-dsl/`: núcleo DSL real (`tipos/nucleo/catalogo/verificador/
  runtime/templates`) + fachada `api` + persistência stub `db/` (deferida §22.4.4). Schema `motor` na
  migration `…0006` (5 tabelas estáticas do Eixo B §22.7.6). Detalhe em `src/oplenario/motor/README.md`.
- `src/oplenario/{main,sistema,http}.clj` — host/composição.

Comunicação inter-módulo: **só HTTP (diplomat/http/out → diplomat/http/in) ou eventos (producers/consumers)**.

## Rodar
- Stack: `docker compose up`
- Testes: `clojure -M:test :unit` · `:integration` · `:e2e`  (suítes em `tests.edn`)

## Módulos de projeção (read-models — §22.10)
- `paineis/` (§16.11 Painéis/Pendências/Notificações) e `tempo_real/` (§22.6 eixo G, fan-out SSE).
- Silhueta enxuta: `consumers` + `db`(read-model) + `wire/`(`in`·`out`)/`models`/`adapters` + `http/in`; **sem** `logic`/`relacoes`. Rebuildáveis do event log.
- **Exceção (GAP 4):** o **ledger de entrega de notificação** (`paineis.notificacao_entrega`, migration `…0004`) é **durável/idempotente** — e-mail/push que saiu não se re-projeta; a *vista* (sininho/inbox) segue dropável. Três planos do evento: outbox (transporte) / SSE (efêmero) / notificações (durável).

## Módulo supratenant/operacional (3ª categoria — §22.10)
- `admin_sistema/` — a área do **operador SaaS**. Dono de verdade **acima da linha do tenant**: schema próprio **sem `ente_id`** (é quem *emite* o `ente_id`), **sem RLS / sem partição** `hash(ente_id)`.
- Dono de: registry de entes/provisionamento, billing/plano, feature flags/config global, principal+papéis do admin interno (RBAC **disjunto** do `usuario_papel` de tenant), auditoria do operador, observabilidade cross-tenant, grant de acesso de suporte (§22.5.2 eixo D). **Não duplica** o catálogo do `motor` — só o opera.
- Atrás do **Keycloak fisicamente separado** (§22.9 Eixo 6). Token do operador **não carrega `ente_id`** → middleware (§22.5 eixo E) só o aceita em endpoints daqui; cross-esfera = 403/404.
- Migration própria: `20260620000003-admin-sistema.*` (schema + registry `ente`). Refs a `admin_sistema.ente` de outros módulos cruzam por **guard de serviço**, nunca FK/JOIN cross-schema.

## TODO (deferido p/ implementação — validação ecc)
- `relacoes/` → registro no catálogo do `motor` (depende da dobra do `motor-dsl-clj`).
- `policy.clj` por módulo (política declarativa; mecânica em `kernel/autorizacao`).
- HoneySQL **sempre** schema-qualified (`:legislativo.proposicao`); **nunca** `search_path` global (vaza entre módulos no pool compartilhado).
- `arquitetura_test.clj`: implementar a varredura real da matriz de import-lint.
- Dockerfile de produção: uberjar + `eclipse-temurin:21-jre`.
- `sistema.clj`/`legislativo/components.clj`: fiação `using` mínima como exemplo-template.
- `compliance` (§22.7): avaliar **CHECK constraints** em `compliance_avaliacao.veredito`/`origem_avaliacao` (hoje texto+comentário, padrão do projeto — valor é gerado pelo motor em código, não input). Hardening adiado: a tabela é a prova do Invariante 10 (sem UPDATE/DELETE p/ corrigir dado corrompido). Decidir junto da dobra do `motor-dsl-clj`.
- `motor` (§22.7, **dobrado**): (a) **persistência real** do `db/*.clj` sobre as 5 tabelas do catálogo — stub hoje, deferida ao chat de stack (§22.4.4: não materializar o repositório antes); (b) **orquestração de runtime** que o `compliance` opera — o `runtime` em-memória (`atom`) é referência; produção persiste em `compliance.prazo_dominio_ativo`/`compliance_avaliacao` chamando `motor/avaliar` com **resolvedor de fatos injetado** (funções de relação via contexto dono, §22.5.3 disc.5), não o `:estado` em-memória; (c) popular `[GAP]` de `calendario_feriado`/`prazo_dominio_vigente` (conteúdo regulatório real, com o especialista em regimento). **Reconciliação registrada:** as 5 tabelas do Eixo B vão ao schema `motor` (não `compliance` como dizia `docs/06` pré-§22.10) — razão: resolução junta definição+binding, sem cross-schema JOIN (§22.10). **Seed `../motor-dsl-clj/` superseded** — candidato a remoção quando a dobra buildar verde sob kaocha.
