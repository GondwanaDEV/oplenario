(ns oplenario.integracao-ia.wire.out
  "Contratos de SAIDA da fronteira core -> IA (ADR-0008). Versionados pelo prefixo da rota (/v1) e, nos eventos,
  por `tipo` + `versao`. Fechados: drift de campo e' bug de servidor (-> 500), nunca resposta que o satelite
  interprete errado."
  ;; Nenhum import de `sessoes` (§22.10): os vocabularios de fala/fase ja' chegam validados pelo CHECK da origem;
  ;; aqui so' a forma.
  )

(def EventoOut
  [:map {:closed true}
   [:seq :int]
   [:ente-id :string]
   [:tipo :string]
   [:versao :int]
   [:chave :string]
   [:payload :map]
   [:criado-em :string]])

(def EventosOut
  "O feed. `proximo` = o cursor a mandar na proxima chamada (o seq do ultimo evento, ou o `depois` recebido)."
  [:map {:closed true}
   [:eventos [:sequential EventoOut]]
   [:proximo :int]])

(def SegmentoContextoOut
  [:map {:closed true}
   [:id :string]
   [:iniciou-em :string]
   [:encerrou-em [:maybe :string]]
   [:conteudo-uri :string]])

(def FalaContextoOut
  "Uma fala registrada pela Mesa — a ancora do Caminho C (quem TINHA A PALAVRA, e quando)."
  [:map {:closed true}
   [:id :string]
   [:orador-id :string]
   [:orador-nome [:maybe :string]]
   [:tipo-fala :string]
   [:fase :string]
   [:fala-pai-id [:maybe :string]]
   [:iniciou-em :string]
   [:encerrou-em [:maybe :string]]])

(def ContextoSessaoOut
  [:map {:closed true}
   [:sessao [:map {:closed true}
             [:id :string]
             [:tipo-sessao :string]
             [:numero-sequencial :int]
             [:estado :string]
             [:aberta-em [:maybe :string]]
             [:encerrada-em [:maybe :string]]]]
   [:segmentos [:sequential SegmentoContextoOut]]
   [:falas [:sequential FalaContextoOut]]])

(def AtaPublicadaOut
  "A.6c: o texto final de uma versao publicada da ata (a IA compara com o rascunho que redigiu)."
  [:map {:closed true}
   [:versao :int]
   [:texto :string]
   [:conteudo-sha256 :string]
   [:origem-redacao :string]])

(def ReciboEventoOut
  [:map {:closed true}
   [:chave :string]
   [:aplicado :boolean]])
