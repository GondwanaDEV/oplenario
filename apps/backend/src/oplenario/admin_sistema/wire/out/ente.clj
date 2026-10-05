(ns oplenario.admin-sistema.wire.out.ente
  "Representacao externa (wire) do registro de Casas no console do operador (ADR-0016). Metadado da Casa e da
  atuacao da Operacao — nunca dado de dentro da Casa (a Operacao nao ve' os dados dela sem acesso de suporte)."
  (:require [oplenario.admin-sistema.wire.out.exportacao :as exportacao]))

(def ^:private Instante [:maybe :string])

(def CasaOut
  [:map {:closed true}
   [:ente-id :string]
   [:nome :string]
   [:nome-curto [:maybe :string]]
   [:uf :string]
   [:municipio [:maybe [:map {:closed true} [:ibge :string] [:nome [:maybe :string]]]]]
   [:estado [:enum "provisionar" "ativo" "suspenso" "encerrado"]]
   [:criada-em Instante]
   [:convite-enviado-em Instante]
   [:ativada-em Instante]
   ;; ADR-0018: a restricao vigente (so' numa Casa suspensa) e a suspensao aprovada que espera a sessao em curso
   [:restricao [:maybe [:map {:closed true} [:motivo :string] [:desde :string]]]]
   [:suspensao-agendada :boolean]
   ;; ADR-0018 (fatia 2): a Casa encerrada (quando) e para onde foi o acervo publico
   [:encerrada-em Instante]
   [:destino-acervo-url [:maybe :string]]])

(def PedidoOut
  "Um pedido de suspensao/encerramento (ADR-0018). `pedido-por-id` deixa a tela saber se quem olha e' quem pediu (quem
  pediu nao aprova: retira)."
  [:map {:closed true}
   [:id :string]
   [:ente-id :string]
   [:casa-nome [:maybe :string]]
   [:acao [:enum "suspender" "encerrar" "apagar"]]
   [:motivo :string]
   [:justificativa :string]
   [:estado [:enum "aguardando" "aprovado" "recusado" "expirado" "retirado"]]
   [:pedido-por-id :string]
   [:pedido-por [:maybe :string]]
   [:pedido-em :string]
   [:confirmar-ate Instante]
   [:efetivado-em Instante]])

(def ListaDeCasasOut
  [:map {:closed true}
   [:casas [:vector CasaOut]]
   [:resumo [:map {:closed true} [:total :int] [:ativas :int] [:aguardando-admin :int] [:suspensas :int]
             [:encerradas :int]]]
   [:pendentes [:vector PedidoOut]]])

(def AtuacaoOut
  [:map {:closed true}
   [:id :string]
   [:em :string]
   [:acao :string]
   [:operador [:maybe :string]]
   [:detalhe [:map-of :keyword :any]]
   [:selo :string]])

(def EncerramentoOut
  "ADR-0018 (fatia 2): a sequencia do encerramento na ficha — exportacao -> confirmacao -> guarda -> destino do acervo ->
  apagamento -> encerrada. `pode-pedir-apagamento` ja' diz se a salvaguarda passou (o backend confere de novo)."
  [:map {:closed true}
   [:em-curso :boolean]
   [:desde Instante]
   [:exportacoes [:vector exportacao/ExportacaoOut]]
   [:confirmacao [:maybe exportacao/ExportacaoOut]]
   [:apagamento-possivel-em Instante]
   [:pode-pedir-apagamento :boolean]
   [:exportacao-disponivel :boolean]
   [:apagamento-disponivel :boolean]
   [:apagamento-pendente [:maybe PedidoOut]]
   [:destino-acervo-url [:maybe :string]]
   [:encerrada-em Instante]
   [:apagamento [:maybe [:map-of :keyword :any]]]])

;; ADR-0017 (adendo de 05/10/2026): os atos da Operacao iniciados e sem desfecho registrado (so' leitura, console).
(def AtoSemDesfechoOut
  "`acao` e' a da TENTATIVA (`entrada-no-console-iniciada`, `ia-orcamento-iniciado`, ...). `operador` e' o nome; sem
  pessoa (linha de comando) vem nulo e `origem` diz `linha-de-comando`. `casa-nome` nulo = ato sem Casa (a entrada)."
  [:map {:closed true}
   [:id :string]
   [:em :string]
   [:acao :string]
   [:operador [:maybe :string]]
   [:origem [:maybe :string]]
   [:ente-id [:maybe :string]]
   [:casa-nome [:maybe :string]]])

(def AtosSemDesfechoOut
  "`total` e' a conta inteira; `atos` traz ate' `limite` (os mais antigos). `truncado` = ha' mais do que a lista mostra."
  [:map {:closed true}
   [:tolerancia-segundos :int]
   [:limite :int]
   [:total :int]
   [:truncado :boolean]
   [:atos [:vector AtoSemDesfechoOut]]])

(def FichaDaCasaOut
  [:map {:closed true}
   [:casa CasaOut]
   [:primeiro-admin [:maybe [:map {:closed true} [:nome [:maybe :string]] [:email [:maybe :string]]]]]
   [:pedido-aberto [:maybe PedidoOut]]
   [:encerramento [:maybe EncerramentoOut]]
   [:atuacao [:vector AtuacaoOut]]])

(def TransicaoOut
  "A resposta de pedir/aprovar/recusar/reativar: a Casa como ficou, o pedido (quando houve) e o efeito da aprovacao."
  [:map {:closed true}
   [:casa CasaOut]
   [:pedido [:maybe PedidoOut]]
   [:efeito [:maybe [:enum "imediato" "agendado" "ja-efetivado" "encerrada" "apagamento-interrompido"]]]
   ;; o apagamento que parou no meio diz por que (a tela oferece retomar)
   [:erro {:optional true} :string]])

(def ProvisionadaOut
  [:map {:closed true}
   [:casa CasaOut]
   [:convite [:enum "enviado" "falhou"]]])
