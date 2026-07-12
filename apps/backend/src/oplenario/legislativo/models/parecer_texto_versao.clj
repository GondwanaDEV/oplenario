(ns oplenario.legislativo.models.parecer-texto-versao
  "Representacao INTERNA (dominio) da versao de texto do PARECER — Malli (§22.10 models/, eixo F / F3.6b).
  MESMA estrategia do eixo B (models/texto-versao): conteudo append-only hibrido inline/URI, `estado-versao`
  e' a mutacao controlada (promocao). Enums de legislativo.logic (os CHECK da migration 0020 espelham)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def ParecerTextoVersao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:parecer-id :uuid]
   [:numero-versao :int]
   [:origem-versao (enum-de logic/origens-parecer-versao)]
   [:origem-ref {:optional true} [:maybe :uuid]]
   [:origem-tipo {:optional true} [:maybe :string]]
   [:estado-versao (enum-de logic/estados-versao)]
   [:formato :string]
   ;; XOR no banco: exatamente um de inline/uri (o caller resolve via decidir-armazenamento)
   [:texto-inline {:optional true} [:maybe :string]]
   [:conteudo-uri {:optional true} [:maybe :string]]
   [:hash-conteudo {:optional true} [:maybe :string]]
   ;; concorrencia: exposto p/ o CAS de promover!
   [:lock-version :int]
   ;; assinatura em 2 toques (Onda C4, feature 7.3) — NULL ate' `promover!` receber `assinatura-algoritmo`
   ;; preenchido (so' a versao que vira vigente por esse caminho e' assinada; mesmo padrao de
   ;; models/artefato-publicacao, mas aqui de fato opcional — a maioria das versoes nunca e' assinada).
   [:assinatura-algoritmo {:optional true} [:maybe :string]]
   [:assinatura-b64 {:optional true} [:maybe :string]]
   [:assinado-por {:optional true} [:maybe :uuid]]
   [:assinado-em {:optional true} [:maybe km/Instante]]])
