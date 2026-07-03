(ns oplenario.participacao.adapters.out.resposta-ouvidoria
  "Gate de SAIDA `models -> wire/out` dos atos administrativos sobre a manifestacao de ouvidoria (§22.10
  adapters/out, ADR-0001) — chamado SO pelo diplomat/. Projeta os recibos de responder/arquivar/prorrogar,
  FILTRANDO quem agiu (respondido-por/arquivado-por/prorrogado-por), ente-id e ids internos. Validada contra
  wire/out (drift = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.resposta-ouvidoria :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn resposta-recibo->wire
  "Recibo da resposta {:respondida-em} -> RespostaReciboOut (resposta 200). So o carimbo do ato."
  [r]
  (validar! wire/RespostaReciboOut {:respondida-em (->str (:respondida-em r))} "RespostaReciboOut"))

(defn arquivar-recibo->wire
  "Recibo do arquivamento {:arquivada-em} -> ArquivarReciboOut (resposta 200). So o carimbo do ato."
  [r]
  (validar! wire/ArquivarReciboOut {:arquivada-em (->str (:arquivada-em r))} "ArquivarReciboOut"))

(defn prorrogar-recibo->wire
  "Recibo da prorrogacao {:prorrogado-ate} -> ProrrogarReciboOut (resposta 200). So a nova data efetiva."
  [r]
  (validar! wire/ProrrogarReciboOut {:prorrogado-ate (->str (:prorrogado-ate r))} "ProrrogarReciboOut"))
