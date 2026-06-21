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

## TODO (deferido p/ implementação — validação ecc)
- `relacoes/` → registro no catálogo do `motor` (depende da dobra do `motor-dsl-clj`).
- `policy.clj` por módulo (política declarativa; mecânica em `kernel/autorizacao`).
- HoneySQL **sempre** schema-qualified (`:legislativo.proposicao`); **nunca** `search_path` global (vaza entre módulos no pool compartilhado).
- `arquitetura_test.clj`: implementar a varredura real da matriz de import-lint.
- Dockerfile de produção: uberjar + `eclipse-temurin:21-jre`.
- `sistema.clj`/`legislativo/components.clj`: fiação `using` mínima como exemplo-template.
