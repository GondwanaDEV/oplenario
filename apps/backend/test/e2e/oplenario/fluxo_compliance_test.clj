(ns oplenario.fluxo-compliance-test
  (:require [clojure.test :refer [deftest is]]))

;; E2E: full-stack via HTTP + eventos — evento -> materializa -> avalia -> gera remessa -> aceita -> obrigação cumprida
