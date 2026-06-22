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
| **Ficha/detalhe** | Um registro a fundo: cabeçalho + abas/seções + trilha + ações | espelho da matéria (11.8) | ⬜ a desenhar |
| **Wizard / multi-passo** | Fluxo guiado com indicador de etapa e voltar | tramitação, protocolo | ⬜ a desenhar |
| **Config / admin** | Formulários de ajuste agrupados, com salvar/descartar | admin do ente (1.8/1.9) | ⬜ a desenhar |

Os 4 primeiros estão materializados nas telas-herói; os 4 últimos são o trabalho de **Eixo 2** do
escalonamento. O cruzamento arquétipo × feature está em [`INVENTARIO-TELAS.md`](./INVENTARIO-TELAS.md).

---

## 3. Registro de receitas (ainda nas telas)

Padrões já provados que vivem inline numa ou mais telas. Marcados como `receita` na galeria. A coluna
**usos** dispara a promoção (≥2 ⇒ promover ao chassi).

| Receita | Onde vive | Usos | Promoção |
|---|---|---|---|
| **Faixa de azulejo** (assinatura) | sessão, portal, painéis | 3 | **promover já** — componente SVG parametrizado (estado × cor por etapa); a galeria tem a demo fiel |
| **Ilha-palco** (telão escuro) | sessão, painéis | 2 | **promover** — tokens `--palco-*` já no chassi; falta o componente de moldura |
| **Ilha-papel** (documento) | expediente | 1 | aguardar 2º uso (ex.: visualizar autógrafo 3.13) |
| **Anel de prazo** (donut honesto) | portal, painéis | 2 | **promover** — parametrizar por fração + rótulo + token de cor por urgência |
| **Camada de confiança da IA** | portal | 1 | padrão **obrigatório** onde houver IA pública (5.4, 4.13, 3.11) — promover ao 2º uso |
| **Chips de status / semáforo** | painéis, portal | 2 | **promover** — `chip-ok/alerta/risco` com ícone+texto; usar `--aviso-texto` |
| **Nota [GAP]** | painéis | 1 | candidata; disciplina de "regra em homologação" |
| **Campos de formulário** | expediente, galeria | 2 | **promover no Eixo 2** com estados (foco/erro/ajuda/disabled) |
| **Cartão `.bloco`** (cabeça+corpo) | painéis | 1+ | alta frequência; promover cedo (será o cartão padrão das listas/fichas) |
| **Trilho de prazo / fila de ação / tabuleiro de estágios** | painéis | 1 | densos; promover conforme as telas de gestão pedirem |

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

- Promover a 1ª leva de receitas (azulejo, ilha-palco, anel, chips, `.bloco`) ao chassi parametrizado.
- Desenhar os 4 arquétipos faltantes (Eixo 2), começando por **lista/tabela filtrável**.
- Manter este registro e o `INVENTARIO-TELAS.md` em dia conforme as telas reais forem nascendo.
