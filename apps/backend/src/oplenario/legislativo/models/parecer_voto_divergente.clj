(ns oplenario.legislativo.models.parecer-voto-divergente
  "Representacao INTERNA (dominio) do voto divergente de membro da comissao — Malli (§22.10 models/, eixo F
  / F3.6b). Tabela AUXILIAR append-only PURO: o voto vencido, registrado e nunca alterado (Inv.10). Sem
  lock_version (nao muta). `voto` e' :string (vocabulario regimental aberto, §22.4.4)."
  (:require [oplenario.kernel.malli :as km]))

(def ParecerVotoDivergente
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:parecer-id :uuid]
   [:vereador-id :uuid]
   ;; voto e' a posicao juridica do vereador — obrigatorio e nao-vazio (review F3.6b clojure-MENOR)
   [:voto [:string {:min 1}]]
   [:justificativa {:optional true} [:maybe :string]]
   [:criado-em km/Instante]])
