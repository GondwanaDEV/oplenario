(ns oplenario.cadastros.db.comum
  "Helpers do db/ do cadastros. As funcoes db/ recebem a `tx` ja no contexto do tenant (com-tenant*) —
  a RLS isola; reference tables (sem ente_id) sao lidas na MESMA tx (mesmo schema, sem JOIN cross-schema)."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn linha->kebab
  "Linha next.jdbc (:tabela/coluna_snake -> val) -> mapa de dominio {:coluna-kebab val}; descarta o ns."
  [linha]
  (when linha
    (update-keys linha (fn [k] (keyword (str/replace (name k) \_ \-))))))

(defn linhas->kebab [linhas] (mapv linha->kebab linhas))
