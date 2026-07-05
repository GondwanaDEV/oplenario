(ns oplenario.transparencia.adapters.out.ente
  "Gate de SAIDA `models -> wire/out` do perfil publico do ente (§22.10 adapters/out, ADR-0001) — chamado SO
  pelo diplomat/. Valida contra EnteOut (drift de campo = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.ente :as wire]))

(set! *warn-on-reflection* true)

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn ->wire
  "Perfil de ente (dominio, de cadastros/buscar-ente injetado pelo host) -> EnteOut."
  [e]
  (validar! wire/EnteOut {:nome-oficial (:nome-oficial e) :nome-curto (:nome-curto e)} "EnteOut"))
