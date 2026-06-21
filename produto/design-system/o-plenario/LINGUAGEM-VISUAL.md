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

## Tema claro e escuro

A plataforma tem **dois modos da mesma língua** (decisão Emilio, 21/06): o **claro** é o concreto
luminoso sob luz tropical; o **escuro — "a noite de Brasília"** — mantém a paleta de azulejo sobre
um fundo jade-carvão, com jade/telha/cobalto/amarelo brilhando no escuro. Implementação por **tokens
semânticos** (`--bg`, `--surface`, `--linha`, `--texto`, `--texto-2`, `--marca`, `--acao`,
`--acento-texto`, `--foco`, `--palco-*`) remapeados por `[data-tema]`; respeita `prefers-color-scheme`
e lembra a escolha (`localStorage`). A **paleta-marca do azulejo é fixa** — só superfícies e texto trocam.

| Token | Claro | Escuro |
|---|---|---|
| `--bg` fundo | `#F1ECDD` areia | `#0E1A15` jade-carvão |
| `--surface` cartão | `#FBF8F0` | `#16271F` |
| `--linha` | `#E0D7BF` | `#2C4438` |
| `--texto` | `#19211C` | `#ECE7D6` |
| `--texto-2` | `#4C574F` | `#9FB0A4` |
| `--marca` wordmark/dados | `#0C5340` | `#43BD93` |
| `--acao` botão primário | `#0C5340` | `#18A074` |
| `--acento-texto` telha | `#B9421F` | `#F0794B` |
| `--foco` | `#1E5FA8` | `#6AA6DE` |
| `--palco-bg` placar | `#0A4334` (ilha escura) | `#0B3A2B` (painel saturado) |

Referência viva: **`telas/sessao-ao-vivo.html`** (toggle no header). Contraste AA+ verificado nos dois modos.

**Ilhas que não trocam de tema (decisão de linguagem):** uma superfície pode permanecer fixa quando o
**conteúdo é a coisa**, e o tema vira moldura. Dois casos, em inversão proposital: o **placar** da sessão
é uma **ilha escura** (em ambos os temas) — a votação é um telão; o **documento** do Expediente é uma
**ilha de papel** (creme em ambos, só acalmando à noite) — um ofício é papel. Em volta, a UI escurece.
Na mesma lógica, o **brasão da câmara é objeto heráldico** — selo de cores fixas que não tematiza (assim
o jade sempre lê no escuro).

**Inversão white-label na superfície pública (decisão de linguagem):** nas telas internas (Mesa, servidor)
**O Plenário lidera a marca** no topo. No **portal do cidadão**, a inversão: a **Câmara lidera** (brasão +
nome + "Portal do Cidadão"), e "O Plenário" recua para o rodapé como *plataforma*. A câmara parece sua;
o esqueleto/IA é o mesmo entre câmaras (5.1). E a **faixa de azulejo da tramitação encontra aqui seu lar**:
é a timeline pública da proposição (5.6) **e** o ciclo de vida do pedido de e-SIC (Protocolado → Em análise
→ Respondido → Recurso) — a mesma gramática de Bulcão, agora codificando o direito do cidadão.

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

- **Linguagem visual FECHADA = República Luminosa (B)**, agora em **dois modos (claro + escuro)**.
  v0 descartado (removido); A/C/D em `opcoes/`.
- **1ª tela aplicada:** `telas/sessao-ao-vivo.html` — Mesa de condução ao vivo (4.2/4.3/4.4/4.16/4.17),
  dual-theme, validada em desktop + mobile (a11y AA+).
- **2ª tela aplicada:** `telas/expediente.html` — Expediente · gerar documento (3.22/3.23/3.18/3.10),
  o balcão do servidor (HERO de POC). Herói = o **documento em papel ao vivo** com os campos do cadastro
  **sublinhados em telha** (merge legível) + **carimbo do Protocolo Geral** + **livro do Protocolo Geral**
  (numerador único, 3.23). Dual-theme, validada desktop + mobile (a11y AA+).
- **3ª tela aplicada:** `telas/portal-cidadao.html` — Portal do Cidadão (6.1 e-SIC amplo + 5.10 titular LGPD
  + 5.4/5.6 demo-power), a **porta da rua** white-label (a Câmara lidera; sem cadastro para consultar).
  Herói = a proposição em **tramitação viva** (faixa de azulejo, 5.6) + **resumo em linguagem simples** com
  camada de confiança da IA (rótulo + revisão humana + reportar erro). Dois **balcões de direito**: e-SIC
  **amplo** (qualquer info pública — sem seletor de tema; **anel do prazo legal** LAI 20+10 + recurso +
  azulejo do ciclo do pedido) e **Meus dados (LGPD)**, balcão separado com Encarregado/DPO público. Crítica
  adversarial multi-lente (a11y/anti-IA/jurídico/consistência) incorporada. Dual-theme, desktop + mobile, AA+.
- **Próximo:** painéis da Mesa (16.11). Cada tela puxa os tokens semânticos; UX/charts/consistência via
  `ui-ux-pro-max`; microcopy crítico em e-SIC/LGPD/cidadão coberto via `frontend-design`.
