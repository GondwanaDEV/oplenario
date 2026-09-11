(ns oplenario.legislativo.wire.out.proposicao
  "Representacao EXTERNA de SAIDA da leitura de proposicoes (§22.10 wire/out, ADR-0001, Onda B Slice 1) — o
  contrato de GET /legislativo/proposicoes, do qual o Eixo 8 gera os tipos TS. `estado` fica :string (nao
  enum fechado): a maquina fina de tramitacao e' TEMPLATE-DRIVEN por camara (F3.3) — mesmo racional de
  paineis/wire/out/tramitacao e de tramitacao-vista.ts no FE."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def ProposicaoResumoOut
  "Uma linha da lista (nao a proposicao inteira — sem atributos_especificos/texto_vigente_versao_id/
  lock_version/created_by/updated_by)."
  [:map {:closed true}
   [:id :string]
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   [:atualizado-em :string]])

(def ListaProposicoesOut
  "O envelope da resposta de GET /legislativo/proposicoes — a pagina de itens (ProposicaoResumoOut) mais os
  metadados de paginacao (total do MESMO filtro, pagina e tamanho-pagina efetivos)."
  [:map {:closed true}
   [:itens [:sequential ProposicaoResumoOut]]
   [:total :int]
   [:pagina :int]
   [:tamanho-pagina :int]])

(def ProposicaoDetalheOut
  "GET /legislativo/proposicoes/:id (Onda B Slice 2) — a proposicao inteira (nao o resumo estreito da
  lista) + o texto vigente inline, p/ o form de edicao pre-encher (e a resposta do POST de criacao, com
  o numero oficial + lock-version iniciais)."
  [:map {:closed true}
   [:id :string]
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   [:estado :string]
   ;; T3-A/Fatia 2 (guarda-autografo-votacao): o fato "a Casa APROVOU" que o FE gateia botao (autografo,
   ;; pos-aprovacao) — vem do ATO (votacao encerrada 'aprovada'), NUNCA do :estado acima (texto livre,
   ;; template por camara — ver db/votacao.clj/aprovada-em-votacao?). :boolean, nao {:optional true}: toda
   ;; leitura de detalhe computa o fato, entao a ausencia do campo e' sempre bug de servidor, nunca "nao sei".
   [:aprovada :boolean]
   [:lock-version :int]
   [:atualizado-em :string]
   [:texto {:optional true} [:maybe :string]]])

(def TramitacaoReciboOut
  "POST /legislativo/proposicoes/:id/tramitacao (Fatia 2) — o RECIBO da transicao que OCORREU. Nao e' a
  proposicao inteira: e' a prova do ato, no mesmo vocabulario da linha de `proposicao_transicao_historico`
  e do evento `proposicao.transicionou` (de/para/gatilho/ocorrido-em) — o operador precisa ver PARA ONDE o
  rito levou a materia, que ele nao pediu e nao poderia pedir.

  `de`/`para` :string (nao enum): sao chaves de `template_estado`, config do tenant — Inv.4, mesmo racional
  de `:estado` acima. `ocorrido-em` e' o instante REAL da transicao (RETURNING do historico), nao o do
  processamento — mesma disciplina de `events/proposicao.TransicionouPayload`."
  [:map {:closed true}
   [:proposicao-id :string]
   [:de :string]
   [:para :string]
   [:gatilho :string]
   [:ocorrido-em :string]])

;; ---------- Fatia 3: a LEITURA da tramitacao (GET .../:id/tramitacao) ----------

(def TramitacaoHistoricoItemOut
  "Uma linha do historico de tramitacao. Mesmos 4 campos de `wire.out.ficha-materia/HistoricoTramitacaoItemOut`,
  e a duplicacao e' DELIBERADA: `ficha-materia` ja' requer ESTE namespace, entao reusar em sentido contrario
  seria ciclo, e mover o schema p/ ca' alteraria um contrato ja' mergeado por motivo cosmetico. Fica o
  registro de que sao dois schemas que precisam mudar JUNTOS.

  SEM `:contexto`, `:ator-id` e `:template-id` — a MESMA decisao de escopo de `ficha-materia`, mantida aqui
  de proposito e nao por inercia: `contexto` virou entrada de CLIENTE na fatia 2, e devolve-la e' uma decisao
  sobre o que a visao de auditoria deve (e a quem), nao sobre engenharia; `ator-id` cru e' o defeito #11 do
  ledger de prontidao (UUID na tela) enquanto nao houver resolvedor ator->nome. Ambos sao [CARRY] do relatorio."
  [:map {:closed true}
   [:de-estado :string]
   [:para-estado :string]
   [:gatilho :string]
   [:ocorrido-em :string]])

(def GatilhoPossivelOut
  "Um ato que o rito DECLARA a partir do estado atual.

  `gatilho` e' o verbo que vai no corpo do POST — e' o unico motivo de esta lista existir: sem ela o
  operador teria de adivinhar a string, e a rota de escrita ficaria inutilizavel pela interface.

  `pode-ser-recusado` e' o preco da honestidade desta rota. A leitura NAO avalia os guards (ver
  `logic/gatilhos-possiveis`: o guard le' `contexto`, que so' existe no POST). `true` = ha' condicao, e o
  rito pode recusar no disparo (409). `false` = o rito declara o ato incondicional — e ainda assim NAO e'
  promessa transacional: o estado pode mudar entre a leitura e o disparo. Uma interface que trate `false`
  como garantia estara' errada no mesmo dia em que duas pessoas mexerem na mesma materia.

  `destinos-possiveis` e' informacao, NUNCA escolha: mostra para onde o rito levaria, e o cliente segue sem
  poder pedir destino nenhum (`wire/in/TramitarProposicao` e' :closed e so' aceita gatilho). Mais de um
  destino = o mesmo ato leva a lugares diferentes conforme o guard; quem decide e' o template.
  :string (nao enum) pelo mesmo Inv.4 de `:estado`: sao chaves de `template_estado`, config do tenant.

  `exige-autorizacao` (3-A) e' a pergunta IRMA e DIFERENTE de `pode-ser-recusado`: aquela diz se o rito
  poe condicao sobre o MUNDO ('isto aconteceu?'), esta diz se poe condicao sobre QUEM PEDE ('voce pode
  declarar que aconteceu?'). As duas recusas vao para pessoas diferentes — 409 para o operador, 403 para
  quem administra acesso. `false` NAO significa 'autorizado': significa que o rito nao declarou regra de
  pessoa para este ato, e resta apenas o gate GROSSO da rota. E' de proposito que isso apareca: um rito
  que esqueceu de declarar quem pode disparar fica visivel em vez de passar por autorizado."
  [:map {:closed true}
   [:gatilho :string]
   [:destinos-possiveis [:vector :string]]
   [:pode-ser-recusado :boolean]
   [:exige-autorizacao :boolean]])

(def TramitacaoOut
  "GET /legislativo/proposicoes/:id/tramitacao (Fatia 3) — o historico da materia e o que a Casa permite AGORA.

  `estado-atual` sai da LINHA, nao do fim do historico: quem manda e' a coluna que a engine le' sob FOR
  UPDATE. Derivar do ultimo `para-estado` divergiria da verdade em toda materia cujo estado tenha sido
  tocado fora da engine.

  `historico-truncado` e' obrigatorio e nunca implicito. Com teto, `n` linhas devolvidas sao
  indistinguiveis de 'a materia so' teve n atos', e o leitor tomaria a linha mais antiga MOSTRADA pelo
  comeco do processo — mentira por omissao dentro de um artefato de auditoria.

  `estado-terminal` e' TRI-VALORADO: true/false quando o rito declara o estado (e diz se e' fim de
  processo); `nil` quando nao ha' rito, ou quando o rito nao conhece o estado atual. Achatar 'desconhecido'
  em `false` apagaria exatamente o caso que pede intervencao humana.

  `nota` so' e' preenchida quando `gatilhos-possiveis` esta' VAZIO, e diz de qual das quatro causas se
  trata (sem rito / estado fora do rito / estado terminal / beco sem saida) — cada uma pede uma acao
  diferente, e 'nenhum ato disponivel' as achataria numa frase verdadeira e inutil."
  [:map {:closed true}
   [:proposicao-id :string]
   [:estado-atual :string]
   [:template-id [:maybe :string]]
   [:estado-terminal [:maybe :boolean]]
   [:historico [:vector TramitacaoHistoricoItemOut]]
   [:historico-truncado :boolean]
   [:gatilhos-possiveis [:vector GatilhoPossivelOut]]
   [:nota [:maybe :string]]])
