(ns oplenario.migracoes-lint-test
  "Lint de migrations (gate de CI, carry F0.3): o schema usa SEMPRE timestamptz, nunca `timestamp` sem
  zona — a convencao Instant<->timestamptz (kernel/db-tipos) depende disso (um `timestamp` sem zona
  voltaria como Instant errado SILENCIOSAMENTE). Aqui isso vira regra de build, nao disciplina humana."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- sem-comentarios
  "Remove o que vem depois de `--` em cada linha (descarta a prosa dos comentarios SQL)."
  [sql]
  (->> (str/split-lines sql)
       (map #(first (str/split % #"--" 2)))
       (str/join "\n")))

;; `timestamp` NAO seguido de `tz` e NAO seguido de ` with time zone` = timestamp sem zona (proibido).
(def ^:private timestamp-sem-zona #"(?i)timestamp(?!tz)(?!\s+with\s+time\s+zone)")

(defn- arquivos-migration []
  (->> (io/file "resources/migrations")
       (.listFiles)
       (filter #(str/ends-with? (.getName ^java.io.File %) ".sql"))))

(deftest nenhuma-migration-usa-timestamp-sem-zona
  (let [ofensores (for [f (arquivos-migration)
                        :let [corpo (sem-comentarios (slurp f))]
                        :when (re-find timestamp-sem-zona corpo)]
                    (.getName ^java.io.File f))]
    (is (empty? ofensores)
        (str "migrations com `timestamp` sem zona (use timestamptz): " (pr-str (vec ofensores))))))

(deftest lint-tem-dentes
  ;; prova que a regra DETECTA o ofensor e PERMITE o legitimo (senao passaria vacuo).
  (is (re-find timestamp-sem-zona "criado_em timestamp NOT NULL") "timestamp sem zona = ofensor")
  (is (nil? (re-find timestamp-sem-zona "criado_em timestamptz NOT NULL")) "timestamptz = ok")
  (is (nil? (re-find timestamp-sem-zona "ts timestamp with time zone")) "timestamp with time zone = ok"))
