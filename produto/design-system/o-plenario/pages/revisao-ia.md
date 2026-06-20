# Page Override — Revisão Humana de IA (Camada de Confiança)

> **LOGIC:** sobrescreve o `../MASTER.md` para esta superfície. O que não está aqui herda o MASTER.
>
> **Superfície:** `produto/13` feature **8.5** (workflow de revisão humana obrigatório antes de
> publicar) — a **superfície compartilhada** que governa os três outputs de IA: **ata por IA (4.13
> `[HERO]`)**, **resumo cidadão (5.4)** e **texto de projeto / copiloto (3.11)**. Materializa a
> **Camada de Confiança inteira**: citação de fontes (**8.1**), incerteza como metadata (**8.2**,
> §22.3.5), log auditável (**8.3**, Invariante 10), reportar erro (**8.4**). Tags `[DIF] [IA]`.
>
> **É a tela mais estratégica do produto:** encontro da Aposta 1 (IA) com a Aposta 2 (UX) — onde o
> **servidor decide a POC**. O contrato §22.3 é a lei desta tela: **a IA propõe, o humano dispõe;
> a saída é sempre sugestão editável; nada publica sem aprovação humana.**
>
> **Disciplina de consistência:** **uma** superfície reutilizável para os 3 outputs, não três telas.
> O mockup mostra a instância **ata** (a mais rica); resumo e texto de projeto reusam o mesmo padrão.
>
> **Stack deferida (§22.4.4):** tokens/layout de design; protótipo agnóstico — não decide RN/web.

---

## 1. Intenção de design

O servidor chega aqui da Caixa de Pendências ("Revisar ata gerada por IA — 15ª Sessão"). A tela tem
de produzir **três sensações** simultâneas — e elas são a venda da POC:

1. **"Eu sou o autor, a IA é o rascunho."** Controle total: nada vai ao portal sem meu aval; o texto
   é editável; minha edição fica registrada como minha.
2. **"Revisar é mais rápido que escrever do zero."** O modo produtividade (§16.8): a IA já fez 90%;
   a tela me leva direto ao que precisa de olho humano.
3. **"Eu consigo confiar — e provar."** Vejo a fonte de cada afirmação, vejo onde a IA tem dúvida,
   posso reportar erro, e tudo fica auditado.

---

## 2. Tipo de produto e estilo
- **Editor pane:** *Notes & Writing App* → **Minimalism + clean white**, "editor syntax colors" para
  as anotações inline. Nosso **Accessible & Ethical** do MASTER se mantém (secundário confirmado).
- **Densidade:** documento legível (line-length controlada, serif opcional EB Garamond pode entrar no
  corpo da ata renderizada — é texto jurídico), com **chrome mínimo** ao redor.

---

## 3. Layout — duas colunas (edição + evidência)

**Header do documento (sticky):** `← Pendências` · selo **"Ata · gerada por IA"** (ícone *sparkle*
em azul, **nunca** gradiente roxo) · título · **status pill** do workflow · resumo de confiança
("3 trechos a revisar") · ações: `Reportar erro` (8.4) · `Salvar rascunho` · **`Aprovar e publicar`**
(primária, **gated**).

| Coluna | Conteúdo | Largura |
|---|---|---|
| **Esquerda — Documento editável** | A ata (rascunho da IA), editável inline. Carrega as **anotações**: realces de incerteza (8.2) + chips de fonte (8.1). Linha de proveniência no topo. | ~58% |
| **Direita — Evidência / fonte da verdade** | **Transcrição (4.12)** com fala pré-atribuída + timestamp + scrubber de áudio. Clicar uma citação à esquerda **rola e realça** o segmento-fonte aqui. Citação de fontes feita espaço. | ~42% |

Mobile: vira **abas** (Documento ⇄ Fonte) — não há espaço para lado-a-lado; a citação troca de aba e
realça. Editor em foco primeiro (content-priority).

---

## 4. Componentes-chave

### 4.1 Realce de incerteza (8.2) — o componente que constrói confiança
Trecho de baixa confiança = **sublinhado pontilhado + ícone de bandeira + leve tinta âmbar**
(`--vencendo-bg`). **Nunca cor sozinha** (`color-not-only`): o ícone + o rótulo no popover carregam o
significado. Clicar abre **popover** com: (a) sinal de confiança, (b) o **segmento-fonte** na
transcrição, (c) ações `Confirmar` · `Editar` · `Reportar erro`. Confirmar marca como revisado-por-humano.

### 4.2 Chip de fonte (8.1)
Marcador inline em afirmação factual (contagem de votos, atribuição de fala, nº de proposição) →
clicar realça a evidência à direita. **Toda** afirmação factual tem fonte alcançável — é o coração de 8.1.

### 4.3 Status do workflow (8.5) — o gate
Pill de estado: `Rascunho de IA — requer revisão` → (após edição) `Em revisão` → `Pronta para publicar`.
**`Aprovar e publicar` dispara diálogo de confirmação** (publicar = outward-facing): *"Esta ata será
publicada no portal e enviada para assinatura. Você revisou os 3 trechos sinalizados?"* — severidade
High no checklist do plugin. Pós-aprovação: ata → fluxo de **assinatura** (Pendências); resumo → portal;
texto de projeto → rascunho de trabalho.

### 4.4 Proveniência + auditoria (8.3)
Linha no topo do doc: *"Gerado por IA a partir da transcrição · modelo/data · revise antes de publicar."*
Rodapé: *"Toda edição e a aprovação ficam no log auditável (quem/quando)"* — Invariante 10.

### 4.5 Estados
- **Loading:** skeleton do documento (>300ms).
- **Vazio (nada a revisar):** "Nenhum trecho de baixa confiança ✓ — revise e publique" (positivo).
- **Salvando/aprovando:** botão em loading → toast de sucesso (`submit-feedback`, High).
- **Erro de publicação:** mensagem com causa + retry.

---

## 5. Acessibilidade (reforços — herda o MASTER)
- Incerteza com ícone + rótulo além da cor; realce com contraste ≥ 4.5:1.
- Popovers e citações navegáveis por teclado; foco gerenciado ao abrir/fechar; `Esc` fecha.
- Conteúdo longo da fonte: **truncar com expandir** (line-clamp), não overflow.
- `aria-live` discreto ao confirmar/editar um trecho.

---

## 6. Anti-patterns específicos (além do MASTER)
- ❌ **Gradiente roxo/rosa de "IA genérica"** — o afeto de IA é selo rotulado + ícone sparkle em azul.
- ❌ Incerteza por **cor sozinha** — sempre ícone + rótulo.
- ❌ **Publicar sem confirmação** — ação outward-facing exige diálogo.
- ❌ Afirmação factual **sem fonte alcançável** — viola 8.1, descaracteriza a Camada de Confiança.
- ❌ Esconder que é gerado por IA — proveniência é **explícita**, é o que vende confiança (não o contrário).

---

## 7. Mockup
`../mockups/revisao-ia.html` — protótipo HTML/CSS autocontido. Conteúdo de ata é **placeholder
ilustrativo**; não inventa fala/voto reais nem conteúdo regulatório (`[GAP]`).
