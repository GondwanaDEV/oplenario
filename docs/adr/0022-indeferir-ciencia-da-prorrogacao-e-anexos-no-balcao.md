# ADR-0022 — Balcão de atendimento: indeferir, ciência da prorrogação e anexos

- **Status:** ✅ **Aceita** (04/10/2026). O Daouda confirmou a frente ("Confirmo, abre a frente 1"); as decisões de
  cada eixo foram tomadas durante a execução e confirmadas por ele no mesmo dia ("Confirmo a ADR-0022").
- **Origem:** o que o balcão de atendimento (04/10/2026) deixou em "Falta": indeferir, anexos e a ciência da
  justificativa da prorrogação ao requerente.
- **Relacionadas:** ADR-0001 (silhueta de módulo), ADR-0009 (catálogo de ações), ADR-0015 (cidadão pelo gov.br),
  ADR-0017 (trilha de auditoria), ADR-0018 (Casa suspensa e encerrada), ADR-0020 (anexos dos comunicados, o padrão
  reaproveitado).

## Contexto

- Os estados `indeferido` (e-SIC) e `indeferida` (LGPD) existiam no grafo e no CHECK do banco, mas nenhum caminho de
  código chegava neles. A Casa só conseguia responder.
- A secretaria prorrogava o prazo com justificativa, e o requerente só via a data nova. A LAI (art. 11 §2º) manda
  cientificar o requerente da justificativa.
- A resposta era só texto. A resposta a um pedido de informação costuma ser um documento.

## Decisão

### Eixo 1 — Indeferir é um ato próprio

- `POST /esic/pedidos/:id/indeferir` e `POST /lgpd/solicitacoes/:id/indeferir`, papel `secretario`, com
  `fundamentacao` obrigatória (LAI art. 11 §1º II; LGPD art. 18 §4º). Sem taxonomia de hipóteses legais: isso depende
  do regulamento de cada Casa.
- Uma transação: CAS do estado a partir dos estados abertos, a fundamentação gravada na mesma tabela da resposta, o
  prazo cumprido e o evento no outbox. Pedido já terminal dá 409.
- Evento próprio (`participacao.pedido_esic.indeferido`, `participacao.solicitacao_titular.indeferida`), sem dado
  pessoal e sem o texto. `paineis` fecha a pendência nos dois.
- **Negar dentro do prazo conta como prazo cumprido.** A métrica do e-SIC mede a resposta no prazo, não o teor.
- O recurso do e-SIC continua cabendo depois do indeferimento.

### Eixo 2 — A justificativa da prorrogação vai ao requerente, e só a ele

- `GET /portal/meus-protocolos` traz `prorrogacao` no e-SIC e na ouvidoria: data original, data nova, quando e a
  justificativa.
- As rotas públicas de acompanhamento por número de protocolo **não** mostram a justificativa: o número é sequencial
  e o texto é livre.
- O formulário de prorrogar avisa a secretaria de que o requerente lê a justificativa.
- **Limite:** o manifestante anônimo da ouvidoria não tem canal para receber a justificativa. A tela diz à secretaria
  que, nesse caso, ela fica só no registro da Casa.
- **Limite:** as prorrogações antigas, escritas antes do aviso na tela, também passam a aparecer ao requerente.
- **Fora:** a prova de que o requerente viu. Fica para quando houver e-mail.

### Eixo 3 — O anexo pertence ao protocolo e tem origem

- Tabela `participacao.anexo`, ligada ao protocolo (`objeto_tipo` ∈ `pedido_esic`, `manifestacao_ouvidoria`,
  `solicitacao_titular`), com `origem` ∈ `casa`, `requerente`. Só SELECT e INSERT, RLS por Casa.
- O blob fica em `atendimento/<ente>/<protocolo>/<id>`. A exportação e o apagamento da Casa descobrem blobs pela
  convenção `<pasta>/<ente>/…`, então não precisaram mudar.

### Eixo 4 — Dez minutos depois do ato, como nos comunicados

- A resposta e o pedido são imutáveis, e o arquivo sobe por outra requisição. A janela é de 10 minutos e é medida no
  começo do envio, para a conexão lenta não perder a janela.
- **Casa** (`secretario`): depois do último ato de resposta (resposta, indeferimento e, no e-SIC, a decisão do
  recurso, que reabre a janela uma vez). Arquivar a manifestação da ouvidoria não abre janela.
- **Requerente** (o dono autenticado do protocolo): depois de protocolar. A manifestação anônima não tem anexo, porque
  não existe rota pública de envio.
- Até 5 arquivos por protocolo e por origem, 10 MB cada. O anexo retirado não conta.
- **Cota do cidadão:** 100 MB em 24 horas por identidade, por Casa. A Casa não tem cota.
- **Reenvio:** o mesmo arquivo (mesmo SHA-256) no mesmo protocolo e origem devolve o anexo que já existe, sem gastar
  vaga. Vale abaixo do limite e dentro da janela.

### Eixo 5 — Tipos fechados, conferidos pelo conteúdo; download sempre como arquivo

- Aceitos: PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS, pela extensão.
- O servidor confere a assinatura do conteúdo contra a extensão (PDF, PNG, JPEG, ZIP nos formatos de escritório; texto
  sem byte nulo). O tipo que o navegador declara não decide: navegador que não declara o tipo é aceito.
- O tipo gravado e servido é o da extensão. O download sai com `Content-Disposition: attachment` e
  `X-Content-Type-Options: nosniff`.
- Baixam a secretaria da Casa e o dono do protocolo. Qualquer outro recebe 404, igual a "não existe".
- O download pela secretaria entra na trilha de auditoria como leitura sensível.

### Eixo 6 — Retirar um anexo

- `POST /atendimento/{esic,ouvidoria,lgpd}/:id/anexos/:anexo/retirar`, papel `secretario`, com motivo obrigatório.
  Vale para anexo das duas origens. É a contenção para o documento anexado ao protocolo errado.
- A retirada é uma linha em `participacao.anexo_retirada` (só INSERT). O blob sai do object storage, o download vira
  404 e as listas mostram o anexo como retirado. O motivo aparece só no balcão.

### Eixo 7 — O interceptor de upload é do host e protege o processo

`participacao` não pode importar `comunicacao` (§22.10). O interceptor de multipart saiu de `comunicacao` para
`oplenario.interceptors` e os dois módulos o usam.

- **Antes de ler o corpo:** as rotas de anexo conferem alvo, dono, janela e limite. Quem não pode anexar é recusado
  sem que um byte seja lido.
- **Teto do processo:** 4 envios simultâneos (503 com `Retry-After`) e um envio por pessoa por vez (429).
- **Teto do corpo:** contado no que é lido, não no `Content-Length`. A leitura para no primeiro arquivo.
- O nome do arquivo é lido em UTF-8 explícito, sem depender do locale da JVM, por leitura linear.
- `commons-fileupload2-core` está declarada em `deps.edn` na versão que já resolvia. Subir a versão é tarefa à parte.

### Eixo 8 — Nada disso é ferramenta do agente

As rotas novas entram em `fora-do-catalogo.edn` como `:so-tela`: pedido e arquivo do cidadão são dado pessoal e esperam
o `[GAP]` jurídico do fornecedor de IA. Os envios e a retirada entram na allowlist da Casa suspensa, junto das
respostas dos servidores e dos protocolos do cidadão.

## O contrato

| Rota | Quem |
|---|---|
| `POST /esic/pedidos/:id/indeferir` · `POST /lgpd/solicitacoes/:id/indeferir` | `secretario` |
| `POST /atendimento/{esic,ouvidoria,lgpd}/:id/anexos` · `GET …/anexos/:anexo` · `POST …/anexos/:anexo/retirar` | `secretario` |
| `POST /portal/meus-protocolos/{esic,ouvidoria,lgpd}/:id/anexos` · `GET …/anexos/:anexo` | o dono do protocolo |
| `POST /atendimento/{esic,ouvidoria,lgpd}/:id/complementos` (corpo `{corpo}`) | `secretario` |
| `POST /atendimento/{esic,ouvidoria,lgpd}/:id/anexos/:anexo/substituir` (multipart: um arquivo + o campo `motivo`) | `secretario` |

Migrations: `20261004000184-participacao-anexo` (`participacao.anexo` e `participacao.anexo_retirada`),
`20261004000185-participacao-complemento` (`participacao.complemento`) e
`20261004000187-participacao-anexo-substitui` (a coluna `substitui_anexo_id` em `participacao.anexo`).

## Complemento da resposta

A Casa acrescenta um texto a um protocolo que já respondeu, mesmo depois de a janela de 10 minutos dos anexos fechar.

1. **Ato próprio e imutável.** Insert-only em `participacao.complemento` (RLS por Casa), com autor e instante. O texto é
   obrigatório e tem os mesmos limites do texto da resposta (1 a 50000). Sem limite numérico de complementos.
2. **Quando cabe.** Só com ato de resposta da Casa: respondido, indeferido ou (e-SIC) recurso decidido. Na ouvidoria, depois da
   resposta; a manifestação só arquivada não aceita. Protocolo aberto dá 409: "Responda o pedido antes de complementar."
3. **Não mexe em estado nem em prazo.** Não reabre recurso e não conta como nova resposta nas métricas (não há evento).
4. **Reabre a janela de anexos da Casa** por 10 minutos: o complemento passa a ser o "último ato de resposta" da origem
   `casa` (`logic/anexo/ultimo-ato-de-resposta`). O limite de 5 por protocolo e origem e a janela do requerente não mudam.
5. **Quem vê.** O histórico do balcão (evento `complemento`, com o nome de quem escreveu) e o dono em
   `GET /portal/meus-protocolos` (`complementos`: id, texto e instante; nunca o autor), em ordem de chegada. As rotas públicas
   por número de protocolo não mostram o texto da resposta, então também não mostram o do complemento. Na ouvidoria vale a
   regra de identidade de sempre (Lei 13.460, art. 10 §7º): o balcão nomeia só quem agiu pela Casa, e a manifestação anônima
   não tem dono a quem mostrar.
6. **Fora do agente.** `fora-do-catalogo.edn` (`:so-tela`, dado pessoal). Entra na allowlist da Casa suspensa, junto das
   respostas dos servidores.
7. **Sem corrida a tratar.** Ter resposta só cresce (uma resposta nunca some), então a conferência prévia não envelhece e a
   gravação não precisa de CAS.

## Substituir um anexo

A secretaria troca o arquivo errado pelo certo num só ato, mesmo depois da janela de 10 minutos.

1. **Um ato só.** `POST /atendimento/{esic,ouvidoria,lgpd}/:id/anexos/:anexo/substituir`, papel `secretario`, multipart com
   UM arquivo e o campo de texto `motivo` (obrigatório, até 1000 caracteres, aparado; o mesmo teto do motivo da retirada).
   Mesmas validações do envio: tipos fechados conferidos pelo conteúdo, 10 MB, nome em UTF-8. Passa pelo interceptor comum
   `oplenario.interceptors/anexo-multipart`, que ganhou a opção `:campos` (nomes de campos de texto lidos junto do arquivo,
   até 4 KB cada, em `(:request :campos-do-envio)`; sem a opção os campos seguem ignorados). Alvo, anexo e estado são conferidos
   ANTES de ler o corpo; o motivo, assim que o corpo chega e antes de gravar qualquer blob.
2. **Só o anexo da Casa e ainda vigente.** O do requerente só se retira. Anexo já retirado ou já substituído, e o mesmo arquivo
   (mesmo SHA-256) já vigente no protocolo (inclusive o próprio anexo), dão 409 com a frase em palavras. O anexo novo também
   pode ser substituído depois: a cadeia segue.
3. **A qualquer tempo.** Não depende da janela de 10 minutos, como a retirada.
4. **Efeito, numa transação** (trava consultiva do protocolo): o anexo antigo é retirado (linha em
   `participacao.anexo_retirada` com o motivo e quem retirou; o blob sai do object storage; o download vira 404) e o novo
   nasce como linha em `participacao.anexo` com `substitui_anexo_id` apontando para o antigo. A tabela segue só SELECT/INSERT:
   a coluna entra no INSERT do novo, e "substituído por" é lido de volta pela relação inversa. Índice único parcial: um
   anexo só é substituído uma vez; a FK é composta com a Casa. A retirada e o novo levam o mesmo instante.
5. **A vaga é do novo.** O antigo, retirado na mesma transação, já não conta no limite de 5 por origem, então substituir
   cabe com 5 anexos ativos.
6. **Ordem do storage e do banco** (a do envio e a da retirada: nunca linha sem blob). O blob novo sobe primeiro; a
   transação grava a retirada do antigo e a linha nova juntas; o blob antigo sai DEPOIS do commit. Pontos de falha:
   - o upload do novo falha: nada mudou (500), o antigo segue lá;
   - a transação é recusada com certeza (409, violação de integridade): o blob novo sai, o antigo segue lá;
   - o resultado da transação é desconhecido (a conexão cai): nenhum blob sai. Se passou, o novo existe e o antigo (já
     retirado, com download 404) fica órfão até alguém retirar de novo; se não passou, sobra um blob novo sem linha;
   - o blob antigo não sai depois do commit: a troca está feita (201) e o antigo fica órfão com download 404. `retirar` sobre
     ele (idempotente) conclui a remoção.

   Em nenhum ponto o protocolo fica sem os dois arquivos.
7. **Quem vê.** No balcão, o antigo aparece como "Substituído em <data>" (a data da troca é a da retirada) com o motivo,
   que só a secretaria lê, e o novo logo abaixo. Em `/meus-protocolos` (o dono), o antigo aparece como "Substituído em
   <data>", sem motivo, e o novo é baixável. O fio ganhou `substituido-por` (o id do anexo que o trocou) no antigo, nos dois
   contratos. As rotas públicas por número de protocolo não mudam (não mostram anexo).
8. **Fora do agente e na Casa suspensa.** `fora-do-catalogo.edn` (`:so-tela`, arquivo de um cidadão é dado pessoal) e na
   allowlist da Casa suspensa, junto de retirar e anexar (`restricao_da_casa.clj`; a cópia de propósito em
   `vazamento_estado_test`).

## Reconciliação entre o banco e o object storage

O upload grava o blob antes da linha, então uma falha no meio deixa blob sem linha; o contrário (linha sem blob) é perda do arquivo.
Existe o comando `reconciliar-anexos [--ente <uuid>] [--apagar-orfaos]` (host: `oplenario.reconciliar-anexos`; sem agendador):

- Cobre `participacao.anexo` (`atendimento/<ente>/…`) e `comunicacao.anexo` (`comunicados/<ente>/…`), uma Casa por vez na tx do tenant.
  `participacao` e `comunicacao` expõem `chaves-de-anexos` pela própria porta; o host compõe.
- Por padrão só relata: linhas, blobs, blob sem linha e linha sem blob (200 itens por lista, com o total ao lado). Sai com 1 se há
  divergência. Anexo retirado sem blob é o estado correto.
- `--apagar-orfaos` tira do storage só o blob sem linha com mais de 24 h; nunca toca em linha. Casa encerrada não é tocada.
- Uso e códigos de saída: [`docs/27`](../27-runbook-ia-producao.md), seção 8.

## Fora, de propósito

- Antivírus.
- Pré-visualização do arquivo no navegador.
- Confirmação antes de responder, arquivar e prorrogar. Só o indeferimento tem.
- Prova de ciência da prorrogação e qualquer aviso por e-mail.
- "Reportar erro" nas outras telas de IA: é outra frente.

## Pontos para o jurídico

- O arquivo enviado pelo cidadão entra na exportação da Casa. O `enviado_por` sai pseudonimizado, mas o nome e o
  conteúdo do arquivo podem identificar a pessoa.
- O anexo da Casa numa manifestação anônima fica só no registro interno: quem manifestou não tem como baixar.
- Prazos em dia corrido, como no resto do módulo (`[GAP]` corridos ou úteis).
