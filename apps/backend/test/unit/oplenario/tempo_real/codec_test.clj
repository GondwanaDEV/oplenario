(ns oplenario.tempo-real.codec-test
  "O formato da mensagem de canal dentro do Valkey: so' dado puro atravessa, nos dois sentidos."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.tempo-real.codec :as codec])
  (:import (java.nio.charset StandardCharsets)))

(defn- bytes-de [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- razao-da-recusa
  "A `:razao` com que `decodificar` recusa `ba`; nil se NAO recusou."
  [ba]
  (try (codec/decodificar ba) nil
       (catch clojure.lang.ExceptionInfo e
         (when (:tempo-real/mensagem-recusada? (ex-data e)) (:razao (ex-data e))))))

(defn- razao-do-malformado [m]
  (try (codec/codificar m) nil
       (catch clojure.lang.ExceptionInfo e
         (when (:tempo-real/payload-malformado? (ex-data e)) (:razao (ex-data e))))))

(defrecord UmRecord [tipo dados])

(deftest ida-e-volta-do-dado-puro
  (let [m {:tipo "voto.registrado" :ente-id (random-uuid)
           :dados {:texto "aspas \" barra \\ quebra\nacento ção" :n 7 :grande 12345678901234567890N :dec 1.5M
                   :fracao 0.25 :ok false :nada nil :lista [1 [2 [3]]] :conjunto #{:a :b} :chave/com-ns "x"}}]
    (is (= m (codec/decodificar (bytes-de (codec/codificar m)))))
    (is (.startsWith ^String (codec/codificar m) codec/prefixo) "o texto gravado leva o prefixo de versao")))

(deftest sequencia-preguicosa-e-inteiro-de-32-bits-gravam
  (is (= {:tipo "a" :dados {:xs '(1 2 3) :i 5}}
         (codec/decodificar (bytes-de (codec/codificar {:tipo "a" :dados {:xs (map inc [0 1 2]) :i (int 5)}}))))))

(deftest leitura-recusa-o-que-nao-e-o-formato
  (testing "sem o prefixo — inclui o que o Carmine gravava (marcador + Nippy) e texto solto"
    (is (= :sem-prefixo (razao-da-recusa (byte-array [0 62 78 80 89 0 112 1]))))
    (is (= :sem-prefixo (razao-da-recusa (bytes-de "{:tipo \"a\" :dados {}}"))))
    (is (= :sem-prefixo (razao-da-recusa (byte-array 0)))))
  (testing "nao e' bytes"
    (is (= :nao-e-bytes (razao-da-recusa nil)))
    (is (= :nao-e-bytes (razao-da-recusa {:tipo "a" :dados {}}))))
  (testing "EDN quebrado, vazio ou com sobra"
    (is (= :edn-invalido (razao-da-recusa (bytes-de "edn1:{:tipo"))))
    (is (= :vazia (razao-da-recusa (bytes-de "edn1:"))))
    (is (= :sobra-depois-da-forma (razao-da-recusa (bytes-de "edn1:{:tipo \"a\" :dados {}} {:outra 1}")))))
  (testing "grande demais"
    (is (= :grande-demais (razao-da-recusa (bytes-de (str "edn1:\"" (apply str (repeat (* 300 1024) "x")) "\""))))))
  (testing "aninhamento alem do teto, inclusive o que estouraria a pilha do leitor"
    (is (some? (razao-da-recusa (bytes-de (str "edn1:" (apply str (repeat 40 "[")) (apply str (repeat 40 "]")))))))
    (is (some? (razao-da-recusa (bytes-de (str "edn1:" (apply str (repeat 100000 "[")))))))))

(deftest leitura-nunca-constroi-objeto-nem-roda-leitor
  (let [executou (atom false)]
    (binding [*data-readers* {'oplenario/explode (fn [_] (reset! executou true) {})}]
      (testing "etiqueta registrada no processo NAO roda"
        (is (some? (razao-da-recusa (bytes-de "edn1:#oplenario/explode \"x\""))))
        (is (false? @executou))))
    (testing "construtor de record/classe, avaliacao na leitura, simbolo e data nao atravessam"
      (doseq [texto ["edn1:#oplenario.tempo_real.codec_test.UmRecord{:tipo \"a\" :dados {}}"
                     "edn1:#java.util.Date \"x\""
                     "edn1:#=(java.lang.Runtime/getRuntime)"
                     "edn1:{:tipo \"a\" :dados {:x #=(+ 1 1)}}"
                     "edn1:{:tipo \"a\" :dados {:x simbolo}}"
                     "edn1:{:tipo \"a\" :dados {:quando #inst \"2026-10-04T00:00:00Z\"}}"
                     "edn1:{:tipo \"a\" :dados {:c \\a}}"]]
        (is (some? (razao-da-recusa (bytes-de texto))) texto)))))

(deftest escrita-so-grava-dado-puro
  (is (= :tipo-fora-da-allowlist (razao-do-malformado {:tipo "a" :dados {:quando (java.util.Date.)}})))
  (is (= :tipo-fora-da-allowlist (razao-do-malformado (->UmRecord "a" {}))) "record nao e' mapa puro")
  (is (= :tipo-fora-da-allowlist (razao-do-malformado {:tipo "a" :dados {:o (Object.)}})))
  (is (= :tipo-fora-da-allowlist (razao-do-malformado {:tipo "a" :dados {:f inc}})))
  (is (some? (razao-do-malformado {:tipo "a" :dados {(keyword "chave com espaco") 1}}))
      "o que nao rele identico nao e' gravado"))
