(ns oplenario.admin-sistema.wire.out.ente
  "Representacao externa (wire) do registro de Casas no console do operador (ADR-0016). Metadado da Casa e da
  atuacao da Operacao — nunca dado de dentro da Casa (a Operacao nao ve' os dados dela sem acesso de suporte).")

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
   [:suspensao-agendada :boolean]])

(def PedidoOut
  "Um pedido de suspensao/encerramento (ADR-0018). `pedido-por-id` deixa a tela saber se quem olha e' quem pediu (quem
  pediu nao aprova: retira)."
  [:map {:closed true}
   [:id :string]
   [:ente-id :string]
   [:casa-nome [:maybe :string]]
   [:acao [:enum "suspender" "encerrar"]]
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
   [:resumo [:map {:closed true} [:total :int] [:ativas :int] [:aguardando-admin :int] [:suspensas :int]]]
   [:pendentes [:vector PedidoOut]]])

(def AtuacaoOut
  [:map {:closed true}
   [:id :string]
   [:em :string]
   [:acao :string]
   [:operador [:maybe :string]]
   [:detalhe [:map-of :keyword :any]]
   [:selo :string]])

(def FichaDaCasaOut
  [:map {:closed true}
   [:casa CasaOut]
   [:primeiro-admin [:maybe [:map {:closed true} [:nome [:maybe :string]] [:email [:maybe :string]]]]]
   [:pedido-aberto [:maybe PedidoOut]]
   [:atuacao [:vector AtuacaoOut]]])

(def TransicaoOut
  "A resposta de pedir/aprovar/recusar/reativar: a Casa como ficou, o pedido (quando houve) e o efeito da aprovacao."
  [:map {:closed true}
   [:casa CasaOut]
   [:pedido [:maybe PedidoOut]]
   [:efeito [:maybe [:enum "imediato" "agendado" "ja-efetivado"]]]])

(def ProvisionadaOut
  [:map {:closed true}
   [:casa CasaOut]
   [:convite [:enum "enviado" "falhou"]]])
