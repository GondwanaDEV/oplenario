(ns oplenario.legislativo.gerador-publicacao-test
  "UNIT (puro) — F6c Slice 4a: o RENDERIZADOR PROPRIO do artefato de publicacao oficial ('DO-lite', doc-mestre
  L287). Projeta os dados JA RESOLVIDOS (metadados da norma + texto legal integral) num documento intermediario;
  serializacao/assinatura sao dos ports. Fail-closed: campo do ato nao resolvido LANCA (documento legal nao sai
  com campo em branco — §5 'incidente inaceitavel')."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.gerador-publicacao :as ger]))

(def ^:private dados-ok
  {:especie "lei" :numero 42 :ano 2026 :urn "urn:lex:br;ce;fortaleza:lei:2026-06-28;42"
   :ementa "Dispoe sobre X" :publicado-em "2026-06-28T12:00:00Z"
   :veiculo "Diario Oficial do Municipio"
   :corpo "Art. 1o Esta lei entra em vigor na data de sua publicacao."})

(deftest renderiza-o-ato-completo
  (let [doc (ger/renderizar dados-ok)]
    (is (= "do-lite-v0" (:spec-versao doc)) "carimba a versao do renderizador")
    (is (= "lei" (:especie doc)))
    (is (= 42 (:numero doc)))
    (is (= 2026 (:ano doc)))
    (is (= "urn:lex:br;ce;fortaleza:lei:2026-06-28;42" (:urn doc)))
    (is (= "Dispoe sobre X" (:ementa doc)))
    (is (= "Diario Oficial do Municipio" (:veiculo doc)))
    (is (= "2026-06-28T12:00:00Z" (:publicado-em doc)))
    (is (= (:corpo dados-ok) (:corpo doc)) "o texto legal integral entra no documento")))

(deftest deterministico
  (is (= (ger/renderizar dados-ok) (ger/renderizar dados-ok)) "mesma entrada -> mesmo documento"))

;; ---------- fail-closed: cada campo do ato e' obrigatorio ----------

(deftest ementa-ausente-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo do artefato de publicacao nao resolvido"
        (ger/renderizar (dissoc dados-ok :ementa)))
      "ementa ausente -> LANCA"))

(deftest corpo-ausente-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo do artefato de publicacao nao resolvido"
        (ger/renderizar (dissoc dados-ok :corpo)))
      "texto legal ausente -> LANCA (artefato legal vazio)"))

(deftest corpo-branco-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo do artefato de publicacao nao resolvido"
        (ger/renderizar (assoc dados-ok :corpo "   ")))
      "texto branco = nao resolvido -> LANCA"))

(deftest numero-nil-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo do artefato de publicacao nao resolvido"
        (ger/renderizar (assoc dados-ok :numero nil)))
      "numero nil -> LANCA"))

(deftest veiculo-branco-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"campo do artefato de publicacao nao resolvido"
        (ger/renderizar (assoc dados-ok :veiculo "")))
      "veiculo vazio -> LANCA (a publicacao precisa do veiculo)"))
