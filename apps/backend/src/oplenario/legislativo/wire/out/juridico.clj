(ns oplenario.legislativo.wire.out.juridico
  "Contrato de SAIDA do caminho da comissao e do parecer juridico (ADR-0019 fatia 1). Malli fechado. Papeis: 'secretario'
  e 'juridico' (fila e detalhe), 'secretario'/'vereador'/'juridico' (ficha), anonimo (portal, so' depois da deliberacao).
  O id de quem assinou nao sai: a assinatura carrega nome, OAB e qualificacao, gravados no ato.")

(def conclusoes [:enum "favoravel" "contrario" "com_ressalvas" "orientacao"])

(def AssinaturaOut
  [:map {:closed true}
   [:nome :string] [:oab :string] [:qualificacao [:enum "efetivo" "comissionado" "contratado"]] [:em :string]])

(def ParecerJuridicoOut
  "`relatorio`/`fundamentacao` faltam na fila (so' o resumo). `numero`/`ano` so' existem depois de assinado."
  [:map {:closed true}
   [:id :string]
   [:numero [:maybe :int]]
   [:ano [:maybe :int]]
   [:estado [:enum "rascunho" "assinado"]]
   [:relatorio {:optional true} :string]
   [:fundamentacao {:optional true} :string]
   [:conclusao [:maybe conclusoes]]
   [:assinatura [:maybe AssinaturaOut]]
   [:substitui-id {:optional true} [:maybe :string]]
   [:substituido :boolean]])

(def MateriaDoPedidoOut
  [:map {:closed true} [:id :string] [:ref :string] [:ementa :string]])

(def PedidoJuridicoOut
  [:map {:closed true}
   [:id :string]
   [:proposicao [:maybe MateriaDoPedidoOut]]
   [:assunto :string]
   [:prazo [:maybe :string]]
   [:estado [:enum "pendente" "atendido" "cancelado"]]
   [:pedido-por [:maybe :string]]
   [:em-nome-de [:maybe :string]]
   [:origem [:enum "secretaria" "relator"]]
   [:criado-em :string]
   [:parecer [:maybe ParecerJuridicoOut]]])

(def PedidosJuridicosOut
  [:map {:closed true} [:pedidos [:sequential PedidoJuridicoOut]]])

(def ParecerDaMateriaOut
  (into ParecerJuridicoOut [[:pedido-id :string]]))

(def PedidoAbertoOut
  [:map {:closed true} [:id :string] [:assunto :string] [:prazo [:maybe :string]] [:criado-em :string]])

(def PareceresDaMateriaOut
  [:map {:closed true}
   [:pareceres [:sequential ParecerDaMateriaOut]]
   [:pedidos-abertos [:sequential PedidoAbertoOut]]])

(def ParecerPublicoOut
  [:map {:closed true}
   [:numero [:maybe :int]] [:ano [:maybe :int]] [:conclusao [:maybe conclusoes]]
   [:relatorio :string] [:fundamentacao :string] [:assinatura AssinaturaOut]])

(def PareceresPublicosOut
  [:map {:closed true} [:pareceres [:sequential ParecerPublicoOut]]])

;; ---- caminho da comissao ----

(def ComissoesOut
  [:map {:closed true} [:comissoes [:sequential [:map {:closed true} [:id :string] [:nome :string]]]]])

(def ParecerAbertoOut
  [:map {:closed true}
   [:id :string] [:comissao-id :string] [:comissao-nome [:maybe :string]]
   [:relator-id [:maybe :string]] [:relator-nome [:maybe :string]]
   [:estado :string] [:ja-existia :boolean]])

(def PareceresAbertosOut
  [:map {:closed true} [:pareceres [:sequential ParecerAbertoOut]]])

(def RelatorDesignadoOut
  [:map {:closed true} [:id :string] [:relator-id :string] [:relator-nome [:maybe :string]]])
