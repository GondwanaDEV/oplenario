# frontend — O Plenário (Next.js)

> **Esqueleto de intenção.** O scaffold real (`create-next-app`) entra quando o track FE começar.
> A fonte de verdade de design vive em `../../produto/design-system/` (HTML/CSS autorado à mão,
> "República Luminosa", 47 telas, dual-theme). O frontend **porta** essas telas, não as reinventa.

## Stack (§22.9 eixo 8/9)

- **Next.js self-host** (não Vercel) — provider-neutral, mesmo princípio do backend.
- **3 superfícies**: portal cidadão (SSR/SSG, white-label), app interno (servidor/vereador), painel
  ao vivo (SSE sobre o bus).
- **Theming**: `produto/design-system/o-plenario/sistema/tokens.css` → TS/Tailwind (paleta-marca fixa
  + semânticos por `[data-tema]` claro/escuro) + `tema.js` → React Context + `localStorage('oplenario-tema')`
  + `prefers-color-scheme`.
- **Tipos do contrato**: gerados do Malli do backend (`apps/backend` codegen `malli→TS`) por fatia
  vertical — o cliente tipado de cada tela chega quando o `wire/out` do módulo chega.
- **PWA-first** (§22.9 eixo 9): responsivo + Web Push.

## Ordem de portação (casada com o backend pronto — `docs/11` Track FE)

1. `login` / `entrar-govbr` / cadastros — após **F1** ✅
2. `editor-proposicao` / `proposicoes` / `tramitacao-board` / `ficha-materia` — **F3** (em curso)
3. `sessao-ao-vivo` / `telao-votacao` / `pauta-convocacao` / `ata-revisao` — **F4**
4. `portal-cidadao` / `portal-materias` / `ouvidoria` — **F6**
5. `console-operador` — admin

## Componentes
Portar 1:1 o `chassi.css` antes de inventar; começar pelos 11 promovidos (`.topo`·`.btn`·`.chip`·
`.sinal`·`.azulejo`·`.ilha-palco`·`.ilha-papel`·`.lacre/.trilha`·`.passos`·`.govbr`·`.card`).
`GUIDELINES-CHECKLIST.md` (no design-system) = gate de revisão (contraste AA medido nos 2 temas).
