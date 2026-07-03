# oplenario.transparencia

Módulo (bounded context §22.2) — o portal público de transparência legislativa (§16.5). Nesta fatia (F6c
Slice 1) é **só projeção**: consome eventos de `legislativo` e materializa um read-model público, sem
verdade própria. Silhueta enxuta (review architect, doc drift corrigido — não é a silhueta completa de
`legislativo`):

`wire/out` · `models/` · `adapters/in|out/` · `db/` (só importado pelo `components/repositorio.clj`) ·
`controllers` · `diplomat/(http/in·http/out·consumers·producers)` · `components/` — sem `port/`, sem
`wire/in` (nenhuma rota recebe corpo), sem `events/`/`logic`/`relacoes` própria (o módulo não emite eventos
nem participa do registry de relações do motor nesta fatia).
