(ns oplenario.participacao.models.moderacao-comentario
  "Representacao INTERNA (dominio) da TRILHA de moderacao de um comentario (§22.10 models/, ADR-0001) —
  Malli. APPEND-ONLY (Inv.10): cada decisao (aprovar|rejeitar) registrada; `moderado-por` = o servidor autor
  (injetado do ator)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ModeracaoComentario
  "Decisao de moderacao persistida (participacao.moderacao_comentario)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:comentario-id :uuid]
   [:acao (km/enum-de logic/acoes-moderacao)]
   [:motivo-rejeicao [:maybe (km/enum-de logic/motivos-rejeicao-comentario)]]
   [:moderado-por :uuid]
   [:moderado-em km/Instante]
   [:criado-em km/Instante]])
