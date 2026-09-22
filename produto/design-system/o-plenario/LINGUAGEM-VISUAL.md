# O Plenário — Linguagem Visual

> **Direção escolhida (Daouda Traore, 21/06/2026): "República Luminosa" (Cívico Tropical).**
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
| `--verde-1` | `#E7F0E2` | Sálvia 1 — o campo (fundo claro). No escuro vira **tinta**. |
| `--verde-2` | `#C7D9C4` | Sálvia 2 — divisórias no claro. |
| `--verde-3` | `#A6BFA2` | Sálvia 3 — texto secundário no escuro. |
| `--verde-4` | `#7A9B7A` | Sálvia 4 — a mais escura da paleta; ação no escuro. |
| `--creme` | `#FFF7EA` | Creme — superfície de cartão no claro, e o papel. |
| `--jade` | `#2F5D3F` | Primário — verde da marca, re-afinado para a família da sálvia. |
| `--jade-fundo` | `#234A31` | Blocos sólidos / hover do primário / palco. |
| `--jade-claro` | `#7A9B7A` | Realce. |
| `--telha` | `#D9542B` | **Acento** coral/telha — fills, faixas, azulejo. |
| `--telha-fundo` | `#B9421F` | Telha escurecida p/ fills sólidos. |
| `--cobalto` | `#1E5FA8` | Azul de azulejo — secundário **e anel de foco**. |
| `--cobalto-fundo` | `#184E8A` | Cobalto sólido. |
| `--amarelo` | `#E8B23A` | Amarelo Marajó — 4ª cor do azulejo, **escassa** (realce). |
| `--bg` | `#E7F0E2` | Fundo claro — o campo de sálvia. **Semântico**: inverte no escuro. |
| `--surface` | `#FFF7EA` | Superfície de cartão — creme. |
| `--linha` | `#C7D9C4` | Divisórias. |
| `--texto` *(tinta)* | `#16231A` | Texto principal (13.93:1 sobre `--bg`). |
| `--texto-2` | `#46584A` | Texto secundário (6.52:1 sobre `--bg`). |

> **Nota:** a paleta-marca (`--jade`…`--amarelo`) é **fixa** (`:root` em `sistema/tokens.css`).
> **Telha, cobalto e amarelo permanecem de propósito**: não são decoração, carregam função (acento,
> foco, aviso). Um sistema monocromático verde apagaria a distinção entre esses estados — foi a razão
> de a troca de paleta cobrir superfícies + marca, e não os hues semânticos.
> Os tokens de superfície/texto são **semânticos** e resolvem por tema; os valores na tabela são os do
> **claro**. **Fonte de verdade: `sistema/tokens.css`.**

**Disciplina de cor (load-bearing):** a telha cheia `#D9542B` é para **fills/azulejo/bordas**; para
**texto** use `--acento-texto` (`#A33A19` no claro), medido AA sobre as superfícies novas. Cor nunca é o
único sinal — sempre acompanha ícone/rótulo. Foco visível: anel cobalto 3px.

## Tema claro e escuro

A plataforma tem **dois modos da mesma língua**: o **claro** é o campo de sálvia sob luz tropical, com
papel creme; o **escuro** é a **mesma sálvia invertida** — os tons claros da paleta deixam de ser
superfície e viram **tinta** sobre verde profundo. É o que permite uma paleta só servir os dois temas.

**Mecânica (mudou em 22/09/2026):** cada token é declarado **uma vez** com `light-dark(claro, escuro)`;
quem escolhe o lado é a propriedade `color-scheme` (`:root` = `light dark`, e `[data-tema]` sobrescreve).
Antes havia um bloco `@media(prefers-color-scheme)` que duplicava o escuro à mão — e a paridade já tinha
furado. Agora é impossível os temas divergirem por esquecimento.

⚠️ **Precondição:** o LightningCSS reescreve `light-dark()` e só funciona se houver declaração
`color-scheme`. Sem ela o token compila para lixo **e a build passa**. Travado por teste
(`src/app/tokens.test.ts`).

| Token | Claro | Escuro |
|---|---|---|
| `--bg` fundo | `#E7F0E2` sálvia | `#111A13` verde profundo |
| `--surface` cartão | `#FFF7EA` creme | `#1A251C` |
| `--linha` | `#C7D9C4` | `#334536` |
| `--texto` | `#16231A` | `#E7F0E2` |
| `--texto-2` | `#46584A` | `#A6BFA2` |
| `--marca` wordmark/dados | `#2F5D3F` | `#8FBE92` |
| `--acao` botão primário | `#2F5D3F` | `#7A9B7A` |
| `--acento-texto` telha | `#A33A19` | `#F0794B` |
| `--foco` | `#1B5490` | `#7FB3E6` |
| `--palco-bg` placar | `#234A31` (ilha escura) | `#1B3A28` (painel saturado) |

Referência viva: **`telas/sessao-ao-vivo.html`** (toggle no header). Contraste AA+ verificado nos dois modos.

**Ilhas que não trocam de tema (decisão de linguagem):** uma superfície pode permanecer fixa quando o
**conteúdo é a coisa**, e o tema vira moldura. Dois casos, em inversão proposital: o **placar** da sessão
é uma **ilha escura** (em ambos os temas) — a votação é um telão; o **documento** do Expediente é uma
**ilha de papel** (creme em ambos, só acalmando à noite) — um ofício é papel. Em volta, a UI escurece.
Na mesma lógica, o **brasão da câmara é objeto heráldico** — selo de cores fixas que não tematiza (assim
o jade sempre lê no escuro). O mesmo dispositivo de **ilha escura** reaparece nos **Painéis da Mesa** como o
**telão da saúde institucional** (a Casa perante o TCE) — não é votação, mas é igualmente "o que a sala inteira lê".

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
- **4ª tela aplicada:** `telas/paineis-mesa.html` — Painéis da Mesa (módulo 16.11 read-model: 11.4 dashboard
  institucional + 11.1 "o que vence" + 11.2 despachos + 11.3 pipeline + ponte 4.17), **a tela do comprador**
  (Presidente/Mesa lê a vitrine de cima; jurídico/risco lê a prova embaixo — mesmo read-model, leituras
  diferentes). Herói = **ilha-placar da saúde institucional** (o telão "A Casa está em dia com o TCE-CE":
  placar de obrigações 11·1·0 + anel da próxima remessa + **3ª leitura da faixa de azulejo** = ciclo da
  obrigação de compliance [Aberta → Em curso → Aceita] + selos de continuidade que desarmam o jurídico).
  Charts honestos (semáforo por linha, trilho de prazos por urgência, tabuleiro de estágios com azulejo,
  fila de ação — **sem donut/KPI-card**). Disciplina de **[GAP]**: datas do TCE = "regra em homologação" +
  nota visível. Crítica adversarial 4-lente incorporada (orgulho fora da barra fixa; data da audiência LRF
  corrigida art. 9º §4º; sparklines → referência honesta; placar unificado; contraste **AA medido em pixel
  composto** nos 2 temas). Dual-theme, desktop + mobile, AA+.
- **Próximo:** as **4 telas-que-decidem-a-compra** estão fechadas — os 3 públicos decisores cobertos. O próximo
  macro-passo deixa de ser telas-herói e vira **escalar o sistema** ao catálogo amplo (113 features / 12
  módulos): biblioteca de componentes e padrões reusáveis a partir da linguagem provada. Cada tela puxa os
  tokens semânticos; UX/charts/consistência via `ui-ux-pro-max`; microcopy crítico via `frontend-design`.
