(ns oplenario.participacao.adapters.out.denuncia-comentario
  "Gate de SAIDA `models -> wire/out` da DENUNCIA de um comentario (§22.10 adapters/out, ADR-0001) —
  chamado SO pelo diplomat/. Projeta a confirmacao (200), identica na 1a denuncia e em qualquer repeticao
  idempotente. Validada contra wire/out (drift = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.denuncia-comentario :as wire]))

(set! *warn-on-reflection* true)

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Confirmacao {:denunciado} -> ReciboOut (resposta 200)."
  [r]
  (validar! wire/ReciboOut {:denunciado (boolean (:denunciado r))} "ReciboOut"))
