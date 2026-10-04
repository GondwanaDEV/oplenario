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

Migrations: `20261004000184-participacao-anexo` (`participacao.anexo` e `participacao.anexo_retirada`) e
`20261004000185-participacao-complemento` (`participacao.complemento`).

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

## Fora, de propósito

- Antivírus.
- Substituir um anexo.
- Pré-visualização do arquivo no navegador.
- Rotina de reconciliação entre o banco e o object storage (blob sem linha, linha sem blob).
- Confirmação antes de responder, arquivar e prorrogar. Só o indeferimento tem.
- Prova de ciência da prorrogação e qualquer aviso por e-mail.
- "Reportar erro" nas outras telas de IA: é outra frente.

## Pontos para o jurídico

- O arquivo enviado pelo cidadão entra na exportação da Casa. O `enviado_por` sai pseudonimizado, mas o nome e o
  conteúdo do arquivo podem identificar a pessoa.
- O anexo da Casa numa manifestação anônima fica só no registro interno: quem manifestou não tem como baixar.
- Prazos em dia corrido, como no resto do módulo (`[GAP]` corridos ou úteis).
