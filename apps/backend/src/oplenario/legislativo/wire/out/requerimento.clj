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
