(ns oplenario.kernel.arquivo-test
  "UNIT (puro) — o que e' comum a todo modulo que recebe arquivo (kernel/arquivo): o nome de exibicao seguro, o tipo
  declarado e o cabecalho do download SEMPRE como arquivo. Nasceu em comunicacao e foi compartilhado com os anexos do
  atendimento; o comportamento nao mudou (os testes de comunicacao/logic seguem valendo)."
  (:require [clojure.test :refer [deftest is]]
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
  (is (= "application/octet-stream" (arquivo/tipo-de-midia nil))))

(deftest o-download-e-sempre-attachment-com-o-nome-em-ascii-e-utf-8
  (is (= "attachment; filename=\"folha.pdf\"; filename*=UTF-8''folha.pdf" (arquivo/content-disposition "folha.pdf")))
  (is (= "attachment; filename=\"relat_rio de gest_o.pdf\"; filename*=UTF-8''relat%C3%B3rio%20de%20gest%C3%A3o.pdf"
         (arquivo/content-disposition "relatório de gestão.pdf"))
      "o ASCII de reserva troca o acento; o UTF-8 (RFC 5987) o preserva, com espaco como %20")
  (is (.startsWith ^String (arquivo/content-disposition "x.txt") "attachment;") "nunca inline"))
