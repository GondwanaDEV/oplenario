(ns oplenario.sessoes.adapters.out.tribuna
  "Gate de SAIDA `models -> wire/out` da TRIBUNA (§22.10 adapters/out, ADR-0001 §3) — eixo F. Projeta os recibos
  de inscricao/desistencia p/ a borda. Validado contra o contrato wire/out (drift de campo = bug de servidor ->
  500, nunca resposta malformada que envenena o codegen do front, Eixo 8)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn recibo-inscricao->wire
  "Recibo de dominio {:id uuid :ordem int} -> InscricaoReciboOut (validado, resposta 201)."
  [{:keys [id ordem]}]
  (validado wire/InscricaoReciboOut {:id (->str id) :ordem ordem}
            "recibo de inscricao viola o contrato InscricaoReciboOut (bug de servidor)"))

(defn recibo-desistencia->wire
  "Recibo de dominio {:inscricao-id uuid :de string :para string} -> DesistenciaInscricaoOut (validado, 200)."
  [{:keys [inscricao-id de para]}]
  (validado wire/DesistenciaInscricaoOut {:inscricao-id (->str inscricao-id) :de de :para para}
            "recibo de desistencia viola o contrato DesistenciaInscricaoOut (bug de servidor)"))
