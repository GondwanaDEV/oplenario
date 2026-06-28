(ns oplenario.kernel.tempo-test
  "Relogio injetado (§22.6 'tempo como coordenada de primeira classe'): producao usa o
  relogio do sistema; teste crava o instante. O motor ja consome 'agora' como valor (runtime),
  o kernel so o PRODUZ de forma injetavel."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Instant LocalDate ZoneId)))

(deftest relogio-fixo-devolve-o-instante-cravado
  (let [t (Instant/parse "2026-06-26T12:00:00Z")
        r (tempo/relogio-fixo t)]
    (is (= t (tempo/agora r)) "relogio fixo devolve exatamente o instante cravado")))

(deftest relogio-sistema-devolve-um-instant
  (let [r (tempo/relogio-sistema)]
    (is (instance? Instant (tempo/agora r)) "relogio do sistema devolve um java.time.Instant")))

(deftest hoje-projeta-o-instante-em-localdate-na-zona
  ;; 2026-06-26T02:00:00Z e' 2026-06-25 em America/Fortaleza (UTC-3) — prova que a zona conta.
  (let [r (tempo/relogio-fixo (Instant/parse "2026-06-26T02:00:00Z"))]
    (is (= (LocalDate/parse "2026-06-25")
           (tempo/hoje r (ZoneId/of "America/Fortaleza")))
        "hoje converte o instante na zona dada (nao em UTC)")))
