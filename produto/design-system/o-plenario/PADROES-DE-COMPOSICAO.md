# O Plenário — Padrões de composição

> Companheiro de [`LINGUAGEM-VISUAL.md`](./LINGUAGEM-VISUAL.md) (a língua) e de
> [`componentes.html`](./componentes.html) (a biblioteca viva). Enquanto aqueles descrevem
> **tokens e componentes**, este documenta **como compor telas** — os arquétipos de página, o
> registro das "receitas" ainda não promovidas ao chassi, e o gatilho de promoção. É um
> documento **vivo**: cresce conforme o design system escala às 113 features / 12 módulos.

A fonte de verdade executável continua em `sistema/` (`tokens.css` · `chassi.css` · `tema.js`).
Em conflito, o código prevalece sobre este doc.

---

## 1. A regra de promoção (receita → chassi)

Um padrão nasce como **receita local** (CSS dentro de uma tela). Ele é promovido a **componente
de chassi** (CSS em `sistema/chassi.css`, documentado em `componentes.html`) quando cruza o gatilho:

> **2º uso real ⇒ promover.** Na 1ª aparição, vive na tela. Quando uma 2ª tela precisa do mesmo
> padrão, extraia para o chassi parametrizado (variáveis/modificadores) em vez de copiar — copiar
> é o drift que a extração de fundação veio matar.

Disciplina de cor/contraste vale **na promoção também**: todo componente promovido carrega AA nos
dois temas (texto 4.5:1, gráfico 3:1) e cor nunca como sinal único. Tokens AA-legíveis derivados
(`--acento-texto`, `--telha-fundo`, `--aviso-texto`, `--amarelo-traco`) existem justamente para isso.

---

## 2. Arquétipos de página

As 113 features se reduzem a um punhado de **formas de tela**. Cada nova tela começa escolhendo seu
arquétipo e herda dele a estrutura, em vez de reinventar layout.

| Arquétipo | O que é | Telas-âncora (provas) | Estado |
|---|---|---|---|
| **Cockpit de governança** | Vitrine read-model: ilha-herói + grade de painéis + barra de comando | Painéis da Mesa (16.11) | ✅ provado |
| **Cabine ao vivo** | Operação em tempo real: placar-ilha + hemiciclo + faixa de azulejo + comando | Sessão ao vivo (4.17) | ✅ provado |
| **Balcão de trabalho** | Servidor produz um artefato: painel de entrada + **ilha-papel** ao vivo + comando | Expediente (3.22) | ✅ provado |
| **Leitura pública** | Porta da rua white-label: hero + busca + cartões; mais ar, mobile-first | Portal do Cidadão (5.x/6.1) | ✅ provado |
| **Lista/tabela filtrável** | Coleção com filtros, ordenação, ações em massa, paginação, vazio | Proposições (3.x/11.5/11.7) | ✅ provado |
| **Ficha/detalhe** | Um registro a fundo: cabeçalho + abas/seções + trilha + ações | Ficha da matéria (11.8) | ✅ provado |
| **Wizard / multi-passo** | Fluxo guiado com indicador de etapa e voltar | Nova proposição (3.1) | ✅ provado |
| **Config / admin** | Formulários de ajuste agrupados, com salvar/descartar | Config da Câmara (1.9) | ✅ provado |

Os 4 primeiros estão materializados nas telas-herói; os 4 últimos são o trabalho de **Eixo 2** do
escalonamento. O cruzamento arquétipo × feature está em [`INVENTARIO-TELAS.md`](./INVENTARIO-TELAS.md).

---

## 3. Registro de receitas (ainda nas telas)

Padrões já provados que vivem inline numa ou mais telas. Marcados como `receita` na galeria. A coluna
**usos** dispara a promoção (≥2 ⇒ promover ao chassi).

| Receita | Onde vive | Usos | Promoção |
|---|---|---|---|
| **Faixa de azulejo** (assinatura) | sessão, portal, painéis, ficha, board (coluna), proposições (mini) | 6 | **promover já** — componente SVG parametrizado (estado × cor por etapa); a galeria tem a demo fiel |
| **Camada de confiança da IA** | portal, **editor**, **ata**, protocolo | 4 | **PROMOVER JÁ** — obrigatória onde houver IA (rótulo "gerado por IA" + revisão humana + fonte + reportar erro). Ver `GUIDELINES-CHECKLIST §3`. |
| **Ilha-palco** (telão escuro) | sessão, painéis, **app do vereador** | 3 | **promover** — tokens `--palco-*` já no chassi; falta o componente de moldura |
| **Ilha-papel** (documento) | expediente, **editor**, **ata**, **convocação** | 4 | **PROMOVER** — `--papel-*` no chassi; ⚠ texto secundário sobre `--papel-2` escurece no escuro (`GUIDELINES §5.1`) |
| **Anel de prazo** (donut honesto) | portal, painéis, **pendências** | 3 | **promover** — parametrizar por fração + rótulo + token de cor por urgência |
| **Chips de status / prazo / semáforo** | painéis, portal, **board**, **pendências**, **app** | 5 | **PROMOVER** — `chip-ok/alerta/risco/prazo/urgência` ícone+texto; `--aviso-texto`; branco sobre telha → `--telha-fundo` |
| **Pino/marca ancorada ↔ painel** (IA lê ESTE conteúdo) | **editor** (artigo↔check), **ata** (trecho↔áudio) | 2 | **promover** — âncora bidirecional texto↔observação; `aria-describedby` |
| **Campos de formulário** | expediente, protocolo, **pauta**, **config**, galeria | 5 | **promover** com estados (foco/erro/ajuda/disabled); borda ≥3:1 (`58% mix`) |
| **Cartão `.card`/`.bloco`** (cabeça+corpo) | painéis, board, pendências, pauta, app | 5+ | **promover** — cartão padrão de listas/fichas/cockpits |
| **Bottom tab bar** (mobile/PWA) | **app do vereador** | 1 | nova; ≤5 itens ícone+rótulo, `aria-current`; promover ao 2º mobile |
| **Player de áudio** (a fonte do ASR) | **ata** | 1 | nova; timeline + playhead + marcadores; promover se outra tela tocar gravação |
| **Reordenar acessível** (handles + setas teclado) | **pauta** | 1 | nova; `aria-disabled` nas pontas; só onde a ordem é editorial |
| **Nota [GAP] / [Regimento]** | painéis, **pendências**, **pauta**, **ata** | 4 | **promover** — "regra em homologação / varia por câmara" |

---

## 4. Princípios de composição (transversais)

- **Uma ação primária por tela.** A barra `.comando` ancora a decisão; secundárias subordinadas.
- **A ousadia mora em um lugar só** (a faixa de azulejo / a ilha-herói). O resto fica disciplinado.
- **Ilhas que não tematizam** quando o conteúdo é a coisa (placar = telão; documento = papel).
- **Densidade por público:** interno dá densidade ao servidor; público (portal) ganha ar e medida mais estreita (`body.superficie-publica`).
- **Voz:** rótulo sempre visível, erro com causa + correção, vazio que convida à ação (ver `LINGUAGEM-VISUAL.md` §Voz).
- **[GAP] é visível:** número ilustrativo / regra em homologação leva nota — nunca cravar conteúdo regulatório não-fechado.

---

## 5. Próximo

- **Os 8 arquétipos estão provados e as 8 telas ALTA feitas** (Fase E). Gate de revisão formalizado em
  [`GUIDELINES-CHECKLIST.md`](./GUIDELINES-CHECKLIST.md) (Eixo 4).
- **Promover a 1ª leva ao chassi parametrizado** — as ≥4-usos primeiro: camada de IA, azulejo, ilha-papel,
  chips, campos de formulário, `.card`. Documentar cada uma na galeria (`componentes.html`).
- **Telas MÉDIA** (login/MFA, admin usuários, cadastros, livro de atas, notificações, ouvidoria, navegação
  pública…) — todas **aplicam** arquétipos provados; rodar o `GUIDELINES-CHECKLIST` em cada uma.
- Manter este registro e o `INVENTARIO-TELAS.md` em dia conforme as telas reais nascerem.
