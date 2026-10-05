# ADR-0024 — Histórico auditável da Clara, a assistente da Casa

- **Status:** ✅ **Aceita** (05/10/2026). "Confirmo" do Daouda para os quatro pontos abaixo, depois do pedido
  "tem que manter o histórico das interações com a IA, lembra-se que tudo tem que ser auditável".
- **Relacionadas:** `docs/25` §5.3 (onde fica o registro de cada execução), ADR-0010 (identidade delegada e o audit
  das chamadas de agente, Eixo 3.5), ADR-0012 (proposta de ato), ADR-0017 (trilha de auditoria, sem conteúdo),
  ADR-0018 (exportar e apagar a Casa), `docs/04` §6 (o nome Clara), a prancha
  `produto/design-system/o-plenario/telas/assistente-da-casa.html`.

## Contexto

A Clara (o assistente da Casa, B.3) vai acompanhar toda tela interna num painel retrátil. Até aqui nenhuma conversa
era guardada: o `/assistente` mantinha a conversa na memória da página, o registro do satélite é sem conteúdo de
propósito (B4), e a trilha da Casa (ADR-0017) registra que houve um POST, nunca o que foi perguntado. O `docs/25`
§5.3 tratava a conversa como artefato técnico do satélite e deixava a retenção como `[GAP]` LGPD.

O pedido muda isso: a interação com a IA passa a ser **registro da Casa**. Quem perguntou o quê, o que a IA respondeu,
de onde tirou e com que modelo precisa poder ser lido depois e provado íntegro.

## Decisão

1. **O histórico mora no core**, em `integracao_ia.interacao_assistente`: só inserção (trigger
   `shared.imut_append_only`), isolada por Casa (RLS, como o resto do schema). Uma linha por pergunta; as perguntas
   da mesma conversa compartilham `conversa_id`. Revoga, para a Clara, a parte do `docs/25` §5.3 que punha a conversa
   no satélite: o satélite continua sem conteúdo. Por estar no core e ter `ente_id` com RLS, a tabela entra sozinha no
   inventário da Casa (ADR-0018): é exportada no encerramento e apagada no apagamento, sem lista a manter.
2. **O que se guarda por pergunta:**
   - a pergunta, quem perguntou (`identidade_id`) e com que público (`secretaria`, `vereador`…);
   - o desfecho: `resposta` ou `indisponivel` (a pergunta sem resposta também fica);
   - a resposta como a tela recebeu: texto, citações conferidas, parágrafos sem fonte, incerteza, conteúdo de
     terceiro;
   - os passos (ferramenta, argumentos, ok) e as propostas de ato criadas na execução (id, título, ritual);
   - o modelo e o id da execução no satélite (o que liga ao registro da Camada de Confiança e ao "Reportar erro");
   - o `execucao_id` da credencial delegada, que liga às chamadas de ferramenta.

   **Do que cada ferramenta devolveu, só o identificador e o hash.** Toda chamada de agente, inclusive de leitura,
   passa a entrar em `integracao_ia.chamada_agente` com `resultado_sha256` (SHA-256 do JSON canônico da saída).
   Prova o que a IA viu sem duplicar dado pessoal. Amplia o audit da ADR-0010, que registrava só as chamadas que
   escrevem.
3. **Quem lê:** a própria pessoa e o `auditor` (controle interno) da Casa. Cada leitura do auditor vai à trilha. Nem
   o `admin_ente` nem o operador da plataforma leem. *(As rotas de leitura entram na fatia seguinte, com a tela.)*
4. **Ligação com a trilha:** cada linha guarda `conteudo_sha256`, o SHA-256 do registro canônico (pergunta,
   desfecho, resposta, passos, propostas, modelo, execuções). A trilha continua sem conteúdo (ADR-0017): a entrada
   do `POST /agente/perguntas` aponta a interação (`recurso-tipo` + `recurso-id`) e carrega o hash em
   `detalhe.conteudo_sha256`. Como a trilha é selada em corrente, o hash ancorado ali prova que o histórico não foi
   mexido.
5. **Falha fechada:** se o histórico não grava, a resposta não sai. A pessoa recebe "indisponível, siga pela tela"
   (R-IA-1) e o erro vai ao log. Resposta de IA sem registro não existe.

## O que fica `[GAP]`

- **Prazo de guarda** e o pedido de apagamento do titular (LGPD art. 18) sobre um registro que a Casa mantém por
  obrigação: jurídico. A tela já diz isso ("ainda está em definição").
- O vereador também perguntará pela Clara no app dele; o mesmo histórico vale para ele.

## Consequências

- Uma pergunta custa duas gravações a mais (interação + uma chamada por ferramenta de leitura), na mesma requisição.
- O conteúdo das perguntas é dado da Casa sob RLS, exportável no encerramento; ele sai da Casa só pelo caminho que
  a pessoa escolher (copiar, imprimir), como qualquer tela.
- Um histórico que cresce sem prazo até o `[GAP]` jurídico fechar.

## Materialização

- Fatia 1 (armazenamento): migration `integracao_ia.interacao_assistente` + `chamada_agente.resultado_sha256`;
  `kernel/catalogo.clj` registra toda chamada de agente com o hash da saída; `agente.clj` grava a interação antes de
  responder (falha fechada) e devolve `interacao-id` e `conversa-id` no evento `fim`; a trilha recebe o hash.
- Fatia 2 (leitura e tela): rotas do histórico (pessoa e auditor), o painel da Clara no layout interno.
