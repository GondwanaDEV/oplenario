(ns oplenario.transparencia.voto-publico-estrutura-test
  "ESTRUTURAL — o voto por vereador so' sai do portal pela porta `db.parlamentar/da-votacao-publica`.

  O read-model `transparencia.voto_parlamentar` guarda tambem o voto nominal de sessao secreta ou fechada ao publico
  (a projecao nao conhece a sessao). Quem LE a tabela para uma saida publica tem de filtrar pelas votacoes de sessao
  publica que o host entrega. Este teste varre o fonte e falha se: (1) um arquivo fora dos dois de leitura passar a
  citar a tabela; (2) uma funcao dos dois de leitura ler a tabela sem usar `da-votacao-publica`. Heuristica de texto —
  prende a forma de hoje; a prova do comportamento e' `voto_de_sessao_secreta_test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [honey.sql :as sql]
            [oplenario.transparencia.db.parlamentar :as parlamentar])
  (:import (java.io File)))

(def ^:private tabela "voto_parlamentar")

(def ^:private arquivos-de-leitura
  #{"src/oplenario/transparencia/db/parlamentar.clj" "src/oplenario/transparencia/db/dados_abertos.clj"})

(def ^:private fora-da-regra
  "Quem pode citar a tabela sem ler voto para saida publica: a escrita da projecao (mesmo arquivo de leitura, funcao
  `registrar-voto!`) e o Repo (so' docstring e a delegacao as funcoes acima)."
  #{"src/oplenario/transparencia/components/repositorio.clj"})

(defn- clj-sob [raiz]
  (->> (file-seq (io/file raiz))
       (filter #(.isFile ^File %))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (map #(.getPath ^File %))))

(defn- defns
  "[[nome texto]] de cada defn/defn- de topo do arquivo (corte textual em `\n(defn`)."
  [texto]
  (->> (str/split texto #"\n(?=\(defn-? )")
       (keep (fn [bloco] (when-let [[_ nome] (re-find #"^\(defn-? (\S+)" bloco)] [nome bloco])))))

(deftest so-os-dois-arquivos-de-leitura-citam-a-tabela
  (let [citam (->> (clj-sob "src/oplenario")
                   (filter #(str/includes? (slurp %) tabela))
                   set)]
    (is (= (into arquivos-de-leitura fora-da-regra) citam)
        (str "um arquivo novo cita `transparencia.voto_parlamentar`: se ele le voto para uma saida publica, tem de "
             "passar por `parlamentar/da-votacao-publica` — e entrar na lista deste teste. Citam: " (pr-str citam)))))

(deftest toda-leitura-da-tabela-passa-por-da-votacao-publica
  (doseq [arquivo arquivos-de-leitura
          [nome texto] (defns (slurp arquivo))
          :when (and (str/includes? texto tabela)
                     (not= "registrar-voto!" nome)
                     (not= "da-votacao-publica" nome))]
    (testing (str arquivo " / " nome)
      (is (str/includes? texto "da-votacao-publica")
          (str "`" nome "` le `transparencia.voto_parlamentar` sem `da-votacao-publica`: o voto de sessao secreta "
               "ou fechada ao publico sairia")))))

(deftest o-predicado-e-fail-closed
  (let [ids (hash-set (random-uuid) (random-uuid))
        [onde & params] (sql/format {:select [:*] :from [:t] :where (parlamentar/da-votacao-publica :votacao_id ids)})]
    (testing "com votacoes publicas: `= ANY` sobre o conjunto"
      (is (str/includes? onde "votacao_id = ANY"))
      (is (= 1 (count params))))
    (testing "conjunto vazio ou nil: o WHERE nunca casa linha nenhuma"
      (doseq [vazio [#{} nil []]]
        (let [[onde & params] (sql/format {:select [:*] :from [:t]
                                           :where (parlamentar/da-votacao-publica :votacao_id vazio)})]
          (is (str/includes? onde "1 = 0"))
          (is (empty? params)))))))
