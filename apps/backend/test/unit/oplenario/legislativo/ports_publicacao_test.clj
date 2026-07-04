(ns oplenario.legislativo.ports-publicacao-test
  "UNIT — F6c Slice 4a: os ports do artefato de publicacao. SerializadorPublicacao (fixture) leva o documento
  intermediario ao formato fisico DETERMINISTICO ([GAP] layout real); AssinadorICP (stub) assina os bytes
  (assinatura DESTACADA; [GAP] ICP real, algoritmo='STUB-ICP-v0')."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.legislativo.components.assinador-icp :as assinador]
            [oplenario.legislativo.components.serializador-publicacao :as ser]))

(def ^:private doc
  {:spec-versao "do-lite-v0" :especie "lei" :numero 42 :ano 2026
   :urn "urn:lex:br;ce;fortaleza:lei:2026-06-28;42" :ementa "Dispoe sobre X"
   :publicado-em "2026-06-28T12:00:00Z" :veiculo "Diario Oficial do Municipio"
   :corpo "Art. 1o ..."})

(deftest serializa-deterministico-com-content-type
  (let [s (ser/serializador-fixture)
        {b1 :bytes ct :content-type} (ser/serializar s doc)
        {b2 :bytes} (ser/serializar s doc)]
    (is (= "text/plain; charset=utf-8" ct))
    (is (= (seq b1) (seq b2)) "mesma entrada -> mesmos bytes (hash estavel)")
    (let [txt (String. ^bytes b1 "UTF-8")]
      (is (str/includes? txt "LEI N. 42/2026") "cabecalho com especie/numero/ano")
      (is (str/includes? txt "urn:lex:br;ce;fortaleza:lei:2026-06-28;42") "URN")
      (is (str/includes? txt "Dispoe sobre X") "ementa")
      (is (str/includes? txt "Diario Oficial do Municipio") "veiculo")
      (is (str/includes? txt "Art. 1o") "o texto legal integral"))))

(deftest assina-deterministico-e-marca-stub
  (let [a (assinador/assinador-stub)
        conteudo (.getBytes "conteudo do ato" "UTF-8")
        r1 (assinador/assinar a conteudo)
        r2 (assinador/assinar a conteudo)]
    (is (= "STUB-ICP-v0" (:algoritmo r1)) "marca honestamente que NAO e' ICP real")
    (is (string? (:assinatura-b64 r1)))
    (is (= (:assinatura-b64 r1) (:assinatura-b64 r2)) "deterministico sobre o conteudo")
    (is (not= (:assinatura-b64 r1)
              (:assinatura-b64 (assinador/assinar a (.getBytes "outro conteudo" "UTF-8"))))
        "conteudo diferente -> assinatura diferente")))
