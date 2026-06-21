# Page Override — Painel de Tramitação (kanban da máquina de estados)

> **LOGIC:** sobrescreve o `../MASTER.md` para esta superfície. O que não está aqui herda o MASTER.
>
> **Superfície:** `produto/13` feature **11.3** — painel de tramitação ("onde está cada proposição").
> `[DIF] [RM]` `§22.4(eixo C)`. **Read-model sobre a máquina de estados declarativa** de tramitação.
> Público interno: servidor (operação) + Mesa (visão). Tema claro (herda o MASTER).
>
> **Disciplina-chave (§22.4 eixo C + Invariante 4):** as **colunas são os estados da máquina
> declarativa** — **configuráveis pelo regimento de cada câmara, não hard-coded**. O board se adapta
> ao rito de cada cliente. Mover um card **não é drag livre**: dispara uma **transição governada pelo
> motor** (guards/regras do eixo C); movimento inválido é recusado com o motivo. O kanban é a *cara*
> do motor de tramitação — mesmo motor declarativo de compliance/auth/plenário (Disciplina 5).
>
> **Stack deferida (§22.4.4):** tokens/layout; protótipo agnóstico.

---

## 1. Intenção de design

O servidor precisa responder, num relance: *"onde está cada proposição, o que está parado, o que vai
para a próxima pauta."* É a visão de **fluxo** que a Caixa de Pendências (11.2, individual) e o Painel
de Prazos (11.1, temporal) não dão. O kanban torna **o estado da máquina visível e acionável** sem
abrir cada proposição.

---

## 2. Tipo de produto e estilo
- **Padrão:** *board / pipeline* sobre o estilo Accessible & Ethical do MASTER. Densidade média.
- **Resumo:** **funil (grau AA)** no topo para distribuição por etapa — **com contagem textual** e
  **fallback de lista** (regra de a11y do plugin). Funil só ilustra; o board é o conteúdo.

---

## 3. Layout

**Header:** título · **filtros** (tipo · comissão · autor · período) · **busca com autocomplete**
(debounced) · **alternância de visão Kanban ⇄ Lista** · **exportar** (11.7).

**Faixa-resumo (funil):** contagem por etapa, horizontal, com números — leitura rápida de gargalo
(maior acúmulo destacado).

**Board (kanban):** colunas = **estados do motor** (ex.: Protocolada → Em comissão → Pronta p/ pauta →
Em votação → Aprovada → Sanção/Promulgação — **vindos da config do regimento**, não fixos). Cada coluna:
nome + **contagem**. Cards = proposição.

---

## 4. Componentes-chave

### 4.1 Card de proposição (read-model)
nº + título (truncado com expandir) + autor + **tempo na etapa** ("há 5 dias") + **prazo pill** quando
houver (mesma semântica das outras telas: âmbar=vencendo, vermelho=vencido). Card **parado além do
esperado** é sinalizado — reusa o motor de prazo (3.8 `prazo_dominio_ativo`).

### 4.2 Movimento = transição governada (não drag livre)
Arrastar/avançar dispara a **transição da máquina de estados**. O motor valida (guards do eixo C):
movimento permitido → confirma com feedback; **inválido → recusado com o motivo** (ex.: "exige parecer
da comissão antes da pauta"). A regra mora no motor, não na UI. Multi-seleção + ação em lote para
mover vários (bulk actions do plugin).

### 4.3 Estados
- **Loading:** skeleton das colunas (>300ms).
- **Coluna vazia:** "Nenhuma proposição nesta etapa" (não buraco branco).
- **Busca sem resultado:** "Nada encontrado — tente outro termo/filtro" + sugestão (não "0 resultados").
- **Mover:** loading → sucesso/erro com motivo (`submit-feedback`, High).

---

## 5. Acessibilidade (herda o MASTER)
- Funil com **contagem textual** + **visão de lista** alternável (nunca só o desenho).
- Board navegável por teclado; mover card acionável sem mouse (menu "mover para…" como alternativa ao drag).
- Status/prazo com ícone + rótulo, não cor sozinha; tempo na etapa tabular.
- Coluna com scroll próprio não sequestra o scroll da página.

---

## 6. Anti-patterns específicos (além do MASTER)
- ❌ **Drag livre** que ignora as regras do regimento — toda transição passa pelo motor (eixo C).
- ❌ **Estados hard-coded** — colunas vêm da config declarativa; o board de cada câmara difere.
- ❌ Funil **sem contagem textual** ou sem fallback de lista.
- ❌ Mover sem feedback / recusar sem motivo.
- ❌ Duplicar regra de tramitação na UI — a verdade é o motor, a tela só projeta.

---

## 7. Mockup
`../mockups/painel-tramitacao.html` — kanban + faixa-funil. Proposições **ilustrativas** (`[GAP]`);
as etapas mostradas são exemplo, não rito real cravado.
