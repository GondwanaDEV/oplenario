(ns oplenario.legislativo.models.votacao
  "Representacao INTERNA (dominio) da votacao — Malli (§22.10 models/, eixo G). Objeto POLIMORFICO
  (objeto_tipo,objeto_id). Resultado/totais/base preenchidos no encerramento. Os enums vem de
  legislativo.logic (fonte unica; os CHECK da migration 0021 espelham)."
  (:require [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def Votacao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:objeto-tipo (enum-de logic/objetos-votacao)]
   [:objeto-id :uuid]
   [:modalidade (enum-de logic/modalidades-votacao)]
   [:quorum-tipo (enum-de logic/quoruns)]
   [:estado (enum-de logic/estados-votacao)]
   ;; preenchidos no encerramento
   [:resultado {:optional true} [:maybe [:enum "aprovada" "rejeitada"]]]
   [:total-sim {:optional true} [:maybe :int]]
   [:total-nao {:optional true} [:maybe :int]]
   [:total-abstencao {:optional true} [:maybe :int]]
   [:base-membros {:optional true} [:maybe :int]]
   ;; correcao = nova votacao apontando a corrigida
   [:votacao-corrige-id {:optional true} [:maybe :uuid]]
   ;; contexto temporal na sessao (forward-ref §22.10) — votacao e' sobre a materia, nao o item (§22.6 eixo B)
   [:sessao-id {:optional true} [:maybe :uuid]]
   [:pauta-item-id {:optional true} [:maybe :uuid]]
   [:lock-version :int]])
