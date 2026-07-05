# Spec — Track FE · Onda A2 · Portal do Cidadão (shell público white-label)

> **Fatia FE** do `docs/13-plano-track-fe.md` (Onda A2). Fecha o último público decisor (cidadão) →
> **Marco MFE-1** ("os 4 públicos têm software rodando"). Fonte de design: `produto/design-system/o-plenario/telas/portal-cidadao.html` (verbatim como referência visual). Backend: rotas `/portal/...` do F6 (participação + transparência), **já registradas** — zero fan-out especulativo.
>
> **Data:** 2026-07-04 · **Branch:** `fe-6-portal-cidadao` (off `main`) · **Escopo aprovado:** Opção A.

---

## 1. Objetivo e escopo (Opção A, aprovada por Daouda 04/07)

Assentar o **shell público white-label** (a fundação reusável das ondas públicas) + a **home do portal**
(`/portal/casa/[ente]`) composta como a tela-fonte, ligada **só ao que tem rota de leitura pública real**, com
**estados "em breve" honestos** para as superfícies sem backend — a mesma disciplina do `LenteJuridico` "em-breve
honesto" já usada no Dashboard da Mesa (A1). O click-through de leitura (ficha da matéria) completa a jornada.

**No escopo (A2):**
1. **Shell público** — layout `superficie-publica`, header white-label (brasão + nome da Casa), rodapé com a marca
   "O Plenário" recuada, toggle de tema (reusa o Context existente), roteamento por `:ente`, **sem auth-guard**.
2. **Home** (`/portal/casa/[ente]`) — capa, destaque "Em tramitação agora" (lista real), balcões de direito
   (e-SIC acompanhar-por-número + Encarregado/DPO), navegação cívica (com em-breve honesto onde não há backend),
   rodapé.
3. **Ficha da matéria** (`/portal/casa/[ente]/materias/[proposicaoId]`) — leitura completa: tramitação + ligação
   à norma (se publicada) + comentários aprovados (read-only).

**Fora do escopo (A2) — deferido, com em-breve honesto na UI onde a tela-fonte os mostra:**
- **Fluxos de escrita autenticados** (abrir pedido e-SIC / solicitação LGPD / manifestação ouvidoria / comentar /
  seguir): dependem do gov.br/Keycloak vivo (carry F1.4). A UI mostra os pontos de entrada (botões) mas eles levam
  a um estado "em breve"/entrar, **não** ao fluxo de escrita. Sub-fatia futura.
- **Superfícies sem backend:** badge "sessão ao vivo agora", busca, Transparência fiscal, Dados abertos, Agenda
  pública, Carta de Serviços (Onda E). Renderizadas como cartões/estados em-breve honestos.
- **Resumo em linguagem simples (IA):** Track IA satélite. A tela-fonte já traz o fallback honesto
  (`data-ia="off"`) — portamos o **estado off por padrão**, sem inventar resumo.
- **Legislação browser + download do artefato:** rota pronta (`/legislacao...`), mas é uma vertical própria;
  **stretch** — só entra como commits finais desta branch se o momentum permitir; senão vira A2.4.

## 2. Rotas de backend consumidas (todas já registradas — inventário real)

**Leitura pública (sem auth) — o coração de A2:**

| Uso na UI | Rota | Contrato (wire/out) |
|---|---|---|
| Destaque + "mais em tramitação" | `GET /portal/casa/:ente/materias` | `MateriaOut[]` (teto 200, exclui nada) |
| Ficha da matéria (click-through) | `GET /portal/casa/:ente/materias/:proposicao_id` | `FichaOut` (matéria + `:norma` se publicada) |
| Comentários aprovados na ficha | `GET /portal/casa/:ente/materias/:proposicao_id/comentarios` | lista de comentários aprovados |
| Encarregado/DPO (balcão LGPD) | `GET /portal/casa/:ente/encarregado` | contato do DPO (legalmente público) |
| Acompanhar e-SIC por protocolo | `GET /portal/casa/:ente/esic/acompanhar/:protocolo` | status público do pedido |
| Acompanhar ouvidoria por protocolo | `GET /portal/casa/:ente/ouvidoria/acompanhar/:protocolo` | status público da manifestação |

**Legislação (stretch A2.4):** `GET /portal/casa/:ente/legislacao`, `.../:norma_id`, `.../:norma_id/artefato` (binário).

**Escrita autenticada (deferida):** `POST /portal/esic/pedidos`, `.../lgpd/solicitacoes`, `.../ouvidoria/manifestacoes`,
`.../materias/:id/comentarios`, `.../acompanhar`. Fora de A2.

## 3. Arquitetura FE (segue o padrão A1)

- **Next.js 16 App Router.** Novo route group público: `src/app/(publico)/portal/casa/[ente]/`.
  - `layout.tsx` — shell público (header/footer white-label, `<body class="superficie-publica">` via classe no
    container, tema). **Sem** `AuthContext`/guard (a home é pública).
  - `page.tsx` — a home; Server Component que faz os fetches públicos e passa dados aos componentes de seção.
  - `materias/[proposicaoId]/page.tsx` — a ficha.
- **Proxy same-origin** `/api/*` → :8888 (já existe). Os fetches públicos vão a `/api/portal/casa/{ente}/...`.
- **Contrato tipado via codegen** (decisão docs/13 §5.3, já provada em A1 = `contrato-mesa.gen.ts`): gerar
  `contrato-portal.gen.ts` a partir dos schemas Malli `MateriaOut`/`FichaOut`/`NormaOut`/`EncarregadoOut`/
  status de acompanhamento (`oplenario.codegen.malli-ts`). O `contrato.ts` hand-rolled **não** é tocado.
- **View-models puros e testáveis (vitest)** — toda derivação é função pura, sem I/O:
  - `tramitacao-vista.ts` — `estado` (string do backend) → timeline pública da faixa de azulejo (estágios +
    qual está ativo/concluído/pendente) + rótulo de situação ("Em votação"/"1º turno"/"Aprovado"). **Fonte da
    verdade do vocabulário de `estado` confirmada no impl** a partir do state-set de transição do `legislativo`
    (começa `"protocolada"`; terminais incluem `"arquivada"`/`"aprovada"`). Fail-closed: estado desconhecido →
    faixa mínima honesta (mostra só "Protocolo" + rótulo cru), nunca quebra.
  - `materia-vista.ts` — escolhe o destaque (mais recente) + a lista "mais em tramitação"; deriva ref
    (`PL 042/2026`) de tipo/ano/sequencial; expõe o permalink URN.
  - `ficha-vista.ts` — compõe FichaOut + comentários + ligação à norma.
- **Componentes de seção** (finos, um arquivo por seção, espelham a tela-fonte): `BarraInstitucional`,
  `Capa` (com busca em-breve + badge ao-vivo em-breve), `DestaqueTramitacao`, `MaisTramitacao`,
  `BalcaoEsic` (form acompanhar-por-número → track), `BalcaoLgpd` (encarregado real + direitos em-breve),
  `NavegacaoCivica` (cartões com em-breve honesto), `RodapeInstitucional`.
- **Primitiva de chart nova:** `AzulejoFaixa` (a faixa de azulejo da tramitação — a assinatura Bulcão do portal;
  SVG, tematizada, `role="img"` + `aria-label` descritivo). Vira componente reusável (`src/lib/charts/`), à la as
  primitivas de A1. O **anel de prazo legal** (e-SIC) reusa `anel-prazo.tsx` **já existente** de A1.
- **Estado em-breve honesto:** componente `EmBreve` reusável (rótulo + motivo honesto), extraído do padrão
  `LenteJuridico`. Toda superfície sem backend passa por ele — nada de dado falso.

## 4. Fatias (branch única `fe-6-portal-cidadao`, commits por fatia; TDD + review ecc)

- **A2.0 — Shell público + codegen + fundação.** Route group `(publico)`; layout white-label; `contrato-portal.gen.ts`;
  cliente de leitura pública (fetch helpers); componente `EmBreve`; primitiva `AzulejoFaixa` + `tramitacao-vista.ts`
  (com testes). *Prova:* a rota `/portal/casa/[ente]` responde com o shell (header/footer/tema) e um esqueleto.
- **A2.1 — Capa + Destaque (matéria em tramitação).** `materia-vista.ts` (testes) + fetch de `/materias`; a capa
  (busca/ao-vivo em-breve honesto); o destaque (card grande com `AzulejoFaixa` + resumo-IA no estado off honesto) +
  "mais em tramitação". Semear demo: protocolar 1+ proposição para projetar em `transparencia.materia`. *Prova:*
  lista real do Postgres renderiza; faixa reflete o `estado`.
- **A2.2 — Balcões + Navegação cívica.** `BalcaoEsic` (form acompanhar-por-número → `/esic/acompanhar/:protocolo`,
  render do status + faixa do pedido) + `BalcaoLgpd` (Encarregado/DPO real + direitos como em-breve/entrar) +
  `NavegacaoCivica` (Legislação real-ou-stretch; demais cartões em-breve honesto). *Prova:* acompanhar um protocolo
  real devolve status; DPO real renderiza.
- **A2.3 — Ficha da matéria (click-through).** `ficha-vista.ts` (testes) + `/materias/[proposicaoId]`: tramitação
  completa + ligação à norma (se publicada) + comentários aprovados (read-only). *Prova:* clicar um item da lista
  abre a ficha com dado real.
- **A2.4 — (stretch) Legislação browser + download do artefato.** Só se o momentum permitir; senão vira fatia
  própria futura.

## 5. Tratamento de erro e bordas

- **Fetch público falha / vazio:** cada seção degrada isolada (um card de erro/vazio honesto na seção), **nunca**
  derruba a página inteira (mesmo princípio de A1 — degradação por card, não 500 global).
- **`:ente` inválido:** o backend responde 404 via `resolver-ente-publico`; a home mostra um estado "Casa não
  encontrada" honesto.
- **Sem PII / sem cadastro para consultar:** nenhuma leitura pública exige auth; o header oferece "Entrar com
  gov.br" como ponto de entrada (stub até o carry F1.4), nunca como pré-requisito de leitura.
- **Sigilo/segurança:** só consumimos rotas públicas por construção; nenhuma leitura autenticada nesta fatia.

## 6. Verificação (por fatia — padrão docs/13 §7)

- `pnpm test` (vitest — view-models puros) verde; `tsc` + `eslint` + `next build` limpos.
- **Paridade visual lado-a-lado** com `portal-cidadao.html` + `GUIDELINES-CHECKLIST.md` nos **2 temas** (contraste
  em pixel composto, um tema por passada com flush — a armadilha do stale-bg de A1).
- Cliente tipado consome a **API real** (Postgres via docker, dados semeados).
- Review **ecc react-reviewer + security-reviewer + a11y** antes do merge de cada fatia; incorporar CRÍTICO/MAJOR.
- **Docker mandatório** (memória `oplenario-docker-mandato`): FE roda em container, nunca `next dev` solto no host.

## 7. Riscos / decisões diferidas

- **Vocabulário de `estado`** (mapa da faixa): confirmado no A2.1 a partir do state-set real do `legislativo`;
  view-model fail-closed cobre estados fora do mapa.
- **Forma do `:ente` na URL** (UUID vs código IBGE vs slug): confirmada no A2.0 a partir do `resolver-ente-publico`;
  na demo passa-se o `ente` que o `seed_demo` grava.
- **Auth dev-token vs gov.br:** fora de A2 (só leitura pública); os fluxos de escrita esperam o carry F1.4.
