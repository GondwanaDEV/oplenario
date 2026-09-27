# ADR-0012 — Proposta de ato: o agente prepara, a pessoa confirma na tela

- **Status:** aceita (27/09/2026)
- **Contexto de decisão:** docs/25 Eixo 4 (CONFIRMADO em 26/09/2026 — 4.2 opção B), plano docs/26 B.6
- **Relacionadas:** ADR-0009 (catálogo de ações), ADR-0010 (identidade delegada)

## Contexto

O catálogo classifica cada ação em `leitura`, `rascunho` ou `ato` (Eixo 4.1; sem classe = `ato`, 4.4). Até a B.5 o
agente só lia, e a ADR-0010 negava todo `ato` pedido por agente (`:ato-so-por-proposta`), à espera desta fatia. O
Eixo 4.2 decidiu: o agente **cria uma proposta** com o conteúdo exato, e a pessoa confirma **na interface da própria
plataforma**, com o mesmo ritual da tela (assinatura em 2 toques) — nunca no chat do agente, para que ninguém
descreva uma coisa e assine outra. O 4.3 separa os atos que o agente **nem propõe**: voto, presença e condução da
sessão ao vivo. O 4.5 exige que a proposta mostre ao confirmador que a execução leu conteúdo de terceiro.

## Decisão

1. **Entidade genérica `integracao_ia.proposta_ato`** (mig 0095), por tenant: a pessoa (única que confirma), a
   execução e o agente de origem, a ferramenta, a **entrada exata já validada**, o título e o texto do que será feito,
   o ritual, as leituras de terceiro da execução, o estado (`aguardando` → `executando` → `confirmada`, ou
   `recusada`/`expirada`) e o resultado. Vale **72 h**; depois expira na leitura.
2. **No catálogo**, uma entrada `ato` declara também `:ritual` (`:confirmar` | `:assinatura`) e `:apresentar`
   `(fn [deps ator entrada] -> {:titulo :texto} | nil)` — o que a pessoa vai ver e confirmar (o requerimento já
   formatado, com autor e data do servidor). Entrada `ato` sem os dois não carrega (fail-closed).
3. **Ato pedido por agente vira proposta, não execução.** `kernel.catalogo/executar` confere papel e classe, valida a
   entrada, apresenta (nil = não encontrado) e chama o seam `:propor` do host; a ferramenta devolve ao agente
   `{proposta-id, titulo, estado "aguardando_confirmacao", mensagem}` e o audit registra o desfecho `proposta`.
   Agente institucional segue sem `ato` (`:institucional-nunca-ato`).
4. **A confirmação é só da tela.** `POST /propostas/:id/confirmacao` exige a sessão da pessoa (credencial de agente é
   recusada), a mesma pessoa da proposta, estado `aguardando` e prazo válido; toma a proposta (`executando`, update
   condicional — duas confirmações não executam duas vezes) e executa a entrada guardada pela **mesma** entrada do
   catálogo, agora como a pessoa — papel e policy recalculados na hora. Sucesso = `confirmada` com o resultado;
   falha volta a `aguardando` com o erro. `POST /propostas/:id/recusa` encerra. A tela mostra a apresentação
   **recalculada no momento** (é o que será assinado hoje) e o que foi proposto.
5. **Atos que o agente nem propõe (4.3)** ficam fora do catálogo com a categoria `:pessoal` em
   `fora-do-catalogo.edn`: votar, abrir/encerrar votação, presença (confirmar, registrar, lote, chamada), transição
   da sessão, falas, cronômetro, anúncio do item e decisão da Mesa. O lint cobra que essa lista não encolha e que
   nenhuma entrada do catálogo aponte para elas.
6. **Contaminação (4.5).** Uma entrada de leitura que devolve conteúdo de terceiro declara `:terceiro`
   `(fn [saida] -> [{:origem :referencia}])`; o core grava a leitura na execução (`integracao_ia.leitura_de_terceiro`),
   o MCP marca o resultado `oplenario/origem: terceiro`, e a proposta criada depois leva essas leituras — a tela
   mostra "feita depois de ler e-SIC nº X". Nenhuma ferramenta do catálogo lê conteúdo de terceiro hoje; o mecanismo
   está pronto para a primeira (e-SIC, participação).
7. **Primeiro ato:** `protocolar_requerimento` (vereador, ritual `:assinatura`, a mesma ação de
   `POST /meu/requerimentos`), com a leitura `modelos_de_requerimento`. O assistente passa a receber a classe `ato`
   junto de `leitura` — que, por (3), só propõe.

## Consequências

- O pior caso de uma instrução escondida vira uma proposta que a pessoa recusa (4.5, defesa estrutural).
- A B.7 (copiloto do requerimento) usa esta proposta como saída.
- **Fica para depois:** bloquear proposta que "leva para fora" dado restrito lido na mesma execução (4.5.2) — hoje o
  catálogo só entrega conteúdo público ao agente, então não há o que bloquear; entra junto da primeira ferramenta que
  ler conteúdo restrito. Step-up na confirmação entra com a assinatura ICP real.
