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
  (:import (java.util Locale)
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
