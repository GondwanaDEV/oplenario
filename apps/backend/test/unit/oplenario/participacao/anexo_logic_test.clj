(ns oplenario.participacao.anexo-logic-test
  "UNIT (puro) — os ANEXOS do atendimento ao cidadao: a allowlist de tipos (extensao E tipo declarado, coerentes), a chave
  no object storage (a convencao que a exportacao e o apagamento da Casa descobrem), o ULTIMO ato de resposta (a resposta,
  o indeferimento e, no e-SIC, a decisao do recurso; na ouvidoria o arquivamento NAO conta) e a janela de 10 minutos."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.encerramento.arquivos :as arquivos]
            [oplenario.participacao.logic.anexo :as anexo])
  (:import (java.time Instant)))

(defn- t [s] (Instant/parse s))

;; ---------- o tipo: extensao E tipo declarado ----------

(deftest as-nove-extensoes-aceitas-com-o-tipo-canonico
  (is (= #{"pdf" "png" "jpg" "jpeg" "txt" "csv" "docx" "xlsx" "odt" "ods"} (set (keys anexo/tipos-aceitos)))
      "PDF, PNG, JPEG (.jpg e .jpeg), TXT, CSV, DOCX, XLSX, ODT, ODS")
  (doseq [[nome declarado canonico]
          [["folha.pdf" "application/pdf" "application/pdf"]
           ["foto.png" "image/png" "image/png"]
           ["foto.jpg" "image/jpeg" "image/jpeg"]
           ["foto.JPEG" "image/jpeg" "image/jpeg"]
           ["nota.txt" "text/plain" "text/plain"]
           ["dados.csv" "text/csv" "text/csv"]
           ["oficio.docx" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"]
           ["planilha.xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"]
           ["oficio.odt" "application/vnd.oasis.opendocument.text" "application/vnd.oasis.opendocument.text"]
           ["planilha.ods" "application/vnd.oasis.opendocument.spreadsheet" "application/vnd.oasis.opendocument.spreadsheet"]]]
    (is (= canonico (anexo/classificar nome declarado)) nome)))

(deftest o-csv-do-excel-no-windows-passa-com-o-tipo-canonico
  (is (= "text/csv" (anexo/classificar "exportado.csv" "application/vnd.ms-excel"))
      "o navegador no Windows declara vnd.ms-excel para .csv: grava e serve como text/csv"))

(deftest tipo-fora-da-lista-ou-incoerente-nao-passa
  (testing "extensao fora da lista, mesmo com tipo de documento"
    (doseq [[nome declarado] [["programa.exe" "application/pdf"] ["planilha.xls" "application/vnd.ms-excel"]
                              ["pagina.html" "text/html"] ["arquivo.zip" "application/zip"] ["script.js" "text/javascript"]
                              ["oficio.doc" "application/msword"] ["semextensao" "application/pdf"] ["termina." "application/pdf"]]]
      (is (nil? (anexo/classificar nome declarado)) nome)))
  (testing "extensao na lista, tipo declarado de OUTRA coisa (incoerente) ou desconhecido"
    (doseq [[nome declarado] [["folha.pdf" "text/plain"] ["foto.png" "image/jpeg"] ["foto.jpg" "image/png"]
                              ["dados.csv" "application/pdf"] ["oficio.docx" "application/pdf"]
                              ["folha.pdf" "application/octet-stream"] ["nota.txt" "text/html"]
                              ["planilha.xlsx" "application/vnd.ms-excel"]]]
      (is (nil? (anexo/classificar nome declarado)) (str nome " declarado " declarado))))
  (testing "a extensao e' a ULTIMA: pdf.exe e' exe; exe.pdf e' pdf"
    (is (nil? (anexo/classificar "folha.pdf.exe" "application/pdf")))
    (is (= "application/pdf" (anexo/classificar "exe.pdf" "application/pdf")))))

(deftest a-extensao
  (is (= "pdf" (anexo/extensao "a.PDF")))
  (is (= "gz" (anexo/extensao "a.tar.gz")))
  (is (nil? (anexo/extensao "sem")))
  (is (nil? (anexo/extensao "termina.")))
  (is (nil? (anexo/extensao nil))))

;; ---------- a chave ----------

(deftest a-chave-segue-a-convencao-do-apagamento-da-casa
  (let [ente (random-uuid) protocolo (random-uuid) id (random-uuid)
        chave (anexo/chave-do-anexo ente protocolo id)]
    (is (= (str "atendimento/" ente "/" protocolo "/" id) chave))
    (is (arquivos/da-convencao? ente chave) "a exportacao e o apagamento a descobrem pelo ente no caminho")
    (is (not (arquivos/da-convencao? (random-uuid) chave)) "nunca o blob de outra Casa")))

;; ---------- o ultimo ato de resposta ----------

(deftest ultimo-ato-de-resposta
  (testing "sem resposta: nil"
    (is (nil? (anexo/ultimo-ato-de-resposta :esic {:estado "protocolado" :respostas []})))
    (is (nil? (anexo/ultimo-ato-de-resposta :lgpd {:estado "protocolada" :respostas nil}))))
  (testing "a resposta (e o indeferimento, que e' gravado como resposta)"
    (is (= (t "2026-07-03T12:00:00Z")
           (anexo/ultimo-ato-de-resposta :esic {:estado "indeferido" :respostas [{:respondida-em (t "2026-07-03T12:00:00Z")}]})))
    (is (= (t "2026-07-03T12:00:00Z")
           (anexo/ultimo-ato-de-resposta :lgpd {:estado "respondida" :respostas [{:respondida-em (t "2026-07-03T12:00:00Z")}]}))))
  (testing "no e-SIC, a DECISAO do recurso e' o ultimo ato quando e' mais recente"
    (is (= (t "2026-07-09T09:00:00Z")
           (anexo/ultimo-ato-de-resposta
            :esic {:estado "respondido"
                   :respostas [{:respondida-em (t "2026-07-03T12:00:00Z")}]
                   :recurso {:respostas [{:respondida-em (t "2026-07-09T09:00:00Z")}]}}))))
  (testing "recurso pendente (sem decisao ainda): vale a resposta ao pedido"
    (is (= (t "2026-07-03T12:00:00Z")
           (anexo/ultimo-ato-de-resposta :esic {:estado "respondido" :respostas [{:respondida-em (t "2026-07-03T12:00:00Z")}]
                                                :recurso {:respostas []}}))))
  (testing "ouvidoria: a resposta conta; o ARQUIVAMENTO (mesma tabela) nao — encerrar sem resposta de merito nao tem documento"
    (is (= (t "2026-07-03T12:00:00Z")
           (anexo/ultimo-ato-de-resposta :ouvidoria {:estado "respondida" :respostas [{:respondida-em (t "2026-07-03T12:00:00Z")}]})))
    (is (nil? (anexo/ultimo-ato-de-resposta :ouvidoria {:estado "arquivada"
                                                        :respostas [{:respondida-em (t "2026-07-03T12:00:00Z")}]})))))

;; ---------- a janela e o limite ----------

(deftest janela-de-dez-minutos
  (let [ato (t "2026-07-03T12:00:00Z")]
    (is (true? (anexo/na-janela? ato (t "2026-07-03T12:00:00Z"))))
    (is (true? (anexo/na-janela? ato (t "2026-07-03T12:10:00Z"))) "o proprio minuto 10 vale")
    (is (false? (anexo/na-janela? ato (t "2026-07-03T12:10:01Z"))))
    (is (false? (anexo/na-janela? nil (t "2026-07-03T12:00:00Z"))) "sem ato de resposta, nunca")))

(deftest pode-anexar-so-dentro-da-janela-e-abaixo-do-limite
  (let [ato (t "2026-07-03T12:00:00Z") dentro (t "2026-07-03T12:05:00Z") fora (t "2026-07-03T12:11:00Z")]
    (is (true? (anexo/pode-anexar? ato 0 dentro)))
    (is (true? (anexo/pode-anexar? ato 4 dentro)))
    (is (false? (anexo/pode-anexar? ato 5 dentro)) "5 da Casa: o limite")
    (is (false? (anexo/pode-anexar? ato 0 fora)))
    (is (false? (anexo/pode-anexar? nil 0 dentro)))
    (is (= 5 anexo/max-anexos-da-casa))
    (is (= (* 10 1024 1024) anexo/max-bytes-anexo))))

(deftest so-os-anexos-da-casa-contam-para-o-limite
  (is (= 2 (count (anexo/da-casa [{:origem "casa"} {:origem "requerente"} {:origem "casa"} {:origem "requerente"}])))))

;; ---------- o requerente anexa ao proprio pedido (origem `requerente`) ----------

(deftest janela-do-requerente-conta-do-protocolo
  (let [recibo (t "2026-07-03T12:00:00Z")]
    (is (true? (anexo/na-janela-do-requerente? recibo (t "2026-07-03T12:00:00Z"))))
    (is (true? (anexo/na-janela-do-requerente? recibo (t "2026-07-03T12:10:00Z"))) "o proprio minuto 10 vale")
    (is (false? (anexo/na-janela-do-requerente? recibo (t "2026-07-03T12:10:01Z"))))
    (is (false? (anexo/na-janela-do-requerente? nil (t "2026-07-03T12:00:00Z"))) "sem recibo, nunca")))

(deftest pode-anexar-do-requerente-so-dentro-da-janela-e-abaixo-do-limite
  (let [recibo (t "2026-07-03T12:00:00Z") dentro (t "2026-07-03T12:05:00Z") fora (t "2026-07-03T12:11:00Z")]
    (is (= 5 anexo/max-anexos-do-requerente))
    (is (true? (anexo/pode-anexar-requerente? recibo 0 dentro)))
    (is (true? (anexo/pode-anexar-requerente? recibo 4 dentro)))
    (is (false? (anexo/pode-anexar-requerente? recibo 5 dentro)) "5 do requerente: o limite")
    (is (false? (anexo/pode-anexar-requerente? recibo 0 fora)))))

(deftest o-limite-e-por-origem
  (let [anexos [{:origem "casa"} {:origem "requerente"} {:origem "casa"} {:origem "requerente"} {:origem "requerente"}]]
    (is (= 2 (count (anexo/da-origem anexos "casa"))))
    (is (= 3 (count (anexo/da-origem anexos "requerente"))))
    (is (= (anexo/da-casa anexos) (anexo/da-origem anexos "casa")))))
