# oplenario.transparencia

Módulo (bounded context §22.2) — o portal público de transparência legislativa (§16.5).

- **Slice 1 (projeção):** read-model público (`materia`, `norma`) projetado de eventos de `legislativo`; rotas
  públicas sem auth.
- **Slice 2 (verdade de domínio):** `acompanhamento` — a subscrição do cidadão a uma matéria (seguir/deixar
  de seguir/minhas matérias), autenticada e consent-gated (§22.5). O pipeline de notificação (fan-out +
  entrega de e-mail) é do F7/paineis, não desta fatia.

Silhueta (parcial — cresce por fatia): `wire/out` · `models/` · `adapters/in|out/` · `db/` (só importado pelo
`components/repositorio.clj`) · `controllers` · `diplomat/(http/in·http/out·consumers·producers)` ·
`components/`. Ainda **sem** `port/`, `wire/in` (nenhuma rota recebe corpo), `events/`/`logic`/`relacoes`
(o módulo não emite eventos próprios nem participa do registry de relações nesta fase).
