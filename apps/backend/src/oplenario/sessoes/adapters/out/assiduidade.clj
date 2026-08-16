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
            [oplenario.sessoes.wire.out :as wire])
  (:import (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

;; ---------- JSON — apuracao (modelo) -> AssiduidadeOut (wire, validado) ----------

(defn- validar! [schema out nome]
  (when-not (m/validate schema out)
    (throw (ex-info (str nome " viola o contrato de saida (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn- sessao->wire [{:keys [id numero tipo estado data-de-referencia sigilosa quorum]}]
  {:id (str id) :numero numero :tipo tipo :estado estado
   :data-de-referencia (str data-de-referencia) :sigilosa sigilosa :quorum quorum})

(defn- vereador->wire [{:keys [id nome nome-parlamentar partido partido-variou]}]
  {:id (str id) :nome nome :nome-parlamentar nome-parlamentar :partido partido
   :partido-variou (boolean partido-variou)})

(defn- por-vereador->wire [{:keys [vereador-id] :as agregado}]
  (-> agregado (dissoc :vereador-id) (assoc :vereador-id (str vereador-id))))

(defn- detalhe-linha->wire [{:keys [sessao-id vereador-id estado sigilosa]}]
  {:sessao-id (str sessao-id) :vereador-id (str vereador-id) :estado (name estado) :sigilosa sigilosa})

(defn apuracao->wire
  "`logic/apurar-assiduidade` (modelo) -> `wire.out/AssiduidadeOut` (validado). Converte uuid -> string,
  `LocalDate` -> string ISO, o `:estado` KEYWORD do detalhe -> string (`name`) — o UNICO lugar do modulo que
  faz essa conversao para este contrato (o renderizador CSV abaixo reusa o resultado, nunca recomputa)."
  [{:keys [sessoes vereadores por-vereador detalhe totais]}]
  (validar! wire/AssiduidadeOut
            {:sessoes (mapv sessao->wire sessoes)
             :vereadores (mapv vereador->wire vereadores)
             :por-vereador (mapv por-vereador->wire por-vereador)
             :detalhe (mapv detalhe-linha->wire detalhe)
             :totais totais}
            "AssiduidadeOut"))

;; ---------- CSV — o entregavel que CIRCULA FORA do sistema (decisao do Daouda) ----------
;; UTF-8 COM BOM (Excel pt-BR so' reconhece UTF-8 sem BOM como Latin-1, acentos quebram), separador `;`
;; (Excel pt-BR usa o separador de LISTA do locale, nao a virgula do RFC4180 — CSV com virgula abre numa
;; coluna so'), CRLF, escape RFC4180 (aspas duplicadas), e a GUARDA DE INJECAO DE FORMULA (obrigatoria: nome
;; de vereador e' texto digitado por humano, e o arquivo abre num Excel de servidor publico).

(def ^:private bom "﻿")
(def ^:private separador ";")
(def ^:private crlf "\r\n")

(def ^:private primeiros-caracteres-de-formula
  "Um campo cujo PRIMEIRO caractere e' um destes vira formula ao abrir no Excel/LibreOffice
  (`=HYPERLINK(...)`, `+1+1`, `-2+3`, `@SUM(...)`) OU e' interpretado como inicio de campo deslocado
  (TAB, CR) — CVE classe CSV-injection. Nome de vereador e rotulos livres (nome-parlamentar, partido) sao
  texto que um HUMANO digitou; o arquivo circula por e-mail e abre num Excel de servidor publico."
  #{\= \+ \- \@ \tab \return})

(defn- guardar-formula
  "Prefixa `'` (apostrofo) quando o PRIMEIRO caractere do campo e' perigoso — o Excel/LibreOffice tratam o
  apostrofo inicial como marcador de TEXTO LITERAL e nunca o exibem, entao o dado nao muda para quem le."
  [s]
  (if (and (seq s) (contains? primeiros-caracteres-de-formula (first s)))
    (str "'" s)
    s))

(defn- campo-csv
  "Um valor -> campo RFC4180 seguro: `nil` -> \"\"; a GUARDA DE FORMULA roda ANTES do quote-se-preciso (o
  apostrofo prefixado nunca contem `;`/aspas/quebra por si so', entao a ordem nao muda o resultado do quote
  — mas rodar a guarda DEPOIS deixaria a checagem de `;`/aspas ver o campo sem o prefixo, e um campo que
  começa em `=` mas nao contem `;`/aspas sairia SEM aspas E sem o apostrofo se a ordem fosse invertida
  por engano; aqui a ordem esta' certa por construcao). Precisa de aspas (RFC4180) quando contem o
  SEPARADOR, aspas, CR ou LF — aspas internas DUPLICADAS."
  [v]
  (let [s (cond (nil? v) "" (string? v) v (keyword? v) (name v) :else (str v))
        guardado (guardar-formula s)]
    (if (re-find #"[;\"\r\n]" guardado)
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
               (some-> percentual str)]))
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

(defn- comentario [texto] (str "# " (campo-csv texto) crlf))

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
  `adapters.in/query->apresentacao`; nao ha' caminho de producao que alcance este `case` sem allowlist)."
  [{:keys [totais] :as wire} {:keys [de ate tipos recorte]}]
  (let [tipos-txt (if (seq tipos) (str/join ", " tipos) "todos")
        cabecalho (str (comentario (str "Apuracao de assiduidade - periodo " de " a " ate))
                        (comentario (str "Tipos de sessao: " tipos-txt))
                        (comentario (str "Recorte: " (name recorte)))
                        (comentario (str "Criterio de inclusao: " (:criterio-de-inclusao totais)))
                        (comentario (str "Nota de metodologia: " (:nota-de-metodologia totais)))
                        (comentario (str "Sessoes sigilosas no periodo: " (:sessoes-sigilosas totais)))
                        (comentario (str "Sessoes fechadas sem data de referencia (fora do periodo): "
                                         (:sessoes-sem-data-de-referencia totais)))
                        crlf)
        [colunas linhas] (case recorte
                            :resumo  [colunas-resumo (linhas-resumo wire)]
                            :detalhe [colunas-detalhe (linhas-detalhe wire)]
                            (throw (IllegalArgumentException. (str "recorte desconhecido: " recorte))))
        corpo (apply str (linha-csv colunas) (map linha-csv linhas))]
    (.getBytes ^String (str bom cabecalho corpo) "UTF-8")))

;; ---------- o wrapper Ring do CSV (mesma forma de folha/->pdf-download) ----------
;; A resposta JSON nao precisa de wrapper proprio: o handler chama `oplenario.http/json-resposta 200` direto
;; sobre `apuracao->wire`, mesmo contrato de `folha->wire`/`folhas-da-sessao->wire` (o gate JA' e' a validacao).

(defn ->csv-download
  "Resposta Ring do CSV: 200, `text/csv; charset=utf-8`, `Content-Disposition: attachment` com nome ESTAVEL
  e DESCRITIVO (periodo + recorte — dois exports do mesmo dia com recortes diferentes nao se sobrescrevem
  no download do secretario). Mesma forma de `folha/->pdf-download`/`transparencia/artefato/->download`."
  [conteudo {:keys [de ate recorte]}]
  {:status 200
   :headers {"Content-Type" "text/csv; charset=utf-8"
             "Content-Disposition" (str "attachment; filename=\"assiduidade-" de "-a-" ate "-"
                                        (name recorte) ".csv\"")}
   :body (ByteArrayInputStream. ^bytes conteudo)})
