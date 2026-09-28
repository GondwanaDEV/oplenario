(ns oplenario.transparencia.dados-abertos-logic-test
  "Unit: a serializacao CSV dos dados abertos (RFC 4180) e o dicionario dos datasets."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.transparencia.logic.dados-abertos :as da]))

(def votos (da/por-arquivo "votos-nominais.csv"))

(deftest csv-rfc-4180-com-bom-e-crlf
  (let [id (random-uuid)
        csv (da/->csv votos [{:votacao-id id :ocorrido-em (java.time.Instant/parse "2026-09-10T17:00:00Z")
                              :proposicao-id nil :materia-tipo "projeto_lei" :materia-sequencial 7 :materia-ano 2026
                              :vereador-id id :voto "sim"}]
                      {id "Helena \"Past\", a vereadora"})]
    (is (str/starts-with? csv "﻿votacao_id,ocorrido_em,proposicao_id,materia,vereador_id,vereador,voto\r\n")
        "BOM (planilha reconhece o UTF-8) e o cabecalho com os nomes do dicionario")
    (is (str/includes? csv ",projeto_lei 7/2026,") "a materia montada como tipo numero/ano")
    (is (str/includes? csv ",\"Helena \"\"Past\"\", a vereadora\",sim\r\n")
        "virgula e aspas no valor: entre aspas, aspas dobradas")
    (is (str/includes? csv "2026-09-10T17:00:00Z,,projeto_lei") "nil vira campo vazio, instante em ISO 8601")))

(deftest quebra-de-linha-na-ementa-nao-quebra-o-registro
  (let [csv (da/->csv (da/por-arquivo "proposicoes.csv") [{:ementa "Primeira linha\nsegunda" :tipo "pl"}] {})]
    (is (str/includes? csv "\"Primeira linha\nsegunda\""))
    (is (= 2 (count (re-seq #"\r\n" csv))) "cabecalho + 1 registro: a quebra interna nao e' CRLF de registro")))

(deftest dicionario-completo
  (testing "todo dataset tem arquivo .csv e cada coluna tem descricao"
    (doseq [d da/datasets]
      (is (str/ends-with? (:arquivo d) ".csv"))
      (is (every? (fn [[nome descricao g]] (and (seq nome) (>= (count descricao) 10) (or (keyword? g) (fn? g))))
                  (:colunas d))
          (:chave d))))
  (is (= ["proposicoes" "legislacao" "votos-nominais"] (map :chave da/datasets))))
