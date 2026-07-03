(ns oplenario.transparencia.adapters.out.materia
  "Gate de SAIDA `models -> wire/out` da materia (§22.10 adapters/out, ADR-0001) — chamado SO pelo diplomat/.
  `->wire` projeta o item de listagem; `ficha->wire` projeta a ficha (materia + norma, se houver — a ligacao
  'proposicao -> lei' de 16.5). NAO importa adapters.out.norma (ADR-0001 §3: adapters/ so' e' chamado pelo
  diplomat/ — import adapters->adapters violaria a mesma regra); a projecao da norma embutida na ficha e'
  local a este ns (poucos campos, duplicacao aceita — evita import cross-adapters). Validada contra wire/out
  (drift de campo = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.materia :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- campos-comuns [m]
  {:proposicao-id (->str (:proposicao-id m)) :tipo (:tipo m) :ano (:ano m) :sequencial (:sequencial m)
   :urn-lex (:urn-lex m) :ementa (:ementa m) :autor-tipo (:autor-tipo m) :autor-texto (:autor-texto m)
   :estado (:estado m)})

(defn- norma-embutida [n]
  (when n
    {:norma-id (->str (:norma-id n)) :proposicao-id (->str (:proposicao-id n))
     :tipo-norma (:tipo-norma n) :numero (:numero n) :ano (:ano n) :urn (:urn n)
     :ementa (:ementa n) :publicado-em (->str (:publicado-em n))
     :veiculo-publicacao (:veiculo-publicacao n)}))

(defn ->wire
  "Materia (dominio) -> MateriaOut (item de listagem)."
  [m]
  (validar! wire/MateriaOut (campos-comuns m) "MateriaOut"))

(defn ->wires
  "A lista inteira, item a item."
  [ms]
  (mapv ->wire ms))

(defn ficha->wire
  "Materia + norma (dominio, opcional) -> FichaOut."
  [m norma]
  (validar! wire/FichaOut (assoc (campos-comuns m) :norma (norma-embutida norma)) "FichaOut"))
