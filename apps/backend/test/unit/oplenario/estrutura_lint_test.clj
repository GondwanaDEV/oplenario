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

(defn- adapters-planos-sob
  "Arquivos .clj cujo diretorio-PAI se chama `adapters` — i.e., adapter direto sob adapters/, FORA de
  in/ ou out/ (ADR-0001 §3: o gate e' dividido por direcao, espelhando wire/ e diplomat/)."
  [raiz]
  (->> (file-seq (io/file raiz))
       (filter #(.isFile ^File %))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (filter #(= "adapters" (.getName (.getParentFile ^File %))))
       (mapv #(.getPath ^File %))))

(deftest sem-pasta-port-ou-schema-no-src
  (let [ofensores (proibidas-sob "src/oplenario")]
    (is (empty? ofensores)
        (str "estrutura-lint (ADR-0001): pasta proibida reapareceu. `port/` foi dissolvida (protocolo "
             "co-localizado em components/ ou diplomat/http/out) e `schema/` virou wire/in + wire/out. "
             "Ofensores: " (pr-str ofensores)))))

(deftest adapters-divididos-por-direcao
  (let [ofensores (adapters-planos-sob "src/oplenario")]
    (is (empty? ofensores)
        (str "estrutura-lint (ADR-0001 §3): adapter deve viver em adapters/in/ ou adapters/out/, nunca "
             "direto sob adapters/ (gate dividido por direcao: in valida/coage, out projeta/filtra). "
             "Ofensores: " (pr-str ofensores)))))

(deftest lint-tem-dentes
  ;; prova que o detector PEGA o ofensor e IGNORA o legitimo (senao passaria vacuo).
  (let [base (.toFile (Files/createTempDirectory "estrlint" (make-array FileAttribute 0)))]
    (try
      (.mkdirs (io/file base "mod" "port"))         ; ofensor
      (.mkdirs (io/file base "mod" "wire" "in"))    ; legitimo
      (.mkdirs (io/file base "mod" "components"))   ; legitimo
      (.mkdirs (io/file base "mod" "adapters" "in"))     ; legitimo (adapter por direcao)
      (spit (io/file base "mod" "adapters" "plano.clj") "(ns x)")        ; ofensor: adapter plano
      (spit (io/file base "mod" "adapters" "in" "ok.clj") "(ns y)")      ; legitimo: sob in/
      (let [achadas (proibidas-sob (.getPath base))
            planos  (adapters-planos-sob (.getPath base))]
        (is (some #(str/ends-with? % (str File/separator "port")) achadas) "detecta a pasta port/ sintetica")
        (is (not-any? #(str/includes? % "wire") achadas) "nao acusa wire/")
        (is (not-any? #(str/includes? % "components") achadas) "nao acusa components/")
        (is (some #(str/ends-with? % "plano.clj") planos) "detecta adapter plano sob adapters/")
        (is (not-any? #(str/includes? % (str File/separator "in" File/separator)) planos) "nao acusa adapters/in/"))
      (finally
        (doseq [^File f (reverse (file-seq base))] (.delete f))))))

;; ---- GUC app.correcao_auditada: o desbloqueio da imutabilidade so pode nascer no kernel ----
;; (ADR-0002 §4b / review de seguranca): setar esse GUC reescreve a identidade canonica de uma proposicao
;; PUBLICADA. So o kernel (futuro com-correcao-auditada* com authz) pode toca-lo — nunca um controller solto.
(defn- arquivos-clj-fora-do-kernel []
  (->> (file-seq (io/file "src/oplenario"))
       (filter #(.isFile ^File %))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (remove #(str/includes? (.getPath ^File %) (str File/separator "kernel" File/separator)))))

(deftest correcao-auditada-so-no-kernel
  (let [ofensores (for [^File f (arquivos-clj-fora-do-kernel)
                        :when (str/includes? (slurp f) "app.correcao_auditada")]
                    (.getPath f))]
    (is (empty? ofensores)
        (str "seguranca (ADR-0002 §4b): 'app.correcao_auditada' so pode ser setado no kernel "
             "(com-correcao-auditada* com authz). Ofensores: " (pr-str (vec ofensores))))))
