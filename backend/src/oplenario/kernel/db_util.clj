(ns oplenario.kernel.db-util
  "Helpers de data layer compartilhados (kernel — nao importa modulo, §22.10). A conversao da linha do
  next.jdbc (chaves namespaced snake :tabela/coluna_snake) para o mapa de dominio (kebab, sem namespace)."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn linha->kebab
  "Linha next.jdbc (:tabela/coluna_snake -> val) -> mapa de dominio {:coluna-kebab val}; descarta o ns."
  [linha]
  (when linha
    (update-keys linha (fn [k] (keyword (str/replace (name k) \_ \-))))))

(defn linhas->kebab [linhas] (mapv linha->kebab linhas))
