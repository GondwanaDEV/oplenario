(ns oplenario.kernel.ids-test
  "Politica central de identidade de linha: PK = UUID (§22.9 Eixo 2). Centralizado para poder
  trocar a versao do UUID (ex.: v7 time-ordered p/ localidade de B-tree) sem tocar call sites.
  A numeracao canonica gapless (linha-contador) e' db-backed e fica na F0.3."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.ids :as ids])
  (:import (java.util UUID)))

(deftest novo-id-e-um-uuid
  (is (instance? UUID (ids/novo-id)) "novo-id devolve um java.util.UUID"))

(deftest novo-id-e-unico
  (is (not= (ids/novo-id) (ids/novo-id)) "dois ids consecutivos sao distintos"))
