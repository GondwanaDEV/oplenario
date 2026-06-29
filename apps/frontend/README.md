# frontend — O Plenário (Next.js)

> A fonte de verdade de design vive em `../../produto/design-system/` (HTML/CSS autorado à mão,
> "República Luminosa", 47 telas, dual-theme). O frontend **porta** essas telas, não as reinventa.

## Stack (§22.9 eixo 8/9)

- **Next.js 16 self-host** (App Router, TS, Tailwind 4) — provider-neutral, mesmo princípio do backend.
- **3 superfícies**: portal cidadão (SSR/SSG, white-label), app interno (servidor/vereador), painel
  ao vivo (SSE sobre o bus).
- **Theming**: `tokens.css` + `chassi.css` copiados **verbatim** do design-system para `src/app/`,
  importados em `globals.css` (paleta-marca fixa + semânticos por `[data-tema]`). `tema.js` → React
  Context (`src/lib/tema.tsx`) + `localStorage('oplenario-tema')` + `prefers-color-scheme` + script
  anti-FOUC no layout.
- **Proxy same-origin**: `next.config.ts` faz rewrite `/api/* → :8888/*` (mata CORS; espelha o
  reverse-proxy de prod). `BACKEND_URL` sobrescreve o alvo.
- **Tipos do contrato**: hand-rolled em `src/lib/contrato.ts` neste 1º corte (chaves kebab fiéis ao fio);
  substituídos pelo codegen Malli→TS (Eixo 8) por fatia vertical.
- **PWA-first** (§22.9 eixo 9): responsivo + Web Push (posterior).

## Fatia entregue — painel do plenário ao vivo (FE.1, HERO M4)

Porta `produto/design-system/.../telas/sessao-ao-vivo.html` ligada às rotas reais:
- `GET /api/sessoes/:id` → estado inicial (`SessaoOut`).
- SSE `GET /api/sessoes/:id/plenario` → eventos, consumidos via **fetch+stream** (não `EventSource`,
  que não envia header `Authorization`).

Mostra **ao vivo** o que o contrato emite (`tipos-plenario`): estado da sessão, quórum/presença,
tribuna/cronômetro, inscritos. **Pauta e placar de votação** ainda não têm rota/evento (W3 fan-out) —
marcados honestamente como integração pendente, sem inventar contrato.

Núcleo lógico testado (vitest, 24 testes): `plenario-reducer.ts` (dobra os 7 eventos), `sse.ts`
(`extrairFrames`), `cronometro.ts` (decorrido com pausa/retomada).

Rota: `/sessoes/<id>/plenario?token=<json-claims>` (token de dev = idp-dev; prod = Keycloak, carry F1.4).

## Rodar local

```bash
# 1) backend de pé (de apps/backend): docker compose up -d --build  (Pedestal em :8888)
# 2) front:
npm install
npm run dev            # http://localhost:3000  (proxy /api -> :8888)
npm test               # vitest (lógica pura)
npm run build && npm run lint
```

Para o painel funcionar end-to-end é preciso uma sessão existente + um ator com vínculo ativo (seed) e
o token de dev correspondente — ver carry de E2E no relatório do slice.

## Ordem de portação (casada com o backend pronto — `docs/11` Track FE)

1. `login` / `entrar-govbr` / cadastros — após **F1** ✅
2. `editor-proposicao` / `tramitacao-board` / `ficha-materia` — **F3** ✅ (rotas via fan-out)
3. **`sessao-ao-vivo`** — **F4** ✅ painel ao vivo (esta fatia) · `telao-votacao`/`pauta` aguardam fan-out
4. `portal-cidadao` / `portal-materias` / `ouvidoria` — **F6**
5. `console-operador` — admin

## Componentes
Portar 1:1 o `chassi.css` antes de inventar; começar pelos 11 promovidos (`.topo`·`.btn`·`.chip`·
`.sinal`·`.azulejo`·`.ilha-palco`·`.ilha-papel`·`.lacre/.trilha`·`.passos`·`.govbr`·`.card`).
`GUIDELINES-CHECKLIST.md` (no design-system) = gate de revisão (contraste AA medido nos 2 temas).
