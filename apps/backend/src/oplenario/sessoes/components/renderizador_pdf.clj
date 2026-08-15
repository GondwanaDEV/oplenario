(ns oplenario.sessoes.components.renderizador-pdf
  "Port de SAIDA RenderizadorPdf (Etapa 5 fatia 3): leva o HTML CANONICO (bytes, produzido por
  `serializador-folha-html`, Fatia 2) ao PDF congelavel da folha de presenca. Irmao de forma de
  `serializador-folha.clj`: mesmo par port+adapter no mesmo ns, mesmo `defrecord` sem estado construido
  por uma funcao de zero-arg que o host injeta.

  O PDF NASCE DO MESMO HTML QUE A TELA SERVE (§ Fatia 6) — este ns NAO reimplementa layout, NAO parseia o
  documento, NAO conhece `FolhaDocumento`. Ele so' leva bytes de HTML autocontido a bytes de PDF. Se o
  layout precisar mudar para renderizar bem no PDF, quem muda e' `folha.css`/`serializador-folha.clj`
  (o HTML CANONICO) — nunca este ns bifurca um segundo caminho de renderizacao.

  A FONTE ME CORRIGIU (dois fatos medidos contra `openhtmltopdf-pdfbox` 1.1.73 real, nao supostos —
  ver `.superpowers/sdd/etapa5-folha-brief.md` e o relatorio da fatia):

  1) `withHtmlContent` (openhtmltopdf-core) usa um parser XML ESTRITO, nao um parser HTML5 tolerante — e
     NAO existe modulo `openhtmltopdf-jsoup`/`-html5-parser` publicado no Maven Central para este grupo
     (medido: `repo1.maven.org/maven2/io/github/openhtmltopdf/` lista so' core/pdfbox/objects/svg-support/
     etc., nenhum parser lenient). `<!doctype html>` em minusculas e qualquer `<` ou `&` cru dentro do
     elemento style (mesmo em comentario CSS) derrubam o parse com `SAXParseException`. Por isso o HTML
     CANONICO usa `<!DOCTYPE html>` maiusculo (`serializador-folha.clj`) e `folha.css` evita esses dois
     caracteres nos proprios comentarios (ver o cabecalho do arquivo). NENHUMA das duas correcoes muda a
     RENDERIZACAO visual — so' destrava o parser estrito.

  2) As pilhas de fonte base-14 (`\"Times New Roman\", Times, serif` / `\"Courier New\", Courier,
     monospace`) resolvem para os nomes base-14 SEM embutimento: medido com `PDResources/getFont` sobre o
     PDF gerado — `/BaseFont` sai `Times-Roman`/`Times-Bold`/`Courier`, `.isEmbedded()` `false`,
     `.isStandard14()` `true`. O aviso de log `Using fallback font LiberationSans for base font ...` que o
     PDFBox 3.0.7 emite e' so' METRICA INTERNA de largura de glifo (a familia Liberation nao entra no
     arquivo — confirmado pela ausencia de `/FontFile`/`/FontFile2` no PDF e pelo tamanho do arquivo, que
     nao cresce com o numero de familias base-14 usadas); nao contradiz a decisao de nao embutir fonte.

  DETERMINISMO — as TRES portas do brief, medidas contra `PdfBoxRenderer`/`PDDocument`/`COSWriter`
  (PDFBox 3.0.7) reais, nao supostas:

  (a) `createPDFWithoutClosing()` monta o `PDDocument` em memoria mas NAO grava bytes no stream (medido no
      bytecode de `createPdfFast`: o `PDDocument/save` so' roda quando o parametro interno `closeAfter` e'
      verdadeiro). `finishPDF()`, por sua vez, so' fecha o documento — TAMBEM nao salva. Quem salva e' ESTE
      ns, chamando `PDDocument/save` manualmente ENTRE os dois, depois de mutar os metadados — e' a janela
      que o brief pede para 'pegar o PDDocument ANTES de salvar'.
  (b) `PdfBoxRenderer` carimba `CreationDate` com `Calendar/getInstance()` (relogio da maquina) e
      `Producer` com o valor do builder ANTES de devolver o `PDDocument` — este ns SOBRESCREVE os dois
      depois, com o instante de congelamento recebido por parametro (nunca `now`) e strings fixas.
      `Calendar/getInstance` recebe TimeZone e `Locale/ROOT` explicitos: sem o Locale explicito, um
      Locale default tailandes/bugista trocaria o SISTEMA de calendario inteiro (nao so' o formato dos
      digitos, como no adapter HTML) — a mesma familia de armadilha que ja custou bug nesta base, um
      degrau mais funda.
  (c) O `/ID` do trailer (`COSWriter`, PDFBox 3.0.7): quando o documento nao e' uma atualizacao
      incremental, `/ID` = SHA-256(`Long.toString(documentId)` + o `toString()` de cada valor do
      dicionario `/Info`), repetido nas duas posicoes do array. `PDDocument` usa `Calendar/getInstance()`
      (relogio) como `documentId` quando ninguem o define — este ns chama `PDDocument/setDocumentId` com
      um `long` derivado dos 8 primeiros bytes do SHA-256 do HTML de entrada. Como o `/Info` tambem fica
      inteiramente determinado pelo instante de congelamento (item b), o `/ID` que a PDFBox calcula vira
      funcao PURA de (bytes do HTML, instante de congelamento) — nao precisei montar o array eu mesmo.
      NENHUM outro lugar escreve XMP com data: os metodos que geram pacote XMP
      (`PdfBoxRenderer/createPdfaSchema`) so' rodam sob `usePdfAConformance`, que este ns nunca liga
      (PDF/A e' fora de escopo desta fatia — ver CARRY em `folha.css`)."
  (:require [oplenario.kernel.tempo :as tempo])
  (:import (com.openhtmltopdf.pdfboxout PdfRendererBuilder)
           (java.io ByteArrayOutputStream)
           (java.nio ByteBuffer)
           (java.security MessageDigest)
           (java.time Instant ZoneId)
           (java.util Calendar Locale TimeZone)))

(set! *warn-on-reflection* true)

;; ---------- metadados fixos — NUNCA o relogio, NUNCA o ambiente ----------

(def ^:private producer "O Plenário — sistema de gestão legislativa")
(def ^:private creator producer)

;; baseUri FIXO e' proposital: o HTML canonico e' autocontido (§ Fatia 2 — zero recurso externo, CSS
;; inline), entao nenhuma URI relativa e' resolvida de fato. Passar `nil` lanca NullPointerException
;; dentro do openhtmltopdf (medido); uma string vazia e' o valor NEUTRO que nao entra em bytes algum do
;; PDF (baseUri nao aparece em nenhum objeto do documento gerado — so' guia resolucao de recurso, que aqui
;; nao existe), preservando o determinismo sem introduzir um literal de ambiente.
(def ^:private base-uri-neutro "")

;; ---------- sha256 — primitivo local, na convencao de cada modulo ter o seu (ver
;; `compliance/components/repositorio.clj`, `legislativo/components/repositorio.clj`: nenhum kernel
;; compartilhado hoje, e `sessoes` nao importa `compliance`/`legislativo`, ADR-0001) ----------

(defn- sha256 ^bytes [^bytes b]
  (.digest (MessageDigest/getInstance "SHA-256") b))

(defn- documento-id-de
  "Um `Long` deterministico derivado dos 8 primeiros bytes do SHA-256 do HTML de entrada — vira a semente
  do `/ID` do trailer (ver ponto (c) do docstring do ns). NAO e' o hash de integridade do artefato (isso
  e' Fatia 4, sobre os bytes do PDF INTEIRO); e' so' o que evita que `PDDocument` caia no fallback
  `System/currentTimeMillis` (o relogio) quando ninguem define um id."
  ^long [^bytes html-bytes]
  (.getLong (ByteBuffer/wrap (sha256 html-bytes) 0 8)))

(defn- calendario-de
  "`Instant` -> `Calendar` no MESMO fuso civil que o HTML canonico usa para a hora impressa no papel
  (`tempo/zona-civil-padrao` — consumidor (f), ver docstring da constante), com `Locale/ROOT` EXPLICITO.
  Sem o Locale explicito, `Calendar/getInstance` resolve o SISTEMA DE CALENDARIO (gregoriano vs. budista
  etc.) pelo Locale default da JVM — um degrau mais fundo que a armadilha de digito que ja custou bug
  nesta base (aquela trocava so' os digitos; esta trocaria o proprio calendario)."
  ^Calendar [^Instant instante]
  (doto (Calendar/getInstance (TimeZone/getTimeZone ^ZoneId tempo/zona-civil-padrao) Locale/ROOT)
    (.setTime (java.util.Date/from instante))))

;; ---------- o port ----------

(defprotocol RenderizadorPdf
  (renderizar [this html-bytes instante-de-congelamento]
    "Renderiza o HTML CANONICO (bytes, UTF-8, produzido por `SerializadorFolha`) no PDF congelavel da
     folha de presenca. Devolve {:bytes <byte-array> :content-type \"application/pdf\"}. DETERMINISTICO:
     o MESMO par (html-bytes, instante-de-congelamento) produz bytes identicos — pre-requisito do hash de
     integridade que a Fatia 4 vai congelar. `instante-de-congelamento` e' dado do artefato (o instante em
     que a Secretaria pede o congelamento), nunca `(Instant/now)` lido por este ns."))

(defrecord RenderizadorPdfOpenHtmlToPdf []
  RenderizadorPdf
  (renderizar [_ html-bytes instante-de-congelamento]
    (let [saida (ByteArrayOutputStream.)
          html-str (String. ^bytes html-bytes "UTF-8")
          builder (doto (PdfRendererBuilder.)
                    (.useFastMode)
                    (.withHtmlContent html-str base-uri-neutro)
                    (.withProducer producer)
                    (.toStream saida))]
      (with-open [renderer (.buildPdfRenderer builder)]
        (.createPDFWithoutClosing renderer)
        (let [doc (.getPdfDocument renderer)
              info (.getDocumentInformation doc)
              calendario (calendario-de instante-de-congelamento)]
          ;; As TRES portas do determinismo (docstring do ns, itens a/b/c) — nesta ordem: metadados de
          ;; /Info primeiro, `/ID` depois, porque o `/ID` e' derivado do CONTEUDO de /Info (item c).
          (.setCreationDate info calendario)
          (.setModificationDate info calendario)
          (.setCreator info creator)
          (.setProducer info producer)
          (.setDocumentId doc (documento-id-de html-bytes))
          (.save doc saida))
        (.finishPDF renderer))
      {:bytes (.toByteArray saida)
       :content-type "application/pdf"})))

(defn renderizador-pdf
  "Cria o adapter RenderizadorPdf via openhtmltopdf-pdfbox (sem estado — o host o constroi e injeta, como
   `serializador-folha-html`)."
  []
  (->RenderizadorPdfOpenHtmlToPdf))
