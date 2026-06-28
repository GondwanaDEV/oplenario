(ns oplenario.estrutura-lint-test
  "Lint ESTRUTURAL (gate de CI; ADR-0001): a silhueta de módulo nao pode regredir. Falha o build se
  reaparecer uma pasta `port/` (dissolvida — protocolo co-localizado em components/ ou diplomat/http/out)
  ou `schema/` (renomeada p/ wire/in + wire/out). Varredura de filesystem sobre src/, rapida e
  deterministica. Complementa o import-lint do arquitetura-test (§22.10) com a forma das PASTAS."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; nomes de pasta PROIBIDOS pela ADR-0001 (§4 port/ dissolvida; §5 schema/ -> wire/in+wire/out).
(def ^:private pastas-proibidas #{"port" "schema"})

(defn- proibidas-sob
  "Diretorios sob `raiz` cujo nome esta em pastas-proibidas. Caminhos relativos a `raiz`."
  [raiz]
  (->> (file-seq (io/file raiz))
       (filter #(.isDirectory ^File %))
       (filter #(contains? pastas-proibidas (.getName ^File %)))
       (mapv #(.getPath ^File %))))

(deftest sem-pasta-port-ou-schema-no-src
  (let [ofensores (proibidas-sob "src/oplenario")]
    (is (empty? ofensores)
        (str "estrutura-lint (ADR-0001): pasta proibida reapareceu. `port/` foi dissolvida (protocolo "
             "co-localizado em components/ ou diplomat/http/out) e `schema/` virou wire/in + wire/out. "
             "Ofensores: " (pr-str ofensores)))))

(deftest lint-tem-dentes
  ;; prova que o detector PEGA o ofensor e IGNORA o legitimo (senao passaria vacuo).
  (let [base (.toFile (Files/createTempDirectory "estrlint" (make-array FileAttribute 0)))]
    (try
      (.mkdirs (io/file base "mod" "port"))         ; ofensor
      (.mkdirs (io/file base "mod" "wire" "in"))    ; legitimo
      (.mkdirs (io/file base "mod" "components"))   ; legitimo
      (let [achadas (proibidas-sob (.getPath base))]
        (is (some #(str/ends-with? % (str File/separator "port")) achadas) "detecta a pasta port/ sintetica")
        (is (not-any? #(str/includes? % "wire") achadas) "nao acusa wire/")
        (is (not-any? #(str/includes? % "components") achadas) "nao acusa components/"))
      (finally
        (doseq [^File f (reverse (file-seq base))] (.delete f))))))
