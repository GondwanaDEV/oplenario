# O Plenário — Design System (fase de design da V1)

> **Ponto de entrada do design.** Esta pasta é o artefato da **fase de design** da trilha de
> produto/UX (`produto/`). Tooling: **UI/UX Pro Max – Design Intelligence** como **consultor**
> (regras de estilo/cor/tipo/chart/ux + checklist de a11y) — **autoria dos artefatos à mão**, amarrada
> às âncoras de arquitetura (§22.x do `documento-mestre-camaras.md`).
>
> **Stack deferida (§22.4.4):** tudo aqui são **tokens/layout de design agnósticos** + protótipos
> HTML/CSS. **Não** constitui decisão de stack (RN/web). Conteúdo dos mockups é **ilustrativo** —
> não inventa conteúdo regulatório real do TCE/regimento (`[GAP]` segue `[GAP]`).

---

## Como ler

1. **`MASTER.md`** — o design system global: estilo (*Accessible & Ethical*, WCAG), paleta
   (navy `#0F172A` + azul `#0369A1`), tipografia (**Lexend + Source Sans 3**; EB Garamond só em texto
   de lei), espaçamento 4/8px, sombras, componentes, anti-patterns, checklist de pré-entrega.
2. **`pages/<x>.md`** — **override por superfície**: sobrescreve o MASTER só onde a superfície pede.
   Cada um carrega intenção de design, layout, componentes, estados, a11y e anti-patterns específicos.
3. **`mockups/<x>.html`** — protótipo visual autocontido (abre no navegador) que **realiza** o override.

Regra: ao construir uma superfície, lê-se `pages/<x>.md`; o que não estiver lá, herda o `MASTER.md`.

---

## As 7 superfícies (leva `[DIF]` completa)

| Superfície | Persona / aposta | `pages/` · `mockups/` | Decisão de design central |
|---|---|---|---|
| **Painel de Prazos + Pendências** (11.1/11.2) | servidor / confiança | `painel-prazos-pendencias` · `painel-prazos` | narrativa "estou seguro? → o que queima? → o que faço hoje" |
| **Revisão Humana de IA** (8.5) | servidor / IA+UX | `revisao-ia` · `revisao-ia` | a IA propõe, o humano dispõe: fonte (8.1) + incerteza (8.2) + gate de publicação |
| **App do Vereador** (16.7) | vereador / UX | `app-vereador` · `app-vereador` | densidade baixa; assinar em 2 toques; **sem gabinete** |
| **Dashboard da Mesa** (11.4) | presidente / engajamento | `dashboard-mesa` · `dashboard-mesa` | placar institucional; engajamento cidadão é herói; charts a11y-first |
| **Portal Cidadão** (16.5) | cidadão / transparência | `portal-cidadao` · `portal-cidadao` | linguagem simples (IA) **antes** do texto oficial; eMAG/WCAG |
| **Sessão ao Vivo** (16.4) | todos / tagline | `sessao-ao-vivo` · `sessao-ao-vivo` | telão; **tema escuro = desvio sancionado**; voto = cor+ícone+rótulo |
| **Painel de Tramitação** (11.3) | servidor/Mesa / fluxo | `painel-tramitacao` · `painel-tramitacao` | kanban da máquina de estados; mover = transição governada pelo motor |

Cobrem **3 personas decisoras × 3 apostas de produto + o momento-âncora da sessão**.

---

## Fios que costuram tudo (consistência cross-surface)

- **Tipografia única:** Lexend (títulos) + Source Sans 3 (corpo); EB Garamond só em **conteúdo de lei**
  (ata, texto oficial) — chrome nunca em serif.
- **Semântica de status única:** âmbar = atenção/vencendo · vermelho = crítico/vencido · verde = ok ·
  neutro = tramitando — **sempre ícone + rótulo + cor** (nunca cor sozinha, WCAG/daltonismo).
- **IA em azul, nunca gradiente roxo/rosa** — selo rotulado + ícone *sparkle*; proveniência sempre
  explícita (a IA propõe, o humano dispõe — contrato §22.3).
- **Read-models `[RM]`** projetam dado já capturado (eventos, audit log, motor de prazo) — sem novo
  modelo de dados.
- **Exceção de tema documentada:** só a Sessão ao Vivo (16.4) usa tema escuro — justificado por
  projeção, e marcado para **não** virar desculpa de dark-mode nas demais.

---

## Fora desta leva (deliberado)
- **CRUD-padrão** (cadastros 16.2, protocolo, comissões) herda o MASTER direto — não precisa de override
  próprio para ser consistente.
- **Tail caro** parqueado nas features (DOe-de-registro, consolidação por IA, BI de verdade) — V1.5+.
- **Fidelidade de produção / componentização** depende da decisão de stack (§22.4.4), ainda aberta.
