# Monólito modular — O Plenário (backend)

Esqueleto do padrão **ports & adapters (versão Nubank)** consolidado em **§22.10** do documento-mestre.

- `src/oplenario/<ctx>/` — um módulo por bounded context (§22.2). `legislativo/` é o template completo.
  Camadas: `schema/`(externo) `models/`(interno) `adapters/`(gate) `db/`(funções) `port/`(protocolos)
  `events/` + `logic` `controllers` `diplomat/` `relacoes` `components`.
- `src/oplenario/kernel/` — compartilhado puro (não importa módulo).
- `src/oplenario/motor/` — o motor de regras (§22.7); seed em `../motor-dsl-clj/` (dobra pra cá depois).
- `src/oplenario/{main,sistema,http}.clj` — host/composição.

Comunicação inter-módulo: **só HTTP (port→http_client→http_server) ou eventos (producer/consumer)**.

## Rodar
- Stack: `docker compose up`
- Testes: `clojure -M:test :unit` · `:integration` · `:e2e`  (suítes em `tests.edn`)

## Módulos de projeção (read-models — §22.10)
- `paineis/` (§16.11 Painéis/Pendências/Notificações) e `tempo_real/` (§22.6 eixo G, fan-out SSE).
- Silhueta enxuta: `consumer` + `db`(read-model) + `schema`/`models`/`adapters` + `http_server`; **sem** `logic`/`relacoes`. Rebuildáveis do event log.
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
