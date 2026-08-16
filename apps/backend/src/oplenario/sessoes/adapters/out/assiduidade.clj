(ns oplenario.sessoes.adapters.out.assiduidade
  "Gate de SAIDA `models -> wire/out` da APURACAO DE ASSIDUIDADE (Etapa 6 fatia 3, §22.10 adapters/out,
  ADR-0001) — `GET /assiduidade`. DUAS projecoes do MESMO mapa que `controllers/apurar-assiduidade` devolve:

  - `apuracao->wire` (JSON): passa por Malli contra `wire.out/AssiduidadeOut` — drift de campo e' bug de
    SERVIDOR (500 via a excecao lancada aqui), nunca resposta malformada. E' o UNICO lugar que converte
    uuid/LocalDate/keyword do dominio para string — o resto deste ns (o renderizador CSV) consome o mapa JA'
    convertido, nunca o modelo cru.
  - `apuracao->csv`/`->csv-download` (CSV): o RENDERIZADOR e' PURO (dados -> string), sem Malli — o 'wire' de
    um CSV e' o proprio texto + os headers de transporte (mesmo racional de
    `transparencia.adapters.out.artefato/->download`, que `folha/->pdf-download` ja' copiava a forma de).
    Opera sobre o mapa JA' VALIDADO por `apuracao->wire` (nunca o modelo cru): reusa a MESMA conversao, uma
    vez, e evita uma segunda passagem de uuid/data pelo renderizador — molde do `serializador-folha` da
    Etapa 5 (uma funcao pura no coracao, um wrapper Ring por fora)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]
            [oplenario.sessoes.wire.out :as wire])
  (:import (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

;; ---------- JSON — apuracao (modelo) -> AssiduidadeOut (wire, validado) ----------

(defn- validar! [schema out nome]
  (when-not (m/validate schema out)
    (throw (ex-info (str nome " viola o contrato de saida (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

;; AS TRES PROJECOES ABAIXO **NAO CONSTROEM MAPA LITERAL** (achado da revisao adversarial da Fatia 3), e a
;; razao e' o que o `:closed true` do wire existe para dar. Enumerar as chaves num mapa novo DESCARTA o campo
;; desconhecido ANTES de o Malli ve-lo: uma fatia futura que acrescentasse um campo a cada sessao veria o
;; JSON sair sem ele, o CSV sem a coluna, HTTP 200 e zero log — exatamente o drift silencioso que o `:closed`
;; foi posto la' para transformar em 500. MEDIDO na versao anterior: injetando `:campo-novo`, `:sessoes`,
;; `:vereadores` e `:detalhe` ENGOLIAM em silencio; so' `:por-vereador` e `:totais` (que ja' passavam o mapa
;; adiante) lancavam. Aqui cada projecao TRANSFORMA os campos que mudam de tipo e deixa o resto passar, de
;; modo que campo desconhecido CHEGA ao Malli e vira 500 de servidor.

(defn- sessao->wire [sessao]
  (-> sessao
      (update :id str)
      (update :data-de-referencia str)))

(defn- vereador->wire [vereador]
  (-> vereador
      (update :id str)
      (update :partido-variou boolean)))

(defn- por-vereador->wire [agregado]
  (update agregado :vereador-id str))

(defn- detalhe-linha->wire [linha]
  (-> linha
      (update :sessao-id str)
      (update :vereador-id str)
      (update :estado name)))

(def ^:private AssiduidadeSemDetalheOut
  "`AssiduidadeOut` MENOS `:detalhe`, DERIVADO por `mu/dissoc` (nunca redigitado — uma segunda redacao das
  quatro chaves envelheceria em silencio, que e' a classe de defeito que o manifesto do codegen existe para
  pegar). Nao e' publico no `wire/out` de proposito: e' uma projecao INTERNA da serializacao (o CSV
  `recorte=resumo` nao consome `:detalhe`), nao um contrato novo para o FE — publica-lo faria o codegen
  emitir uma interface TS que ninguem importa."
  (mu/dissoc wire/AssiduidadeOut :detalhe))

(defn apuracao->wire
  "`logic/apurar-assiduidade` (modelo) -> `wire.out/AssiduidadeOut` (validado). Converte uuid -> string,
  `LocalDate` -> string ISO, o `:estado` KEYWORD do detalhe -> string (`name`) — o UNICO lugar do modulo que
  faz essa conversao para este contrato (o renderizador CSV abaixo reusa o resultado, nunca recomputa).

  GARANTIA DE DRIFT, medida e nao presumida: campo DESCONHECIDO em qualquer das cinco projecoes
  (`:sessoes`, `:vereadores`, `:por-vereador`, `:detalhe`, `:totais`) CHEGA ao Malli e estoura — as
  projecoes transformam campo a campo em vez de reconstruir o mapa (ver o comentario acima delas).

  `com-detalhe?` = `false` quando a apresentacao NAO consome `:detalhe` (CSV `recorte=resumo`): o modelo
  chega sem a chave (`logic/apurar-assiduidade` nem a constroi) e a validacao roda contra
  `AssiduidadeSemDetalheOut`. Um modelo que traga `:detalhe` neste modo e' bug de chamador e LANCA — dropar
  a chave em silencio seria a mesma classe de drift que o paragrafo acima fecha."
  ([apuracao] (apuracao->wire apuracao true))
  ([{:keys [sessoes vereadores por-vereador detalhe totais] :as apuracao} com-detalhe?]
   (when (and (not com-detalhe?) (contains? apuracao :detalhe))
     (throw (ex-info "apuracao->wire: :detalhe presente no modelo com com-detalhe? false (bug de chamador)"
                     {:tipo :servidor/erro})))
   (let [base {:sessoes (mapv sessao->wire sessoes)
               :vereadores (mapv vereador->wire vereadores)
               :por-vereador (mapv por-vereador->wire por-vereador)
               :totais totais}]
     (if com-detalhe?
       (validar! wire/AssiduidadeOut (assoc base :detalhe (mapv detalhe-linha->wire detalhe))
                 "AssiduidadeOut")
       (validar! AssiduidadeSemDetalheOut base "AssiduidadeOut (sem :detalhe)")))))

;; ---------- CSV — o entregavel que CIRCULA FORA do sistema (decisao do Daouda) ----------
;; UTF-8 COM BOM (Excel pt-BR so' reconhece UTF-8 sem BOM como Latin-1, acentos quebram), separador `;`
;; (Excel pt-BR usa o separador de LISTA do locale, nao a virgula do RFC4180 — CSV com virgula abre numa
;; coluna so'), CRLF, escape RFC4180 (aspas duplicadas), e a GUARDA DE INJECAO DE FORMULA (obrigatoria: nome
;; de vereador e' texto digitado por humano, e o arquivo abre num Excel de servidor publico).

(def ^:private bom
  "U+FEFF como ESCAPE, nunca como o caractere literal no fonte (achado da revisao adversarial da Fatia 3):
  um marcador INVISIVEL colado no .clj sobrevive enquanto o arquivo estiver em UTF-8 e some em silencio se
  alguem o reencodar (ou se um editor 'limpar' caracteres invisiveis) — e o sintoma seria um CSV que abre
  com acentos quebrados no Excel pt-BR, nao um erro."
  "\ufeff")
(def ^:private separador ";")
(def ^:private crlf "\r\n")

(def ^:private caracteres-de-formula
  "Um campo cujo primeiro caractere SIGNIFICATIVO e' um destes vira formula ao abrir no Excel/LibreOffice/
  Google Sheets (`=HYPERLINK(...)`, `+1+1`, `-2+3`, `@SUM(...)`) — CVE classe CSV-injection. Nome de vereador
  e rotulos livres (nome-parlamentar, partido) sao texto que um HUMANO digitou (o MESMO papel `secretario`
  que exporta e' quem os escreve no cadastro); o arquivo circula por e-mail e abre num Excel de servidor
  publico, isto e', FORA do tenant e FORA do sistema."
  #{\= \+ \- \@})

(def ^:private controles-de-abertura
  "Um campo que COMECA por um destes tambem sai guardado. Nao e' redundante com o skip de espaco em branco
  abaixo: aqui o caractere de controle e' ele proprio a anomalia (campo de planilha nao comeca em TAB/CR/LF),
  e prefixa-lo custa um apostrofo e nao perde dado."
  #{\tab \return \newline})

(defn- espaco?
  "`isWhitespace` OU `isSpaceChar`, e nao so' o primeiro: em Java o NBSP (U+00A0, o que um nome colado do
  Word carrega) NAO e' `isWhitespace` — e' `isSpaceChar`. Nao sabemos qual planilha pula qual (nao ha' Excel
  nesta maquina para medir), e pular espaco a mais so' custa um apostrofo num campo que ja' era anomalo."
  [c]
  (let [ch (char c)]
    (or (Character/isWhitespace ch) (Character/isSpaceChar ch))))

(defn- inicia-formula?
  "A guarda anterior testava SO' o primeiro caractere cru, e isso era contornavel com UM ESPACO: MEDIDO no
  fio HTTP real, `=HYPERLINK(...)` saia guardado mas `⎵=1+1+cmd|' /C calc'!A0` (um espaco antes) saia CRU.
  O argumento e' interno ao proprio conjunto: TAB e CR estavam la' PORQUE o Excel os pula antes de avaliar o
  que vem depois — se o modelo vale para TAB, vale para ESPACO e LF, e a versao anterior os excluia. Entao a
  regra e' UMA so': pula todo espaco em branco a esquerda e testa o primeiro caractere SIGNIFICATIVO."
  [s]
  (boolean
   (or (contains? controles-de-abertura (first s))
       (contains? caracteres-de-formula (first (drop-while espaco? s))))))

(defn- guardar-formula
  "Prefixa `'` (apostrofo) quando o campo abre uma formula (ver `inicia-formula?`). O apostrofo e' a
  convencao de 'texto literal' das planilhas.

  O QUE ESTA' GARANTIDO AQUI e' so' o que este codigo controla: o campo sai com um apostrofo na frente e o
  restante do texto INTACTO. Como cada planilha EXIBE esse apostrofo (o Excel classicamente o esconde na
  celula e o mostra so' na barra de formula) e' comportamento de software EXTERNO, NAO verificado nesta
  maquina — ver o carry escrito no relatorio da Fatia 3. Se algum leitor o exibir, o efeito e' um nome de
  vereador com um apostrofo a mais num anexo de oficio; se a guarda nao existisse, o efeito seria execucao
  de formula na maquina de quem abre o anexo."
  [s]
  (if (inicia-formula? s) (str "'" s) s))

(def ^:private precisa-de-aspas
  "Gatilho do quote RFC4180. Inclui a VIRGULA e o TAB alem do separador `;` (achado da revisao adversarial
  da Fatia 3): o arquivo e' escrito com `;` porque o Excel pt-BR usa o separador de lista do locale, mas ele
  NAO e' lido so' la' — num Excel en-US, no Google Sheets ou num `pandas.read_csv` do requerente do e-SIC a
  virgula E' o separador, e um `Ana,=1+1+cmd|...` sem aspas vira DUAS celulas, a segunda comecando em `=`
  (isto e', a guarda de formula burlada pelo lado do delimitador). Entre aspas o campo permanece UM campo em
  qualquer dialeto."
  #"[;,\"\t\r\n]")

(defn- campo-csv
  "Um valor -> campo RFC4180 seguro: `nil` -> \"\"; a GUARDA DE FORMULA roda ANTES do quote-se-preciso (o
  apostrofo prefixado nunca contem `;`/aspas/quebra por si so', entao a ordem nao muda o resultado do quote
  — mas rodar a guarda DEPOIS deixaria a checagem de `;`/aspas ver o campo sem o prefixo, e um campo que
  começa em `=` mas nao contem `;`/aspas sairia SEM aspas E sem o apostrofo se a ordem fosse invertida
  por engano; aqui a ordem esta' certa por construcao).

  VALOR NUMERICO NAO PASSA PELA GUARDA: `-5` viraria `'-5`, isto e', um numero publicado como texto numa
  planilha que o secretario vai somar. Nenhum contador desta apuracao e' negativo hoje (todos sao `nat-int`
  e o percentual e' >= 0), entao o defeito era LATENTE — mas a correcao e' reconhecer o tipo, e nao contar
  com o dominio continuar assim."
  [v]
  (let [s (cond (nil? v) "" (string? v) v (keyword? v) (name v) :else (str v))
        guardado (if (number? v) s (guardar-formula s))]
    (if (re-find precisa-de-aspas guardado)
      (str "\"" (str/replace guardado "\"" "\"\"") "\"")
      guardado)))

(defn- linha-csv [campos] (str (str/join separador (map campo-csv campos)) crlf))

(def ^:private colunas-resumo
  ["vereador-id" "nome" "nome-parlamentar" "partido" "partido-variou" "sessoes-computadas"
   "comparecimentos" "ausencias-justificadas" "ausencias-com-justificativa-pendente"
   "ausencias-injustificadas" "sessoes-licenciado" "percentual"])

(def ^:private colunas-detalhe
  ["sessao-id" "sessao-numero" "sessao-tipo" "sessao-data" "sessao-sigilosa" "vereador-id"
   "vereador-nome" "estado"])

(defn- sim-nao [b] (if b "sim" "nao"))

(defn- por-id [xs] (into {} (map (juxt :id identity)) xs))

(defn- linhas-resumo
  "Uma linha por vereador (`:por-vereador`), com NOME/PARTIDO JUNTADOS AQUI a partir de `:vereadores` — o
  JSON nao repete identidade por vereador (minimizacao, LGPD), mas o arquivo PLANO para humano precisa do
  nome (brief §Fatia 3). `motivo` NAO entra (nunca entrou: nem `:por-vereador` nem `:vereadores` o carregam)."
  [{:keys [vereadores por-vereador]}]
  (let [vs (por-id vereadores)]
    (mapv (fn [{:keys [vereador-id sessoes-computadas comparecimentos ausencias-justificadas
                        ausencias-com-justificativa-pendente ausencias-injustificadas
                        sessoes-licenciado percentual]}]
            (let [v (get vs vereador-id)]
              [vereador-id (:nome v) (:nome-parlamentar v) (:partido v) (sim-nao (:partido-variou v))
               sessoes-computadas comparecimentos ausencias-justificadas
               ausencias-com-justificativa-pendente ausencias-injustificadas sessoes-licenciado
               ;; NUMERO cru, nao `(str percentual)`: `campo-csv` ja' resolve `nil` -> campo vazio e so'
               ;; assim o valor chega la' como NUMERO (a guarda de formula nao se aplica a numeros — ver a
               ;; docstring de `campo-csv`).
               percentual]))
          por-vereador)))

(defn- linhas-detalhe
  "Uma linha por (sessao, vereador) (`:detalhe`), com nome do vereador + data/tipo/numero da sessao
  JUNTADOS a partir de `:vereadores`/`:sessoes`. A coluna `sessao-sigilosa` e' OBRIGATORIA aqui (achado da
  revisao adversarial da Fatia 2): sem ela a linha de uma sessao SECRETA sai identica a de uma ordinaria
  num arquivo que vai por e-mail. `motivo` da justificativa NAO entra (dado de saude, LGPD — nem
  `AssiduidadeDetalheLinhaOut` o carrega, entao nao ha' como reintroduzi-lo aqui por acidente)."
  [{:keys [vereadores sessoes detalhe]}]
  (let [vs (por-id vereadores)
        ss (por-id sessoes)]
    (mapv (fn [{:keys [sessao-id vereador-id estado sigilosa]}]
            (let [v (get vs vereador-id)
                  s (get ss sessao-id)]
              [sessao-id (:numero s) (:tipo s) (:data-de-referencia s) (sim-nao sigilosa)
               vereador-id (:nome v) estado]))
          detalhe)))

(defn- comentario
  "UMA linha `# ...` — e ela tem de CABER numa linha. Achado dos DOIS revisores da Fatia 3: a versao anterior
  fazia `(str \"# \" (campo-csv texto) crlf)`, com o `# ` FORA das aspas do `campo-csv`. Como
  `:criterio-de-inclusao` e `:nota-de-metodologia` sao literais MULTI-LINHA (`logic.clj`) e o criterio ainda
  contem `;`, o resultado em PRODUCAO era: as aspas nao protegiam nada (o `#` ficava fora delas), os `\\n`
  internos QUEBRAVAM o registro, e 7 comentarios viravam ~12 linhas fisicas — ~10 delas sem `#`, uma delas
  partida em duas colunas pelo `;`. A nota de metodologia (o texto que avisa que os numeros sao RECALCULADOS
  e por isso divergem da folha congelada) chegava ilegivel no documento que sustenta perda de mandato.

  Passou verde porque a fixture do teste redigitava `\"criterio fixo de inclusao\"` — string de UMA linha,
  INVENTADA para o teste. E' a armadilha ja' registrada no projeto (fixture que redigita o vocabulario em vez
  de ler da FONTE); o teste agora usa as constantes REAIS de `logic`.

  Correcao: NORMALIZA (toda sequencia de espaco em branco, quebras inclusive, colapsa em UM espaco) e emite
  SEM quoting RFC4180 — comentario nao e' registro, nao tem campo nem coluna, e uma aspa aberta na linha de
  comentario e' que criaria ambiguidade para o parser."
  [texto]
  (str "# " (str/trim (str/replace (str texto) #"\s+" " ")) crlf))

(defn apuracao-wire->csv
  "`AssiduidadeOut` (JA' VALIDADO por `apuracao->wire`) + os metadados do pedido ({:de :ate :tipos :recorte})
  -> bytes CSV (UTF-8 COM BOM, separador `;`, CRLF, RFC4180 + guarda de formula). PURA — dados para string,
  sem IO, testavel sem HTTP (molde do `serializador-folha` da Etapa 5).

  O CABECALHO DE COMENTARIO (linhas `# `) e' obrigatorio: o arquivo CIRCULA SOLTO (encaminhado por e-mail,
  anexado a oficio) sem o JSON ao lado — sem ele o leitor nao sabe o PERIODO, os TIPOS pedidos, o
  CRITERIO DE INCLUSAO nem a NOTA DE METODOLOGIA que explicam por que o denominador e' o que e' (brief
  §Fatia 3, carry da revisao da Fatia 2).

  `recorte` `:resumo` -> `linhas-resumo`; `:detalhe` -> `linhas-detalhe` (ver as duas para o que cada uma
  junta). Formato desconhecido -> `IllegalArgumentException` (a borda ja' rejeitou antes de chegar aqui,
  `adapters.in/query->apresentacao`; nao ha' caminho de producao que alcance este `case` sem allowlist).

  TAMANHO — declarado, nao streamed. O documento e' montado INTEIRO em memoria (um `StringBuilder` -> um
  `String` -> um `byte[]`). O que o limita e' o teto de LINHAS que `logic/apurar-assiduidade` ja' aplica
  fail-closed (`teto-de-linhas-de-detalhe-de-assiduidade` = 60.000 linhas, 422 antes de qualquer
  serializacao): a ~120 bytes por linha de detalhe, o pior caso PERMITIDO fica na casa de 7 MB por request.
  Streaming incremental (escrever no `OutputStream` da resposta enquanto deriva) NAO foi feito — exigiria
  inverter o fluxo do handler e nao cabia nesta correcao. CARRY declarado: com pool de 10 e sem
  `statement_timeout` (carry de infra da Fatia 1), requests concorrentes no teto sao um pico de heap
  proporcional ao numero deles."
  [{:keys [totais] :as wire} {:keys [de ate tipos recorte]}]
  (let [tipos-txt (if (seq tipos) (str/join ", " tipos) "todos")
        [colunas linhas] (case recorte
                            :resumo  [colunas-resumo (linhas-resumo wire)]
                            :detalhe [colunas-detalhe (linhas-detalhe wire)]
                            (throw (IllegalArgumentException. (str "recorte desconhecido: " recorte))))
        ;; UM StringBuilder, e nao `(apply str ...)` sobre uma seq de linhas: a versao anterior materializava
        ;; a seq de linhas, o corpo, o arquivo inteiro e so' entao os bytes — 3 a 4 copias do documento vivas
        ;; ao mesmo tempo no heap. Nao e' streaming (o corpo ainda e' um `byte[]` na memoria; ver o teto
        ;; declarado na docstring), mas corta as copias intermediarias com uma linha de codigo.
        sb (StringBuilder.)]
    (doto sb
      (.append ^String bom)
      (.append ^String (comentario (str "Apuracao de assiduidade - periodo " de " a " ate)))
      (.append ^String (comentario (str "Tipos de sessao: " tipos-txt)))
      (.append ^String (comentario (str "Recorte: " (name recorte))))
      (.append ^String (comentario (str "Criterio de inclusao: " (:criterio-de-inclusao totais))))
      (.append ^String (comentario (str "Nota de metodologia: " (:nota-de-metodologia totais))))
      (.append ^String (comentario (str "Sessoes sigilosas no periodo: " (:sessoes-sigilosas totais))))
      (.append ^String (comentario (str "Sessoes fechadas sem data de referencia (fora do periodo): "
                                        (:sessoes-sem-data-de-referencia totais))))
      (.append ^String crlf)
      (.append ^String (linha-csv colunas)))
    (doseq [l linhas] (.append sb ^String (linha-csv l)))
    (.getBytes (.toString sb) "UTF-8")))

;; ---------- o wrapper Ring do CSV (mesma forma de folha/->pdf-download) ----------
;; A resposta JSON nao precisa de wrapper proprio: o handler chama `oplenario.http/json-resposta 200` direto
;; sobre `apuracao->wire`, mesmo contrato de `folha->wire`/`folhas-da-sessao->wire` (o gate JA' e' a validacao).

(defn ->csv-download
  "Resposta Ring do CSV: 200, `text/csv; charset=utf-8`, `Content-Disposition: attachment` com nome ESTAVEL
  e DESCRITIVO (periodo + recorte — dois exports do mesmo dia com recortes diferentes nao se sobrescrevem
  no download do secretario). Mesma forma de `folha/->pdf-download`/`transparencia/artefato/->download` —
  INCLUSIVE o CSP, que era a metade que faltava (achado da revisao adversarial da Fatia 3: a docstring dizia
  'mesma forma de `folha/->pdf-download`' e a resposta saia com o CSP DEFAULT do Pedestal, isto e', com
  `script-src 'unsafe-inline' 'unsafe-eval'`; a folha SOBREESCREVE o dela desde a revisao da Etapa 5, e a
  alegacao de 'mesma forma' era falsa)."
  [conteudo {:keys [de ate recorte]}]
  {:status 200
   :headers {"Content-Type" "text/csv; charset=utf-8"
             ;; MESMA politica de `folha/csp-pdf` e pela MESMA razao: o default de
             ;; `io.pedestal.http.secure-headers` (instalado por `http/default-interceptors`) e' pensado para
             ;; API JSON e libera `script-src 'unsafe-inline' 'unsafe-eval'`. Este corpo e' texto digitado por
             ;; humano servido same-origin; `sandbox` da' origem opaca e script nenhum, e `allow-downloads` e'
             ;; obrigatorio — sandbox puro faz o Chrome BLOQUEAR o proprio download que o
             ;; `Content-Disposition: attachment` pede.
             "Content-Security-Policy" "sandbox allow-downloads; default-src 'none'"
             "Content-Disposition" (str "attachment; filename=\"assiduidade-" de "-a-" ate "-"
                                        (name recorte) ".csv\"")}
   :body (ByteArrayInputStream. ^bytes conteudo)})
