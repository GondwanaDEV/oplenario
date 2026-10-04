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
                              ["nota.txt" "text/html"]
                              ["planilha.xlsx" "application/vnd.ms-excel"]]]
      (is (nil? (anexo/classificar nome declarado)) (str nome " declarado " declarado))))
  (testing "a extensao e' a ULTIMA: pdf.exe e' exe; exe.pdf e' pdf"
    (is (nil? (anexo/classificar "folha.pdf.exe" "application/pdf")))
    (is (= "application/pdf" (anexo/classificar "exe.pdf" "application/pdf")))))

(deftest tipo-declarado-vazio-ou-octet-stream-vale-quando-a-extensao-esta-na-lista
  ;; o navegador que nao conhece .odt/.docx declara octet-stream (ou nada, e a borda normaliza para octet-stream): a
  ;; extensao manda, e a ASSINATURA do conteudo e' que barra o impostor (ver `assinatura-confere?`)
  (doseq [[nome canonico] [["oficio.odt" "application/vnd.oasis.opendocument.text"]
                           ["oficio.docx" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"]
                           ["folha.pdf" "application/pdf"] ["nota.txt" "text/plain"]]]
    (is (= canonico (anexo/classificar nome "application/octet-stream")) nome))
  (is (nil? (anexo/classificar "programa.exe" "application/octet-stream")) "extensao fora da lista: nunca")
  (is (nil? (anexo/classificar "pagina.html" "application/octet-stream")))
  (is (nil? (anexo/classificar "folha.pdf" "text/html")) "octet-stream e' o unico 'nao sei' aceito; outro tipo incoerente segue recusado"))

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

;; ---------- a ASSINATURA do conteudo contra a extensao ----------

(defn- bs ^bytes [& xs] (byte-array (map unchecked-byte xs)))
(defn- txt ^bytes [^String x] (.getBytes x "UTF-8"))

(deftest assinatura-do-conteudo-confere-com-a-extensao
  (testing "PDF: %PDF- na abertura (ate' o primeiro KiB, a tolerancia da especificacao)"
    (is (true? (anexo/assinatura-confere? "pdf" (txt "%PDF-1.7\n..."))))
    (is (true? (anexo/assinatura-confere? "pdf" (txt (str "lixo\n" "%PDF-1.4")))) "preambulo curto antes do cabecalho")
    (is (false? (anexo/assinatura-confere? "pdf" (txt (str (apply str (repeat 1100 "x")) "%PDF-1.4"))))
        "depois do primeiro KiB, nao e' mais um PDF")
    (is (false? (anexo/assinatura-confere? "pdf" (txt "MZ\u0090 this program cannot be run in DOS mode")))))
  (testing "PNG e JPEG: os bytes magicos"
    (is (true? (anexo/assinatura-confere? "png" (bs 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A 0 0))))
    (is (false? (anexo/assinatura-confere? "png" (txt "%PDF-1.4"))))
    (is (true? (anexo/assinatura-confere? "jpg" (bs 0xFF 0xD8 0xFF 0xE0 0))))
    (is (true? (anexo/assinatura-confere? "jpeg" (bs 0xFF 0xD8 0xFF 0xDB 0))))
    (is (false? (anexo/assinatura-confere? "jpg" (bs 0x89 0x50 0x4E 0x47)))))
  (testing "DOCX/XLSX/ODT/ODS sao ZIP: PK\\x03\\x04"
    (doseq [ext ["docx" "xlsx" "odt" "ods"]]
      (is (true? (anexo/assinatura-confere? ext (bs 0x50 0x4B 0x03 0x04 0x14 0))) ext)
      (is (false? (anexo/assinatura-confere? ext (txt "%PDF-1.4"))) ext)
      (is (false? (anexo/assinatura-confere? ext (bs 0x50 0x4B 0x05 0x06))) (str ext " zip vazio nao e' documento"))))
  (testing "TXT e CSV: texto, sem byte NUL nos primeiros 8 KiB"
    (is (true? (anexo/assinatura-confere? "txt" (txt "ola\nmundo"))))
    (is (true? (anexo/assinatura-confere? "csv" (txt "a;b\n1;2"))))
    (is (false? (anexo/assinatura-confere? "txt" (bs 0x4D 0x5A 0x90 0x00 0x03))) "MZ... com NUL")
    (is (false? (anexo/assinatura-confere? "csv" (bs 0x61 0x00 0x62))))
    (is (true? (anexo/assinatura-confere? "txt" (byte-array (concat (repeat 8192 (byte 0x61)) [(byte 0)]))))
        "o NUL depois dos 8 KiB nao conta: so' o comeco e' examinado"))
  (testing "extensao fora da lista, ou sem extensao: nunca confere"
    (is (false? (anexo/assinatura-confere? "exe" (txt "MZ"))))
    (is (false? (anexo/assinatura-confere? nil (txt "%PDF-"))))))

;; ---------- a cota de disco do cidadao (origem requerente) ----------

(deftest cota-de-disco-do-requerente
  (is (= (* 100 1024 1024) anexo/cota-do-requerente-bytes) "100 MB nas ultimas 24 h")
  (is (= (java.time.Duration/ofHours 24) anexo/janela-da-cota))
  (is (false? (anexo/cota-estourada? 0 anexo/max-bytes-anexo)))
  (is (false? (anexo/cota-estourada? (- anexo/cota-do-requerente-bytes 10) 10)) "chegar ao teto, sem passar, cabe")
  (is (true? (anexo/cota-estourada? (- anexo/cota-do-requerente-bytes 10) 11)))
  (is (true? (anexo/cota-estourada? anexo/cota-do-requerente-bytes 1))))

;; ---------- o anexo retirado nao conta para o limite ----------

(deftest anexo-retirado-nao-conta-para-o-limite-de-cinco
  (let [anexos [{:origem "requerente"} {:origem "requerente" :retirado-em (t "2026-07-03T12:00:00Z")} {:origem "casa"}
                {:origem "casa" :retirado-em (t "2026-07-03T12:00:00Z")}]]
    (is (= 1 (count (anexo/da-origem anexos "requerente"))))
    (is (= 1 (count (anexo/da-casa anexos))))
    (is (true? (anexo/pode-anexar-requerente? (t "2026-07-03T12:00:00Z") (count (anexo/da-origem anexos "requerente")) (t "2026-07-03T12:01:00Z"))))))
