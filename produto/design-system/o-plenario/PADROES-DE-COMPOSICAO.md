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
| **Display / projeção** | Telão read-only, glanceável a distância, SEMPRE escuro, tipo enorme | Telão de votação (4.2) | ✅ provado |
| **Calendário** | Grade de mês + agenda lateral; evento por cor+rótulo | Calendário institucional (2.6) | ✅ provado |

**Os 10 arquétipos estão provados.** Toda tela do catálogo (113 features / 12 módulos) tem uma forma
de referência; o que resta é **aplicar**. O cruzamento arquétipo × feature está em
[`INVENTARIO-TELAS.md`](./INVENTARIO-TELAS.md).

---

## 3. Registro de receitas (ainda nas telas)

Padrões já provados que vivem inline numa ou mais telas. Marcados como `receita` na galeria. A coluna
**usos** dispara a promoção (≥2 ⇒ promover ao chassi).

> **✅ Fase A do INVENTARIO §5.2 (26/06) — promovidos ao chassi:** o NOVO **cartão de sinal**,
> mais **azulejo** (faixa + stepper de tramitação), **ilha-palco**, **ilha-papel**, **chips**,
> **selo encadeado**, **passos do wizard** e **botão gov.br**. Saíram do `<style>` da galeria (ou
> nasceram no chassi) e agora vivem em `sistema/chassi.css`, documentados em `componentes.html`. AA
> medida nos 2 temas. As telas-fonte migram para o chassi nos seus próprios redesigns (Fase B), não aqui.

| Receita | Onde vive | Usos | Promoção |
|---|---|---|---|
| **Cartão de sinal** (camada de atenção) | minhas-pendencias `.item`, vereador-app `.card`, paineis-mesa `.fila-item` | 3 | ✅ **chassi.css** — `.sinal` parametrizado por gravidade (informativo↔crítico/FALHA); telha só na crítica; DNA `ator·gravidade⟂prazo·UMA ação·deep-link` |
| **Faixa de azulejo** (assinatura) | sessão, portal, painéis, ficha, board (coluna), proposições (mini) | 6 | ✅ **chassi.css** — `.azulejo`/`.az-*` (estado real × cor por etapa); `role="img"`+`aria-label` |
| **Camada de confiança da IA** | portal, **editor**, **ata**, protocolo | 4 | **PROMOVER JÁ** — obrigatória onde houver IA (rótulo "gerado por IA" + revisão humana + fonte + reportar erro). Ver `GUIDELINES-CHECKLIST §3`. |
| **Ilha-palco** (telão escuro) | sessão, painéis, **app do vereador** | 3 | ✅ **chassi.css** — `.ilha-palco` (+ tokens `--palco-*`) |
| **Ilha-papel** (documento) | expediente, **editor**, **ata**, **convocação** | 4 | ✅ **chassi.css** — `.ilha-papel` (+ tokens `--papel-*`); ⚠ `.merge` no escuro = 3.72 (sublinhado 2px = sinal gráfico; `GUIDELINES §5.1`, passe futuro de token) |
| **Anel de prazo** (donut honesto) | portal, painéis, **pendências** | 3 | **promover** — parametrizar por fração + rótulo + token de cor por urgência |
| **Chips de status / prazo / semáforo** | painéis, portal, **board**, **pendências**, **app** | 5 | ✅ **chassi.css** — `.chip` + `ok/alerta/risco/info/neutro` + `.chip-cheio` (crítica preenchida); ícone+texto; branco só sobre `--telha-fundo` |
| **Pino/marca ancorada ↔ painel** (IA lê ESTE conteúdo) | **editor** (artigo↔check), **ata** (trecho↔áudio) | 2 | **promover** — âncora bidirecional texto↔observação; `aria-describedby` |
| **Campos de formulário** | expediente, protocolo, **pauta**, **config**, galeria | 5 | **promover** com estados (foco/erro/ajuda/disabled); borda ≥3:1 (`58% mix`) |
| **Cartão `.card`/`.bloco`** (cabeça+corpo) | painéis, board, pendências, pauta, app | 5+ | **promover** — cartão padrão de listas/fichas/cockpits |
| **Bottom tab bar** (mobile/PWA) | **app do vereador** | 1 | nova; ≤5 itens ícone+rótulo, `aria-current`; promover ao 2º mobile |
| **Player de áudio** (a fonte do ASR) | **ata** | 1 | nova; timeline + playhead + marcadores; promover se outra tela tocar gravação |
| **Reordenar acessível** (handles + setas teclado) | **pauta** | 1 | nova; `aria-disabled` nas pontas; só onde a ordem é editorial |
| **Nota [GAP] / [Regimento]** | painéis, **pendências**, **pauta**, **ata**, **autoria**, **anexar-ata** | 6 | **promover** — "regra em homologação / varia por câmara" |
| **Faixa de azulejo da tramitação** (etapas + datas) | **ficha pública**, **perfil vereador** (mini), portal-materias (mini) | 3 | ✅ **chassi.css** — a forma stepper = `.azulejo` + `.az-passos` (rótulos) + datas honestas nas etapas futuras |
| **Selo encadeado / auditoria append-only** | **trilha-auditoria**, **console-operador-tenant** | 2 | ✅ **chassi.css** — `.lacre`/`.trilha`/`.ev`/`.nota-imut`; ação reusa os chips |
| **Passos do wizard** (indicador 1..n) | **autoria-apoiamento** (+ expediente/nova proposição) | 2 | ✅ **chassi.css** — `.passos`/`.bola`/`.traco`; `aria-current="step"` |
| **Switch (toggle on/off)** | **console-operador-tenant** (flags) | 1 | nova; estado por cor **+ rótulo** (Ativado/Desativado) + `role="switch"`/`aria-checked` |
| **Botão gov.br oficial** | **entrar-govbr**, ficha pública (compor comentário) | 2 | ✅ **chassi.css** — `.govbr` (marca oficial, não tematiza); branco 7.33 / amarelo 4.88 AA |
| **Faixa de legenda (closed-caption)** | **legendas-ao-vivo** | 1 | nova; banda fixa-escura tipo-TV (não tematiza), texto grande, controles de tamanho (LBI) |
| **Folha de confirmação (sheet) 2-toques** | **assinatura-2-toques** | 1 | nova; scrim + sheet biométrica; revisar→confirmar; nota de validade jurídica |

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
- ✅ **Fase A (26/06) FEITA** — promovidos ao chassi: **cartão de sinal** (novo), azulejo (+ stepper),
  ilha-palco, ilha-papel, chips, selo encadeado, passos do wizard, botão gov.br. Documentados na galeria.
  **Resta promover** (2ª leva): camada de IA, campos de formulário, `.card`, anel de prazo, nota [GAP].
- **Fase B (próxima):** retrofit das telas-fonte para consumirem o chassi (esvazia o CSS inline duplicado)
  + a camada de atenção nas 4 homes + estado degradado de IA (R-IA-1) por-superfície. Ver `INVENTARIO §5.2`.
- **Telas MÉDIA** (login/MFA, admin usuários, cadastros, livro de atas, notificações, ouvidoria, navegação
  pública…) — todas **aplicam** arquétipos provados; rodar o `GUIDELINES-CHECKLIST` em cada uma.
- Manter este registro e o `INVENTARIO-TELAS.md` em dia conforme as telas reais nascerem.
