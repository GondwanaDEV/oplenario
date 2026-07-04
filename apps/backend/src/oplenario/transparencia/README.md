# oplenario.transparencia

Módulo (bounded context §22.2) — o portal público de transparência legislativa (§16.5).

- **Slice 1 (projeção):** read-model público (`materia`, `norma`) projetado de eventos de `legislativo`; rotas
  públicas sem auth.
- **Slice 2 (verdade de domínio):** `acompanhamento` — a subscrição do cidadão a uma matéria (seguir/deixar
  de seguir/minhas matérias), autenticada e consent-gated (§22.5). O pipeline de notificação (fan-out +
  entrega de e-mail) é do F7/paineis, não desta fatia.
- **Slice 3 (navegação do acervo):** filtro `?tipo=&ano=&numero=` sobre a legislação as-enacted (sem migration
  nova — só query-params na rota `/legislacao`).
- **Slice 4b (projeção do artefato):** `artefato_publicacao` — read-model do artefato legal ("DO-lite") que
  `legislativo` gera e emite (`artefato.publicacao.gerado`, mig 0046/0047). Rota **pública de download binário**
  `/portal/casa/:ente/legislacao/:norma_id/artefato` (o controller lê o blob do `objeto_store`; o Repo só
  resolve o ponteiro). Ponteiro sem blob = ALERTA (500), nunca 404 silencioso (âncora-antes-do-blob).

Silhueta (parcial — cresce por fatia): `wire/out` · `models/` · `adapters/in|out/` · `db/` (só importado pelo
`components/repositorio.clj`) · `controllers` · `diplomat/(http/in·http/out·consumers·producers)` ·
`components/`. Ainda **sem** `port/`, `wire/in` (nenhuma rota recebe corpo), `events/`/`logic`/`relacoes`
(o módulo não emite eventos próprios nem participa do registry de relações nesta fase).
