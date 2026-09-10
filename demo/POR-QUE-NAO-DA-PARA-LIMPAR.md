# Por que não dá para limpar a Casa da demo sem `down -v` — provado, não suposto

**Data:** 10/09/2026, ao fim da Trilha 3.

## O problema

A T3 exercita as 24 escritas **pela interface**, e escrita cria linha. O Método Comum do plano manda
reconstruir limpo antes de cada trilha (`down -v` → `up` → `semear-tudo.sh`), mas o `down -v` **não foi
autorizado** (o classificador de permissão bloqueou). Medido ao fim da frente:

| | Esperado pela demo | Real |
|---|---|---|
| Vereadores | 17 | **181** — sendo **164** alvos de teste ("E2E T3 Alvo de Licença 1789022467903") |
| Sessões | 3 | **153** |
| Sessão `…0212` | `agendada` | `encerrada` |

Numa demonstração, o cliente veria os vereadores de teste na lista.

## A tentativa de limpeza cirúrgica, e a recusa do banco

Escrevi um script que removia **só** o que a T3 criou, casando por padrão de nome, das folhas para a
raiz. O ensaio identificou os alvos corretamente — 164 vereadores, 149 sessões — e então:

    ERROR:  imutabilidade (a) append-only: DELETE proibido em presenca_evento
    CONTEXT:  PL/pgSQL function shared.imut_append_only() line 3 at RAISE

**O banco recusou, e está certo.** `sessoes.presenca_evento` é append-only por desenho: presença é ato
de sessão, e ato de sessão não se apaga. Os vereadores de teste têm FK para lá, então também não saem.
A mesma barreira já tinha aparecido na Trilha 2 (ledger Fase 10), com moderação e denúncia.

## A conclusão, que agora é fato e não opinião

**A Casa da demo não é limpável incrementalmente. Só `down -v` + `semear-tudo.sh` restaura.**

Isso resolve o "conflito estrutural" que estava aberto desde a Fase 10 com três saídas propostas:

| Saída proposta | Veredicto agora |
|---|---|
| 1 · Casa própria para a sonda | Continua boa para **isolar corridas futuras** — mas não desfaz o que já foi escrito |
| 2 · `demo.*` afirmar invariante em vez de contagem | Faria a suíte ficar verde, mas **não devolve a demo** — e perde poder de detecção |
| 3 · `down -v` antes de cada suíte cheia | **É a única que restaura.** E não é preferência: é o que o append-only impõe |

**A recomendação, e ela mudou por causa desta medição:** autorizar o `down -v`. Não como higiene
opcional, mas porque é o único caminho. A rede já está armada — `.backups/oplenario-pre-t3.dump`
(4.2 MB) foi verificado restaurando num banco de rascunho: exit 0, 2801 vereadores, 4498 proposições.

## A receita, quando você autorizar

    cd apps/backend && docker compose down -v && docker compose up -d --build
    cd ../.. && ./demo/semear-tudo.sh

`semear-tudo.sh` falha alto se a projeção do relay não chegar — não termina verde por engano.
Depois disso, a suíte do backend deve voltar aos ~2086/0 e as 6 falhas de `demo.*` somem.
