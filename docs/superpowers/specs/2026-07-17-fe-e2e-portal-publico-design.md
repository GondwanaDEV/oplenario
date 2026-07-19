# Spec — Primeiro harness de browser-e2e (Portal do Cidadão)

**Data:** 2026-07-17
**Branch:** `fe-e2e-portal-publico`
**Autor:** Daouda Traore (design), Claude Opus 4.8

## Contexto

Hoje o projeto tem **zero testes de browser-e2e**. A stack roda inteira via docker
compose (frontend :3000 → proxy BFF `/api/*` → backend Pedestal :8888 → Postgres),
e o fluxo do **Portal do Cidadão** já foi **provado ponta a ponta ao vivo** (Playwright
manual em 2026-07-17): renderizou nome real do ente, uma matéria com URN LexML, a faixa
de tramitação e o white-label, tudo com dado real do banco.

O único achado da prova ao vivo: `GET /api/portal/casa/:ente/encarregado → 404` porque
o ente de teste não tinha registro de Encarregado/DPO semeado. A rota **está montada**
(LGPD art. 41); é lacuna de dado, e o FE degrada com honestidade.

Esta fatia **codifica esse fluxo como o primeiro harness de browser-e2e repetível**.

### Por que o Portal do Cidadão como primeiro alvo

- É o **único fluxo que fecha e2e hoje sem os dois blockers**: não precisa de seed de
  sessão/votação coerente (que não existe) nem de passkey/WebAuthn (não-dirigível headless).
- O harness não é descartável — a config do Playwright, o `global-setup` de seed e a
  fiação de CI são o **esqueleto que o e2e do `/votar` (HERO) vai reusar**.
- De-risca a infra (Playwright no CI? seed contra RLS/tenancy?) na fatia mais simples.
- Cobre um dos 3 públicos decisores (cidadão) + o **white-label**, diferencial comercial.

## Objetivo

Um teste de browser repetível que prova o pipeline FE→BFF→backend→DB→render, **verde
localmente** e **rodável em CI**, servindo de esqueleto para os fluxos autenticados depois.

## Design

### 1. Onde vive

- `apps/frontend/e2e/` com `playwright.config.ts` próprio.
- Separado do `vitest.config.ts` (unit/jsdom) — **não toca a suíte vitest existente**.
- Playwright como `devDependency` do `apps/frontend`.
- Roda dentro de container (mandato Docker do projeto) — ver Riscos.

### 2. Seed (o crux) — reuso do `seed_demo.clj`, zero SQL cru

- Um `global-setup` do Playwright roda, contra o Postgres em :5544:
  - `clojure -X:seed seed-demo/materias` — cria Casa + matéria pública
  - `clojure -X:seed seed-demo/encarregado` — fecha o gap do DPO (404)
- O seed **já imprime a URL do portal / `ente_id`**; o `global-setup` captura esse
  `ente_id` e o entrega ao teste (arquivo em `.playwright/` ou variável de ambiente).
- Cada run gera um **ente novo** (ids aleatórios no seed) → **isolamento natural, sem
  cleanup**. O DB de dev já acumula; em CI o DB é efêmero e limpo.
- Honra o invariante do seed: "usa o próprio código do projeto" (nada de SQL que drifta
  do schema físico).

### 3. Alvo de execução

- Contra a stack docker que já está de pé (`docker compose up`). **Local-first.**
- Uma job nova em `.github/workflows/ci.yml` sobe a compose, semeia, roda o Playwright.
- **Critério de pronto #1 = verde local.** O CI entra na mesma fatia **se subir liso**;
  senão vira carry documentado (não travo a fatia na infra de CI).

### 4. O que "verde" assere (o contrato do teste)

Contra o ente semeado:

1. Portal carrega; **nome real** da Câmara aparece no header e no rodapé.
2. A **matéria semeada** renderiza: título, rótulo "PL nnn/aaaa", estado, faixa de
   tramitação (stepper), e a **URN LexML**.
3. Os dois balcões (e-SIC + LGPD) renderizam, **com o contato do Encarregado/DPO**
   semeado presente (o 404 da prova ao vivo some).
4. Rede: `GET /api/portal/casa/:ente/materias` → 200.
5. **Sem erros de console inesperados** (o 404 do encarregado não deve mais ocorrer).
6. Os "Em breve" honestos (sessões, transparência, ouvidoria, dados abertos…) são
   asseridos **como placeholders** — parte do contrato white-label, não falha.

### 5. Escopo fora (YAGNI)

- Dual-theme, fluxos autenticados, viewport mobile, regressão visual (screenshot diff).
- Um tema, um fluxo, um conjunto de asserts. O harness fica pronto para receber `/votar`
  quando houver seed de sessão.

## Riscos e trade-offs

- **Acoplamento ao `seed_demo`:** reusar as funções de seed acopla o e2e à assinatura
  delas — se mudarem, o `global-setup` quebra. **Aceito**, porque a alternativa (fixture
  SQL) acopla ao schema físico, que drifta calado e é pior.
- **Mandato Docker:** o projeto proíbe rodar node/clj direto no host. O Playwright e o
  seed rodam em container. O `global-setup` chama o seed via container de Clojure efêmero
  (padrão já usado no projeto) e o Playwright roda em imagem com browsers. Definir o
  mecanismo exato é tarefa do plano.
- **CI:** se a job de Playwright no GitHub Actions exigir setup pesado (browsers,
  serviços), pode escorregar de fatia — por isso o critério #1 é verde local, CI é
  best-effort na mesma fatia.

## Critérios de aceitação

- [ ] `apps/frontend/e2e/` com `playwright.config.ts` e ao menos um spec do Portal.
- [ ] `global-setup` semeia via `seed_demo.clj` e expõe o `ente_id` ao teste.
- [ ] O spec passa localmente contra a stack de pé, asserindo os 6 pontos da seção 4.
- [ ] A suíte vitest existente segue intacta.
- [ ] CI: job adicionada **ou** carry documentado com o motivo.
