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
3. **Medir contraste em pixel composto** (compositando o fundo pela cadeia de ancestrais, tratando
   `rgb()` 0–255 **e** `color(srgb …)` 0–1). **Um tema por chamada**, com flush de layout
   (`void document.body.offsetHeight`) após trocar o tema — medir os dois temas numa só passada
   gera leitura defasada para elementos de fundo transparente (artefato conhecido).
4. Conferir **mobile** (≈390) e **desktop**; telas-herói passam por **crítica adversarial** (workflow)
   antes do commit.
5. Só então: commit (uma tela por commit, na branch de design).

---

## Procedência

Esta lista nasceu da Fase E (telas reais): editor+copiloto, board de tramitação, minhas
pendências, pauta+convocação, ata-IA e app do vereador. As armadilhas de §5.1 são **achados medidos**
dessas telas — não teoria.
