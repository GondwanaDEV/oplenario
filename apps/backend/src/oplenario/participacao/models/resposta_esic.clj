(ns oplenario.participacao.models.resposta-esic
  "Representacao INTERNA (dominio) da RESPOSTA e-SIC (§22.10 models/, ADR-0001) — Malli. APPEND-ONLY (Inv.10):
  o ato de responder um pedido OU decidir um recurso. Cita EXATAMENTE UM alvo — `pedido-id` XOR `recurso-id`
  (a CHECK resposta_alvo_exclusivo da mig 0040 espelha; aqui o schema exige ambos presentes-por-chave, nulaveis
  por valor, e a exclusividade e' guardada no db/ + banco). `respondido-por` = o servidor autor (injetado do ator)."
  (:require [oplenario.kernel.malli :as km]))

(def RespostaEsic
  "Resposta e-SIC persistida (participacao.resposta_esic)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:pedido-id [:maybe :uuid]]
   [:recurso-id [:maybe :uuid]]
   [:corpo [:string {:min 1}]]
   [:respondido-por :uuid]
   [:respondida-em km/Instante]
   [:criado-em km/Instante]])
