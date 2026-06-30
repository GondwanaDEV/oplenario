(ns oplenario.sessoes.models.incidente
  "Representacao INTERNA (dominio) dos INCIDENTES PROCESSUAIS da sessao (§16.13) — Malli (§22.10 models/). Ato
  regimental APPEND-ONLY (suscitado+deliberado num so registro, p/ a ata), espelha DecisaoMesa. Enums de
  sessoes.logic (fonte unica; os CHECK da mig 0035 espelham). `objeto`/`requerente` sao forward-ref OPCIONAIS
  a outros schemas (uuid, sem FK, §22.10)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def IncidenteProcessual
  "Incidente processual (pedido de vista, verificacao de votacao, urgencia, votacao em bloco). `objeto`/
  `requerente`/`deliberacao` opcionais."
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:tipo (km/enum-de logic/tipos-incidente)]
   [:resultado (km/enum-de logic/resultados-incidente)]
   [:descricao [:string {:min 1}]]
   [:objeto-tipo {:optional true} [:maybe (km/enum-de logic/tipos-objeto-incidente)]]
   [:objeto-id {:optional true} [:maybe :uuid]]
   [:requerente-id {:optional true} [:maybe :uuid]]
   [:deliberacao {:optional true} [:maybe :string]]
   [:ocorrido-em km/Instante]])
