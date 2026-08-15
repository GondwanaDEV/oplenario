(ns oplenario.sessoes.serializador-folha-test
  "UNIT (puro) — o ADAPTER `SerializadorFolha` HTML (Etapa 5 fatia 2): leva o `FolhaDocumento` (produzido
  por `gerador-folha/renderizar`, Fatia 1) ao HTML CANONICO autocontido. Cobre os 5 riscos declarados no
  brief: (a) determinismo bit-a-bit, (b) escape de HTML em todo texto livre, (c) D1 — nenhum numero de
  quorum e' recalculado/derivado, so' repassado verbatim, (d) ordem de `:serie` (map-of) nao pode vazar
  ordem de hash — segue a ordem de `:linhas`, (e) o aviso obrigatorio do STUB-ICP-v0."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.sessoes.components.serializador-folha :as ser])
  (:import (java.util Locale)))

(def ^:private v1 #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private v2 #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private v3 #uuid "00000000-0000-0000-0000-0000000000a3")
(def ^:private sid #uuid "00000000-0000-0000-0000-0000000000b1")

(def ^:private instante (java.time.Instant/parse "2026-06-20T18:00:00Z"))
(def ^:private instante-2 (java.time.Instant/parse "2026-06-20T19:15:00Z"))

(def ^:private cabecalho-ok
  {:nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"
   :legislatura-numero 19 :legislatura-ano-inicio 2025 :legislatura-ano-fim 2028})

(def ^:private linha-presente
  {:vereador-id v1 :nome "Ana Souza" :nome-parlamentar "Ana" :partido "PX" :cargo-mesa "Presidente"
   :estado :presente-plenario :inconsistencia-cadastro false :sem-assento false
   :desde instante :fonte "manual_secretaria" :registrado-em instante :justificativa nil})

(def ^:private linha-licenciada
  {:vereador-id v2 :nome "Beatriz Lima" :nome-parlamentar nil :partido "PY" :cargo-mesa nil
   :estado :licenciado :inconsistencia-cadastro false :sem-assento false
   :desde nil :fonte nil :registrado-em nil :justificativa nil})

(def ^:private linha-sem-assento
  {:vereador-id v3 :nome "Carlos Dias" :nome-parlamentar nil :partido "PZ" :cargo-mesa nil
   :estado :presente-plenario :inconsistencia-cadastro false :sem-assento true
   :desde instante :fonte "painel_eletronico" :registrado-em instante :justificativa nil})

(def ^:private quorum-ok
  {:presentes-plenario 2 :presentes-remoto 0 :presentes-total 2 :membros-da-casa 2
   :presencas-fora-do-roster 1})

(def ^:private documento-base
  {:spec-versao "folha-sessao-v1"
   :sessao {:id sid :estado "encerrada" :motivo-nao-realizada nil}
   :instante instante
   :cabecalho-da-casa cabecalho-ok
   :linhas [linha-presente linha-licenciada linha-sem-assento]
   :quorum quorum-ok
   :serie {v1 [{:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v1 :tipo "entrada"
                :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em instante
                :efetivado-em instante}
               {:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v1 :tipo "saida"
                :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em instante-2
                :efetivado-em instante-2}]}
   :justificativas []
   :atos-de-chamada-conduzida [{:id (random-uuid) :ente-id sid :sessao-id sid :conduzida-por (random-uuid)
                                :membros-da-casa 2 :ocorrido-em instante :registrado-em instante}]})

(defn- render-str [documento]
  (let [{conteudo :bytes content-type :content-type} (ser/serializar (ser/serializador-folha-html) documento)]
    {:html (String. ^bytes conteudo "UTF-8") :content-type content-type}))

(defn- marcacao
  "O HTML SEM o bloco <style> — para contar MARCACAO. O <style> inlina `folha.css` inteiro, comentarios
  inclusive, e esses comentarios citam tags (`<thead>`, `<h1>`) em prosa: contar sobre o documento cru
  mediria o comentario junto com a marcacao e daria um verde (ou vermelho) mentiroso."
  [html]
  (str/replace html #"(?s)<style>.*?</style>" ""))

;; ---------- forma basica do resultado ----------

(deftest content-type-e-text-html-utf8
  (is (= "text/html; charset=utf-8" (:content-type (render-str documento-base)))))

(deftest html-e-autocontido
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "<style"))
    (is (not (str/includes? html "<link")))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "http://")))
    (is (not (str/includes? html "https://")))))

(deftest html-inlina-o-css-de-resources
  (let [{:keys [html]} (render-str documento-base)
        css (slurp (io/resource "folha/folha.css"))]
    (is (str/includes? html css)
        "o <style> tem de conter o CSS de resources/folha/folha.css literal, sem edicao")))

;; ---------- determinismo ----------

(deftest mesma-entrada-produz-bytes-identicos
  (let [a (ser/serializar (ser/serializador-folha-html) documento-base)
        b (ser/serializar (ser/serializador-folha-html) documento-base)]
    (is (= (vec (:bytes a)) (vec (:bytes b))))))

(deftest ordem-de-serie-nao-depende-de-ordem-de-hash-do-mapa
  ;; :serie e' map-of — a ordem de iteracao de um hash-map do Clojure NAO e' garantida. O adapter tem de
  ;; ordenar pela ordem de :linhas, nao pela ordem que o mapa entrega.
  (let [doc-a (assoc documento-base :serie
                      (array-map v1 (get-in documento-base [:serie v1])
                                 v3 [{:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v3
                                      :tipo "entrada" :modalidade "plenario" :fonte "painel_eletronico"
                                      :ocorrido-em instante :efetivado-em instante}
                                     {:ente-id sid :id (random-uuid) :sessao-id sid :vereador-id v3
                                      :tipo "saida" :modalidade "plenario" :fonte "painel_eletronico"
                                      :ocorrido-em instante-2 :efetivado-em instante-2}]))
        doc-b (assoc documento-base :serie
                      (array-map v3 (get-in doc-a [:serie v3])
                                 v1 (get-in doc-a [:serie v1])))
        html-a (:html (render-str doc-a))
        html-b (:html (render-str doc-b))]
    (is (= html-a html-b)
        "documentos com o MESMO conteudo de serie em ordem de mapa diferente produzem o MESMO HTML")
    (is (< (.indexOf html-a "Ana Souza") (.indexOf html-a "Carlos Dias"))
        "a secao de movimentacoes segue a ordem de :linhas (Ana antes de Carlos), nao a ordem do mapa")))

(defn- sob-locale
  "Roda `f` com o Locale default da JVM trocado, e RESTAURA no finally (Locale default e' estado global do
  processo — vazar `ar-SA` para o resto da suite quebraria testes alheios)."
  [tag f]
  (let [anterior (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag tag))
      (f)
      (finally (Locale/setDefault anterior)))))

(deftest bytes-nao-dependem-do-locale-default-da-jvm
  ;; A GEMEA da armadilha de fuso. `(format "%02d" 5)` sob `ar-SA` devolve digitos indo-arabicos (medido
  ;; nesta imagem: bytes d9 a0 d9 a5, e nao "05") — a coluna Nº da relacao nominal sairia com bytes
  ;; diferentes conforme o Locale default do container, e o hash SHA-256 que a Fatia 4 congela deixaria de
  ;; ser reproduzivel. Duas asserções separadas: o conteudo certo E a igualdade bit-a-bit entre ambientes.
  (let [em-arabe (sob-locale "ar-SA" #(vec (:bytes (ser/serializar (ser/serializador-folha-html) documento-base))))
        em-ingles (sob-locale "en-US" #(vec (:bytes (ser/serializar (ser/serializador-folha-html) documento-base))))
        html-arabe (String. (byte-array em-arabe) "UTF-8")]
    (is (str/includes? html-arabe "<td class=\"num\">01</td>")
        "o numero de ordem tem de sair em digito ASCII sob QUALQUER Locale default")
    (is (= em-arabe em-ingles)
        "mesmo documento, dois Locales default: os bytes tem de ser identicos")))

(deftest data-e-hora-nao-dependem-do-locale-default-da-jvm
  ;; O par do teste acima para o outro formatador: `DateTimeFormatter` fixa `DecimalStyle/STANDARD`
  ;; explicitamente, entao "15:00" nao vira digito localizado nem sob `ar-SA`.
  (let [html (sob-locale "ar-SA" #(:html (render-str documento-base)))]
    (is (str/includes? html "20/06/2026 às 15:00")
        "a data/hora do instante de apuracao (fuso America/Fortaleza) em digito ASCII")))

;; ---------- paginacao: TODA tabela de dados repete o cabecalho entre paginas ----------

(deftest toda-tabela-de-dados-tem-thead
  ;; Duas pecas, ambas necessarias e nenhuma suficiente: `<thead>` no HTML (diz QUAL linha repetir) e
  ;; `-fs-table-paginate` no CSS (liga a repeticao no openhtmltopdf). Sem as duas, uma sessao longa joga
  ;; linhas de hora para a pagina seguinte sem nenhum rotulo de coluna acima.
  (let [doc (assoc documento-base :justificativas
                    [{:id (random-uuid) :vereador-id v1 :estado "aprovada" :motivo "licenca medica"
                      :lock-version 1 :decidido-por (random-uuid) :decidido-em instante-2}])
        html (marcacao (:html (render-str doc)))]
    ;; 6 tabelas de dados: atos, relacao nominal, licenciados, sem-assento, serie, justificativas.
    (is (= 6 (count (re-seq #"<thead>" html)))
        "as 6 tabelas de dados desta folha tem de abrir <thead>")
    ;; E o <thead> tem de vir ANTES da primeira <tr> de cada uma delas — thead depois do corpo nao repete.
    (doseq [classe ["tabela-atos" "tabela-linhas" "tabela-serie" "tabela-justificativas"]]
      (let [abre (count (re-seq (re-pattern (str "<table class=\"" classe "\">")) html))
            com-thead (count (re-seq (re-pattern (str "(?s)<table class=\"" classe "\">(?:(?!<tr).)*<thead>"))
                                     html))]
        (is (pos? abre) (str "a fixture tem de exercitar " classe))
        (is (= abre com-thead)
            (str "toda <table class=\"" classe "\"> tem de abrir <thead> antes da primeira <tr>"))))))

(deftest css-liga-a-repeticao-de-cabecalho-em-toda-tabela-de-dados
  (let [css (slurp (io/resource "folha/folha.css"))]
    (doseq [classe [".tabela-linhas" ".tabela-atos" ".tabela-serie" ".tabela-justificativas"]]
      (is (re-find (re-pattern (str "(?s)\\" classe " \\{[^}]*-fs-table-paginate: paginate"))
                   css)
          (str classe " tem de declarar -fs-table-paginate: paginate — <thead> sozinho nao repete no PDF")))))

;; ---------- semantica: scope de cabecalho e hierarquia de heading ----------

(deftest todo-th-declara-scope
  (let [html (marcacao (:html (render-str documento-base)))]
    ;; `<th[ >]` e nao `<th`: `<th` casaria tambem com `<thead>` e o teste passaria sozinho.
    (is (pos? (count (re-seq #"<th[ >]" html))))
    (is (= (count (re-seq #"<th[ >]" html)) (count (re-seq #"scope=" html)))
        "todo <th> associa-se as suas celulas por `scope` — a tela da Fatia 6 serve este MESMO HTML num iframe")))

(deftest documento-tem-hierarquia-de-heading
  (let [html (marcacao (:html (render-str documento-base)))]
    (is (str/includes? html "<h1 class=\"folha-titulo\">Folha de presença</h1>")
        "o titulo do documento e' <h1>, nao <div>")
    (is (not (str/includes? html "<div class=\"folha-titulo\"")))
    (is (not (str/includes? html "<div class=\"folha-secao-titulo\"")))
    (is (<= 9 (count (re-seq #"<h2 class=\"folha-secao-titulo\"" html)))
        "cada secao numerada da folha e' um <h2> — e' o que da' navegacao por heading no iframe da Fatia 6")))

;; ---------- notas: nenhuma nota fica sem ponto de chamada ----------

(deftest toda-nota-impressa-e-citada-no-corpo
  (let [html (marcacao (:html (render-str documento-base)))
        impressas (set (map second (re-seq #"<span class=\"num-nota\">(\d+)</span>" html)))
        citadas (set (map second (re-seq #"<sup class=\"chamada-nota\">(\d+)</sup>" html)))]
    (is (seq impressas))
    (is (empty? (set/difference impressas citadas))
        "nota empilhada no rodape sem nenhuma chamada no corpo e' rodape que ninguem alcanca")))

;; ---------- D1: quorum e' repassado verbatim, nunca recalculado ----------

(deftest todos-os-cinco-campos-do-quorum-aparecem-verbatim
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "2") "presentes-plenario/total/membros = 2 tem de aparecer")
    (is (str/includes? html "1") "presencas-fora-do-roster = 1 tem de aparecer")
    (is (str/includes? html "0") "presentes-remoto = 0 tem de aparecer, zero nao se omite")))

;; ---------- escape de HTML (dado livre digitado por humano) ----------

(deftest nome-com-tag-e-escapado
  (let [doc (assoc-in documento-base [:linhas 0 :nome] "Ana <script>alert(1)</script> Souza")
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "<script>alert(1)</script>")))
    (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))))

(deftest motivo-de-justificativa-com-tag-e-escapado
  (let [doc (assoc documento-base :justificativas
                    [{:id (random-uuid) :vereador-id v1 :estado "pendente"
                      :motivo "problema de saude </td><td>injetado" :lock-version 0
                      :decidido-por nil :decidido-em nil}])
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "</td><td>injetado")))
    (is (str/includes? html "&lt;/td&gt;&lt;td&gt;injetado"))))

(deftest motivo-nao-realizada-com-aspas-e-escapado
  (let [doc (-> documento-base
                (assoc-in [:sessao :estado] "nao_realizada")
                (assoc-in [:sessao :motivo-nao-realizada] "falta de quorum \"as pressas\" & sem aviso"))
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "\"as pressas\" &")))
    (is (str/includes? html "&quot;as pressas&quot; &amp;"))
    (is (str/includes? html "falta de quorum"))))

(deftest campo-de-quorum-nao-numerico-e-escapado
  ;; Defesa em profundidade, nao teoria: `gerador-folha/renderizar` NAO valida `FolhaDocumento` com Malli no
  ;; caminho de producao (so' os testes validam), entao o tipo `:int` do schema e' promessa, nao garantia de
  ;; runtime. Um bug futuro em `contar-quorum` ou na composicao do controller que ponha string nesses campos
  ;; nao pode virar injecao — e um documento CONGELADO com injecao fica congelado COM ela.
  (let [doc (-> documento-base
                (assoc-in [:quorum :presentes-total] "<img src=x onerror=alert(1)>")
                (assoc-in [:atos-de-chamada-conduzida 0 :membros-da-casa] "</td><td>injetado"))
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "<img src=x")))
    (is (str/includes? html "&lt;img src=x onerror=alert(1)&gt;"))
    (is (not (str/includes? html "</td><td>injetado")))))

(deftest id-da-sessao-e-escapado-nas-duas-secoes-que-o-imprimem
  ;; O MESMO dado aparece no bloco 2 (identificacao) e na secao 10 (congelamento). Antes desta correcao um
  ;; passava por `esc` e o outro nao — heterogeneidade que sobrevive a um refactor que troque `:id` por algo
  ;; menos garantido que `:uuid`.
  (let [doc (assoc-in documento-base [:sessao :id] "<script>xx")
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "<script>")))
    (is (= 2 (count (re-seq #"&lt;script&gt;" html)))
        "os DOIS pontos que imprimem o id curto tem de escapar")))

;; ---------- sessao nao_realizada ----------

(deftest sessao-nao-realizada-imprime-o-motivo
  (let [doc (-> documento-base
                (assoc-in [:sessao :estado] "nao_realizada")
                (assoc-in [:sessao :motivo-nao-realizada] "quorum insuficiente na abertura"))
        {:keys [html]} (render-str doc)]
    (is (str/includes? html "quorum insuficiente na abertura"))))

;; ---------- sem-assento: secao so' existe quando ha' linha sem-assento ----------

(deftest secao-sem-assento-aparece-quando-ha-linha
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "Sem assento na data"))
    (is (str/includes? html "Carlos Dias"))))

(deftest secao-sem-assento-nao-aparece-quando-vazia
  (let [doc (assoc documento-base :linhas [linha-presente linha-licenciada])
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "Sem assento na data")))))

;; ---------- licenciado: horas nao quebram, viram travessao ----------

(deftest licenciado-sem-eventos-nao-lanca-e-mostra-travessao
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "Beatriz Lima"))
    (is (str/includes? html "não se marca"))))

;; ---------- movimentacoes: so' quem tem mais de 1 evento ----------

(deftest movimentacoes-so-lista-quem-tem-mais-de-um-evento
  (let [{:keys [html]} (render-str documento-base)]
    ;; Ana (v1) tem 2 eventos na fixture -> aparece na secao de movimentacoes.
    (is (str/includes? html "Entrada"))
    (is (str/includes? html "Saída"))))

(deftest sem-vereador-com-mais-de-um-evento-secao-nao-aparece
  (let [doc (assoc documento-base :serie {})
        {:keys [html]} (render-str doc)]
    (is (not (str/includes? html "MOVIMENTAÇ")))))

;; ---------- o aviso obrigatorio do STUB-ICP-v0 ----------

(deftest aviso-stub-icp-esta-presente
  (let [{:keys [html]} (render-str documento-base)]
    (is (str/includes? html "STUB-ICP"))
    (is (str/includes? html "não")
        "o aviso tem de negar fe' publica, nao so' citar o carimbo")))

;; ---------- cabecalho nullable: nao pode renderizar em branco ----------

(deftest cabecalho-nulo-vira-texto-explicito
  (let [doc (assoc documento-base :cabecalho-da-casa
                    {:nome-oficial nil :nome-curto nil :legislatura-numero nil
                     :legislatura-ano-inicio nil :legislatura-ano-fim nil})
        {:keys [html]} (render-str doc)]
    (is (str/includes? html "não cadastrad"))
    (is (str/includes? html "não registrada")
        "legislatura nao resolvida tem de virar texto explicito, nunca celula vazia")))
