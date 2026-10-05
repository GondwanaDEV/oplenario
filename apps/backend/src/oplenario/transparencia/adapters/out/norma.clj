(ns oplenario.transparencia.adapters.out.norma
  "Gate de SAIDA `models -> wire/out` da norma (§22.10 adapters/out, ADR-0001) — chamado SO pelo diplomat/.
  Validada contra wire/out (drift de campo = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.norma :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn ->wire
  "Norma (dominio) -> NormaOut."
  [n]
  (validar! wire/NormaOut
            {:norma-id (->str (:norma-id n)) :proposicao-id (->str (:proposicao-id n))
             :tipo-norma (:tipo-norma n) :numero (:numero n) :ano (:ano n) :urn (:urn n)
             :ementa (:ementa n) :publicado-em (->str (:publicado-em n))
             :veiculo-publicacao (:veiculo-publicacao n)}
            "NormaOut"))

(defn ->wires
  "A lista inteira, item a item."
  [normas]
  (mapv ->wire normas))

(defn normas->wire
  "{:normas :normas-total :pagina :por-pagina} (dominio) -> NormasOut — a pagina + o total de GET
  /portal/casa/:ente/legislacao (frente 'truncamento-familia', sitio (c)). SEM `(or ... 0)` (corrige achado
  IMPORTANTE da revisao adversarial): `:normas-total`/`:pagina`/`:por-pagina` ausentes sao bug de servidor e tem de
  reprovar no schema (500), nao virar `0` silencioso."
  [{:keys [normas normas-total pagina por-pagina]}]
  (validar! wire/NormasOut
            {:normas (->wires normas) :normas-total normas-total :pagina pagina :por-pagina por-pagina}
            "NormasOut"))
