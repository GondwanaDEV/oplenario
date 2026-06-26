(ns oplenario.kernel.tempo
  "Relogio injetado: o kernel PRODUZ 'agora' de forma injetavel — producao le o relogio do
  sistema, teste crava o instante. Java time (Instant/LocalDate), consistente com o motor
  (que consome 'agora' como valor no runtime). 'tempo como coordenada de primeira classe' (§22.6)."
  (:import (java.time Instant LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(defprotocol Relogio
  (agora [r] "Instante atual como java.time.Instant."))

(defn relogio-sistema
  "Relogio de producao: le o relogio do sistema a cada chamada."
  []
  (reify Relogio
    (agora [_] (Instant/now))))

(defn relogio-fixo
  "Relogio de teste: devolve sempre o instante cravado (determinismo)."
  [^Instant t]
  (reify Relogio
    (agora [_] t)))

(defn hoje
  "Data civil (LocalDate) do relogio na zona dada — prazos legais correm por fuso, nao em UTC."
  ^LocalDate [r ^ZoneId zona]
  (LocalDate/ofInstant ^Instant (agora r) zona))
