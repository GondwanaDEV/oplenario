# ADR-0004 — A guarda de transição só lê verdade apurada: o vocabulário do `amb` é declarado pelo módulo, por coluna

- **Status:** ✅ **Aceito** — decisão do Daouda Traore em 2026-09-11, com o contra-argumento da Fatia 4
  explicitamente na mesa (ver "O precedente que esta ADR reverte").
- **Decisor:** Daouda Traore (CTO)
- **Reverte:** a posição da **Fatia 4** do eixo C (§22.4), registrada em docstring — não em ADR — de
  `legislativo/db/tramitacao.clj` e `legislativo/wire/in/proposicao.clj`.
- **Enforçada por máquina:** `motor/api/validar-guarda` com vocabulário, chamada em
  `legislativo/db/tramitacao.clj/criar-transicao!` (o **único** INSERT em `legislativo.template_transicao`)
  + a remoção de `alegado` dos dois `amb` de runtime. Teste: ver seção "Enforcement".

## Contexto

A DSL de tramitação avalia a **guarda** de uma transição contra um `amb` com canais de proveniência
diferente:

| Canal | Proveniência | Confiança |
|---|---|---|
| `proposicao` / `parecer` | a linha, lida pelo servidor na própria tx sob `FOR UPDATE` | **apurada** |
| fato por nome (`aprovada_em_votacao(…)`) | resolvido pelo `RegistroFatos` contra a tx do tenant | **apurada** |
| `alegado` | o corpo do POST, renomeado na borda por `adapters/in/proposicao.clj/contexto->alegado` | **alegação do operador** |

Com o terceiro canal legível na guarda, um rito perfeitamente razoável —
`alegado.com_parecer == verdadeiro` na saída de `em_comissoes` — deixa **quem conduz o ato afirmar a
própria precondição**. É a forma exata do achado **T3-A** (autógrafo nascendo em matéria que a Câmara
nunca aprovou), um nível acima: lá o operador escolhia o *estado*, aqui ele fabrica a *condição*.

A Decisão B (11/09/2026) já havia fechado a variante pior — gatilho com várias portas, o operador
escolhendo a destrancada pelo corpo. O que sobrava era o gatilho de **porta única**: quem já passou pelo
`autorizacao` afirma a precondição e passa.

### O precedente que esta ADR reverte

A Fatia 4 do mesmo eixo considerou proibir e **decidiu não proibir**, em duas docstrings:

> *"Isto NÃO proíbe ler o cliente — há uso legítimo (escolher destino por `alegado.comissao`, carimbar
> quem pediu). **Proibir seria decidir pelo regimento**, que é justamente o que o Inv.4 veda."*
> — `legislativo/db/tramitacao.clj`

> *"quem limita o USO é quem escreve o rito, e é assim que tem de ser (Inv.4: o código não decide o
> regimento)" … "O QUE A FATIA 4 FEZ COM ISSO, já que proibir não era opção: tornou a confiança VISÍVEL
> na expressão."*
> — `legislativo/wire/in/proposicao.clj`

O argumento é real e foi pesado. **Ele cai no único uso legítimo que cita.** "Despachar à CCJ" e
"despachar à Fazenda" não são um ato parametrizado pelo corpo: são **atos distintos no regimento**, com
gatilhos distintos. Modelá-los como gatilho-por-destino é *mais* fiel ao texto do que ler o corpo do POST
— então proibir aqui não decide pelo regimento, decide pela **proveniência do dado**, que é matéria de
engenharia. O segundo uso citado (carimbar quem pediu) nunca precisou da guarda: a auditoria já grava o
corpo inteiro.

Tornar a confiança *visível* (o que a Fatia 4 entregou, renomeando `contexto` → `alegado`) resolve o
problema de quem **lê** o rito. Não resolve o de quem **escreve** um rito inseguro sem perceber — e é o
segundo que chega ao cliente como incidente.

## Decisão

**Uma guarda de transição só pode referenciar verdade apurada.** Fail-closed **no cadastro do rito**,
não em runtime no meio do ato.

O mecanismo **não é uma palavra proibida**. O motor é biblioteca compartilhada pelos quatro usos da DSL
(tramitação §22.4, autorização §22.5, plenário §22.6, compliance §22.7) e a §22.10 diz *"kernel/motor
nunca importam um módulo"*: gravar `"alegado"` — vocabulário do legislativo — dentro de
`src/oplenario/motor/` seria a violação maior.

Em vez disso, **allowlist de vocabulário declarada pelo módulo, por coluna**:

1. O **motor** ganha o mecanismo genérico: percorrer uma expressão e devolver quais
   **identificadores-raiz** ela referencia. O walk cobre os sete `:t` do AST; o nome de um **fato**
   (`:chamada`) não conta como identificador do `amb` (resolve pelo `RegistroFatos`), mas os seus
   **argumentos** contam; o nome de um **campo** (`:campo`) não conta, só o objeto.
2. O **legislativo** declara, em `criar-transicao!`, qual vocabulário é legal em cada coluna — porque é
   ele, não o motor, que sabe o que cada `amb` carrega:

   | Coluna | `amb` real | Vocabulário declarado |
   |---|---|---|
   | `guarda` | `{"proposicao" …}` / `{"parecer" …}` | **o sujeito daquele template** — nunca `alegado` |
   | `autorizacao` | `{"ator" … "recurso" …}` (via `politica-dsl`) | `ator`, `recurso` (fixo) |

   **O vocabulário da guarda não é a união dos sujeitos.** `template_transicao` é subject-agnóstica,
   mas cada *linha* de `template_tramitacao` fala de **um** sujeito, fixado em `criar-template!` na
   coluna `sujeito` (`NOT NULL`, `CHECK IN ('proposicao','parecer')`, migration `…0019`; a coluna já
   nasceu prevendo um terceiro sujeito — eleição da Mesa). `criar-transicao!` lê esse dado já gravado.
   A união deixaria `parecer.x` passar num template de proposição e explodir só em runtime, quando o
   `amb` real não tivesse a chave — exatamente o incidente que esta ADR existe para evitar.

   **Vocabulário ausente lança; vocabulário vazio recusa.** Chamar a aridade-2 sem `:vocabulario` é erro
   de programação e lança — se a omissão degradasse para "só sintático", o gate teria um caminho
   permissivo acionado por **esquecimento**, a mesma classe de fail-open que esta ADR fecha um nível
   acima. Quem quer só a sintaxe chama a aridade-1, que o diz na assinatura. Já `#{}` é declaração
   legítima ("nada é permitido aqui" — o caso do template inexistente) e devolve `INVALIDA`: resposta de
   domínio, não exceção.

3. `alegado` **sai dos dois `amb` de runtime** (`db/tramitacao.clj` e `db/parecer_tramitacao.clj`). É o
   que fecha a porta para rito gravado fora de `criar-transicao!` (import, SQL direto): o avaliador lança
   `{:erro :runtime}` e **a matéria não tramita** — nunca vira `nil`/`false` silencioso.

**A auditoria não muda.** `proposicao_transicao_historico.contexto` e `parecer_transicao_historico.contexto`
continuam gravando o corpo do POST integralmente. O corpo deixa de **decidir**; não deixa de ser
**registrado**.

### O buraco latente que a decisão fecha de graça

`validar-guarda` é a mesma função para as duas colunas, e até aqui nenhuma delas tinha vocabulário. Uma
`autorizacao` podia referenciar `proposicao` — que não existe no `amb` dela — e só falhar **em runtime**,
no meio do ato. Com o vocabulário por coluna, isso passa a ser recusado no cadastro.

## Enforcement

- `criar-transicao!` é o **único** INSERT em `legislativo.template_transicao` (não existe
  `atualizar-transicao!`), e a tabela é **subject-agnóstica** — governa proposição *e* parecer, que não
  tem cadastro próprio. Um gate ali cobre os dois sujeitos.
- Gate de cadastro **mais** rede de runtime. Só o primeiro seria falso senso de segurança (rito que entre
  por fora do `criar-transicao!` escaparia); só o segundo empurraria a falha para o meio da sessão.
- Testes: um caso por **esconderijo sintático** do identificador ilegal (raiz nua, sob `:campo`, em
  `:args` de `:chamada`, em `:elementos` de `:conjunto-lit`, nos dois lados de `:binop`, sob `:operando`
  de `:unop`, e aninhado) — o risco da fatia é um `:t` que o walk esqueça de visitar.

## Consequências

**O catálogo de fatos apurados vira o gargalo, e isso passa a ser visível.** Medido em 11/09/2026: o
`RegistroFatos` publica **20 assinaturas**, das quais **uma** fala sobre a proposição
(`aprovada_em_votacao`) — e ela exclui `requerimento` por decisão de domínio deliberada
(`db/votacao.clj`). Depois desta ADR, uma guarda expressa condições sobre o estado do sujeito mais esse
fato, e pouco mais.

Isso **não é custo da decisão, é a medida do que ainda não foi modelado** — e a decisão o torna barulhento
no lugar certo: quem cadastra um rito que precisa de um fato inexistente descobre **ao salvar**, com o
vocabulário permitido na mensagem, em vez de escrever uma guarda que lê o corpo e parece funcionar.

**Roteamento passa a ser gatilho-por-destino.** Um gatilho `avancar` cuja porta se escolhia por
`alegado.comissao` vira gatilho por destino (`despachar_ccj`, `despachar_fazenda`). Mais verboso no rito,
e mais próximo do texto do regimento.

**`exigir-portas-coerentes!` (Decisão B) continua necessária.** Ela é cega ao conteúdo da guarda — só olha
`:autorizacao`. Protege uma classe **ortogonal** de rito incoerente (porta com fechadura ao lado de porta
sem), que existe mesmo quando a guarda lê só fato apurado. As duas proteções não se sobrepõem.

**Custo em dado: zero.** `template_transicao` só é populado em testes; a semente da demo cria seis
transições, todas com `:guarda nil`. Nenhum rito do repositório lê `alegado`. Nenhum consumidor da rota
`POST /legislativo/proposicoes/:id/tramitacao` existe no frontend nem no e2e. O custo real foi em código
e em **quatro testes de integração que afirmavam o vazamento** (`tramitacao_db_test.clj`) — reescritos
para provar o veto.

**Dívida de documentação paga junto.** Cinco pontos passaram a mentir com esta decisão e foram corrigidos
no mesmo commit: as duas docstrings citadas acima, a de `guarda-dsl` e a de `validar-guarda` em
`motor/api.clj` (ambas ainda citavam `contexto`, vocabulário morto desde a Fatia 4), e a de
`parecer_tramitacao.clj`.

## Alternativas descartadas

| Alternativa | Por que não |
|---|---|
| **Manter, como a Fatia 4 decidiu** — o nome `alegado` torna a confiança visível e o rito assume o risco | Resolve para quem *lê* o rito, não para quem o *escreve*. O incidente chega ao cliente pelo segundo |
| **Denylist de `"alegado"` dentro de `motor/`** | Põe vocabulário de um módulo na biblioteca compartilhada pelos 4 usos da DSL — §22.10 |
| **Walk próprio em `legislativo/`, fora do motor** | Terceira implementação do mesmo percurso de AST; quebra a disciplina 5 (motor declarativo compartilhado) |
| **Só a rede de runtime, sem gate de cadastro** | Empurra a falha para o meio do ato — exatamente o que o Inv. comercial de §22.7 proíbe (regra falhando em runtime é incidente inaceitável) |
| **Só o gate de cadastro, sem tirar do `amb`** | Rito que entre por import/SQL direto escapa do gate e continua lendo o corpo |
| **Proibir `alegado` só em posição de precondição, mantendo-o para roteamento** | Não é distinguível estruturalmente: na engine, a guarda **é** quem escolhe a porta. Precondição e roteamento são a mesma posição sintática |
