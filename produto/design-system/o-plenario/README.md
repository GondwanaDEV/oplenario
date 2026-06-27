# O Plenário — Design System ("República Luminosa")

> **Ponto de entrada da pasta de design.** Se você chegou aqui para **implementar o front**
> (React/Next self-host, §22.9 do documento-mestre), comece por este arquivo. Ele mapeia o que é
> **fonte de verdade**, o que é **referência**, o que é **alvo de tradução** e o que **ignorar**.

A direção visual foi escolhida pelo Daouda Traore (21/06/2026) e está cravada em
[`linguagem-visual.html`](./linguagem-visual.html). Em qualquer conflito, **o que está no código
de `sistema/` e nos style-tiles canônicos prevalece** sobre prosa.

---

## 1. Mapa da pasta

| Caminho | O que é | Papel na implementação |
|---|---|---|
| **`sistema/`** | **A SSOT executável.** `tokens.css` (99 custom properties: paleta-marca fixa + semânticos por `[data-tema]`), `chassi.css` (reset · base · componentes), `tema.js` (alternância claro/escuro page-agnóstica). | **Gerar daqui.** Tokens → TS/Tailwind; chassi → camada de componentes; tema.js → theming. **Não copiar à mão.** |
| `linguagem-visual.html` | Style-tile canônico (paleta, fontes Sora · Hanken Grotesk · IBM Plex Mono, assinatura = faixa de azulejo). | Referência visual de verdade. |
| `componentes.html` | Biblioteca viva (styleguide) — consome `sistema/`. | Catálogo de componentes a portar. |
| **`telas/`** | **48 telas** HTML, todas linkando `../sistema/` (verificado: 48/48). 3 públicos, 10 arquétipos, as 3 apostas de produto. | **Alvos de tradução** para componentes React. Não são código canônico — são a especificação visual de cada superfície. |
| `opcoes/` | Direções exploradas e **rejeitadas** (`A-modernismo-civico`, `C-pedra-e-bronze`, `D-o-registro`). | **Ignorar na implementação.** Registro histórico da exploração, não é alvo. |

### Os 4 guias (ler nesta ordem)

1. [`LINGUAGEM-VISUAL.md`](./LINGUAGEM-VISUAL.md) — **a língua**: tokens, paleta, tipografia, dois temas.
2. [`PADROES-DE-COMPOSICAO.md`](./PADROES-DE-COMPOSICAO.md) — **como compor**: arquétipos de página + receitas ainda não promovidas ao chassi + gatilho de promoção (2º uso → chassi).
3. [`GUIDELINES-CHECKLIST.md`](./GUIDELINES-CHECKLIST.md) — **o gate**: rodar antes de fechar cada componente/tela (esp. §5.1 — armadilhas de contraste medidas).
4. [`INVENTARIO-TELAS.md`](./INVENTARIO-TELAS.md) — **o mapa de cobertura**: as 113 features / 12 módulos → ~30 telas distintas, o que está feito e o backlog de profundidade.

---

## 2. Regra de ouro do handoff para o front

A migração é **derivar, não recopiar**. O `sistema/` é a única fonte:

- **`tokens.css` → tokens de código.** As 99 custom properties são a superfície a gerar (TS/Tailwind/Malli→TS). Os semânticos trocam por `[data-tema]` — o tema vive em tokens, nunca em valores hard-coded no componente.
- **`chassi.css` → camada de componentes.** Reset, base e os componentes já provados (`.btn`, `.card`, chips, campos, faixa de tramitação, selo encadeado, ilha-papel, camada de IA…). Portar 1:1 antes de inventar.
- **`tema.js` → theming.** Respeita `prefers-color-scheme` + `localStorage('oplenario-tema')`; o CSS é dirigido por `[data-tema]`. Replicar esse contrato no front.
- **`GUIDELINES-CHECKLIST.md` é o gate por componente** — em especial a disciplina de contraste AA medida em pixel composto nos **dois temas**.
- **Cada tela em `telas/` é a spec visual da superfície correspondente** — abrir lado a lado ao construir o componente React equivalente.

> **Servir localmente:** `python3 -m http.server 8755` na raiz do repo; abrir
> `produto/design-system/o-plenario/componentes.html` para a galeria e qualquer arquivo de `telas/`.

---

## 3. Fronteira do que é design

Só esta pasta (`produto/design-system/`) é design. O resto de `produto/` (`01`–`19`: JTBD,
decomposição das features, completude, NFR) é **input** do processo, não artefato de design — não
mexer aqui ao implementar o front.
