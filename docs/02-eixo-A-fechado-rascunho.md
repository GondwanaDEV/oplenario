# §22.7 — Eixo A (vocabulário da DSL) · RASCUNHO DE ORIGEM (consolidado em v1.9)

> ✅ **Consolidado no documento-mestre em §22.7 (v1.9, 19/06/2026).** Mantido como registro de
> origem. As decisões **estruturais** viraram texto canônico; as **listas granulares** (tipos,
> operadores, builtins, schemas de envelope, mecânica fina do registry, opções de forma
> descartadas) seguem "a transcrever da sessão de origem" em §22.7.4 — preenchidas quando a
> sessão for recuperada ou validadas/descobertas pelo stress-test do Eixo C.

> ## ⚠️ Status e proveniência — leia antes de usar
>
> Este arquivo **não é texto canônico**. Ele reconstrói as decisões fechadas no Eixo A de
> §22.7 a partir do **registro resumido da última sessão** — a sessão que abriu o motor de
> compliance e fechou o vocabulário da DSL, **posterior ao documento-mestre v1.8** e ainda
> **não escrita nele**.
>
> Ele **lista as decisões e sua direção**, mas **não substitui o detalhe granular** da
> conversa de origem (listas completas de operadores, builtins, tipos e regras de
> type-checking precisam ser transcritas/conferidas contra aquela sessão).
>
> **Primeiro passo ao retomar:** confirmar ponto a ponto, consolidar como **§22.7 (Eixo A)**
> no documento-mestre no estilo das demais subseções 22.x, e **bumpar para v1.9**.
> Só depois abrir o Eixo C.

---

## 0. Contexto: a DSL não nasce no Eixo A — ela é unificada nele

A DSL declarativa **já existia** em três contextos antes de §22.7, e a disciplina de
"motor declarativo compartilhado" (§22.4.3 disciplina 5; §22.5.3 disciplina 3) sempre exigiu
que fosse **uma só**. O que o Eixo A faz é **formalizar o vocabulário comum** sobre o qual os
três contextos já existentes + o novo (compliance) se apoiam.

Contextos pré-existentes (documentados e canônicos):

- **Tramitação (§22.4 eixo C):** "DSL pequena de expressões booleanas — sem loops, sem
  variáveis mutáveis, sem efeitos colaterais; ações são identificadores que o motor mapeia
  para handlers". Expõe agregadores sobre subprocessos como funções (`pareceres.todos_concluidos`,
  `pareceres.algum_rejeitou`, `pareceres.contagem_terminal >= N`, `pareceres.prazo_vencido_em_alguma`).
- **Autorização (§22.5 eixo B):** decisões dinâmicas avaliadas como expressões sobre
  **funções de relação** expostas pelos bounded contexts donos dos recursos. Inventário de
  ~10 funções de relação + 2 consultas ao motor de tramitação.
- **Regras de plenário (§22.6):** quórum, regras de votação por matéria, tempos regimentais
  de tribuna — todos decididos como **configuração no motor**, expondo agregadores como
  `presentes_plenario(sessao, instante)`, `presentes_remoto(sessao, instante)`.

O Eixo A é a base que torna esses quatro usos uma coisa só, não quatro DSLs parecidas.

---

## 1. Decisões fechadas no Eixo A (a confirmar)

### 1.1 Forma da DSL — "A2": núcleo de expressão + envelopes YAML por contexto

**Decisão (opção A2 entre as opções avaliadas):** a DSL tem um **núcleo de expressão** comum
(a gramática de expressões booleanas/valor, sem efeitos colaterais, herdada de §22.4 eixo C)
embrulhado por **wrappers/envelopes em YAML específicos por contexto**. O núcleo é o mesmo
para todos; o envelope adapta forma e campos ao contexto de uso.

> A confirmar: o conjunto exato de opções consideradas (A1/A2/A3…) e a razão registrada para
> descartar as demais — recuperar da sessão de origem para o "descartadas: …" da consolidação.

### 1.2 Type-checking estático no momento de salvar a regra

**Decisão:** a regra é **type-checked estaticamente no momento em que é salva** (rule save
time), não apenas no momento de avaliação. Uma regra mal-formada/mal-tipada **não chega a ser
persistida como ativa**.

**Justificativa (princípio comercial que governa §22.7):** uma regra de compliance falhando
em runtime e fazendo um cliente **perder janela de envio ao TCE** é incidente inaceitável.
Empurrar a detecção para o save time é a disciplina que tira essa classe de falha do caminho
crítico operacional. Bate com o Invariante 4 (regra é dado, mas dado **validado** antes de virar configuração ativa).

### 1.3 Registry central de funções de relação, com ownership por bounded context

**Decisão:** existe um **registry central de funções de relação**, mas a **propriedade
(ownership) de cada função pertence ao bounded context dono do recurso**. O registry é o
ponto único onde a DSL resolve o que cada função significa e qual sua assinatura; quem
**implementa e mantém** cada função é o contexto dono (consistente com §22.5 eixo B, onde
"funções de relação são expostas pelos bounded contexts donos").

Mecânica do registry (a confirmar em detalhe): registro de assinatura
(nome, parâmetros tipados, tipo de retorno), associação a contexto dono, e disponibilização
para o type-checker estático do item 1.2 validar chamadas na hora de salvar a regra.

> A confirmar: regras de visibilidade entre contextos (uma regra de compliance pode chamar
> função de relação de Cadastros? de Sessões? há restrição?), e como o registry lida com
> versionamento de assinatura.

### 1.4 Sistema de tipos — primitivos e compostos

**Decisão:** o Eixo A fechou um **sistema de tipos com primitivos e compostos**. Os tipos são
o que o type-checker do item 1.2 usa para validar expressões e chamadas de função.

> A confirmar (transcrever da sessão de origem): a lista exata de tipos primitivos (provável
> núcleo: booleano, numérico, data/instante, texto, enum) e de tipos compostos (provável:
> coleções/listas, talvez registros), e as regras de coerção/compatibilidade entre eles.

### 1.5 Conjunto de operadores núcleo

**Decisão:** o Eixo A fechou o **conjunto de operadores núcleo** da DSL (operadores lógicos,
de comparação, e o que mais tiver sido definido), coerente com a natureza "sem loops, sem
variáveis mutáveis, sem efeitos colaterais" herdada de §22.4 eixo C.

> A confirmar (transcrever da sessão de origem): a lista exata de operadores e sua semântica/precedência.

### 1.6 Inventário de funções builtin

**Decisão:** o Eixo A fechou um **inventário de funções builtin** — funções de biblioteca da
própria DSL (distintas das funções de relação dos bounded contexts), provavelmente cobrindo
manipulação de datas/prazos, agregação sobre coleções, e utilitários necessários para
expressar regras de compliance e os agregadores já citados (`*.todos_concluidos`, `contagem_terminal >= N`, etc.).

> A confirmar (transcrever da sessão de origem): a lista exata de builtins e suas assinaturas.

### 1.7 Schemas de envelope por contexto: compliance, tramitação, autorização

**Decisão:** cada contexto tem um **schema de envelope** próprio em torno do núcleo de
expressão (item 1.1). Foram fechados os envelopes de **compliance, tramitação e autorização**.

Mapeamento com o já documentado:

- **Tramitação** → o envelope formaliza o que §22.4 eixo C já descreve (template, estado,
  transição, guard, ação).
- **Autorização** → o envelope formaliza o que §22.5 eixo B já descreve (política declarativa
  por ação, avaliada como expressão sobre funções de relação; cláusulas como
  `requer_step_up_recente_em: <categoria>, janela_max_min: N`).
- **Compliance** → o envelope **novo**, alvo de §22.7. É o que o Eixo C vai estressar com
  templates reais do TCE-CE.

> A confirmar: o schema concreto de cada envelope (campos obrigatórios, estrutura YAML), em
> especial o de **compliance**, que é o que o Eixo C usa.
>
> Observação: §22.6 (regras de plenário) também consome o motor. Conferir se o plenário usa o
> envelope de tramitação, o de compliance, ou se ganhou tratamento próprio na sessão de origem.

---

## 2. Disciplinas arquiteturais derivadas (a confirmar)

A sessão fechou também um conjunto de **disciplinas derivadas** do Eixo A. A partir do
registro, as direções são:

- **Uma DSL, um núcleo, múltiplos envelopes.** Não há quatro DSLs — há um núcleo de expressão
  com envelopes por contexto. Qualquer evolução do núcleo é feita uma vez e propaga.
- **Validação no save time é disciplina, não otimização.** Regra mal-tipada não persiste como
  ativa; o type-checker é parte do contrato de salvar regra.
- **Registry central, ownership distribuído.** Resolução central de assinaturas; implementação
  e manutenção no contexto dono. Promover/alterar função de relação é decisão do contexto dono.
- **Ferramental de simulação/debug compartilhado** entre os usos da DSL (consistente com a
  promessa de §22.4.3 disciplina 5 de "ferramental de simulação/debug compartilhado").

> A confirmar: a lista exata e a redação canônica de cada disciplina derivada, para a seção
> "disciplinas arquiteturais derivadas" da consolidação.

---

## 3. O que o Eixo A deixou em aberto para os eixos seguintes

- **Eixo C (próximo):** stress-test do envelope de **compliance** com templates reais do
  TCE-CE — valida se o vocabulário fechado aqui realmente expressa requisitos reais.
- **Eixo B (depois de C):** schema das tabelas de template/regra que persistem essas
  expressões + envelopes.
- **Versionamento de regras de compliance:** mencionado como tema de §22.7 desde o v1.8
  ("DSL, tabelas, formato de templates, versionamento"). Confirmar se ficou no Eixo A ou se
  vai para um dos eixos seguintes (provável: junto do schema, no Eixo B, reaproveitando a
  disciplina de "cópia integral" de templates de §22.4 eixo C).
