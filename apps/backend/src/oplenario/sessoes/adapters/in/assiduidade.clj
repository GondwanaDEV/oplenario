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

(defn- ->single
  "Um query-param do Pedestal e' String (uma ocorrencia) ou VETOR (repetido na URL) — repetido e' AMBIGUO
  p/ um filtro escalar -> 400 (nunca escolhe 'primeiro/ultimo' em silencio). Ausente -> nil.

  FORMA COPIADA (deliberadamente, com atribuicao) de `legislativo/adapters/in/proposicao/->single`, que ja'
  carregava esta licao com a mesma docstring. Copiada e nao importada porque `sessoes` NAO pode importar
  `legislativo` (import-lint §22.10: comunicacao entre modulos so' por HTTP/eventos) — mesma razao pela qual
  `data-iso-civil` abaixo e' uma segunda redacao da guarda de `cadastros`.

  Sem ela, `str/blank?` recebia o VETOR e estourava `ClassCastException` -> 500 `{\"erro\":\"erro interno\"}`
  com stack no log — MEDIDO nos 5 params desta rota. E `?tipos=ordinaria&tipos=secreta` nao e' entrada
  exotica: e' a convencao PADRAO de multivalor (`URLSearchParams.append`), que a Onda E vai montar."
  [v campo]
  (cond (nil? v) nil
        (string? v) v
        :else (invalido! "parametro repetido" {:campo campo :ocorrencias (count v)})))

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

(def ^:private teto-de-tipos
  "Teto de CARDINALIDADE do filtro `tipos` (convencao da casa: teto explicito em todo predicado de
  cardinalidade aberta). O vocabulario real (`logic/tipos-sessao`) tem 5 entradas; 20 e' folgado o bastante
  para que um pedido legitimo nunca esbarre nele e apertado o bastante para que
  `?tipos=ordinaria,ordinaria,...` x800 (cabe numa URL) NAO atravesse ate' a clausula `IN` e ate' o cabecalho
  do CSV. Aplicado sobre o que o cliente MANDOU (antes do `distinct`), que e' o numero que ele controla — um
  teto medido depois do `distinct` nunca dispararia para a forma repetida, e guard-rail que nao pode disparar
  nao e' guard-rail (licao da Fatia 1, teto de datas)."
  20)

(defn- ->tipos
  "String separada por virgula -> vetor de strings TRIMADAS, DISTINTAS e sem elemento em branco;
  ausente/branco -> nil (= todos os tipos, o default do brief). NAO valida contra o vocabulario (ver
  docstring do ns).

  STRING PRESENTE QUE PRODUZ LISTA VAZIA E' ERRO, nunca `nil` (achado dos DOIS revisores da Fatia 3):
  `?tipos=,` / `,,,` / `?tipos=%20,%20` caiam em lista vazia -> `nil` -> TODOS os tipos, sessao `secreta`
  inclusive, com HTTP 200. E' o defeito da Fatia 2 com o SINAL INVERTIDO — la' o filtro malformado devolvia
  resultado EM BRANCO; aqui devolve resultado MAIS AMPLO do que foi pedido, num arquivo que sai da Casa. A
  diferenca entre 'nao mandei filtro' e 'mandei um filtro que nao sobrou nada' tem de ser observavel.

  `distinct` porque o filtro e' um CONJUNTO (o mesmo tipo repetido nao muda o recorte, mas polui a clausula
  `IN` e a linha `# Tipos de sessao:` do CSV), e teto de cardinalidade (`teto-de-tipos`) antes dele."
  [s campo]
  (when-not (str/blank? s)
    (let [brutos (str/split s #"," -1)]
      (when (> (count brutos) teto-de-tipos)
        (throw (ex-info "filtro `tipos` com cardinalidade acima do teto"
                        {:tipo :limite/tipos-excedido :campo campo
                         :medido (count brutos) :teto teto-de-tipos})))
      (let [ts (into [] (comp (map str/trim) (remove str/blank?) (distinct)) brutos)]
        (when (empty? ts)
          (invalido! "filtro `tipos` presente mas sem nenhum tipo" {:campo campo :valor s}))
        ts))))

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
  {:de (->data! (->single de :de) :de)
   :ate (->data! (->single ate :ate) :ate)
   :tipos (->tipos (->single tipos :tipos) :tipos)})

(defn query->apresentacao
  "query-params (:formato? :recorte?) -> {:formato :recorte}, keywords (`:json`/`:csv`,
  `:resumo`/`:detalhe`). Puramente uma decisao de SERIALIZACAO do handler — nunca atravessa o controller."
  [{:keys [formato recorte]}]
  {:formato (keyword (->allowlist! (->single formato :formato) formatos-validos "json" "formato"))
   :recorte (keyword (->allowlist! (->single recorte :recorte) recortes-validos "resumo" "recorte"))})
