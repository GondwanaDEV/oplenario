# ADR-0003 — A camada `relacoes/` pode importar o `db/` do próprio módulo (emenda ao §3-bis da ADR-0001)

- **Status:** 🟡 **RASCUNHO — aguarda decisão do Daouda.** Proposto em 2026-09-11 pela fatia 3-B
  (`tramitacao-borda`). O código e o teste já refletem a emenda; se ela for recusada, o caminho
  alternativo está na seção "Alternativas" e o código volta atrás.
- **Decisor:** Daouda Traore (CTO)
- **Emenda:** [ADR-0001](0001-estrutura-de-pastas-e-silhueta-de-modulo.md) §3-bis (Repo-Component).
- **Enforçada por máquina:** `test/unit/oplenario/arquitetura_test.clj` → `violacoes-db` /
  `db-so-do-repo-component`. O CI reprova quem sair da matriz.

## Contexto

A ADR-0001 §3-bis diz: *"o controller depende do Repo-Component, nunca do `db/` direto"*. O lint
materializou isso como uma lista de camadas permitidas — `components/` (o Repo) e `db/` (db→db do mesmo
módulo). A lista foi escrita contra as camadas que existiam quando a regra nasceu.

A fatia 3-B trouxe o primeiro caso de `relacoes/` precisando de persistência: o legislativo passou a
publicar o fato `aprovada_em_votacao` no registry do motor de DSL, e a consulta que responde a essa
pergunta **já existia** — `db/votacao.clj/aprovada-em-votacao?`, fonte única do autógrafo (T3-A), com
três exclusões nada óbvias (votação aberta, votação anulada, votação corrigida por outra) e o filtro de
`objeto_tipo` polimórfico que inclui `redacao_final` por causa do rito de Fortaleza (`docs/17` §5.1).

Duas saídas ruins estavam disponíveis:

1. **Reescrever a consulta em `relacoes/`** (é o que `cadastros/relacoes` e `sessoes/relacoes` fazem —
   mas por ausência de fonte a reusar, não por decisão). Criaria uma segunda verdade: o próximo conserto
   daquele predicado — cuja docstring ainda lista LIMITES CONHECIDOS em aberto — não chegaria ao guard,
   e o sistema passaria a responder coisas diferentes para a mesma pergunta.
2. **Declarar a função em `components/`**, namespace que o lint já permite, e importá-la de lá. O lint
   ficaria verde e a dependência real seria exatamente a mesma, só travestida — enforcement virando
   teatro.

## Decisão

**`relacoes/` entra na matriz de camadas que podem importar o `db/` do PRÓPRIO módulo.**

O motivo do §3-bis é **quem ABRE transação passa pelo Repo** (é o Repo que trata `com-tenant*`).
`relacoes/` não abre transação nenhuma: ela **recebe** a `tx` do tenant como 1º argumento, injetada pelo
motor via `RegistroFatos`, exatamente como uma função de `db/`. É uma folha tenant-aware do mesmo
módulo, irmã do `db/` — e não há caminho pelo Repo que ela pudesse tomar: o Repo abriria uma **segunda**
transação, fora daquela em que o guard está sendo avaliado.

### O que a emenda NÃO abre (fixado por teste negativo em `db-lint-tem-dentes`)

| Caso | Continua |
|---|---|
| `relacoes/` → `db/` de **outro** módulo | **violação** (barrado também por `violacoes-de`, §22.10) |
| `controllers`/`logic`/`diplomat`/`autenticacao` → `db/` do próprio módulo | **violação** |
| `relacoes/` → `db/` do **próprio** módulo | **permitido** (esta ADR) |

### Corolário — `ente` continua fora da assinatura de relação

A assinatura de uma função de relação é `(fn tx arg-de-domínio…)` e **não** carrega `ente`
(§4-bis/C2 do catálogo: a Casa é 1:1 com o tenant, implícita na `tx`). Quando a função de `db/` delegada
exige `ente-id` explícito (defesa em profundidade sobre a RLS), ele é lido de volta do GUC por
`kernel/tenancy/ente-da-sessao`, que **lança** se a `tx` não estiver em tenant. Devolver `nil` ali faria
a consulta a jusante casar zero linhas e o fato responder `falso` em silêncio — um guard de tramitação
negaria para sempre e o sintoma seria "a Casa não permite este ato", nunca a causa.

## Alternativas consideradas e recusadas

- **Reescrever a consulta em `relacoes/`** — recusada: duas verdades para a mesma pergunta (acima).
- **Roteador em `components/`** — recusada: passa no lint sem mudar a dependência real.
- **Mover `relacoes/` para dentro de `db/`** — recusada: `relacoes/` é vocabulário publicado a OUTRO
  subsistema (o motor), e o host precisa importá-la; o host não pode importar `db/` de módulo.

## Consequências

- Uma camada a mais na matriz do lint — a regra fica mais longa e, em troca, deixa de forçar contorno.
- `relacoes/` de módulos diferentes passam a ter formas legitimamente distintas: `cadastros`/`sessoes`
  escrevem HoneySQL direto (não havia fonte a reusar), `legislativo` delega. Isso é **decisão por caso**,
  não inconsistência — e está declarado na docstring de `legislativo/relacoes.clj`.
