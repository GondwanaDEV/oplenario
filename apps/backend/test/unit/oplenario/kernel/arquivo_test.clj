(ns oplenario.kernel.arquivo-test
  "UNIT (puro) — o que e' comum a todo modulo que recebe arquivo (kernel/arquivo): o nome de exibicao seguro, o tipo
  declarado e o cabecalho do download SEMPRE como arquivo. Nasceu em comunicacao e foi compartilhado com os anexos do
  atendimento; o comportamento nao mudou (os testes de comunicacao/logic seguem valendo)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.kernel.arquivo :as arquivo]))

(deftest nome-de-arquivo-seguro
  (is (= "relatorio.pdf" (arquivo/nome-de-arquivo "C:\\Users\\x\\relatorio.pdf")))
  (is (= "ataque.txt" (arquivo/nome-de-arquivo "../../\"ataque\u0000.txt")))
  (is (= "anexo" (arquivo/nome-de-arquivo "   ")))
  (is (= "anexo" (arquivo/nome-de-arquivo nil)))
  (is (= 200 (count (arquivo/nome-de-arquivo (apply str (repeat 300 "a")))))))

(deftest tipo-de-midia-declarado
  (is (= "application/pdf" (arquivo/tipo-de-midia "application/pdf; charset=binary")))
  (is (= "text/csv" (arquivo/tipo-de-midia "TEXT/CSV")))
  (is (= "application/octet-stream" (arquivo/tipo-de-midia "isto nao e tipo")))
  (is (= "application/octet-stream" (arquivo/tipo-de-midia nil)))
  ;; as tres colunas `tipo_midia` (comunicacao, participacao, contas) tem CHECK de 200: acima disso o INSERT dava 500
  (is (= 200 (count (arquivo/tipo-de-midia (str (apply str (repeat 100 "a")) "/" (apply str (repeat 99 "b")))))))
  (is (= "application/octet-stream" (arquivo/tipo-de-midia (str (apply str (repeat 100 "a")) "/" (apply str (repeat 100 "b")))))))

(deftest o-download-e-sempre-attachment-com-o-nome-em-ascii-e-utf-8
  (is (= "attachment; filename=\"folha.pdf\"; filename*=UTF-8''folha.pdf" (arquivo/content-disposition "folha.pdf")))
  (is (= "attachment; filename=\"relat_rio de gest_o.pdf\"; filename*=UTF-8''relat%C3%B3rio%20de%20gest%C3%A3o.pdf"
         (arquivo/content-disposition "relatório de gestão.pdf"))
      "o ASCII de reserva troca o acento; o UTF-8 (RFC 5987) o preserva, com espaco como %20")
  (is (.startsWith ^String (arquivo/content-disposition "x.txt") "attachment;") "nunca inline"))

(deftest o-nome-perde-os-caracteres-invisiveis-e-de-formato
  ;; U+202E (RLO) faz "recibo\u202Efdp.exe" aparecer como "recibo exe.pdf": o nome e' conteudo de quem enviou e e'
  ;; mostrado a outra pessoa. ZWSP (U+200B), BOM (U+FEFF) e os controles C1 tambem saem.
  (is (= "recibofdp.exe" (arquivo/nome-de-arquivo "recibo\u202Efdp.exe")))
  (is (= "ab.pdf" (arquivo/nome-de-arquivo "a\u200Bb\uFEFF.pdf")))
  (is (= "ab.pdf" (arquivo/nome-de-arquivo "a\u0085b.pdf")) "controle C1 (NEL)")
  (is (= "anexo" (arquivo/nome-de-arquivo "\u202E\u200B")) "so' invisivel: cai no nome padrao"))

(deftest o-corte-em-200-preserva-a-extensao
  (let [n (arquivo/nome-de-arquivo (str (apply str (repeat 300 "a")) ".pdf"))]
    (is (= 200 (count n)))
    (is (.endsWith ^String n ".pdf") "a extensao sobrevive ao corte: o tipo do arquivo nao muda por causa do tamanho do nome")
    (is (= (str (apply str (repeat 196 "a")) ".pdf") n)))
  (testing "extensao absurda (mais de 20 caracteres) nao e' preservada: corta como antes"
    (let [n (arquivo/nome-de-arquivo (str "a." (apply str (repeat 300 "b"))))]
      (is (= 200 (count n))))))
