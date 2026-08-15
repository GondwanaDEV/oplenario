# O Plenário — Guidelines-checklist (gate de revisão de tela)

> Eixo 4 do escalonamento do design system. Companheiro de
> [`LINGUAGEM-VISUAL.md`](./LINGUAGEM-VISUAL.md) (a língua),
> [`PADROES-DE-COMPOSICAO.md`](./PADROES-DE-COMPOSICAO.md) (como compor) e
> [`componentes.html`](./componentes.html) (a biblioteca viva).
>
> **Como usar:** rode esta lista **antes de commitar qualquer tela**. É um gate, não um
> conselho — cada item é verificável. Os itens com 🔴 são os que já quebraram telas reais
> nesta casa (armadilhas medidas), por isso vêm com o valor que falhou e o conserto.

A fonte de verdade executável é `sistema/` (`tokens.css` · `chassi.css` · `tema.js`).
Em conflito, o código prevalece sobre este doc.

---

## 1. Linguagem & marca

- [ ] A tela **linka a fundação** (`../sistema/tokens.css` + `chassi.css` + `tema.js`) e **não
      redefine nenhum token** inline. Todo `var()` resolve.
- [ ] A **ousadia mora em um lugar só** (a faixa de azulejo / a ilha-herói). O resto é disciplina.
- [ ] **Ilhas que não tematizam**: placar = ilha-palco escura (`--palco-*`); documento/edital/ata =
      ilha-papel clara (`--papel-*`). O papel **continua claro no tema escuro** (candeeiro) — é de propósito.
- [ ] Tipografia do sistema (Sora display / Hanken corpo / IBM Plex Mono dados, `tabular-nums` em números).
- [ ] Geometria do sistema (`--raio*`, `--max`). Nada de raio/sombra avulsos.

## 2. Voz & copy

- [ ] **Rótulo sempre visível** (não placeholder-como-rótulo).
- [ ] **Erro com causa + correção**, na voz da interface (não pede desculpa, não é vago).
- [ ] **Vazio que convida à ação** (não "nada aqui").
- [ ] Verbo ativo e consistente: o botão que diz "Publicar" gera o aviso "Publicado".
- [ ] Nomes pelo que a pessoa controla, não pela implementação.

## 3. IA — fosso anti-genérico + confiança (onde houver IA)

- [ ] **Camada de confiança obrigatória**: rótulo visível "**gerado/sugerido por IA**" + **revisão
      humana** explícita + **fonte citada** + **reportar erro**. (editor, ata, portal, protocolo.)
- [ ] **A IA sugere; o humano decide e assume.** A peça só vale após a aprovação humana (a ata só é
      ata depois de aprovada; a redação final é responsabilidade do servidor).
- [ ] **A fonte é a autoridade, não o modelo**: número de votação = **placar oficial**, não a
      transcrição; quórum = lista de chamada, não o áudio; norma = verificada, não "a IA achou".
- [ ] A IA **mostra o trabalho** quando dá (trecho marcado ↔ áudio/artigo ancorado).
- [ ] Nada de visual "templated de IA" (cream+serif+terracota / preto+verde-ácido / broadsheet). A
      direção é a República Luminosa, derivada do assunto.

## 4. `[GAP]` — honestidade regulatória

- [ ] Nenhum conteúdo regulatório **não-fechado** cravado como autoridade. Número ilustrativo, data do
      TCE, prazo regimental → **nota visível** ("regra em homologação" / `[Regimento]` / `[GAP]`).
- [ ] Prazos que **variam por câmara** (antecedência de convocação, prazo do titular LGPD) ficam
      marcados como "confira no seu cadastro/regimento", não fixados.

## 5. Acessibilidade AA — **medida**, nos dois temas

- [ ] Contraste de **texto ≥ 4.5:1** e **gráfico/borda ≥ 3:1**, medido em **pixel composto**, em
      **claro E escuro**. (Método: §8.)
- [ ] **Cor nunca é o único sinal** (chip/pino/semáforo levam ícone ou texto além da cor).
- [ ] Alvos de toque **≥ 44px**. Foco visível em tudo que é interativo.
- [ ] ARIA correto: `role`/`aria-label` em ícones-botão, `aria-current` na navegação, tabs/grupos
      rotulados, `role="img"`+`aria-label` em gráficos, `aria-live` onde atualiza.
- [ ] Reordenar/arrastar tem **alternativa por teclado** (setas com `aria-disabled` nas pontas).

### 5.1 🔴 Armadilhas de contraste já medidas nesta casa (confira sempre)

| Situação | Falha | Conserto |
|---|---|---|
| **Branco sobre `--telha`** (#D9542B) | **4.0** (texto não-grande falha) | use **`--telha-fundo`** (#B9421F) = **5.44**. Já aconteceu 3×. |
| **Texto âmbar fixo sobre `color-mix(amarelo, --surface)`** | dark-on-dark no **escuro** (surface vira escuro) → ~1.5 | use o **token `--aviso-texto`** (clareia no escuro), nunca hex fixo. |
| **Texto secundário/âmbar sobre `--papel-2`** | `--papel-2` é mais escuro no **escuro** → ~4.0 | escureça o texto (`mix toward --papel-texto`; tag âmbar → `#6E5009`). |
| **`--acento-texto` sobre o `body`** | **4.6** (piso AA, passa raspando) | ok — mas não empilhe sobre fundo mais escuro sem remedir. |
| **Amarelo cheio (`--amarelo`) como traço/borda de alerta** | < 3:1 sobre claro | use **`--amarelo-traco`** (#B07D14) p/ a borda; texto âmbar = `--aviso-texto`. |
| **Pílula/badge âmbar com texto** (sessão, calendário) | branco-sobre-âmbar ≈ 2–4; texto-escuro-sobre-`--amarelo-traco`(médio) ≈ 4.3 | âmbar é cor CLARA → fundo **`--amarelo`** (#E8B23A brilhante, invariante) + **texto escuro** (#2E2102) = ~8 nos dois temas. Nunca branco sobre âmbar. |
| **Texto-marca sobre tinta da própria marca** (`color-mix(marca, surface)`) | a tinta levanta o fundo → ~4.4 no escuro | use `--surface-2` puro de fundo; o acento de marca vai na borda. |
| **Preenchimento de gráfico com cor de marca ESCURA sobre trilho escuro** (barras: jade/cobalto/telha sobre `--surface`) | no **escuro** o fill escuro fica < 3:1 vs o trilho → ~1.5–2.5 | override `[data-tema="escuro"]` clareando o fill (jade→`#43BD93`/`#2C8A66`, cobalto→`--foco`, telha→`#F0794B`). Já aconteceu em transparencia-fiscal + estatísticas. |
| **Branco sobre `--telha` em botão** (`.btn-encerrar`) | era 4.0 (corrigido no chassi 22/06) | **já resolvido**: o chassi usa `--telha-fundo` (5.44). Não reintroduzir `var(--telha)` como fundo de texto branco. |
| **`.merge` (campo mesclado) sobre `--papel` no escuro** (`--merge` #B9421F + `--merge-fundo`) | **3.72** no escuro (`--papel` mais cremoso + tinta laranja) | **mitigado** pelo sublinhado de 2px (`--merge`) = sinal gráfico ≥3, não-cor. Token compartilhado por 4 telas — **passe futuro de token** (escurecer `--merge` no escuro), não re-tonalizar num commit de promoção. Medido na Fase A §5.2. |
| **Pílula/segmento SELECIONADO preenchido com `--marca` + texto branco** (toggle, segmented control) | **2.35** no **escuro** (o jade `--marca` clareia no escuro → branco-sobre-claro falha) | preenchimento **invertido**: fundo `var(--texto)` + texto `var(--surface)` — contrasta nos 2 temas (15.51 claro / 12.62 escuro). Não usar cor de marca como fundo de texto branco em estado selecionado. Medido na Fase B §5.2 (toggle de demo do editor); **reconfirmado em `chamada` (15.51 / 12.62)**. |
| **`.avatar` do chassi reusado com FUNDO trocado** (`background: var(--texto-2)` p/ ausentes) | **2.28** no **escuro** — o chassi fixa `color:#fff` (nasceu sobre marca escura) e `--texto-2` **inverte** de claridade (#4C574F → #9FB0A4), virando branco-sobre-claro | `color: var(--surface)`, que inverte junto = **7.11 claro / 6.86 escuro**. Regra geral: ao trocar o FUNDO de um componente herdado, remeça o par nos 2 temas e prefira tokens que invertem em conjunto (`--texto`/`--surface`) a cor literal. Medido em `chamada`. |
| **`--acento-texto` sobre tint da PRÓPRIA telha dentro da barra `.topo`** (pílula "Ao vivo": texto telha sobre `rgba(217,84,43,.1)`) | **4.10** — é o caso "não empilhe sobre fundo mais escuro sem remedir" acontecendo de fato | fundo `var(--surface)` (o tom mais claro) = **5.12 claro / 5.61 escuro**. ⚠ **`sessao-ao-vivo.html` tem a MESMA pílula com o tint e herda a falha** — corrigir no próximo passe daquela tela. Medido em `chamada`. |
| **Borda de campo pela receita "58% mix" de `--texto-2`** | **2.68** — a receita documentada em `PADROES §3` enunciava "≥3:1" e prescrevia um mix que não entrega | **78% mix** = 3.0+ nos 2 temas sobre `--surface-2`. Superfície diferente muda o resultado com o mesmo mix: **meça na sua superfície, não confie no número da receita**. Medido em `chamada`. |

## 6. Integridade de domínio (não inventar mecânica)

- [ ] A interface **não mente sobre o processo**. Em específico:
  - Tramitação **não se arrasta** (avança por ato: despacho/parecer/votação) → board é **read-model
    de status**, não kanban editável.
  - Pauta **se ordena** (é decisão editorial do servidor) → reordenar é legítimo ali.
  - Escopo é **instituição**, nunca gabinete de vereador.
- [ ] **Uma ação primária por tela** (a barra `.comando` ancora a decisão; secundárias subordinadas).

## 7. Estrutura & reuso

- [ ] A tela **declara seu arquétipo** (cabine · balcão · pública · cockpit · lista · ficha · wizard ·
      config) e herda a estrutura dele (ver `PADROES §2`).
- [ ] **Reusa receitas** (azulejo · ilha-palco/papel · anel de prazo · chips/semáforo · camada de IA ·
      bottom tab bar · player). **Promove ao chassi no 2º uso** (`PADROES §1`).
- [ ] Responsivo até o mobile (sem rolagem horizontal indevida; alvos confortáveis).
- [ ] `prefers-reduced-motion` respeitado (vem do chassi; não reintroduzir animação que ignore).

## 8. Método de validação (como medir)

1. Servir a raiz: `python3 -m http.server 8755`. Abrir a tela linkando `../sistema/`.
2. Conferir os **dois temas** (toggle ou `data-tema` via DevTools). Verificar que todo `var()` resolve.
3. 🔴 **DESLIGAR as transições ANTES de qualquer leitura.** Injete
   `*,*::before,*::after{transition:none!important;animation:none!important}`. O chassi anima
   `color`/`background-color` (120–250ms) e `getComputedStyle` durante a transição devolve a cor
   **interpolada** — falha falsa. Medido: 3 componentes corretos reprovaram (2.07 / 2.07 / 1.78)
   ao lado de 1 falha real, na mesma passada. **O flush de layout não resolve** — ele força
   reflow, não conclui a transição. Quando um lote reprova junto, suspeite do instrumento antes
   de consertar os alvos.
4. **Medir contraste em pixel composto** (compositando o fundo pela cadeia de ancestrais, tratando
   `rgb()` 0–255 **e** `color(srgb …)` 0–1). **Um tema por chamada**, com flush de layout
   (`void document.body.offsetHeight`) após trocar o tema — medir os dois temas numa só passada
   gera leitura defasada para elementos de fundo transparente (artefato conhecido).
5. **Varrer TODO texto visível, não uma lista curada** — e afirmar o volume inspecionado
   (ex.: "180 textos × 2 temas × 6 estados = 0 falhas"). Zero achados e "não rodou" têm a mesma
   saída; só o volume distingue.
6. **Asserção por ESTADO da tela.** Para cada par mutuamente exclusivo (em curso ↔ registrada,
   cheia ↔ vazia, editável ↔ somente-leitura), contar os elementos visíveis dos dois grupos e
   falhar se aparecerem juntos. Uma utilitária `.classe{display:none}` (0,1,0) **perde** para
   regra de elemento do chassi como `.comando-ctx b{display:block}` (0,1,1) — sintoma real em
   `chamada`: a folha anunciava uma chamada que não tinha acontecido. Piso: `.x.x` = (0,2,0).
7. Conferir **mobile** (≈390) e **desktop**. No mobile, verificar também que **nenhum filho
   escapa da caixa do pai** (`right > clientWidth`) — `overflow:hidden` esconde a rolagem e o
   controle clipado fica inalcançável, sem produzir scroll horizontal que denuncie. E que a
   grade da linha tem **placement explícito**: com auto-flow, células sobrando migram para fora
   da faixa. Telas-herói passam por **crítica adversarial** (workflow) antes do commit.
8. Só então: commit (uma tela por commit, na branch de design).

---

## Procedência

Esta lista nasceu da Fase E (telas reais): editor+copiloto, board de tramitação, minhas
pendências, pauta+convocação, ata-IA e app do vereador. As armadilhas de §5.1 são **achados medidos**
dessas telas — não teoria.
