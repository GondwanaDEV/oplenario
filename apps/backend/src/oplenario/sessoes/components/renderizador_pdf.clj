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
      (PDF/A e' fora de escopo desta fatia — ver CARRY em `folha.css`).

  (d) A QUARTA porta, que so' apareceu na revisao adversarial e nao estava no brief:
      `PdfBoxFastLinkManager/createFileEmbedLinkAnnotation` carimba `PDEmbeddedFile.setModDate` com
      `Calendar/getInstance()` CRU — relogio da maquina, sem TimeZone nem Locale — e o resultado entra no
      arquivo. E' o unico `Calendar/getInstance` das duas jars que este ns nao neutraliza por sobrescrita,
      porque o objeto e' criado dentro da biblioteca. Alcanca-se por um `<a href=\"...\" download>` no HTML
      (medido no bytecode: `hasAttribute(\"download\")` -> `createFileEmbedLinkAnnotation` ->
      `ExternalResourceType/FILE_EMBED`). A barreira de recurso externo abaixo fecha essa porta ANTES de
      resolver a URI, o que a torna inalcancavel por decisao nossa e nao por acidente de conteudo (o HTML
      canonico nao emite `<a>` HOJE) nem por default de terceiro (`NaiveUserAgent$DefaultAccessController`
      recusa FILE_EMBED, mas e' default de biblioteca, sujeito a upgrade).

  RECURSO EXTERNO — FAIL-CLOSED, medido, nao presumido. O brief diz que o HTML canonico e' autocontido; o
  que a revisao adversarial mostrou e' que ele era autocontido por CONTEUDO, e o motor ficava fail-open por
  CONFIGURACAO. `NaiveUserAgent.checkAccessAllowed` devolve `true` quando nenhum `BiPredicate` esta'
  registrado para a prioridade consultada, e `openStream` faz `new URI(uri).toURL().openStream()` sem
  filtro de esquema. Medido contra a biblioteca real: um `<img src=\"http://127.0.0.1:PORTA/x.png\">`
  produziu DUAS requisicoes GET saindo do processo, e `@import url(...)`/`background-image:url(...)` mais
  uma cada; um `<img src=\"file:///...\">` LEU o disco do servidor e embutiu o arquivo no PDF como XObject
  (`COSName{Im1}`). Num renderizador de servidor isso e' SSRF (metadado de nuvem em `169.254.169.254`,
  servico interno em loopback) e exfiltracao de arquivo local para dentro de um artefato que sera'
  congelado, hasheado e servido. A unica coisa que separava um campo de texto disso era a disciplina de
  `esc` em OUTRO arquivo (`serializador-folha`). Agora a recusa e' desta camada, para TODOS os 9
  `ExternalResourceType`, em `RUN_BEFORE_RESOLVING_URI` — a folha nao tem recurso externo legitimo algum
  (CSS inline, fonte base-14 resolvida dentro do PDFBox, zero imagem), entao negar tudo nao custa nada.
  REFUTADO na mesma medicao, e registrado para nao voltar como alarme: `<!DOCTYPE html SYSTEM \"http://...\">`
  NAO busca a DTD — openhtmltopdf instala um resolvedor de entidade que devolve entidade vazia
  (`Entity public: null, no local mapping. Returning empty entity to avoid pulling from network`).

  LOG — o canal PROPRIO do openhtmltopdf: `com.openhtmltopdf.util.XRLog` e' o log historico do Flying
  Saucer, em `java.util.logging`, INDEPENDENTE do SLF4J que o PDFBox 3.x ja' fala. Sem redirecionamento ele
  escreve linhas cruas (`com.openhtmltopdf.general INFO:: Using fast-mode renderer. Prepare to fly.`) fora
  do pattern do logback e ignorando o nivel WARN do root — Inv.7 furado em TODO render. Este ns instala um
  `XRLogger` que encaminha para `clojure.tools.logging`. Encaminhar, e nao `setLoggingEnabled false`: e' por
  esse mesmo canal que sai o WARNING de recurso externo recusado, que agora e' sinal de SEGURANCA (alguem
  pos um recurso externo no HTML canonico) e nao ruido a calar."
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]
            [oplenario.kernel.tempo :as tempo])
  (:import (com.openhtmltopdf.outputdevice.helper ExternalResourceControlPriority)
           (com.openhtmltopdf.pdfboxout PdfRendererBuilder)
           (com.openhtmltopdf.util XRLog XRLogger)
           (java.io ByteArrayOutputStream)
           (java.nio ByteBuffer)
           (java.security MessageDigest)
           (java.time Instant ZoneId)
           (java.util Calendar Locale TimeZone)
           (java.util.function BiPredicate)
           (java.util.logging Level)))

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

;; ---------- recurso externo: recusa TOTAL, antes de resolver a URI ----------

(def ^:private recusa-todo-recurso-externo
  "Nega os 9 `ExternalResourceType` sem olhar a URI. Nao e' uma allowlist com um furo: a folha e' autocontida
  por contrato (CSS inline, fonte base-14 interna do PDFBox, zero imagem), entao o conjunto de recursos
  externos LEGITIMOS e' vazio — e uma lista vazia se escreve como `false`, nao como filtro de esquema que a
  proxima fatia teria de manter correto. Registrado em `RUN_BEFORE_RESOLVING_URI`: recusa antes de o
  `FSUriResolver` sequer transformar a string, entao nenhum truque de resolucao alcanca a rede ou o disco."
  (reify BiPredicate
    (test [_ _uri _tipo] false)))

;; ---------- log do openhtmltopdf -> logging da aplicacao (Inv.7) ----------

(defn- nivel-de
  "`java.util.logging.Level` -> palavra-chave de `clojure.tools.logging`. Comparacao por `intValue` (e nao por
  identidade de constante) porque a biblioteca tambem emite niveis intermediarios (FINE/FINER/CONFIG)."
  [^Level nivel]
  (let [v (.intValue nivel)]
    (cond
      (>= v (.intValue Level/SEVERE))  :error
      (>= v (.intValue Level/WARNING)) :warn
      (>= v (.intValue Level/INFO))    :info
      (>= v (.intValue Level/FINE))    :debug
      :else                            :trace)))

(def ^:private logger-openhtmltopdf
  (reify XRLogger
    (log [_ nome nivel mensagem]
      (log/log nome (nivel-de nivel) nil mensagem))
    (log [_ nome nivel mensagem erro]
      (log/log nome (nivel-de nivel) erro mensagem))
    (setLevel [_ _nome _nivel]
      ;; quem decide nivel e' o logback, nunca a biblioteca — este metodo existe na interface e e' inerte
      nil)
    (isLogLevelEnabled [_ diagnostico]
      (log-impl/enabled? (log-impl/get-logger log/*logger-factory* "com.openhtmltopdf")
                         (nivel-de (.getLevel diagnostico))))))

(def ^:private roteamento-de-log
  "Forcado pelo construtor do adapter, nunca no load do ns: `XRLog/setLoggerImpl` e' estado GLOBAL da JVM, e
  efeito colateral em load de ns e' o tipo de coisa que muda o comportamento de quem so' quis requerer o
  namespace. `delay` garante uma unica execucao mesmo com N adapters construidos."
  (delay (XRLog/setLoggerImpl logger-openhtmltopdf) :roteado))

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
                    (.useExternalResourceAccessControl
                     recusa-todo-recurso-externo
                     ExternalResourceControlPriority/RUN_BEFORE_RESOLVING_URI)
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
   `serializador-folha-html`). Efeito colateral unico e idempotente: instala o roteamento do log proprio do
   openhtmltopdf para o logging da aplicacao (ver docstring do ns, secao LOG)."
  []
  @roteamento-de-log
  (->RenderizadorPdfOpenHtmlToPdf))

;; ---------- Etapa 5 fatia 5 — o TIMEOUT DE RENDERIZACAO (obrigacao herdada da fatia 3) ----------
;; A revisao de seguranca da Fatia 3 aceitou a ausencia de timeout SO' PORQUE nao existia superficie HTTP
;; disparando a renderizacao. `POST /sessoes/:id/folha` (Fatia 5) e' essa superficie: sem prazo, um documento
;; patologico (que passou pelo TETO de tamanho do serializador mas ainda assim degenera no layout — CSS 2.1
;; com muitas linhas de tabela e' O(n) a O(n^2) conforme o motor de layout) pendura a conexao HTTP e o worker
;; que a atende indefinidamente. O teto de tamanho (`serializador-folha.clj`) e este timeout sao as DUAS
;; camadas da mesma obrigacao — uma barra o payload, a outra barra o TEMPO.

(def ^:const timeout-renderizacao-ms
  "15s — generoso sobre o tempo real de render de uma folha (documento pequeno, CSS 2.1 sem JS/flex/grid,
  layout so' `block`/`table`, fonte base-14 sem embutimento — nada aqui e' caro por design). Protege o
  worker HTTP e o pool de conexoes de um documento patologico sem penalizar o caso comum sob carga normal
  do container (CPU compartilhada, GC)."
  15000)

(defrecord RenderizadorPdfComTimeout [delegate timeout-ms]
  RenderizadorPdf
  (renderizar [_ html-bytes instante-de-congelamento]
    (let [fut (future (renderizar delegate html-bytes instante-de-congelamento))
          v   (try
                (.get ^java.util.concurrent.Future fut timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                (catch java.util.concurrent.TimeoutException _ ::timeout)
                ;; A FONTE ME CORRIGIU (medido contra este JDK/Clojure): `.get` com timeout NAO desembrulha
                ;; `ExecutionException` sozinho (a suposicao original era que `deref` fizesse isso sempre —
                ;; um teste com um delegate que lanca de proposito provou o contrario: a excecao CRUA que
                ;; chegava era `ExecutionException`, nao a causa). Desembrulhamos aqui, manualmente, para o
                ;; comportamento de erro do delegate nao mudar (so' ganha um prazo).
                (catch java.util.concurrent.ExecutionException e
                  (throw (or (.getCause e) e))))]
      (if (= v ::timeout)
        (do (future-cancel fut)
            (throw (ex-info "folha: renderizacao de PDF excedeu o prazo — recusada (nunca serve PDF parcial)"
                            {:tipo :servidor/timeout-renderizacao :timeout-ms timeout-ms})))
        v))))

(defn renderizador-pdf-com-timeout
  "Decora `renderizador-pdf` (ou `delegate`, para teste) com o TIMEOUT DE RENDERIZACAO. O HOST constroi UMA
   instancia (nunca dentro do handler HTTP) e injeta — mesma disciplina de `serializador-folha-html-com-teto`.
   Erro real do delegate (nao timeout): a CAUSA original atravessa desembrulhada (ver o comentario A FONTE
   ME CORRIGIU no `renderizar` acima) — o comportamento de erro do renderizador de baixo nao muda, so' ganha
   um prazo. `future-cancel` no estouro e' melhor-esforco (o openhtmltopdf pode nao responder a interrupcao
   no meio do parse/layout); o que importa e' o worker HTTP nao ficar preso."
  ([] (renderizador-pdf-com-timeout (renderizador-pdf) timeout-renderizacao-ms))
  ([delegate timeout-ms] (->RenderizadorPdfComTimeout delegate timeout-ms)))
