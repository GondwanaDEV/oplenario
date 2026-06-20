# Page Override — Dashboard Institucional da Mesa (persona presidente)

> **LOGIC:** sobrescreve o `../MASTER.md` para esta superfície. O que não está aqui herda o MASTER.
>
> **Superfície:** `produto/13` feature **11.4** — dashboard institucional da Mesa (proposições por
> status, sessões, presença, **engajamento cidadão**). `[DIF] [RM]`. **Cumpre a proposta de valor da
> persona presidente** (§11): aprova politicamente, **ganha com engajamento cidadão**.
>
> **Substrato (read-model — sem novo modelo de dados, `[RM]`):** agrega o que já capturamos —
> acompanhamento de proposição (5.5), comentários (6.3), e-SIC (6.1), presença append-only (4.5),
> proposições/tramitação (§22.4 eixo C), sessões (§22.6). É **projeção/overview**, não BI novo.
>
> **Stack deferida (§22.4.4):** tokens/layout; charts são SVG estático no protótipo, agnósticos.

---

## 1. Intenção de design

O presidente **não** abre isto para trabalhar — abre para **ter orgulho e ter números**. É o
**placar institucional**: o que ele mostra num evento público, na fala de abertura da sessão, na
prestação de contas. Por isso a tela é **overview + tendência** (oposto do painel operacional do
servidor), e o **engajamento cidadão é o herói** — não um rodapé. A narrativa: *"a câmara está
produzindo, sessionando, presente — e o cidadão está acompanhando."*

⚠️ **Guardrail (§16.6):** **presença** por vereador é registro institucional, entra. Mas **ranking de
engajamento por vereador** é explicitamente fora (V2) — não transformar este painel em placar político
entre vereadores. Métricas são da **instituição**, não competição individual.

---

## 2. Tipo de produto e estilo
- **Product type:** *Analytics Dashboard* → **Data-Dense + Drill-Down**, secundário **Accessible &
  Ethical** (MASTER mantido). É o **único** lugar da V1 onde gráficos de verdade são o conteúdo.
- **Aplicação das regras de chart (a11y-first, do plugin):**
  - **Barra horizontal (AAA)** p/ presença por vereador e proposições por tipo — **ordenar desc**, rótulo de valor sempre visível.
  - **Linha (AA)** p/ tendência (acessos ao portal/mês, proposições/mês) — série por estilo de linha, não só cor; tabela alternável.
  - **Barra 100% empilhada** p/ proposições por status — **nunca pizza** (grau C).
  - **Bullet/progress (AAA)** p/ KPI vs meta (sessões realizadas vs previstas) — valor textual sempre.
  - **Evitar:** Sankey (C), pizza (C), radar sem barra-par (B).

---

## 3. Layout

**Header:** identidade da câmara · "Dashboard da Mesa" · **seletor de período** (sessão legislativa /
mês) · **Exportar relatório** (11.7 PDF/CSV — o presidente leva para a prestação de contas).

**Faixas (de cima para baixo):**
1. **KPI strip institucional** — 4 stat-cards com delta vs período anterior: Proposições · Sessões
   realizadas · Presença média · **Cidadãos acompanhando** (este último com destaque — é a vitória).
2. **Engajamento cidadão (HERO)** — faixa destacada: **linha** de acessos ao portal/mês (subindo) +
   mini-métricas (proposições acompanhadas, comentários/participações, e-SIC respondidos) + destaque
   "proposição mais acompanhada".
3. **Produtividade legislativa** — barra 100% empilhada (status) + barra horizontal (por tipo).
4. **Sessões & presença** — barra horizontal de presença por vereador (ordenada) + bullet sessões
   realizadas vs previstas + quórum médio.

Responsivo: charts colapsam para 1 coluna; barras mantêm rótulo textual (não viram só cor).

---

## 4. Componentes-chave e estados
- **Stat-card institucional:** número grande tabular + **delta** (↑/↓ vs período) com seta + cor
  semântica (verde sobe / vermelho desce) **e** sinal textual (não cor sozinha).
- **Chart card:** título + período + o gráfico + **valores em texto** + ação "exportar/ver tabela"
  (fallback de a11y obrigatório dos charts).
- **Loading:** skeleton dos cards/charts (>300ms).
- **Vazio (período sem dado):** "Sem dados no período — ajuste o filtro" (não tela branca).
- **Tempo real:** números podem atualizar durante a sessão (presença/quórum) via SSE — `aria-live` discreto.

---

## 5. Acessibilidade (reforços — herda o MASTER)
- Todo gráfico tem **valor em texto** + **tabela/export alternável** (Sankey/pizza/radar barrados).
- Cor nunca sozinha: delta com seta+sinal; séries de linha por estilo; barras com rótulo.
- Contraste ≥ 4.5:1; foco visível; navegação por teclado nos controles de período/export.
- Números tabular para não dançar entre períodos.

---

## 6. Anti-patterns específicos (além do MASTER)
- ❌ **Pizza/donut** para status ou tipo — usar barra (stacked/horizontal), grau AAA.
- ❌ **Ranking de engajamento por vereador** — fora de escopo (§16.6, V2); não criar placar político.
- ❌ Gráfico **sem valor textual** ou sem fallback de tabela.
- ❌ Vaidade sem ação: cada número deve poder **exportar** (o presidente precisa levar para fora).
- ❌ Densidade operacional de "tarefa" — esta tela é overview, não inbox.

---

## 7. Mockup
`../mockups/dashboard-mesa.html` — charts SVG estáticos. Métricas **ilustrativas** (`[GAP]`),
não inventam dados reais de câmara.
