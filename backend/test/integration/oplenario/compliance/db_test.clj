(ns oplenario.compliance.db-test
  (:require [clojure.test :refer [deftest is]]))

;; INTEGRAÇÃO: db contra Postgres Dockerizado — append-only de compliance_avaliacao + idempotência da materialização (UNIQUE)
