# Evidência — jornadas exploratórias de 12/09/2026

Capturas de tela que sustentam dois achados **críticos** registrados em
`docs/16-ledger-prontidao.md`, seção "🔎 Exploratório de fluxo · Jornadas do PRESIDENTE e do CIDADÃO".

| Arquivo | Prova |
|---|---|
| `esic-respondido-sem-resposta.png` | A cidadã busca `ESIC-2026-000004` no balcão público e a tela mostra **"Situação: Respondido"** e nenhum texto. A resposta existe em `participacao.resposta_esic`; `db/resposta_esic/listar-do-pedido` tem **zero chamadores** em `src/` e em `test/`. A resposta a um pedido LAI é write-only. |
| `vereador-thiago.png` | O perfil **público** (sem login) de Thiago Bezerra mostra **"Presença em sessões: 0 de 3"** e, três blocos abaixo, **"Como votou: PL 016/2026 — A favor"**. A própria página publica a prova de que o voto é impossível. O voto entrou pela rota da Mesa, que não valida `vereador_id` contra o roster (sem FK, sem CHECK). |

Reprodução completa de cada um no ledger.
