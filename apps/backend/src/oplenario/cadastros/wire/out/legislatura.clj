(ns oplenario.cadastros.wire.out.legislatura
  "Representacao EXTERNA de SAIDA de GET /cadastros/legislatura-vigente (Onda D Slice 4) — o seletor do form
  de mandato precisa do id + rotulo (numero/anos) da legislatura vigente. Do Eixo 8 gera o tipo TS.")

(def LegislaturaVigenteOut
  [:map {:closed true}
   [:id :string]
   [:numero :int]
   [:ano-inicio :int]
   [:ano-fim :int]
   [:vigente :boolean]])
