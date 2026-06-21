# O Plenário — Linguagem Visual

> **Direção escolhida (Emilio, 21/06/2026): "República Luminosa" (Cívico Tropical).**
> Style-tile canônico: [`linguagem-visual.html`](./linguagem-visual.html) (abra no navegador).
> As direções exploradas e **não** escolhidas ficam em [`opcoes/`](./opcoes/) como registro:
> `A-modernismo-civico` · `C-pedra-e-bronze` · `D-o-registro`. O v0 descartado vive em
> `../_v0-descartado/` (recuperável). Em conflito, **o `linguagem-visual.html` é a fonte de verdade**;
> esta spec o descreve.

## Conceito

O otimismo cromático do **modernismo brasileiro** — os azulejos de **Athos Bulcão**, os jardins de
**Burle Marx**, o concreto luminoso de Brasília sob a luz dos trópicos. Institucional, mas **quente,
vivo e confiantemente colorido** — brasileiro **sem** o clichê do verde-bandeira. A cor é a ousadia;
a dignidade institucional e a acessibilidade são o piso.

Três públicos, uma língua: **servidor** (eficiência), **vereador** (dignidade/mobile),
**cidadão** (clareza/confiança).

## Tokens de cor

| Token | Hex | Papel |
|---|---|---|
| `--jade` | `#0C5340` | Primário — verde sério (NÃO bandeira). Ações, links, cabeçalhos. |
| `--jade-fundo` | `#0A4334` | Blocos sólidos / hover do primário. |
| `--jade-claro` | `#16785C` | Realce sobre areia. |
| `--telha` | `#D9542B` | **Acento** coral/telha — usado com ousadia em fills, faixas, azulejo. |
| `--telha-fundo` | `#B9421F` | Telha escurecida p/ **texto** sobre areia (passa AA). |
| `--cobalto` | `#1E5FA8` | Azul de azulejo — secundário **e anel de foco**. |
| `--cobalto-fundo` | `#184E8A` | Cobalto sólido. |
| `--amarelo` | `#E8B23A` | Amarelo Marajó — 4ª cor do azulejo, **escassa** (realce). |
| `--areia` | `#F1ECDD` | Fundo — areia quente (com luz tropical difusa no topo). |
| `--areia-2` | `#FBF8F0` | Superfície de cartão. |
| `--areia-borda` | `#E0D7BF` | Divisórias quentes. |
| `--tinta` | `#19211C` | Texto principal (~13:1 sobre areia). |
| `--tinta-2` | `#4C574F` | Texto secundário (AA sobre areia). |

**Disciplina de cor (load-bearing):** a telha cheia `#D9542B` é para **fills/azulejo/bordas**; para
**texto** sobre areia use `--telha-fundo #B9421F` (a versão cheia falha AA em texto). Cor nunca é o
único sinal — sempre acompanha ícone/rótulo. Foco visível: anel cobalto 3px.

## Tipografia

- **Display — `Sora`** (700–800): geométrica-humanista com calor. Wordmark, títulos, hero. **Não-Inter.**
- **Corpo/UI — `Hanken Grotesk`** (400–700): legível, quente, todo o texto de interface e leitura.
- **Dados — `IBM Plex Mono`** (500): protocolo, quórum, datas, prazos, eyebrows — `tabular-nums`.

Escala: **13 · 16 · 21 · 28 · 40 · 56**. Corpo base 16px, linha 1.6, medida 56–60 ch.

## A assinatura — a faixa de azulejo da tramitação

Onde mora a ousadia (e **só** ela; o resto fica disciplinado). Um **módulo de azulejo à la Bulcão**
em que **cada peça é uma etapa** do processo legislativo — `Protocolo → Comissões → 1º turno →
2º turno → Sanção`:
- etapas concluídas: peça preenchida na paleta cheia;
- etapa em curso: o quarto-de-círculo do azulejo aberto pela metade, em **amarelo Marajó**;
- etapas pendentes: peça em "concreto" tracejado.
Codifica estado **real** da tramitação, estruturalmente — nunca decoração. No header, um pequeno
**hemiciclo** (mapa de votação) reforça a marca.

## Movimento

Contido (setor público + anti-IA). 120–250ms, `ease`/`ease-out`. Realces sutis (translateY −1px em
botões). `prefers-reduced-motion` desliga. Sem firehose de animação.

## Voz / copy

Português institucional, porém plano. Voz ativa; do lado do usuário ("acompanhar", não "consultar o
webhook"). Erros dizem **causa + correção**. Empty state convida à ação. Crítico em e-SIC, LGPD e
portal do cidadão.

## Anti-IA (o que recusamos de propósito)

- ❌ Navy `#0F172A` + azul de SaaS (dashboard de governo genérico).
- ❌ Cream + serif alto-contraste + terracota (cluster nº1 de IA).
- ❌ Preto + verde-ácido/vermelhão (cluster nº2).
- ❌ Layout de jornal: fios capilares, colunas densas (cluster nº3).
- ❌ Gradiente roxo/rosa; fontes default (Inter/Roboto/Lexend).
- ✅ Jade + telha + azulejo cobalto/Marajó · faixa de azulejo (Bulcão) · Sora/Hanken/Plex Mono ·
  modernismo brasileiro luminoso · AA+ verificado.

## Status & próximos passos

- **Linguagem visual FECHADA = República Luminosa (B).** v0 arquivado; A/C/D em `opcoes/`.
- **Próximo:** aplicar a linguagem às **telas que decidem a compra** — sessão ao vivo + mesa de
  condução (4.14–4.21), Expediente/documentos (3.22–3.23), portal cidadão + e-SIC/LGPD (6.1/5.10),
  painéis (16.11). Cada tela puxa estes tokens; UX/charts/consistência via `ui-ux-pro-max`.
