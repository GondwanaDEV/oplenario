(ns oplenario.legislativo.models.apensacao
  "Representacao INTERNA (dominio) do FATO de apensacao — Malli (§22.10 models/, eixo E). Associacao
  COM HISTORICO: a desapensacao NAO apaga a linha (UPDATE em `desapensada-em`) — por isso ambos os
  campos do ato de desapensar sao opcionais/nullable (existem so apos desapensar). Imutabilidade
  parcial nivel (c) (§22.4.3 disc.4): so `desapensada-em` e motivos/ato correlatos sao mutaveis, uma
  vez. `apensada-em`/`desapensada-em` chegam como java.time.Instant (kernel/db-tipos; timestamptz) —
  validados pelo spec-base canonico kernel.malli/Instante (consistencia, §22.10)."
  (:require [oplenario.kernel.malli :as km]))

(def Apensacao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:principal-id :uuid]
   [:apensada-id :uuid]
   [:apensada-em km/Instante]
   ;; ativa -> nil; desapensada -> Instant (uma vez)
   [:desapensada-em {:optional true} [:maybe km/Instante]]
   [:motivo-apensacao {:optional true} [:maybe :string]]
   [:motivo-desapensacao {:optional true} [:maybe :string]]
   [:ato-apensacao-ref {:optional true} [:maybe :uuid]]
   [:ato-desapensacao-ref {:optional true} [:maybe :uuid]]
   ;; concorrencia: exposto p/ o CAS de desapensar!
   [:lock-version :int]])
