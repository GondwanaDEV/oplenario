(ns oplenario.legislativo.adapters.in.proposicao
  "Gate de ENTRADA `wire/in -> models` da proposicao (§22.10 adapters/in, ADR-0001). Chamado SO pelo
  diplomat/. Onda B Slice 1: coage os query-params de GET /legislativo/proposicoes — cada filtro de
  CONTEUDO e' OPCIONAL e TOLERANTE ao valor (ex.: tipo/estado desconhecidos so' nao casam nenhuma linha, nao
  sao 400 — mesmo racional de transparencia/adapters/in/portal/filtro-legislacao); ja' pagina/tamanho/
  ordenacao tem DEFAULT quando AUSENTES mas REJEITAM (400) quando PRESENTES e invalidos — nunca absorvidos
  em silencio, porque mudam o contrato de paginacao que o FE depende. Onda B Slice 2: valida (fail-closed ->
  400) e COAGE o corpo JSON de POST/PATCH p/ o dominio (uuid), defendendo a borda; INJETA o que nao vem do
  corpo — `id` novo (criar) ou do path (editar), `created-by`/`updated-by` sempre do `ator` (§22.5)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.proposicao :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- ->single
  "Um query-param do Pedestal e' String (uma ocorrencia) ou VETOR (repetido na URL) — repetido e' AMBIGUO
  p/ um filtro escalar -> 400 (nunca escolhe 'primeiro/ultimo' em silencio). Ausente -> nil."
  [s campo]
  (cond (nil? s) nil (string? s) s :else (invalido! "parametro repetido" {:campo campo})))

(def ^:private texto-max 200)
(def ^:private inteiro-max-chars 11)
(def ^:private tamanho-min 1)
(def ^:private tamanho-max 100)
(def ^:private tamanho-default 20)
(def ^:private pagina-min 1)
(def ^:private pagina-max 100000)
(def ^:private pagina-default 1)
(def ^:private ordenar-por-default "atualizado_em")
(def ^:private ordenar-por-valores #{"atualizado_em" "sequencial" "ano"})
(def ^:private ordenar-dir-default "desc")
(def ^:private ordenar-dir-valores #{"asc" "desc"})

(defn- query-texto [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) texto-max) (invalido! "parametro grande demais" {:campo campo}))
      t)))

(defn- query-inteiro [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (when (> (count t) inteiro-max-chars) (invalido! "inteiro grande demais" {:campo campo}))
      (try (Integer/parseInt t)
           (catch NumberFormatException _ (invalido! "inteiro invalido" {:campo campo}))))))

(defn- query-uuid [s campo]
  (let [t (some-> (->single s campo) str/trim)]
    (when-not (str/blank? t)
      (try (UUID/fromString t) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))))

(defn- query-inteiro-em-faixa [s campo minimo maximo default]
  (if-let [n (query-inteiro s campo)]
    (do (when (or (< n minimo) (> n maximo)) (invalido! "fora da faixa permitida" {:campo campo :valor n}))
        n)
    default))

(defn- query-enum [s campo valores default]
  (if-let [t (query-texto s campo)]
    (do (when-not (contains? valores t) (invalido! "valor nao permitido" {:campo campo :valor t}))
        t)
    default))

(defn listar-proposicoes->dominio
  "query-params (mapa keyword->string|vetor do Pedestal) -> filtro+paginacao de dominio p/
  controllers/listar-proposicoes e db/proposicao.clj (listar/contar)."
  [query-params]
  {:busca       (query-texto (:busca query-params) :busca)
   :tipo        (query-texto (:tipo query-params) :tipo)
   :estado      (query-texto (:estado query-params) :estado)
   :autor-id    (query-uuid (:autor-id query-params) :autor-id)
   :ano         (query-inteiro (:ano query-params) :ano)
   :pagina      (query-inteiro-em-faixa (:pagina query-params) :pagina pagina-min pagina-max pagina-default)
   :tamanho     (query-inteiro-em-faixa (:tamanho query-params) :tamanho tamanho-min tamanho-max tamanho-default)
   :ordenar-por (query-enum (:ordenar-por query-params) :ordenar-por ordenar-por-valores ordenar-por-default)
   :ordenar-dir (query-enum (:ordenar-dir query-params) :ordenar-dir ordenar-dir-valores ordenar-dir-default)})

;; ---------- Onda B Slice 2: criar/editar (corpo JSON, nao query-params) ----------

(def ^:private campos-criar
  ["tipo" "ano" "ementa" "autor-tipo" "autor-id" "autor-texto" "objeto-indicacao" "destinatario-id"
   "destinatario-texto" "tipo-requerimento" "categoria-mocao" "texto"])
(def ^:private campos-editar
  ["lock-version" "ementa" "autor-tipo" "autor-id" "autor-texto" "objeto-indicacao" "destinatario-id"
   "destinatario-texto" "tipo-requerimento" "categoria-mocao" "texto"])

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword ja internada)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->uuid? [s campo] (when (some? s) (->uuid s campo)))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so os nomes-de-campo humanizados (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn criar-proposicao->dominio
  "Corpo externo (wire/in.CriarProposicao) + `ator` -> mapa de dominio p/ Repo/protocolar!. Gera `:id` e
  `:created-by`; `ente-id` vem do ator (o controller injeta) — nunca do cliente (§22.5)."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-criar)]
    (validar! wire/CriarProposicao m "corpo de criar proposicao invalido")
    {:id (random-uuid) :tipo (:tipo m) :ano (:ano m) :ementa (:ementa m)
     :autor-tipo (:autor-tipo m) :autor-id (->uuid? (:autor-id m) :autor-id) :autor-texto (:autor-texto m)
     :objeto-indicacao (:objeto-indicacao m)
     :destinatario-id (->uuid? (:destinatario-id m) :destinatario-id)
     :destinatario-texto (:destinatario-texto m) :tipo-requerimento (:tipo-requerimento m)
     :categoria-mocao (:categoria-mocao m) :texto (:texto m) :created-by (:identidade-id ator)}))

(defn editar-proposicao->dominio
  "Corpo (wire/in.EditarProposicao) + `ator` + `id` (path, ja' UUID) -> mapa de dominio p/
  Repo/editar-proposicao!. `id` = o id do path; `updated-by` = o ator — nunca do corpo (§22.5)."
  [ator id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-editar)]
    (validar! wire/EditarProposicao m "corpo de editar proposicao invalido")
    {:id id :lock-version (:lock-version m) :ementa (:ementa m) :autor-tipo (:autor-tipo m)
     :autor-id (->uuid? (:autor-id m) :autor-id) :autor-texto (:autor-texto m)
     :objeto-indicacao (:objeto-indicacao m)
     :destinatario-id (->uuid? (:destinatario-id m) :destinatario-id)
     :destinatario-texto (:destinatario-texto m) :tipo-requerimento (:tipo-requerimento m)
     :categoria-mocao (:categoria-mocao m) :texto (:texto m) :updated-by (:identidade-id ator)}))

;; ---------- Fatia 2: a borda da TRAMITACAO (eixo C) ----------

(def ^:private campos-tramitar ["gatilho" "contexto"])

(defn- recusa-campo-extra!
  "Recusa (400) QUALQUER chave de topo fora de `permitidos` — em vez de descartar em silencio, que e' o que
  `so-esperados` faz nas bordas irmas.

  POR QUE ESTA BORDA E' DIFERENTE: aqui o campo extra tipico nao e' ruido de cliente desatualizado, e' um
  `{\"para\": \"aprovada\"}` — alguem tentando escolher o destino da materia. Descartar em silencio manteria
  o invariante (o destino continua vindo do template) mas devolveria 200 para um pedido que o servidor NAO
  atendeu como o cliente entendeu: ele acredita que mandou a materia para 'aprovada', e a materia foi para
  onde o rito mandou. Um 400 e' a unica resposta que corrige o modelo mental de quem chamou — e e' a licao
  do T3-A, que nasceu justamente de o chamador achar que escolhia a regra.

  As chaves ECOADAS na ex-data sao NOMES de campo do cliente (nao valores) e vao so' para o log do
  servidor (o interceptor global responde corpo opaco); ainda assim, teto de 5 e truncadas, porque nome de
  campo continua sendo string arbitraria vinda da rede."
  [m permitidos]
  (when-let [extras (seq (remove (set permitidos) (keys m)))]
    (invalido! "campo nao permitido no corpo (esta borda aceita GATILHO, nunca estado-destino)"
               {:campos (mapv #(subs (str %) 0 (min 40 (count (str %)))) (take 5 (sort extras)))})))

(defn- contexto->alegado
  "O `contexto` do corpo (mapa STRING-keyed, como o JSON chega) -> `:alegado` de dominio, KEYWORD-keyed.

  [REVERTIDO por ADR-0004] Ate' 11/09/2026 esta fn tinha DUAS razoes de existir: coagir o mapa para o
  formato que o avaliador da DSL sabe ler (o guard lia `alegado.x`) E marcar a procedencia do dado com o
  nome. A PRIMEIRA razao sumiu — o guard nao le' `alegado` mais, entao a keywordizacao ja' nao serve para
  isso. A fn permanece porque `:alegado` (agora so' um nome de campo interno, sem significado especial de
  confianca) ainda e' o que `registrar-transicao!` grava em
  `proposicao_transicao_historico.contexto` (Inv.10, auditoria NUNCA muda) — o corpo deixou de DECIDIR,
  nao deixou de ser REGISTRADO, e o formato keyword-keyed e' so' a convencao interna de dominio deste
  modulo (paridade com `parecer.clj`, que passa `:alegado {}`). Ausente -> `{}` (nunca nil: mantido por
  simetria com o parametro de `transicionar!`, mesmo que hoje ele nao alimente amb nenhum)."
  [c]
  (if (map? c) (update-keys c keyword) {}))

(defn tramitar->dominio
  "Corpo (wire/in.TramitarProposicao) + `ator` + `proposicao-id` (path, ja' UUID) + `agora` (LocalDate JA'
  RESOLVIDO pelo caller via kernel/tempo — este adapter e' traducao PURA, nao le relogio; mesma disciplina
  de `emitir->dominio` do parecer) -> mapa de dominio p/ Repo/transicionar!.

  NAO devolve `:template-id`: o rito nao e' dado de cliente nem de borda — quem o injeta e' o controller,
  lendo a coluna da PROPRIA linha (fatia 1). Um adapter que aceitasse template-id do corpo reabriria
  exatamente o T3-A.

  O `contexto` do corpo sai daqui como `:alegado` (fatia 4 antiga) — ver `contexto->alegado` logo acima.
  [REVERTIDO por ADR-0004] Este campo JA' NAO E' lido pelo guard do rito, sob nome nenhum: `transicionar!`
  o repassa direto para `registrar-transicao!` (auditoria, Inv.10) e nao o coloca mais no `amb` que o
  motor avalia. Continua existindo aqui so' porque a auditoria precisa dele, nao porque alguma regra
  decida com ele.

  `ator-id` e `updated-by` saem do `ator` resolvido na auth, nunca do corpo (§22.5). `gatilho` e' trimado e
  recusado em branco — o `:min 1` do Malli so' barra a string vazia, e um gatilho de espacos nao casa
  transicao nenhuma (viraria um 409 'a Casa nao permite' enganoso em vez de um 400 honesto)."
  [ator proposicao-id agora wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (recusa-campo-extra! wire-in campos-tramitar)
  (let [m (so-esperados wire-in campos-tramitar)]
    (validar! wire/TramitarProposicao m "corpo de tramitar proposicao invalido")
    (let [gatilho (str/trim (:gatilho m))]
      (when (str/blank? gatilho)
        (invalido! "gatilho obrigatorio (nao-branco)" {:campos [:gatilho]}))
      {:proposicao-id proposicao-id :gatilho gatilho
       :alegado (contexto->alegado (:contexto m))
       :ator-id (:identidade-id ator) :updated-by (:identidade-id ator) :agora agora})))

;; ---------- Fatia 2b: o RECEBIMENTO assinado da movimentacao ----------

(def ^:private campos-receber ["movimentacao-id"])

(defn receber->dominio
  "Corpo (wire/in.ReceberMovimentacao) + `proposicao-id` (path, ja' UUID) + `agora` (resolvido na borda) ->
  {:proposicao-id :transicao-id :agora}. Campo extra e' 400 (mesmo `recusa-campo-extra!` da tramitacao): o
  cliente nao escolhe quem recebe, nem o estado, nem a hora."
  [proposicao-id agora wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (recusa-campo-extra! wire-in campos-receber)
  (let [m (so-esperados wire-in campos-receber)]
    (validar! wire/ReceberMovimentacao m "corpo de receber movimentacao invalido")
    {:proposicao-id proposicao-id
     :transicao-id (->uuid (:movimentacao-id m) :movimentacao-id)
     :agora agora}))

;; ---------- Fatia 3: a LEITURA da tramitacao (query-params) ----------

(def ^:private limite-historico-min 1)
(def ^:private limite-historico-max 500)
(def ^:private limite-historico-default 100)

(defn tramitacao-query->dominio
  "query-params de GET /legislativo/proposicoes/:id/tramitacao -> `{:limite n}` (teto do HISTORICO).

  MESMA disciplina de `pagina`/`tamanho` acima, e pelo mesmo motivo: default quando AUSENTE, 400 quando
  PRESENTE e invalido (fora da faixa, nao-inteiro, repetido) — nunca absorvido em silencio, porque um teto
  silenciosamente trocado muda quanto do processo o operador esta' vendo sem ele saber.

  So' ha' `limite`, nao `pagina`: o historico de uma materia e' append-only e da ordem de dezenas de
  linhas: paginar seria contrato a manter sem caso de uso. O teto de 500 e' o guarda-costas do payload,
  nao o modo normal de uso — e a resposta declara `historico-truncado` sempre que ele morde (o Repo e'
  consultado com `limite+1` como sonda; ver controllers/buscar-tramitacao)."
  [query-params]
  {:limite (query-inteiro-em-faixa (:limite query-params) :limite
                                   limite-historico-min limite-historico-max limite-historico-default)})
