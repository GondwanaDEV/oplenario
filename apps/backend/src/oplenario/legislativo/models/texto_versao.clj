(ns oplenario.legislativo.models.texto-versao
  "Representacao INTERNA (dominio) da versao de texto da proposicao — Malli (§22.10 models/, eixo B).
  Conteudo append-only (hibrido inline/URI); `estado_versao` e' a mutacao controlada (promocao). Os enums
  vem de legislativo.logic (fonte unica; os CHECK da migration 0015 espelham)."
  (:require [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def TextoVersao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:proposicao-id :uuid]
   [:numero-versao :int]
   [:origem-versao (enum-de logic/origens-versao)]
   [:origem-ref {:optional true} [:maybe :uuid]]
   [:origem-tipo {:optional true} [:maybe :string]]
   [:estado-versao (enum-de logic/estados-versao)]
   [:formato :string]
   ;; XOR no banco: exatamente um de inline/uri. Aqui ambos opcionais (o caller resolve via decidir-armazenamento).
   [:texto-inline {:optional true} [:maybe :string]]
   [:conteudo-uri {:optional true} [:maybe :string]]
   [:hash-conteudo {:optional true} [:maybe :string]]
   ;; concorrencia: exposto p/ o CAS de promover! (review F3.2 MAJOR-2)
   [:lock-version :int]])
