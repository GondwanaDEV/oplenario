(ns oplenario.kernel.canonico
  "O JSON CANONICO de um dado e o SHA-256 dele (ADR-0024): a MESMA forma de dado da' sempre o mesmo hash, venha de
  onde vier. Chaves de mapa viram texto e saem em ordem, em todos os niveis; keyword vira o nome; UUID, data e
  instante viram o texto ISO; nil fica null. Serve para provar o que a IA viu (a saida de cada ferramenta) e o que a
  Clara respondeu (o registro da interacao) sem guardar o conteudo na trilha."
  (:require [clojure.walk :as walk]
            [jsonista.core :as json]
            [oplenario.kernel.segredo :as segredo]))

(set! *warn-on-reflection* true)

(defn- chave [k] (if (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)) (str k)))

(defn- folha [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [(chave k) v])) x)
        (keyword? x) (chave x)
        (or (uuid? x) (inst? x) (instance? java.time.temporal.TemporalAccessor x)) (str x)
        (set? x) (vec (sort-by json/write-value-as-string x))
        :else x))

(defn json
  "O texto JSON canonico de `x`."
  ^String [x]
  (json/write-value-as-string (walk/postwalk folha x)))

(defn sha256
  "O SHA-256 (hex minusculo) do JSON canonico de `x`."
  ^String [x]
  (segredo/sha256-hex (json x)))
