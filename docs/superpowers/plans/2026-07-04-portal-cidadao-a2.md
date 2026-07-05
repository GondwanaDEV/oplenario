# Portal do Cidadão (Onda A2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Assentar o shell público white-label + a home do portal do cidadão (`/portal/casa/[ente]`) + a ficha da matéria, ligados às rotas de leitura pública reais do F6, com em-breve honesto no resto → Marco MFE-1.

**Architecture:** Next.js 16 App Router. Novo route group `(publico)` com layout sem auth-guard (só TemaProvider). Fetches públicos via proxy same-origin `/api/portal/casa/{ente}/...`. Toda derivação é view-model puro (vitest). Componentes de seção finos portam `portal-cidadao.html` verbatim. Contrato tipado via codegen Malli→TS.

**Tech Stack:** Next.js 16, React 19, TypeScript, Tailwind 4 (só o que já existe), vitest. CSS = `../sistema/tokens.css`+`chassi.css` (já portados) + `public.css` (específico da tela). Zero libs novas.

## Global Constraints

- **Docker mandatório** — FE roda em container (`apps/frontend/Dockerfile`), nunca `next dev` no host (memória `oplenario-docker-mandato`).
- **Boundary kebab→camel obrigatório** — o backend (`jsonista`) serializa keywords Clojure verbatim (`por-estado`, nunca `porEstado`). TODA resposta passa por `camelizarChaves` no fetch, senão acesso camelCase resolve `undefined`.
- **Sem dado falso** — superfície sem backend passa por `<EmBreve>`. Resumo IA porta o estado `off` honesto por padrão.
- **Só leitura pública** — nenhuma rota autenticada nesta fatia. Nenhuma PII.
- **Degradação por seção** — fetch que falha/vazio degrada só a sua seção (card honesto), nunca 500 global.
- **AA nos 2 temas** — `GUIDELINES-CHECKLIST.md`, contraste em pixel composto, um tema por passada com flush.
- **Verificação por fatia** — `pnpm test` + `tsc` + `eslint` + `next build` limpos; paridade visual lado-a-lado; review ecc react+security+a11y; incorporar CRÍTICO/MAJOR.
- **Fonte visual verbatim:** `produto/design-system/o-plenario/telas/portal-cidadao.html` (referências de linha abaixo apontam para lá).

---

## FATIA A2.0 — Shell público + fundação

### Task 0.1: Extrair boundary kebab→camel para util compartilhado

**Files:**
- Create: `src/lib/boundary.ts`
- Create: `src/lib/boundary.test.ts`
- Modify: `src/lib/use-mesa.ts` (importar de `./boundary` em vez de definir inline)

**Interfaces:**
- Produces: `camelizarChaves(valor: unknown): unknown`, `paraCamel(chave: string): string`

- [ ] **Step 1: Teste** — copie os casos que já vivem implícitos em `use-mesa`: `paraCamel("por-estado") === "porEstado"`; `paraCamel("a--b") === "aB"` (hífens consecutivos, fix b3f69c6); `camelizarChaves({"vence-em":1,"itens":[{"objeto-id":2}]})` deep-cameliza; arrays/escalares preservados.
- [ ] **Step 2:** rode `pnpm test boundary` → FAIL (módulo não existe).
- [ ] **Step 3:** mova `paraCamel`/`camelizarChaves` de `use-mesa.ts` (idênticos — ver `src/lib/use-mesa.ts`) para `boundary.ts`; em `use-mesa.ts` `import { camelizarChaves } from "./boundary"`.
- [ ] **Step 4:** `pnpm test` (boundary + use-mesa) → PASS.
- [ ] **Step 5:** commit `refactor(fe): extrai boundary kebab→camel p/ src/lib/boundary.ts`.

### Task 0.2: Codegen do contrato do portal (Malli→TS)

**Files:**
- Create: `src/lib/contrato-portal.gen.ts` (gerado)
- Backend schemas-fonte: `transparencia/wire/out/{materia,norma}.clj` (`MateriaOut`,`FichaOut`,`NormaOut`), `participacao/wire/out/encarregado.clj`, e o status de acompanhamento e-SIC/ouvidoria.

**Interfaces:**
- Produces: interfaces TS `MateriaOut`, `FichaOut`, `NormaOut`, `EncarregadoOut`, `AcompanhamentoEsicOut`, `AcompanhamentoOuvidoriaOut` (nomes camelCase).

- [ ] **Step 1:** localize como A1 invocou o codegen (`oplenario.codegen.malli-ts`; procure o alias/comando que gerou `contrato-mesa.gen.ts` — `grep -rn "malli-ts\|contrato-mesa" apps/backend/{deps.edn,src,dev,demo} apps/frontend`).
- [ ] **Step 2:** aponte o codegen aos schemas do portal (§ acima); gere `contrato-portal.gen.ts`. Se um schema-fonte não expõe o status de acompanhamento como Malli nomeado, defina a interface à mão no `.gen.ts` com um comentário `// hand-rolled: sem schema Malli nomeado` (mesma disciplina de `ItemBoardOut` em use-mesa).
- [ ] **Step 3:** `tsc --noEmit` limpo sobre o arquivo gerado.
- [ ] **Step 4:** commit `feat(fe): gera contrato-portal.gen.ts (Malli→TS) das rotas públicas`.

### Task 0.3: Cliente de leitura pública

**Files:**
- Create: `src/lib/portal-api.ts`
- Create: `src/lib/portal-api.test.ts`

**Interfaces:**
- Consumes: `camelizarChaves` (Task 0.1).
- Produces: `buscarPublico<T>(caminho: string): Promise<T | null>` — `GET /api/portal/casa/{...caminho}`, `cache: "no-store"`, **sem** header Authorization; `!ok`→`null`; cameliza o corpo; try/catch→`null` (degradação por seção).

- [ ] **Step 1: Teste** — mock `global.fetch`: (a) 200 com corpo kebab `{"autor-texto":"x"}` → retorna `{autorTexto:"x"}`; (b) 404 → `null`; (c) throw → `null`; (d) a URL chamada começa com `/api/portal/casa/`.
- [ ] **Step 2:** `pnpm test portal-api` → FAIL.
- [ ] **Step 3:** implemente `buscarPublico` (espelhe `buscarOuNull` de use-mesa, **sem** o header Bearer).
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(fe): cliente de leitura pública buscarPublico`.

### Task 0.4: Componente EmBreve (em-breve honesto)

**Files:**
- Create: `src/lib/em-breve.tsx`
- Create: `src/lib/em-breve.test.tsx`

**Interfaces:**
- Produces: `<EmBreve titulo motivo />` — render honesto (rótulo "Em breve" + motivo textual); sem dado falso. Extrai o espírito do `LenteJuridico` de A1 (`src/app/(interno)/paineis/mesa/lente-juridico.tsx` — leia p/ o tom da copy).

- [ ] **Step 1: Teste** — render mostra `titulo` e `motivo`; tem `role`/aria que anuncia estado indisponível (ex. `aria-label` contendo "em breve").
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implemente (marcação mínima + classe `.em-breve` que será estilizada no `public.css` da Task 0.6).
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(fe): componente EmBreve (em-breve honesto reusável)`.

### Task 0.5: Primitiva AzulejoFaixa + view-model de tramitação

**Files:**
- Create: `src/lib/tramitacao-vista.ts`
- Create: `src/lib/tramitacao-vista.test.ts`
- Create: `src/lib/charts/azulejo-faixa.tsx`
- Create: `src/lib/charts/azulejo-faixa.test.tsx`

**Interfaces:**
- Produces: `type EstagioTramitacao = { rotulo: string; situacao: "concluido"|"ativo"|"pendente" }`; `derivarTramitacao(estado: string): { estagios: EstagioTramitacao[]; rotuloSituacao: string }`. `<AzulejoFaixa estagios rotuloAria />` (SVG tematizado, `role="img"`+`aria-label`).

- [ ] **Step 1:** confirme o vocabulário real de `estado` — `grep -rn "estado" apps/backend/src/oplenario/legislativo/logic.clj` e o state-set de transição (começa `"protocolada"`; terminais `"arquivada"`/`"aprovada"`). Documente o mapa no topo de `tramitacao-vista.ts`.
- [ ] **Step 2: Teste** — `derivarTramitacao("protocolada")` → estágio "Protocolo" ativo, resto pendente; um estado intermediário (ex. o de 2º turno) → Protocolo/Comissões/1º concluídos, 2º ativo, Sanção pendente; `derivarTramitacao("aprovada")` → todos concluídos, `rotuloSituacao` "Aprovado"; **fail-closed:** `derivarTramitacao("xpto-desconhecido")` → faixa mínima (só "Protocolo") + `rotuloSituacao` = o estado cru, sem throw.
- [ ] **Step 3:** `pnpm test tramitacao-vista` → FAIL.
- [ ] **Step 4:** implemente o view-model puro.
- [ ] **Step 5:** teste + implemente `AzulejoFaixa` (porte o SVG de `portal-cidadao.html:432-453` — a faixa de 5 estágios — parametrizado pelos estágios; **`aria-label` descritivo obrigatório**). Teste: rende N `<text>` com os rótulos + o ativo com classe `.ativo`.
- [ ] **Step 6:** `pnpm test` → PASS.
- [ ] **Step 7:** commit `feat(fe): AzulejoFaixa + tramitacao-vista (faixa de azulejo da tramitação)`.

### Task 0.6: Layout público white-label + shell + rota esqueleto

**Files:**
- Create: `src/app/(publico)/layout.tsx` (TemaProvider só; **sem** AuthProvider/guard)
- Create: `src/app/(publico)/portal/casa/[ente]/page.tsx` (esqueleto: header+footer+EmBreve)
- Create: `src/app/(publico)/barra-institucional.tsx` (header white-label)
- Create: `src/app/(publico)/rodape-institucional.tsx` (footer com marca "O Plenário" recuada)
- Create: `src/app/(publico)/public.css` (porte do `<style>` de `portal-cidadao.html` — só o específico da tela; tokens/chassi já vêm do global)

**Interfaces:**
- Consumes: `useTema` (existente), `EmBreve` (0.4).
- Produces: shell público montado; `params.ente` disponível às páginas.

- [ ] **Step 1:** layout `(publico)` — Client Component com `<TemaProvider>` envolvendo `children`; **não** importa `auth`. (Espelhe `(interno)/layout.tsx` mas sem o `AuthProvider`/`LeitorToken`.) Aplique `superficie-publica` num container (não dá pra trocar `<body>` por grupo — envolva num `<div className="superficie-publica">`).
- [ ] **Step 2:** `BarraInstitucional` — porte `portal-cidadao.html:314-354` (brasão da Casa + nome + nav pública + tema-btn via `useTema` + "Entrar com gov.br" como link stub). White-label: nome da Casa em destaque, não "O Plenário".
- [ ] **Step 3:** `RodapeInstitucional` — porte `portal-cidadao.html:666-701` (marca "O Plenário" recuada no rodapé-fim).
- [ ] **Step 4:** `public.css` — porte o `<style>` de `portal-cidadao.html:32-307` (as classes específicas da tela). Reuse tokens/chassi globais.
- [ ] **Step 5:** `page.tsx` esqueleto — recebe `params.ente`; renderiza `<BarraInstitucional/>` + `<main>` com um `<EmBreve>` provisório + `<RodapeInstitucional/>`.
- [ ] **Step 6:** `next build` limpo; suba o docker e confirme `GET /portal/casa/qualquer-coisa` renderiza o shell nos 2 temas.
- [ ] **Step 7:** commit `feat(fe): shell público white-label (layout + barra + rodapé + public.css)`.

**→ Review ecc (react + security + a11y) da fatia A2.0; incorpore CRÍTICO/MAJOR; AA nos 2 temas.**

---

## FATIA A2.1 — Capa + Destaque (matéria em tramitação)

### Task 1.1: view-model da matéria

**Files:**
- Create: `src/lib/materia-vista.ts`
- Create: `src/lib/materia-vista.test.ts`

**Interfaces:**
- Consumes: `MateriaOut` (0.2), `derivarTramitacao` (0.5).
- Produces: `type MateriaVista = { ref: string; titulo: string; situacao: string; permalink: string; proposicaoId: string; estagios: EstagioTramitacao[] }`; `derivarRef(m): string` (`"PL 042/2026"` de tipo+sequencial+ano, com zero-pad de 3); `escolherDestaque(itens: MateriaOut[]): { destaque: MateriaVista | null; maisTramitacao: MateriaVista[] }` (destaque = 1º da lista já ordenada desc; maisTramitacao = próximos N, ex. 3).

- [ ] **Step 1: Teste** — `derivarRef({tipo:"projeto_lei",sequencial:42,ano:2026})` → `"PL 042/2026"` (mapa tipo→sigla; fail-closed: tipo desconhecido → sigla = tipo cru maiúsculo); `escolherDestaque([])` → `{destaque:null, maisTramitacao:[]}`; com 5 itens → destaque = 1º, maisTramitacao = itens 2-4 (3); `permalink` usa `urnLex`.
- [ ] **Step 2-4:** FAIL → implemente → PASS. Confirme o mapa tipo→sigla contra os tipos reais de proposição (`grep -rn "tipo" apps/backend/src/oplenario/legislativo/models/proposicao.clj`).
- [ ] **Step 5:** commit `feat(fe): materia-vista (destaque + ref + mais em tramitação)`.

### Task 1.2: Capa (hero)

**Files:**
- Create: `src/app/(publico)/capa.tsx`

- [ ] **Step 1:** porte `portal-cidadao.html:358-403` — h1, sub, chips. **Busca**: renderize o campo mas desabilitado com nota honesta (sem backend de busca) OU via `<EmBreve>` inline; **badge "ao vivo agora"**: `<EmBreve>` (sem rota pública de sessão). Chips levam às âncoras reais (`#destaque`, `#balcoes`).
- [ ] **Step 2:** monte no `page.tsx` acima do destaque. `next build` limpo.
- [ ] **Step 3:** commit `feat(fe): capa do portal (hero; busca/ao-vivo em-breve honesto)`.

### Task 1.3: Destaque + MaisTramitacao, wired ao fetch real

**Files:**
- Create: `src/app/(publico)/destaque-tramitacao.tsx`
- Create: `src/app/(publico)/mais-tramitacao.tsx`
- Modify: `src/app/(publico)/portal/casa/[ente]/page.tsx` (fetch `/materias`)

**Interfaces:**
- Consumes: `buscarPublico` (0.3), `escolherDestaque`/`MateriaVista` (1.1), `AzulejoFaixa` (0.5).

- [ ] **Step 1:** `page.tsx` (Server Component) faz `buscarPublico<MateriaOut[]>("{ente}/materias")`, aplica `escolherDestaque`, passa aos componentes. Fetch falho/vazio → seção mostra estado vazio honesto ("Nenhuma matéria em tramitação" / erro de leitura), nunca quebra a página.
- [ ] **Step 2:** `DestaqueTramitacao` — porte `portal-cidadao.html:422-490` (card grande: ref+selo situação, título=ementa, autoria, `<AzulejoFaixa>` dos `estagios`, permalink URN, **resumo-IA no estado `off` honesto** — porte `resumo-off` de :477-480, sem o corpo IA). Link do card → `/portal/casa/{ente}/materias/{proposicaoId}`.
- [ ] **Step 3:** `MaisTramitacao` — porte `portal-cidadao.html:492-508` (lista com ref+ementa+situação por cor); cada item linka à ficha.
- [ ] **Step 4:** `next build` limpo.
- [ ] **Step 5:** commit `feat(fe): destaque + mais-tramitação (matéria real de /portal/.../materias)`.

### Task 1.4: Demo seed + prova end-to-end

**Files:**
- Modify/Create: um passo de seed que protocola 1+ proposição (reuse o código do projeto, à la `apps/backend/demo/seed_demo.clj`) para projetar em `transparencia.materia` via o evento `proposicao.protocolada`.

- [ ] **Step 1:** estenda/some um seed que protocola proposições variadas (estados diferentes p/ exercer a faixa) sob o `ente` da demo; confirme a projeção (`transparencia.materia` populada).
- [ ] **Step 2:** suba docker, abra `/portal/casa/{ente}` e confirme: lista real renderiza, faixa reflete `estado`, click-through leva à URL da ficha (mesmo que a ficha ainda seja A2.3).
- [ ] **Step 3:** commit `chore(fe): seed de demo do portal (proposições protocoladas)`.

**→ Review ecc da fatia A2.1; incorpore; AA nos 2 temas.**

---

## FATIA A2.2 — Balcões de direito + Navegação cívica

### Task 2.1: Balcão e-SIC (acompanhar por número)

**Files:**
- Create: `src/app/(publico)/balcao-esic.tsx` (Client Component — form controlado)
- Create: `src/lib/esic-vista.ts` + `.test.ts`

**Interfaces:**
- Consumes: `buscarPublico` (0.3), `AzulejoFaixa` (0.5), `anel-prazo.tsx` (A1, reuso — leia a assinatura).
- Produces: `derivarStatusEsic(status: AcompanhamentoEsicOut): { estagios; diasRestantes; ... }` (view-model do status público).

- [ ] **Step 1: Teste** de `derivarStatusEsic` — mapeia o status do pedido em estágios da faixa (Protocolado/Em análise/Respondido — `portal-cidadao.html:545-563`) + dias restantes p/ o `anel-prazo`. Fail-closed em status desconhecido.
- [ ] **Step 2-4:** FAIL → implemente → PASS.
- [ ] **Step 5:** `BalcaoEsic` — porte `portal-cidadao.html:519-584`. O form "Acompanhar pelo número" chama `buscarPublico("{ente}/esic/acompanhar/{protocolo}")` e renderiza o status (faixa + anel de prazo). "Abrir um pedido" = `<EmBreve>`/entrar (fluxo autenticado deferido). `<anel-prazo>` reusado de A1.
- [ ] **Step 6:** `next build` limpo; commit `feat(fe): balcão e-SIC (acompanhar por número, real)`.

### Task 2.2: Balcão LGPD + Encarregado/DPO

**Files:**
- Create: `src/app/(publico)/balcao-lgpd.tsx`
- Modify: `page.tsx` (fetch `/encarregado`)

- [ ] **Step 1:** `page.tsx` faz `buscarPublico<EncarregadoOut>("{ente}/encarregado")`; passa ao componente (degrada isolado se falhar).
- [ ] **Step 2:** `BalcaoLgpd` — porte `portal-cidadao.html:586-618`: fronteira LAI×LGPD, os 5 "direitos" como botões que levam a `<EmBreve>`/entrar (fluxo autenticado deferido), e o bloco **Encarregado/DPO real** (nome+email do fetch). Prazo LGPD = texto honesto sem cravar número (a tela-fonte já evita cravar `[GAP]`).
- [ ] **Step 3:** commit `feat(fe): balcão LGPD (encarregado real; direitos deferidos)`.

### Task 2.3: Navegação cívica

**Files:**
- Create: `src/app/(publico)/navegacao-civica.tsx`

- [ ] **Step 1:** porte `portal-cidadao.html:623-662` (6 cartões). **Legislação** → link real (`/portal/casa/{ente}/legislacao`, se A2.4 feita) ou `<EmBreve>`; **Sessões** → link ao plenário se aplicável, senão em-breve; **Transparência/Dados abertos/Agenda/Carta de Serviços** → `<EmBreve>` honesto (sem backend). Não invente prazos/contagens.
- [ ] **Step 2:** monte no `page.tsx`; `next build` limpo; commit `feat(fe): navegação cívica (real onde há backend, em-breve honesto no resto)`.

**→ Review ecc da fatia A2.2; incorpore; AA nos 2 temas.**

---

## FATIA A2.3 — Ficha da matéria (click-through)

### Task 3.1: view-model da ficha

**Files:**
- Create: `src/lib/ficha-vista.ts` + `.test.ts`

**Interfaces:**
- Consumes: `FichaOut` (0.2), `derivarTramitacao` (0.5), `derivarRef` (1.1).
- Produces: `derivarFicha(ficha: FichaOut, comentarios: ComentarioOut[]): FichaVista` — compõe ref/situação/estágios + ligação à norma (se `ficha.norma` presente) + comentários aprovados.

- [ ] **Step 1: Teste** — ficha sem norma → `normaPublicada:null`; com norma → expõe o link/URN da norma; comentários vazios → `[]`; monta estágios via `derivarTramitacao(ficha.estado)`.
- [ ] **Step 2-4:** FAIL → implemente → PASS.
- [ ] **Step 5:** commit `feat(fe): ficha-vista (matéria + norma + comentários)`.

### Task 3.2: Página da ficha

**Files:**
- Create: `src/app/(publico)/portal/casa/[ente]/materias/[proposicaoId]/page.tsx`

- [ ] **Step 1:** Server Component — `buscarPublico<FichaOut>("{ente}/materias/{proposicaoId}")` + `buscarPublico<...>("{ente}/materias/{proposicaoId}/comentarios")` (em paralelo); ficha ausente → 404 honesto; comentários falhos → seção degrada.
- [ ] **Step 2:** render — cabeçalho da matéria (ref+ementa+autoria), `<AzulejoFaixa>` completa, permalink URN, **ligação à norma** se publicada (link/URN), **comentários aprovados** read-only, **resumo-IA off honesto**. Referência visual: `ficha-materia-publica.html` (mesma linguagem; reuse os componentes de seção onde couber).
- [ ] **Step 3:** `next build` limpo; prova docker: clicar um item da lista abre a ficha com dado real.
- [ ] **Step 4:** commit `feat(fe): ficha pública da matéria (/materias/[id])`.

**→ Review ecc da fatia A2.3; incorpore; AA nos 2 temas.**

---

## FATIA A2.4 — (stretch) Legislação browser + download do artefato

> Só se o momentum permitir após A2.3; senão vira fatia futura própria. Backend pronto: `/legislacao`, `.../:norma_id`, `.../:norma_id/artefato` (binário).

### Task 4.1: Legislação (lista + detalhe + download)

**Files:**
- Create: `src/app/(publico)/portal/casa/[ente]/legislacao/page.tsx` (lista via `/legislacao`)
- Create: `src/app/(publico)/portal/casa/[ente]/legislacao/[normaId]/page.tsx` (detalhe + link de download do artefato)

- [ ] **Step 1:** lista — `buscarPublico<NormaOut[]>("{ente}/legislacao")`; render tabela/lista (ref. `legislacao.html`).
- [ ] **Step 2:** detalhe — `.../legislacao/{normaId}`; link "Baixar" → `/api/portal/casa/{ente}/legislacao/{normaId}/artefato` (download binário direto do proxy; sem camelizar binário). `os/obter→nil` = o backend responde 500-alerta, não 404 silencioso; a UI mostra estado honesto.
- [ ] **Step 3:** `next build` limpo; commit `feat(fe): legislação pública (lista + detalhe + download do artefato)`.

**→ Review ecc da fatia A2.4; incorpore; AA nos 2 temas.**

---

## Fechamento

- [ ] Suíte FE inteira verde (`pnpm test`), `tsc`+`eslint`+`next build` limpos.
- [ ] Paridade visual das telas contra `portal-cidadao.html` (+`ficha-materia-publica.html`,`legislacao.html`) nos 2 temas.
- [ ] Todas as reviews ecc incorporadas.
- [ ] Atualizar memória `oplenario-fe-execucao` + `docs/13` (marcar A2 / MFE-1).
- [ ] **PARAR** — apresentar a branch `fe-6-portal-cidadao` pronta p/ merge; Daouda aprova o merge→main.
</content>
