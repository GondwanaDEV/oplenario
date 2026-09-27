(ns oplenario.normas.wire.out
  "Contratos de SAIDA das normas de referencia (ADR-0011).")

(def NormaOut
  [:map {:closed true}
   [:id :string]
   [:camada [:enum "federal" "estadual" "municipal" "casa"]]
   [:especie :string]
   [:titulo :string]
   [:numero [:maybe :string]]
   [:data [:maybe :string]]
   [:da-casa :boolean]])

(def VersaoResumoOut
  [:map {:closed true}
   [:id :string]
   [:estado [:enum "em_conferencia" "vigente" "substituida" "descartada"]]
   [:consolidada-ate [:maybe :string]]
   [:fonte :string]
   [:n-dispositivos :int]
   [:n-alertas :int]
   [:enviada-em :string]
   [:decidida-em [:maybe :string]]])

(def NormaResumoOut
  "GET /normas — uma linha por norma, com a vigente e a que espera conferencia."
  [:map {:closed true}
   [:norma NormaOut]
   [:vigente [:maybe VersaoResumoOut]]
   [:em-conferencia [:maybe VersaoResumoOut]]])

(def ListaNormasOut [:map {:closed true} [:normas [:vector NormaResumoOut]]])

(def DispositivoOut
  [:map {:closed true}
   [:endereco :string]
   [:rotulo :string]
   [:tipo [:enum "preambulo" "artigo" "paragrafo" "inciso" "alinea" "item"]]
   [:pai [:maybe :string]]
   [:ordem :int]
   [:texto :string]
   [:agrupador [:maybe :string]]])

(def VersaoOut
  "GET /normas/versoes/:id — o que a pessoa confere: a norma, os alertas do parser e cada dispositivo."
  [:map {:closed true}
   [:norma NormaOut]
   [:versao VersaoResumoOut]
   [:alertas [:vector :string]]
   [:dispositivos [:vector DispositivoOut]]])
