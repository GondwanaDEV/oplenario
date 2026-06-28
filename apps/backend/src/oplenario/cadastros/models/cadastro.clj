(ns oplenario.cadastros.models.cadastro
  "Representacao INTERNA (dominio) do cadastro — Malli (§22.10 models/). Os estados/enums batem com os
  CHECK do schema (migration 0010); as datas sao LocalDate e os carimbos Instant (convencao kernel/tempo +
  db-tipos). Um arquivo enquanto cabe; vira pasta-por-agregado quando crescer (§22.10 'arquivo vs pasta')."
  (:import (java.time Instant LocalDate)))

(def estados-mandato #{"vigente" "licenciado" "cassado" "renunciado" "falecido" "concluido"})
(def naturezas-mandato #{"titular" "suplencia"})
(def tipos-comissao #{"permanente" "temporaria" "especial" "cpi" "mesa"})

;; enum ordenado: a validacao independe da ordem, mas `sort` torna explain/= deterministico entre JVMs.
(defn- enum-de [s] (into [:enum] (sort s)))
(def ^:private LocalDate? [:fn {:error/message "deve ser java.time.LocalDate"} #(instance? LocalDate %)])

(def Ente
  [:map {:closed true}
   [:ente-id :uuid]
   [:municipio-ibge :string]
   [:nome-oficial :string]
   [:nome-curto {:optional true} [:maybe :string]]
   [:brasao-ref {:optional true} [:maybe :string]]])

(def Legislatura
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:numero :int]
   [:ano-inicio :int]
   [:ano-fim :int]
   [:vigente :boolean]])

(def Vereador
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:identidade-id {:optional true} [:maybe :uuid]]
   [:nome :string]
   [:nome-parlamentar {:optional true} [:maybe :string]]])

(def Mandato
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:vereador-id :uuid]
   [:legislatura-id :uuid]
   [:partido {:optional true} [:maybe :string]]
   [:estado (enum-de estados-mandato)]
   [:natureza (enum-de naturezas-mandato)]
   [:vigencia-inicio LocalDate?]
   [:vigencia-fim {:optional true} [:maybe LocalDate?]]
   [:fim-efetivo {:optional true} [:maybe LocalDate?]]])

(def Comissao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:nome :string]
   [:tipo (enum-de tipos-comissao)]
   [:legislatura-id {:optional true} [:maybe :uuid]]
   [:vigencia-inicio LocalDate?]
   [:vigencia-fim {:optional true} [:maybe LocalDate?]]])

(def ComissaoMembro
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:comissao-id :uuid]
   [:vereador-id :uuid]
   [:vigencia-inicio LocalDate?]
   [:vigencia-fim {:optional true} [:maybe LocalDate?]]])

(def CriadoEm [:fn {:error/message "deve ser java.time.Instant"} #(instance? Instant %)])
