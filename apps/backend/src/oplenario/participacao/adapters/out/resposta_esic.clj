(ns oplenario.participacao.adapters.out.resposta-esic
  "Gate de SAIDA `models -> wire/out` da RESPOSTA e-SIC (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. Projeta o recibo da resposta (200) e a VIEW da resposta ao cidadao, FILTRANDO `respondido-por`
  (qual servidor respondeu), ente-id e ids internos. Validada contra wire/out (drift = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.resposta-esic :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Recibo da resposta {:respondida-em} -> RespostaReciboOut (resposta 200). So o carimbo do ato."
  [r]
  (validar! wire/RespostaReciboOut
            {:respondida-em (->str (:respondida-em r))}
            "RespostaReciboOut"))

(defn resposta->wire
  "View publica de uma resposta {:corpo :respondida-em} -> RespostaOut. FILTRA respondido-por/ente/ids — so o
  conteudo publico (corpo + quando)."
  [r]
  (validar! wire/RespostaOut
            {:corpo         (:corpo r)
             :respondida-em (->str (:respondida-em r))}
            "RespostaOut"))
