# Page Override — Portal Cidadão (face pública)

> **LOGIC:** sobrescreve o `../MASTER.md` para esta superfície. O que não está aqui herda o MASTER.
>
> **Superfície:** `produto/13` módulo **16.5** — portal público. Mockup = a **página de detalhe de
> proposição** (a página-dinheiro do cidadão). Features: **5.1** white-label · **5.2** publicação
> automática · **5.3** eMAG/WCAG AA + responsivo · **5.4** resumo em linguagem simples por IA ·
> **5.5** acompanhar + notificações · **5.6** timeline pública da tramitação `[RM]` · **5.7** artefato
> de publicação oficial · **5.8** legislação consolidada. Tags `[DIF]` (5.4/5.6) + `[PAR]`.
>
> **É onde o engajamento que o presidente celebra (11.4) de fato acontece.** O cidadão chega aqui por
> link compartilhado ou gov.br. O portal típico de câmara é burocrático e ilegível — **a diferenciação
> é ser radicalmente mais claro**.
>
> **Stack deferida (§22.4.4):** tokens/layout; protótipo agnóstico.

---

## 1. Intenção de design

O cidadão não conhece jargão legislativo. A página tem de responder, em segundos: *"o que essa
proposta faz, em quem mexe, e em que pé está?"* — antes de qualquer texto formal. Ordem deliberada:
**resumo em linguagem simples primeiro (5.4), texto oficial depois.** A confiança vem de **dizer o que
é IA e o que é oficial**, lado a lado, sem esconder nenhum dos dois.

---

## 2. Tipo de produto e estilo
- **Product type:** *Government/Public Service* → **Accessible & Ethical + Minimalism**, "professional
  blue + high contrast" (= MASTER confirmado, é literalmente a categoria de origem do design system).
- **Tom:** mais **acolhedor** que as telas internas (é para o cidadão comum), sem perder sobriedade
  institucional. Coluna de leitura confortável (linha 60–75 caracteres), tipografia generosa.

---

## 3. Layout — página pública (coluna de leitura + sidebar)

**Header white-label (5.1):** brasão/identidade configurável da câmara + nav pública (Proposições ·
Sessões · Vereadores · Transparência) + **barra de acessibilidade eMAG** (A− A A+ · alto contraste ·
atalho VLibras). **Não** é estrutura configurável — só identidade visual (guardrail 5.1).

**Corpo (centro, max-width de leitura):**
1. Breadcrumb + cabeçalho da proposição: tipo + nº + título · **status pill** · botão **Acompanhar (5.5)**.
2. **Card "Em linguagem simples" (5.4) — herói**, no topo: selo de IA (azul, sem gradiente roxo) +
   **"resumo revisado por servidor" (8.5)** + aviso *"resumo informativo; o texto oficial prevalece"*.
3. **Texto oficial (5.2)** em serif (EB Garamond — documento jurídico), com link ao PDF assinado (5.7).
4. **Timeline pública da tramitação (5.6 `[RM]`)** — stepper vertical com datas; etapa atual destacada.
5. (quando houver) **resultado de votação** nominal pública.

**Sidebar:** autor (vereador) · datas · documentos (PDF assinado, 5.7) · "acompanhar por e-mail/gov.br"
· link à legislação consolidada (5.8) se virou lei.

---

## 4. Componentes-chave e estados
- **Card de resumo IA (5.4):** visualmente distinto (não se confunde com texto oficial), selo + revisão
  humana + disclaimer. **Citação/origem** alcançável (link ao texto oficial logo abaixo).
- **Status pill:** mesma semântica das telas internas (consistência cross-surface).
- **Timeline (5.6):** etapas concluídas (✓) / atual (destaque) / futuras (apagadas); cada uma com data
  tabular. Read-model sobre eventos.
- **Acompanhar (5.5):** e-mail na V1 (push com o app); confirma inscrição com feedback.
- **Vazio/loading:** skeleton; "sem tramitação registrada ainda"; sem dado regulatório inventado.

---

## 5. Acessibilidade (CRÍTICA aqui — eMAG/WCAG AA, herda + reforça o MASTER)
- **Contraste ≥ 4.5:1** sempre (texto escuro sobre claro; nada de cinza-sobre-cinza).
- **Barra eMAG** visível: redimensionar texto, alto contraste, VLibras.
- **Alt-text** em toda imagem com significado; **hierarquia de headings** sequencial (leitura por leitor de tela).
- **Performance** (cidadão em rede lenta/celular barato): imagens WebP + `srcset`, bundle enxuto, lazy-load — o plugin marca como High.
- Resumo IA com `lang` correto; texto oficial com estrutura semântica (artigos/parágrafos).

---

## 6. Anti-patterns específicos (além do MASTER)
- ❌ **Jargão sem tradução** — se só há texto formal, a página falhou no propósito (5.4 vem primeiro).
- ❌ **IA disfarçada de oficial** — selo + disclaimer obrigatórios; nem o oposto (esconder que tem resumo).
- ❌ Portal "estrutura configurável" por câmara — só identidade visual (guardrail 5.1).
- ❌ Baixo contraste / texto cinza-claro — viola eMAG, é a falha nº1 dos portais atuais.
- ❌ Página pesada (imagens enormes, JS desnecessário) — exclui o cidadão de rede ruim.

---

## 7. Mockup
`../mockups/portal-cidadao.html` — página de detalhe de proposição. Texto/resumo **ilustrativos**
(`[GAP]`), sem inventar conteúdo legal real.
