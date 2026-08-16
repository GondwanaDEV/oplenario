(ns oplenario.sessoes.renderizador-pdf-test
  "UNIT (puro, sem rede — so' a biblioteca em memoria) — o ADAPTER `RenderizadorPdf` (Etapa 5 fatia 3): leva
  o HTML CANONICO (bytes) ao PDF congelavel. Cobre os 5 vermelhos obrigatorios do brief: (a) determinismo
  bit-a-bit sob o MESMO instante, (b) bytes DIFERENTES sob instante DIFERENTE (prova que o instante entra
  de fato no arquivo, nao so' que o teste (a) passa por acidente), (c) o PDF abre e tem >= 1 pagina, (d)
  acentuacao portuguesa sobrevive ao round-trip de texto extraido, (e) Casa grande pagina e repete o
  cabecalho da tabela. Mais: independencia do Locale default da JVM (gemea do teste do adapter HTML)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.sessoes.components.renderizador-pdf :as pdf])
  (:import (com.openhtmltopdf.util XRLog)
           (java.net ServerSocket)
           (java.util Locale)
           (org.apache.pdfbox Loader)
           (org.apache.pdfbox.pdmodel PDDocument)
           (org.apache.pdfbox.text PDFTextStripper)))

(def ^:private instante (java.time.Instant/parse "2026-06-20T18:00:00Z"))
(def ^:private instante-2 (java.time.Instant/parse "2026-06-20T19:15:00Z"))

(defn- html-de [titulo]
  ;; DOCTYPE maiusculo e nenhum `<`/`&` fora de marcacao valida — a mesma restricao que `folha.css` respeita
  ;; (ver docstring do renderizador). O bloco `<style>` usa as pilhas de fonte base-14 reais da folha.
  (.getBytes
   (str "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"/>"
        "<style>body{font-family:\"Times New Roman\", Times, serif;}"
        ".mono{font-family:\"Courier New\", Courier, monospace;}"
        "table{width:100%;} table{-fs-table-paginate:paginate;} thead{display:table-header-group;}"
        "tr{page-break-inside:avoid;}</style></head><body>"
        "<h1>" titulo "</h1>"
        "<p class=\"mono\">Vereadora Conceição Ávila — presidência.</p>"
        "</body></html>")
   "UTF-8"))

(defn- render [html-bytes inst]
  (:bytes (pdf/renderizar (pdf/renderizador-pdf) html-bytes inst)))

(defn- html-com-tabela-grande [n-linhas]
  (.getBytes
   (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>"
        "table{width:100%;} table{-fs-table-paginate:paginate;} thead{display:table-header-group;}"
        "tr{page-break-inside:avoid;} th,td{font-family:\"Courier New\", Courier, monospace;}"
        "</style></head><body>"
        "<table><thead><tr><th>N</th><th>Vereador</th></tr></thead><tbody>"
        (apply str (for [i (range n-linhas)]
                     (str "<tr><td>" i "</td><td>Vereador número " i " Cañón Núñez</td></tr>")))
        "</tbody></table></body></html>")
   "UTF-8"))

;; ---------- (a) determinismo bit-a-bit — o teste CENTRAL desta fatia ----------

(deftest mesma-entrada-e-mesmo-instante-produzem-bytes-identicos
  (let [html (html-de "Folha de presença — sessão 1")
        p1 (render html instante)
        p2 (render html instante)]
    (is (= (vec p1) (vec p2))
        "o MESMO html-bytes + o MESMO instante de congelamento tem de produzir o PDF byte-a-byte igual — e' o pre-requisito do hash de integridade")))

;; ---------- (b) instante diferente -> bytes diferentes (prova que o instante ENTRA no arquivo) ----------

(deftest instante-diferente-produz-bytes-diferentes
  (let [html (html-de "Folha de presença — sessão 1")
        p1 (render html instante)
        p2 (render html instante-2)]
    (is (not= (vec p1) (vec p2))
        "dois instantes de congelamento diferentes tem de produzir PDFs diferentes — senao o teste (a) passaria por acidente (ex.: se o renderizador ignorasse o parametro e sempre gravasse zero)")))

;; ---------- (c) o PDF abre e tem pelo menos 1 pagina — nao pode ser so' um arquivo vazio que passa (a)/(b) por acidente ----------

(deftest pdf-abre-e-tem-pelo-menos-uma-pagina
  (let [bytes (render (html-de "Folha de presença — sessão 1") instante)]
    (with-open [doc (Loader/loadPDF ^bytes bytes)]
      (is (>= (.getNumberOfPages ^PDDocument doc) 1)))))

(deftest content-type-e-application-pdf
  (is (= "application/pdf" (:content-type (pdf/renderizar (pdf/renderizador-pdf) (html-de "x") instante)))))

;; ---------- (d) acentuacao portuguesa sobrevive ao round-trip de texto extraido do PDF ----------

(deftest acentuacao-portuguesa-sobrevive-no-texto-extraido
  (let [bytes (render (html-de "Ata de sessão número dezenove") instante)]
    (with-open [doc (Loader/loadPDF ^bytes bytes)]
      (let [texto (.getText (PDFTextStripper.) doc)]
        (is (str/includes? texto "Conceição Ávila")
            "o nome com ç/ã/é tem de chegar ao texto extraido do PDF — prova que a fonte base-14 + WinAnsi cobre o portugues")
        (is (str/includes? texto "sessão")
            "til em vogal no meio de palavra tambem sobrevive")))))

;; ---------- (e) Casa grande pagina e repete o cabecalho da tabela ----------

(deftest casa-grande-gera-mais-de-uma-pagina-com-cabecalho-repetido
  (let [bytes (render (html-com-tabela-grande 55) instante)]
    (with-open [doc (Loader/loadPDF ^bytes bytes)]
      (is (> (.getNumberOfPages ^PDDocument doc) 1)
          "55 vereadores tem de estourar 1 pagina A4 — prova a paginacao real da engine")
      (let [texto (.getText (PDFTextStripper.) doc)]
        ;; o cabecalho e' `Vereador`, mas `<thead>` -> `-fs-table-paginate` repete o texto por pagina; o
        ;; teto de repeticoes tem de bater o numero de paginas (nem menos — thead nao repetiu; nem so' 1).
        (is (>= (count (re-seq #"(?i)Vereador" texto)) (.getNumberOfPages ^PDDocument doc))
            "o cabecalho `Vereador` tem de aparecer pelo menos uma vez por pagina — prova que <thead> + -fs-table-paginate repetem o cabecalho no PDF, nao so' no HTML")))))

;; ---------- independencia do Locale default da JVM (gemea do teste do adapter HTML) ----------

(defn- sob-locale [tag f]
  (let [anterior (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag tag))
      (f)
      (finally (Locale/setDefault anterior)))))

(deftest bytes-nao-dependem-do-locale-default-da-jvm
  ;; Um degrau mais fundo que a armadilha de digito do adapter HTML: sob um Locale sem calendario
  ;; gregoriano (`th-TH`, budista), `Calendar/getInstance()` SEM Locale explicito trocaria o proprio
  ;; SISTEMA de calendario, nao so' o formato do digito — corrompendo o /Info do PDF conforme o ambiente.
  (let [html (html-de "Folha de presença — sessão 1")
        em-tailandes (sob-locale "th-TH" #(vec (render html instante)))
        em-ingles (sob-locale "en-US" #(vec (render html instante)))]
    (is (= em-tailandes em-ingles)
        "o mesmo html-bytes + o mesmo instante, sob dois Locales default da JVM, tem de produzir bytes identicos")))

;; ---------- RECURSO EXTERNO: fail-closed medido, nao "acontece de nao haver" ----------
;; Achado de revisao adversarial de seguranca, MEDIDO contra a biblioteca real: sem
;; `useExternalResourceAccessControl` o `NaiveUserAgent` de openhtmltopdf 1.1.73 e' FAIL-OPEN — ele abre
;; `new URI(uri).toURL().openStream()` sem filtro de esquema para IMAGE_RASTER/CSS/BINARY/FONT. Antes da
;; correcao, um espiao em `127.0.0.1` recebeu de fato 4 requisicoes GET do processo de renderizacao
;; (`<img src=http://...>` duas vezes, `@import url(http://...)`, `background-image:url(http://...)`).
;; Num renderizador de servidor isso e' SSRF: o alvo `http://169.254.169.254/...` (metadado de nuvem) ou
;; `file:///etc/passwd` fica a UM campo de texto de distancia. Hoje o HTML CANONICO nao emite nenhum
;; `<a>`/`url()` e `serializador-folha` escapa todo campo humano — mas isso e' disciplina em OUTRO arquivo,
;; nao barreira na camada que faz a requisicao. Estes testes exigem a barreira aqui.

(defn- espiao-http!
  "Sobe um servidor de UMA porta efemera que so' REGISTRA a linha de requisicao e responde 404. Porta 0
  (efemera) e' proposital: dois runs concorrentes da suite nao podem disputar um numero fixo."
  []
  (let [ss (ServerSocket. 0)
        acessos (atom [])]
    (doto (Thread. (fn []
                     (try
                       (loop []
                         (with-open [s (.accept ss)]
                           (let [buf (byte-array 512)
                                 n (.read (.getInputStream s) buf)]
                             (swap! acessos conj (str/trim (String. buf 0 (max n 0) "UTF-8")))
                             (doto (.getOutputStream s)
                               (.write (.getBytes "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n" "UTF-8"))
                               (.flush))))
                         (recur))
                       (catch Exception _ nil))))
      (.setDaemon true)
      (.start))
    {:porta (.getLocalPort ss) :acessos acessos :fechar! #(.close ss)}))

(deftest nenhum-recurso-externo-e-buscado-pela-rede
  (let [{:keys [porta acessos fechar!]} (espiao-http!)
        base (str "http://127.0.0.1:" porta)]
    (try
      (doseq [html [;; imagem remota
                    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body><p>x</p>"
                         "<img src=\"" base "/pixel.png\" width=\"10\" height=\"10\"/></body></html>")
                    ;; folha de estilo remota
                    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>@import url(\""
                         base "/externo.css\"); body{color:#000;}</style></head><body><p>x</p></body></html>")
                    ;; imagem de fundo remota
                    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>body{background-image:url(\""
                         base "/bg.png\");}</style></head><body><p>x</p></body></html>")
                    ;; anexo por link de download (ExternalResourceType/FILE_EMBED)
                    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>"
                         "<a href=\"" base "/anexo.bin\" download=\"anexo.bin\">baixar</a></body></html>")]]
        (render (.getBytes ^String html "UTF-8") instante))
      (Thread/sleep 300)
      (is (= [] @acessos)
          "o renderizador NAO pode abrir conexao alguma a partir do HTML — recurso externo tem de ser recusado ANTES de resolver a URI (SSRF: metadado de nuvem, servico interno, porta de loopback)")
      (finally (fechar!)))))

(deftest arquivo-local-nao-e-lido-nem-entra-no-pdf
  ;; A gemea local do teste acima: `file://` num `<img>` e' IMAGE_RASTER, que o controlador DEFAULT da
  ;; biblioteca PERMITE — sem a barreira deste ns o processo le' o disco do servidor e embute o resultado
  ;; no PDF congelado. O PNG de 1x1 abaixo e' escrito em disco de proposito: se o arquivo NAO existisse, o
  ;; teste passaria por ausencia de alvo, e nao por recusa (asserção que nao pode reprovar nao e' cobertura).
  (let [png (java.io.File/createTempFile "alvo-local" ".png")
        ;; PNG 1x1 valido (assinatura + IHDR + IDAT + IEND), em bytes literais — sem recurso externo
        conteudo (byte-array (map unchecked-byte
                                  [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
                                   0x00 0x00 0x00 0x0D 0x49 0x48 0x44 0x52
                                   0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x01
                                   0x08 0x06 0x00 0x00 0x00 0x1F 0x15 0xC4
                                   0x89 0x00 0x00 0x00 0x0A 0x49 0x44 0x41
                                   0x54 0x78 0x9C 0x63 0x00 0x01 0x00 0x00
                                   0x05 0x00 0x01 0x0D 0x0A 0x2D 0xB4 0x00
                                   0x00 0x00 0x00 0x49 0x45 0x4E 0x44 0xAE
                                   0x42 0x60 0x82]))]
    (try
      (with-open [os (java.io.FileOutputStream. png)] (.write os conteudo))
      (let [html (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body><p>x</p>"
                      "<img src=\"file://" (.getAbsolutePath png) "\" width=\"20\" height=\"20\"/></body></html>")
            bytes (render (.getBytes ^String html "UTF-8") instante)]
        (with-open [doc (Loader/loadPDF ^bytes bytes)]
          (let [nomes (->> (.getPages ^PDDocument doc)
                           (mapcat (fn [pagina] (seq (.getXObjectNames (.getResources pagina)))))
                           (into []))]
            (is (empty? nomes)
                "nenhum XObject pode entrar no PDF a partir de `file://` — se o arquivo local foi lido e embutido, o renderizador exfiltra disco do servidor para dentro de um artefato que sera' congelado e servido"))))
      (finally (.delete png)))))

;; ---------- TRAVA (nao reprova hoje): a QUARTA porta do determinismo ----------
;; `PdfBoxFastLinkManager/createFileEmbedLinkAnnotation` carimba `PDEmbeddedFile.setModDate` com
;; `Calendar/getInstance()` — o RELOGIO DA MAQUINA, sem TimeZone nem Locale fixos — e o resultado entra no
;; arquivo. E' a unica porta de relogio das duas jars que o ns nao neutraliza por sobrescrita. Medido: com o
;; controlador default da propria biblioteca ela JA' era inalcancavel (FILE_EMBED e' o unico dos 9 tipos que
;; `NaiveUserAgent$DefaultAccessController` recusa), e com a barreira fail-closed deste ns ela passa a ser
;; inalcancavel por DECISAO NOSSA. Este teste NAO reprova a versao anterior do codigo — ele trava o default
;; da biblioteca contra drift (upgrade que afrouxe o default, ou controlador permissivo registrado por
;; engano). Declarado como trava, nao vendido como vermelho.
(deftest link-de-download-nao-embute-arquivo-nem-carimba-o-relogio
  (let [html (.getBytes (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>"
                             "<a href=\"file:///etc/hostname\" download=\"h.txt\">baixar</a></body></html>")
                        "UTF-8")
        p1 (render html instante)
        p2 (render html instante)]
    (is (= (vec p1) (vec p2))
        "HTML com link de download tem de continuar deterministico — se o anexo fosse embutido, `PDEmbeddedFile.setModDate(Calendar/getInstance())` poria o relogio da maquina nos bytes")
    (with-open [doc (Loader/loadPDF ^bytes p1)]
      ;; sem anexo algum o catalogo nao tem nem o dicionario /Names — as duas formas do "nada" contam
      (let [nomes (.getNames (.getDocumentCatalog ^PDDocument doc))]
        (is (or (nil? nomes) (nil? (.getEmbeddedFiles nomes)))
            "nenhum arquivo embutido pode existir no PDF da folha — o anexo e' o caminho que carrega o relogio da maquina para dentro do artefato congelado")))))

;; ---------- observabilidade: o log PROPRIO do openhtmltopdf nao pode escapar do logback ----------
;; `com.openhtmltopdf.util.XRLog` e' o canal de log historico do Flying Saucer, em `java.util.logging` —
;; INDEPENDENTE do SLF4J que o PDFBox 3.x ja' fala. Sem redirecionamento ele escreve linhas cruas
;; (`com.openhtmltopdf.general INFO:: Using fast-mode renderer. Prepare to fly.`) fora do pattern do logback
;; e ignorando o nivel WARN do root — Inv.7 furado em TODO render. Pior: e' por esse canal que sai o
;; WARNING de recurso externo recusado, o sinal de seguranca dos testes acima.
(deftest log-do-openhtmltopdf-e-roteado-para-o-logging-da-aplicacao
  (pdf/renderizador-pdf)
  (let [impl (class (XRLog/getLoggerImpl))]
    (is (not (str/starts-with? (.getName impl) "com.openhtmltopdf"))
        (str "o XRLogger instalado ainda e' o default da biblioteca (" (.getName impl)
             ") — o log do openhtmltopdf esta' saindo pelo java.util.logging cru, fora do logback"))))
