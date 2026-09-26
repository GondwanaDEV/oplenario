(ns oplenario.legislativo.wire.out.requerimento
  "Representacao EXTERNA de SAIDA do requerimento do VEREADOR (§22.10 wire/out, ADR-0001, fatia 2a).
  `ModeloRequerimentoOut` NAO carrega o `corpo-template` cru — so' os CAMPOS que o formulario pede (os
  automaticos, autor e data, ficam de fora); o texto formatado chega pela previa. O recibo do protocolo traz
  a identidade legal (numero/URN/estado) e o ALGORITMO da assinatura — 'STUB-ICP-v0' enquanto a ICP real nao
  entra, e a tela o mostra como tal.")

(def ModeloRequerimentoOut
  [:map {:closed true}
   [:id :string]
   [:nome :string]
   [:campos [:sequential :string]]])

(def ModelosRequerimentoOut
  [:map {:closed true}
   [:itens [:sequential ModeloRequerimentoOut]]])

(def PreviaRequerimentoOut
  [:map {:closed true}
   [:texto :string]])

(def RequerimentoProtocoladoOut
  [:map {:closed true}
   [:proposicao-id :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:estado :string]
   [:assinatura-algoritmo :string]])

;; ---------- fatia 2c: o requerimento COLETIVO ----------

(def ColegaOut
  "Um vereador que pode ser convidado a subscrever (mandato vigente nesta Casa). `id` e' o do cadastro de
  vereador (o que vai em `coautores`), nao a identidade de login."
  [:map {:closed true}
   [:id :string]
   [:nome :string]
   [:partido [:maybe :string]]])

(def ColegasOut
  [:map {:closed true}
   [:itens [:sequential ColegaOut]]])

(def SubscricaoOut
  "Um convite de subscricao, como o autor e os convidados o veem: quem, e em que pe' esta'. `pendente` ->
  `confirmada` (assinou) | `recusada` | `nao_consta` (nao tinha respondido quando o autor protocolou)."
  [:map {:closed true}
   [:vereador-nome :string]
   [:estado [:enum "pendente" "confirmada" "recusada" "nao_consta"]]
   [:respondida-em [:maybe :string]]])

(def PropostaRequerimentoOut
  "A proposta de requerimento coletivo (antes do protocolo, ou ja' protocolada). `texto` e' o texto CONGELADO que
  os coautores assinam e o autor protocola. `sou-autor` decide o que a tela oferece (protocolar vs. responder);
  `minha-subscricao` e' o estado do convite de quem le, quando e' coautor (nil p/ o autor)."
  [:map {:closed true}
   [:id :string]
   [:ementa :string]
   [:tipo-requerimento :string]
   [:texto :string]
   [:autor-nome :string]
   [:estado [:enum "aguardando_subscricoes" "protocolada"]]
   [:proposicao-id [:maybe :string]]
   [:criada-em :string]
   [:sou-autor :boolean]
   [:minha-subscricao [:maybe :string]]
   [:subscricoes [:sequential SubscricaoOut]]])

(def PropostaResumoOut
  "Uma proposta do autor ainda esperando subscricoes (lista da home), com a contagem."
  [:map {:closed true}
   [:id :string]
   [:ementa :string]
   [:tipo-requerimento :string]
   [:criada-em :string]
   [:confirmadas :int]
   [:pendentes :int]
   [:recusadas :int]])

(def PropostasOut
  [:map {:closed true}
   [:itens [:sequential PropostaResumoOut]]])

(def ConviteSubscricaoOut
  "Um pedido de subscricao esperando a resposta de quem le."
  [:map {:closed true}
   [:proposta-id :string]
   [:ementa :string]
   [:tipo-requerimento :string]
   [:autor-nome :string]
   [:convidada-em :string]])

(def ConvitesSubscricaoOut
  [:map {:closed true}
   [:itens [:sequential ConviteSubscricaoOut]]])

(def RespostaSubscricaoOut
  [:map {:closed true}
   [:proposta-id :string]
   [:estado [:enum "confirmada" "recusada"]]
   [:assinatura-algoritmo [:maybe :string]]])

(def RequerimentoColetivoProtocoladoOut
  "O recibo do protocolo do requerimento coletivo: o mesmo do individual + os coautores que CONSTAM."
  [:map {:closed true}
   [:proposicao-id :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:estado :string]
   [:assinatura-algoritmo :string]
   [:coautores [:sequential :string]]])
