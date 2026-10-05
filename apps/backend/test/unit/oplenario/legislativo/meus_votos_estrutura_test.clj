(ns oplenario.legislativo.meus-votos-estrutura-test
  "ESTRUTURAL — a leitura autenticada do voto do proprio vereador ('Minha atuacao', GET /meu/votos) e' uma porta
  SEPARADA da publica, e as duas regras nao se misturam. O voto de sessao secreta ou fechada ao publico so' sai por
  aqui, para o dono; a regra publica (`voto_publico_estrutura_test`: toda leitura de `transparencia.voto_parlamentar`
  passa por `da-votacao-publica`) segue SEM excecao porque esta leitura nem toca o read-model.

  Heuristica de texto — prende a forma de hoje; a prova do comportamento e' `meus_votos_test` (integracao)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)))

(defn- clj-sob [raiz]
  (->> (file-seq (io/file raiz))
       (filter #(.isFile ^File %))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (map #(.getPath ^File %))))

(defn- sem-comentarios
  "So' o CODIGO importa para o que e' lido: tira os literais de string (docstrings e textos explicam a regra e citam
  os nomes proibidos) e depois os `;` de linha."
  [texto]
  (->> (str/replace texto #"\"(?:[^\"\\]|\\.)*\"" "\"\"")
       str/split-lines
       (map #(str/replace % #";.*$" ""))
       (str/join "\n")))

(deftest a-leitura-do-vereador-vem-da-fonte-nunca-do-read-model-publico-nem-do-sigilo
  (let [codigo (sem-comentarios (slurp "src/oplenario/legislativo/db/meus_votos.clj"))]
    (testing "le legislativo.votos (a fonte), que so' tem voto NOMINAL"
      (is (str/includes? codigo ":legislativo.votos")))
    (testing "nunca o read-model publico: a regra de `da-votacao-publica` fica sem excecao"
      (is (not (str/includes? codigo "voto_parlamentar")))
      (is (not (str/includes? codigo "transparencia"))))
    (testing "nunca o voto secreto: nao ha' como (nem se deve) ligar voto a votante"
      (is (not (str/includes? codigo "votos_secretos"))))))

(deftest o-modulo-publico-nao-importa-a-leitura-do-vereador
  (doseq [arquivo (clj-sob "src/oplenario/transparencia")]
    (is (not (str/includes? (slurp arquivo) "meus-votos"))
        (str arquivo " importa/cita a leitura autenticada do voto do vereador: o portal nao pode ter acesso a ela"))))

(deftest o-handler-so-conhece-o-vereador-pelo-ator
  (let [texto (slurp "src/oplenario/legislativo/diplomat/http/in.clj")
        [_ handler] (re-find #"(?s)(\(defn- meus-votos-handler.*?)\n\(defn- acusar-ciencia-handler" texto)
        codigo (sem-comentarios handler)]
    (is (some? handler) "o handler de GET /meu/votos existe")
    (is (str/includes? codigo "(:ator req)") "o ator e' a unica fonte do vereador")
    (doseq [fonte [":path-params" ":query-params" ":json-params" ":params" ":form-params"]]
      (is (not (str/includes? codigo fonte))
          (str "o handler le " fonte ": nenhum id do request pode escolher de quem sao os votos")))))

(deftest a-rota-exige-o-papel-vereador
  (let [texto (slurp "src/oplenario/legislativo/diplomat/http/in.clj")
        linha (re-find #"\[\"/meu/votos\" :get \[[^\n]*\n" texto)]
    (is (some? linha) "a rota GET /meu/votos existe")
    (is (str/includes? linha "papel-vereador"))))
