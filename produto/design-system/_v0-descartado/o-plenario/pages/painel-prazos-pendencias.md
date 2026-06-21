# Page Override — Painel de Prazos + Caixa de Pendências

> **LOGIC:** este arquivo **sobrescreve** o `../MASTER.md` para esta superfície. O que não está
> aqui herda o MASTER (cor, tipografia, espaçamento, anti-patterns, checklist).
>
> **Superfície:** `produto/13` features **11.1** (Painel de prazos "o que vence") + **11.2** (Caixa
> de pendências "minhas tarefas hoje"). É a **home diária do servidor** — onde o decisor da POC pousa
> todo dia e onde a **confiança operacional (Aposta 3) fica visível**. Tags `[DIF] [RM]`.
>
> **Substrato (read-model, sem novo modelo de dados — `[RM]`):**
> - **11.1** projeta `prazo_dominio_ativo` (polimórfico, §22.7.7) + `compliance_avaliacao`
>   (append-only, a *prova* de compliance, Invariante 10) + prazos de tramitação (3.8). O status
>   (em dia / vencendo / vencido) vem do **monitoramento de prazo S1** do motor — o motor já
>   *monitora*, esta tela só *mostra*.
> - **11.2** projeta: assinaturas pendentes (3.10 ICP-Brasil), pareceres pendentes (3.5), ata-IA /
>   transcrição aguardando revisão (4.13 / 4.12 → workflow de revisão humana **8.5**).
>
> **Stack deferida (§22.4.4):** isto são tokens/layout de design. O mockup HTML é protótipo visual
> agnóstico — **não** decide RN/web.

---

## 1. Intenção de design (o "porquê" antes do "o quê")

A tela responde, de cima para baixo, às **três perguntas do servidor** — e essa narrativa **é** o
pitch de confiança operacional:

1. **"Estou seguro?"** → faixa de KPIs de saúde de prazo (resumo).
2. **"O que está queimando?"** → Painel de Prazos, ordenado por urgência ("o que vence").
3. **"O que eu faço agora?"** → Caixa de Pendências (tarefas acionáveis com CTA + ação em lote).

**Tom:** calmo e sob controle, **não** ansioso. Vermelho é exceção (só vencido/vence-hoje), não o
estado-base. Caixa vazia é vitória, não tela morta ("Tudo em dia ✓").

---

## 2. Tipo de produto e estilo (search da skill)

- **Product type:** *Analytics Dashboard* → estilo **Data-Dense + Drill-Down**, secundário
  **Accessible & Ethical** (= o estilo do MASTER se mantém num dashboard ✓).
- **Densidade:** alta, mas escaneável — hierarquia por **tamanho/peso/espaço**, não por cor (a cor
  é reservada para *status*).

---

## 3. Layout

**App shell:** sidebar navy fixa à esquerda (`--color-primary #0F172A`) + top bar (identidade da
câmara · busca global 11.5 · sino de notificações 11.6 · usuário). Conteúdo sobre `--color-background`.

**Home = 3 faixas:**

| Faixa | Conteúdo | Largura |
|---|---|---|
| **A — Saúde de prazo** | Saudação + data + **4 stat-cards**: Vencidos · Vence hoje · Vence ≤7d · Em dia. Abaixo, **barra 100% empilhada** (mix vencido/vencendo/em-dia) com % em texto. | full |
| **B — Painel de Prazos (11.1)** | "O que vence" — lista priorizada por urgência. Filtros: `Tudo · TCE · Tramitação`. | ~60% (desktop) / full (mobile, vem primeiro) |
| **C — Minhas Pendências (11.2)** | "Minhas tarefas hoje" — inbox agrupado por tipo + **multi-seleção + barra de ação em lote**. | ~40% / full |

Mobile: **content-priority** — B (prazos) antes de C (pendências); KPIs viram carrossel de 2×2.
`max-width` do container = MASTER (`max-w-7xl`). Sem scroll horizontal.

---

## 4. Componentes-chave e estados

### 4.1 Stat-card de prazo (faixa A)
Número grande (**tabular-nums**), rótulo, ícone SVG. O card "Vencidos" só fica vermelho **se > 0**;
zero é neutro. Clicável → filtra a lista B.

### 4.2 Item de prazo (faixa B) — o componente mais importante
Linha/cartão com: **selo de origem** (TCE-CE | Tramitação) · título · **prazo** (data absoluta
`tabular-nums` + countdown relativo "em 3 dias") · **status pill** · responsável · CTA.

**Status pill = ícone + texto + cor (NUNCA cor sozinha — `color-not-only`):**

| Status | Texto | Cor texto / fundo | Ícone |
|---|---|---|---|
| Vencido | `Vencido há Nd` | `#991B1B` / `#FEE2E2` | alert-octagon |
| Vence hoje / ≤2d | `Vence em Nd` | `#92400E` / `#FEF3C7` | alert-triangle |
| Em dia | `No prazo` | `#166534` / `#DCFCE7` | check-circle |
| Concluído/enviado | `Enviado` | `#1E3A5F` / `#E0E7EF` | send |

Ordenação: vencido → vence-hoje → ≤7d → em dia. **Drill-down:** clicar abre o histórico
`compliance_avaliacao` (a prova append-only) — confiança = poder mostrar a prova.

### 4.3 Item de pendência (faixa C)
Agrupado por tipo: **Assinar** (3.10) · **Parecer** (3.5) · **Revisar ata-IA / transcrição**
(4.13/4.12 → 8.5). Cada item: ícone do tipo · descrição · contexto (`PL 042/2026`) · prazo (se
houver) · CTA primário. **Assinar = assinatura em 2 toques (7.3).** Checkbox por linha → **barra de
ação em lote** ("Assinar 4 selecionados").

### 4.4 Estados obrigatórios
- **Loading:** skeleton (animate-pulse) acima de 300ms — nunca UI congelada.
- **Vazio (prazos):** ilustração leve + "Nenhum prazo crítico. Tudo em dia ✓" (positivo).
- **Vazio (pendências):** "Caixa zerada — nada pendente para hoje ✓".
- **Erro:** mensagem com causa + ação de retry (não "erro genérico").
- **Tempo real:** prazo recém-vencido entra com `aria-live="polite"` (sem roubar foco).

---

## 5. Acessibilidade (reforços específicos — herda o resto do MASTER)
- Status **sempre** com ícone + rótulo textual além da cor.
- Pares texto/fundo das pills verificados para **≥ 4.5:1** (alvo AAA onde der).
- Números (datas, contagens, countdown) em **tabular-nums** para não dançar.
- Lista navegável por teclado; ordem de foco = ordem visual; bulk-select acionável por teclado.
- `prefers-reduced-motion`: sem o pulse do skeleton; corta transições.

---

## 6. Anti-patterns específicos (além dos do MASTER)
- ❌ **Pizza/donut** para mix de status (falha WCAG p/ daltônico) → barra 100% empilhada + % textual.
- ❌ **Vermelho como cor-base** do painel — só vencido/vence-hoje. Mar de vermelho = ansiedade, não confiança.
- ❌ **Gauge/velocímetro decorativo** sem valor textual ao lado.
- ❌ Countdown **sem** a data absoluta junto (relativo sozinho é ambíguo).
- ❌ Ação destrutiva/irreversível (ex.: marcar enviado ao TCE) sem confirmação.

---

## 7. Mockup
`../mockups/painel-prazos.html` — protótipo HTML/CSS autocontido, abre no navegador. Dados são
**placeholder ilustrativo** (não inventa conteúdo regulatório real do TCE-CE — segue `[GAP]`).
