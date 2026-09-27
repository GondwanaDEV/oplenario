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
   [:ativada-em Instante]])

(def ListaDeCasasOut
  [:map {:closed true}
   [:casas [:vector CasaOut]]
   [:resumo [:map {:closed true} [:total :int] [:ativas :int] [:aguardando-admin :int]]]])

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
   [:atuacao [:vector AtuacaoOut]]])

(def ProvisionadaOut
  [:map {:closed true}
   [:casa CasaOut]
   [:convite [:enum "enviado" "falhou"]]])
