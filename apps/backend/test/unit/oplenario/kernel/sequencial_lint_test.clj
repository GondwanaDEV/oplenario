(ns oplenario.kernel.sequencial-lint-test
  "Lint ESTRUTURAL (gate de CI; mesmo racional de `estrutura-lint-test` e `arquitetura-test`): nenhum
  teste pode TRUNCAR `shared.sequencial`.

  Por que isto merece um gate de maquina, e nao um comentario: `shared.sequencial` guarda o contador
  gapless de TODOS os entes, e o valor dele so' faz sentido em relacao as linhas ja' numeradas nas
  tabelas de modulo (`legislativo.proposicoes.sequencial`, `participacao.pedido_esic.sequencial`, ...).
  Um TRUNCATE sem escopo apaga METADE desse par de invariante: os contadores somem, as linhas
  numeradas ficam. `sequencial/proximo!` entao volta a 1 e COLIDE com a UNIQUE `(ente, ano, sequencial)`
  a cada chamada — e como a colisao aborta a transacao inteira, o proprio incremento do contador reverte
  junto. **Nao se cura sozinho: e' 500 deterministico e permanente**, em toda numeracao do ente.

  Foi exatamente o que aconteceu neste projeto (achado da T2 grupo B, `docs/16-ledger-prontidao.md`):
  a Casa da demo ficou com 8 escopos de proposicao numerados ate 15 e ZERO contadores, e as tres portas
  de entrada do cidadao (e-SIC, LGPD, ouvidoria) passaram a devolver 500 para qualquer submissao.

  O TRUNCATE nem era necessario: os testes de `sequencial` ja' isolam por `(random-uuid)` como ente."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.io File)))

(def ^:private este-arquivo
  "O proprio lint cita o padrao que proibe (docstring + mensagem de falha). Sem esta exclusao o gate
  nunca poderia passar — e um gate impossivel de satisfazer e' um gate que sera' enfraquecido."
  "sequencial_lint_test.clj")

(defn- arquivos-clj-sob [raiz]
  (->> (file-seq (io/file raiz))
       (filter #(.isFile ^File %))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (remove #(= este-arquivo (.getName ^File %)))))

(defn- trunca-sequencial?
  "Linhas que truncam `shared.sequencial`. Casa `TRUNCATE ... shared.sequencial` na MESMA linha
  (a forma que todo fixture da casa usa: uma string SQL de uma linha em `jdbc/execute!`)."
  [^File f]
  (->> (str/split-lines (slurp f))
       (map-indexed (fn [i l] [(inc i) l]))
       (remove (fn [[_ l]] (str/starts-with? (str/trim l) ";")))   ; comentario nao executa SQL
       (filter (fn [[_ l]]
                 (and (re-find #"(?i)\bTRUNCATE\b" l)
                      (re-find #"(?i)shared\.sequencial" l))))
       (mapv (fn [[n l]] (str (.getPath f) ":" n " " (str/trim l))))))

(deftest nenhum-teste-trunca-shared-sequencial
  (let [ofensores (mapcat trunca-sequencial? (arquivos-clj-sob "test"))]
    (is (empty? ofensores)
        (str "sequencial-lint: TRUNCATE de `shared.sequencial` apaga o contador de TODOS os entes e "
             "deixa as linhas ja' numeradas para tras — `proximo!` volta a 1, colide na UNIQUE e a "
             "colisao reverte o proprio incremento. 500 permanente em toda numeracao do ente. "
             "Isole por ente (`random-uuid`) em vez de truncar. Ofensores: " (pr-str ofensores)))))
