(ns oplenario.sessoes.adapters.in.assiduidade
  "Gate de ENTRADA `wire/in -> models` da APURACAO DE ASSIDUIDADE (Etapa 6 fatia 3, §22.10 adapters/in,
  ADR-0001 §3) — `GET /assiduidade?de=&ate=&tipos=&formato=&recorte=`. Query params (Pedestal: keyword-
  keyed, VALORES string) -> dois mapas de dominio: `query->periodo` ({:de :ate :tipos}, o que
  `controllers/apurar-assiduidade` espera) e `query->apresentacao` ({:formato :recorte}, o que O HANDLER usa
  para decidir a serializacao — nao atravessa o controller).

  FAIL-CLOSED em TODA forma, e e' onde a Fatia 2 apanhou (brief §Fatia 3): `de`/`ate` ausentes ou fora do
  formato ISO nunca viram nil silencioso; `formato`/`recorte` fora da allowlist nunca caem no default em
  silencio — a diferenca entre 'o operador nao mandou' e 'o operador mandou errado' importa (um `nil` e' o
  default legitimo; um valor desconhecido e' erro do cliente, 400). `tipos` so' SEPARA a lista — o vocabulario
  (`logic/tipos-sessao`) e' checado a JUSANTE por `logic/validar-tipos-de-assiduidade!` (controller + `db/`
  como REDE, disciplina do brief), nao aqui: duplicar o vocabulario nesta borda seria a mesma classe de
  divergencia que `estados-mandato-cadastro` existe para evitar."
  (:require [clojure.string :as str])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(def ^:private data-iso-civil
  "AAAA-MM-DD com QUATRO digitos de ano e sem sinal. Mesma guarda de `cadastros/adapters/in/vereador` (o
  ano ESTENDIDO do ISO, `+10000000-01-01`, parseia sem excecao e so' estoura la' embaixo como PSQLException
  22008, fora de qualquer catch de conflito -> 500) — reescrita aqui porque `sessoes` NAO pode importar
  `cadastros` (import-lint §22.10: comunicacao so' por HTTP/eventos)."
  #"^\d{4}-\d{2}-\d{2}$")

(defn- ->data! [s campo]
  (when (str/blank? s)
    (invalido! "data obrigatoria ausente" {:campo campo}))
  (when-not (re-matches data-iso-civil s)
    (invalido! "data invalida (esperado AAAA-MM-DD)" {:campo campo}))
  (try (LocalDate/parse s)
    (catch DateTimeParseException _ (invalido! "data invalida (esperado AAAA-MM-DD)" {:campo campo}))))

(defn- ->tipos
  "String separada por virgula -> vetor de strings TRIMADAS, sem elemento em branco; ausente/branco -> nil
  (= todos os tipos, o default do brief). NAO valida contra o vocabulario (ver docstring do ns)."
  [s]
  (when-not (str/blank? s)
    (let [ts (into [] (comp (map str/trim) (remove str/blank?)) (str/split s #","))]
      (when (seq ts) ts))))

(def ^:private formatos-validos #{"json" "csv"})
(def ^:private recortes-validos #{"resumo" "detalhe"})

(defn- ->allowlist!
  "String -> a PROPRIA string quando pertence a `validos`; branco/ausente -> `default`; qualquer OUTRA coisa
  -> 400 explicito (nunca o default silencioso — e' o defeito que a fatia 2 do brief descreve para `tipos`,
  aqui fechado tambem para `formato`/`recorte`)."
  [s validos default campo]
  (cond
    (str/blank? s)          default
    (contains? validos s)   s
    :else (invalido! (str campo " desconhecido") {:campo (keyword campo) :valor s :validos validos})))

(defn query->periodo
  "query-params (:de :ate :tipos?) -> {:de :ate :tipos} — `:de`/`:ate` como `java.time.LocalDate`
  (`logic/validar-periodo-assiduidade!`, chamado no controller, exige o TIPO antes de qualquer `.isAfter`),
  `:tipos` como vetor de string ou nil."
  [{:keys [de ate tipos]}]
  {:de (->data! de :de) :ate (->data! ate :ate) :tipos (->tipos tipos)})

(defn query->apresentacao
  "query-params (:formato? :recorte?) -> {:formato :recorte}, keywords (`:json`/`:csv`,
  `:resumo`/`:detalhe`). Puramente uma decisao de SERIALIZACAO do handler — nunca atravessa o controller."
  [{:keys [formato recorte]}]
  {:formato (keyword (->allowlist! formato formatos-validos "json" "formato"))
   :recorte (keyword (->allowlist! recorte recortes-validos "resumo" "recorte"))})
