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
   o `admin_ente` nem o operador da plataforma leem. **O auditor lê pela tela, nunca pela Clara** (decisão do Daouda,
   05/10/2026): o histórico e a trilha não viram ferramenta do agente, porque o que a Clara consulta vai ao fornecedor
   de IA.
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
- Fatia 2 (leitura e tela):
  - `GET /agente/historico` (a mais recente primeiro, paginada por `antes`, até 50) e `GET /agente/conversas/:id` (a
    conversa inteira, com `integra` recalculado sobre a linha lida). A pessoa lê o seu; o `auditor` lê o de outra
    pessoa (`?pessoa=`) ou o da Casa (`?escopo=casa`), com o nome de quem perguntou, e cada leitura dessas grava
    `leitura_sensivel` na trilha. Conversa de outra pessoa é 404 para quem não é auditor; `admin_ente` sozinho, 403.
  - O painel da Clara em toda tela interna da secretaria e do vereador (`app/(interno)/clara/`), porte da prancha:
    recolhido (botão, `Ctrl + /`), aberto (janela de 400 px que empurra o conteúdo, até 680 px de altura), expandido
    (histórico ao lado); no celular, folha de 88% com véu e a página inerte. A conversa continua pelo `conversa-id`
    do `fim`; o histórico agrupa por dia no fuso da Casa; a conversa guardada abre só para leitura, com o registro
    (quem, quando, modelo, citações conferidas, se confere com o hash gravado, link para a trilha).
  - Os textos de `/assistente` e a mensagem de indisponível passam a dizer "Clara".
- Fatia 3 (o auditor, o vereador e a prancha completa):
  - `/auditoria/clara`, só do `auditor`: as perguntas da Casa por dia, com quem perguntou; filtra por pessoa e busca;
    a conversa abre só para leitura, com o registro e o link de cada pergunta para a sua linha na trilha. A tela diz
    que cada conversa aberta ali fica registrada na trilha.
  - A trilha filtra por recurso (`GET /auditoria?recurso-tipo=…&recurso-id=…`, os dois juntos, também na exportação;
    soma ao escopo do papel, nunca o alarga). `/auditoria` aceita os dois pela URL e mostra "Só os eventos de uma
    pergunta à Clara", com volta para a trilha inteira. A pessoa vê a linha da própria pergunta; o auditor, qualquer uma.
  - Busca no histórico (`GET /agente/historico?q=`, 2 a 100 caracteres): na pergunta e no texto da resposta, sem
    diferença de maiúscula nem de acento (sem extensão nova no banco), com `%` e `_` literais. No painel e na tela do
    auditor.
  - A dica da tela: a página publica do que trata (`useDicaDaClara`) e o painel mostra "Nesta tela: PL 42/2026" com
    "Perguntar sobre esta matéria", que só começa a pergunta no campo. Na ficha da matéria e no editor.
  - A Clara no app do vereador, o mesmo painel, sempre com o conjunto do vereador; o botão sobe acima das abas.
- Falta:
  - outras telas publicarem a dica (sessão, parecer, pauta);
  - a aba "Assistente" do app do vereador continua como tela cheia.
