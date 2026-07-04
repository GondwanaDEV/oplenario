# docs/13 — Plano de execução da Track FE (Frontend)

> **O análogo FE do `docs/11` (plano de engenharia do backend).** Sequencia a materialização das 47 telas do
> design-system (`produto/design-system/o-plenario/telas/`) sobre a API real. Substitui o esboço de ~8 linhas
> da seção "Track FE" de `docs/11` (que já divergiu na prática e ficou obsoleto após F1–F7 entrarem em `main`).
>
> **Regra-mãe (decisão Daouda, 29/06, mantida):** não fazer fan-out amplo de rotas de backend antes de ter FE
> que as consuma — puxar a borda HTTP **just-in-time por fatia**. Cada fatia = branch → TDD → review `ecc`
> (react + security) → merge. Ferramentas fixas: `frontend-design` (direção+copy) + `ui-ux-pro-max`
> (catálogo/UX/charts). Contraste AA medido nos 2 temas (`GUIDELINES-CHECKLIST.md`).

---

## 1. Onde a Track FE está (estado real, 04/07/2026)

- **App:** `apps/frontend/` — Next.js 16 (App Router, TS, Tailwind 4, React 19), vitest p/ lógica pura.
- **Fundação já assentada (FE.1):** `tokens.css`+`chassi.css` portados verbatim; tema claro/escuro (Context +
  anti-FOUC + localStorage); proxy same-origin `/api/*`→:8888; contrato TS **hand-rolled** kebab-fiel
  (`src/lib/contrato.ts`); SSE via **fetch+stream** (`EventSource` não manda `Authorization`).
- **Única vertical PORTADA e em `main`:** `/sessoes/[id]/plenario` — o plenário ao vivo (presença/quórum,
  tribuna/cronômetro, **placar de votação** com sigilo §22.6 por construção). Cobre **servidor + vereador**.
- **O que ainda NÃO existe no FE:** um **app shell** (a página do plenário é avulsa — sem nav/layout/auth-guard
  compartilhado); qualquer tela **não-plenário**; auth de produção (usa dev-token `?token=`, Keycloak = carry).

## 2. O que mudou desde o esboço de `docs/11` (por que re-planejar)

1. **A régua "casado com o backend pronto" quase não filtra mais.** Com **F1–F7 em `main`**, a maioria das
   telas já tem substrato. A pergunta virou **priorização por valor**, não por disponibilidade.
2. **A ordem escrita já foi abandonada:** começou-se pelo HERO (sessão ao vivo), não por login/cadastros.
3. **O portal público (cidadão) é hoje a superfície MAIS completa de backend** (F6 entregou um conjunto rico
   de rotas `/portal/...`), enquanto o **fluxo diário do servidor** (editor/proposições/ficha interna/parecer)
   tem domínio mas **não tem borda HTTP** — inverte a prioridade do esboço antigo.

## 3. Eixo de ordenação

Ordena-se por, nesta precedência: **(a) valor-de-demo / cobertura de persona decisora** · **(b) prontidão de
backend** (rota registrada NOW vs. precisa de fatia de fan-out) · **(c) composição de componentes compartilhados**
(assentar o shell e as primitivas cedo, para as fatias seguintes só montarem) · **(d) escopo**.

**Prontidão de backend HOJE** (inventário real das rotas registradas):

| Superfície | Rotas existem? | Telas cobertas |
|---|---|---|
| Plenário ao vivo (SSE + votação/tribuna/pauta/presença/incidentes/gravação) | ✅ | `sessao-ao-vivo`, `telao-votacao` *(já portada a base)* |
| Painéis internos (`/paineis/mesa`·`/pendencias`·`/tramitacao`·`/sli/sessoes`) + `/compliance/painel` | ✅ | `paineis-mesa`, `minhas-pendencias`, `tramitacao-board` |
| **Portal público** (`/portal/...`: matérias, legislação+artefato, e-SIC, LGPD, ouvidoria, comentários, acompanhar) | ✅ (rico) | `portal-cidadao`, `portal-materias`, `ficha-materia-publica`, `legislacao` |
| Sessão (criar/transicionar/pauta CRUD) | ✅ | `pauta-convocacao` |
| Back-office cidadão (servidor responde e-SIC/LGPD/ouvidoria; modera comentários) | ✅ | `ouvidoria`, `moderacao-comentarios` |
| **Proposição CRUD interna** (protocolar/editar/listar/tramitar/parecer/expediente) | ❌ borda não registrada | `editor-proposicao`, `proposicoes`, `ficha-materia`, `parecer`, `expediente`, `protocolo`, `pos-aprovacao` |
| **Cadastros** (vereadores/comissões/mesa) | ❌ borda não registrada | `cadastro-vereadores`, `comissoes`, `admin-usuarios` |
| **Auth de produção** (login/gov.br/passkey) | ❌ Keycloak vivo = carry F1.4 | `login`, `entrar-govbr` |
| **IA** (ata-IA, legendas, copiloto, resumo/busca semântica) | ❌ Track IA (satélite) | `ata-revisao`, `legendas-ao-vivo`, copiloto do `editor-proposicao` |

## 4. Ondas sequenciadas

### Onda A — fechar os públicos decisores *(backend 100% pronto)*
Sobre o plenário já feito (servidor+vereador), adicionar as telas dos outros decisores. **Nenhuma precisa de
fan-out de backend** — só consumir.
- **A1 — Dashboard da Mesa** (`paineis-mesa.html` → `/paineis/mesa`). Presidente (vitrine) + jurídico (prova de
  compliance). **Estabelece o APP SHELL interno** (layout+nav+tema+auth-guard) e as **primitivas de charts
  honestos** (skill `dataviz`) reusadas por B/E. Read-only; sem SSE.
- **A2 — Portal do cidadão** (`portal-cidadao.html` → `/portal/casa/:ente/...`). Cidadão. **Estabelece o SHELL
  PÚBLICO white-label** (marca da Câmara na frente, "O Plenário" no rodapé; sem cadastro p/ consultar). e-SIC
  amplo + acompanhamento + LGPD + resumo/tramitação viva. Maior que A1 (multi-feature), mas backend rico e pronto.
- **↳ Marco MFE-1 — "os 4 públicos têm software rodando"** (servidor+vereador ✓ · presidente+jurídico · cidadão).

### Onda B — o fluxo diário do servidor (a POC) *(cada fatia carrega sua borda de backend)*
O trabalho que o servidor faz todo dia. **Cada fatia = 1 vertical (fan-out da borda `legislativo` + FE).**
Ordem por dependência: `proposicoes` (lista) → `editor-proposicao` (criar/editar) → `ficha-materia` (interna) →
`tramitacao-board` (já tem `/paineis/tramitacao`, pode vir cedo) → `parecer` → `expediente`/`protocolo` →
`pos-aprovacao`. **Copiloto do editor = IA-gated** (porta a UI sem copiloto; o copiloto entra quando a Track IA existir).
- **↳ Marco MFE-2 — "o servidor trabalha o dia"** (protocolar→tramitar→ficha→parecer navegável).

### Onda C — vereador PWA + condução da sessão
- `vereador-app` (PWA, Aposta 2 — instalável, Web Push §22.9 eixo 9), `pauta-convocacao` (backend de pauta pronto).
- **↳ Marco MFE-3 — "o vereador no bolso"**.

### Onda D — entrada real + cadastros *(gated: Keycloak vivo = fundação infra)*
`login`, `entrar-govbr`, `cadastro-vereadores`, `comissoes`, `admin-usuarios`. **Bloqueada pela fundação de auth
viva** (F1.4-carry: Keycloak realm-por-tenant/gov.br/passkey + interceptors Pedestal) + borda de cadastros.
Até lá o dev-token cobre as ondas A–C.
- **↳ Marco MFE-4 — "entrada real"** (login gov.br/passkey + cadastro sob RLS).

### Onda E — cauda
`transparencia-fiscal`, `dados-abertos`, `status`, `console-operador`(+tenant), `livro-atas`, `calendario`,
`audiencia-publica`, `julgamento-contas`, `vereador-estatisticas`, `perfil-vereador-publico`, `trilha-auditoria`,
`notificacoes`, `observabilidade-ia`, e as **IA-gated** (`ata-revisao`, `legendas-ao-vivo`) quando a Track IA entregar.

## 5. Fundações transversais (assentar cedo, dentro das primeiras fatias)

1. **App shell interno** (Onda A1): layout raiz autenticado, nav lateral/topo do `chassi`, toggle de tema,
   `AuthContext` (dev-token → Keycloak), rotas guardadas por papel. Hoje inexistente (plenário é avulso).
2. **Shell público white-label** (Onda A2): marca da Câmara, `body.superficie-publica`, sem nav interna.
3. **Contrato/codegen Malli→TS.** Já existe `oplenario.codegen.malli-ts` (F1.5, emite interfaces). **Decisão a
   cravar na Onda A** (Opus-high, cf. `docs/11`): **wire o codegen** (os `wire/out` viram TS gerado, mata drift)
   vs. seguir hand-rolled. Recomendação: adotar o codegen por fatia à medida que se porta (o `contrato.ts`
   hand-rolled vira alvo de migração incremental).
4. **Primitivas de charts honestos** (Onda A1): sparkline/semáforo/trilho-de-prazos/tabuleiro-de-estágios —
   skill `dataviz`, sem donut/KPI-card inventado (disciplina do design memory).
5. **Auth token → Keycloak** (fundação infra, destrava Onda D): dev-token hoje; a troca é carry F1.4.

## 6. Marcos

| Marco | Após | O que se mostra |
|---|---|---|
| **MFE-1 — os 4 públicos têm software** | Onda A | plenário ✓ + dashboard da Mesa + portal cidadão rodando contra a API real |
| **MFE-2 — o servidor trabalha o dia** | Onda B | protocolar→tramitar→ficha→parecer navegável |
| **MFE-3 — o vereador no bolso** | Onda C | PWA do vereador instalável + push |
| **MFE-4 — entrada real** | Onda D | login gov.br/passkey + cadastro sob Keycloak vivo |

## 7. Verificação (por fatia)

- `pnpm test` (vitest, lógica pura: view-models/reducers/parsers) verde; `tsc` + `eslint` + `next build` limpos.
- **Paridade visual lado-a-lado** com a tela-fonte HTML + `GUIDELINES-CHECKLIST.md` nos **2 temas** (contraste
  em pixel composto). Cliente tipado consome a **API real** da fatia (Postgres real via docker, dev-token).
- Revisão `ecc` **react-reviewer + security-reviewer** antes do merge (padrão já provado em FE.1–FE.4).
- E2E vivo do read-path/live-push quando a fatia tiver SSE (Playwright, como no HERO).

## 8. Decisões assumidas / não-bloqueiam

- **Track IA é satélite** (`docs/11` §Decisões): o FE porta a UI sem a peça de IA (copiloto/ata/legendas/resumo)
  e a liga quando a Track IA entregar. Não bloqueia as ondas A–C.
- **PWA-first** (§22.9 eixo 9): Next responsivo + Web Push; RN/Expo diferido.
- **Ordem é guia, não trava:** puxa-se por valor de demo; uma fatia pode ser repriorizada se um cliente pedir.
- **Backend fan-out (Onda B+) entra na branch da fatia FE** (a borda `legislativo`/`cadastros` nasce com o
  consumidor), respeitando a regra-mãe (nada de fan-out especulativo).

## 9. Slice 1 = Onda A1 (Dashboard da Mesa)

O próximo passo concreto: brainstorm/spec do **Dashboard da Mesa** como primeira fatia (estabelece o app shell +
charts honestos + a decisão do codegen). Backend pronto hoje (`/paineis/mesa` + detalhes + `/compliance/painel`).
Depois `writing-plans` → implementação por TDD → review `ecc` → merge → MFE-1 (com A2).
