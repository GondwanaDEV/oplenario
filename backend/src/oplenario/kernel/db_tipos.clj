(ns oplenario.kernel.db-tipos
  "Ponte java.time.Instant <-> SQL timestamptz no data layer (§22.2; carry F0.3). O kernel/tempo
  produz Instant (relogio injetado); o pgjdbc nao infere o tipo SQL de um Instant cru e devolve
  timestamptz como java.sql.Timestamp. Aqui:
    - IDA:   Instant -> OffsetDateTime@UTC (que o pgjdbc liga como timestamptz sem ambiguidade de zona;
             Timestamp seria reinterpretado na tz da JVM).
    - VOLTA: timestamptz (java.sql.Timestamp) -> Instant (.toInstant e' independente de zona).
  Carregar este ns instala as extensoes de protocolo do next.jdbc no processo inteiro — e' `require`-d
  pelo componente datasource, logo vale para todo db/ de modulo. Convencao: o schema usa SEMPRE
  timestamptz (nunca timestamp sem zona), de modo que toda leitura de Timestamp e' um Instant."
  (:require [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs])
  (:import (java.sql Date PreparedStatement Timestamp)
           (java.time Instant ZoneOffset)))

(set! *warn-on-reflection* true)

(extend-protocol prepare/SettableParameter
  Instant
  (set-parameter [inst ^PreparedStatement ps ^long i]
    (.setObject ps i (.atOffset ^Instant inst ZoneOffset/UTC))))

;; .toInstant() so e' correto p/ timestamptz; p/ `timestamp` sem zona o pgjdbc reinterpreta na tz da JVM
;; e o Instant sairia errado. A garantia de que TODA coluna nossa e' timestamptz e' MACHINE-ENFORCED no
;; build pela migracoes-lint-test (mais forte que um assert de runtime, que so dispara na leitura — e que
;; ainda quebraria tabelas de terceiros, ex.: a schema_migrations do migratus, que usa `timestamp` cru).
;; Esta extensao e' global por design (o pgjdbc devolve timestamptz como Timestamp); em JVM UTC
;; (containers de producao) .toInstant e' correto mesmo para a tabela do migratus.
(extend-protocol rs/ReadableColumn
  Timestamp
  (read-column-by-label [v _label] (.toInstant ^Timestamp v))
  (read-column-by-index [v _rsmeta _idx] (.toInstant ^Timestamp v))
  ;; `date` (sem hora/zona) <-> java.time.LocalDate. pgjdbc liga LocalDate->date nativamente na ida
  ;; (setObject); na volta devolve java.sql.Date (irmao de Timestamp, dispatch proprio) -> .toLocalDate.
  Date
  (read-column-by-label [v _label] (.toLocalDate ^Date v))
  (read-column-by-index [v _rsmeta _idx] (.toLocalDate ^Date v)))
