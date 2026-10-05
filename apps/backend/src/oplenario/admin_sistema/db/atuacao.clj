(ns oplenario.admin-sistema.db.atuacao
  "A atuacao do operador (12.5, ADR-0016): append-only com selo ENCADEADO. Cada registro sela o anterior:
  selo = sha256(selo-anterior | id | em | operador | ente | acao | detalhe). Apagar ou reescrever um registro
  quebra a corrente dali em diante, e `verificar-corrente` aponta onde. O role da aplicacao so' insere e le'
  (sem UPDATE/DELETE, mig 0100). Os registros sao serializados por um advisory lock da tx — a corrente e' uma so'."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.segredo :as segredo]))

(set! *warn-on-reflection* true)

(def ^:private chave-da-corrente 7162600016)   ; advisory lock da corrente (ADR-0016)

(defn- detalhe-canonico [detalhe]
  (json/write-value-as-string (into (sorted-map) (update-keys (or detalhe {}) name))))

(defn- ->instant ^java.time.Instant [v]
  (if (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v) v))

(defn selo-de
  "Pura: o selo do registro, dado o selo anterior (\"\" no primeiro)."
  [selo-anterior {:keys [id em operador-id ente-id acao detalhe]}]
  (segredo/sha256-hex (str/join "|" [selo-anterior id (str (->instant em)) (or operador-id "") (or ente-id "") acao
                                     (detalhe-canonico detalhe)])))

(defn registrar!
  "Acrescenta um registro a corrente, na tx `tx`. Devolve o registro com o selo."
  [tx {:keys [operador-id ente-id acao detalhe]}]
  {:pre [(string? acao)]}
  (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(?)" chave-da-corrente])
  (let [anterior (or (:atuacao/selo (jdbc/execute-one! tx (sql/format {:select [:selo] :from [:admin_sistema.atuacao]
                                                                       :order-by [[:seq :desc]] :limit 1})))
                     "")
        em (->instant (:now (jdbc/execute-one! tx ["SELECT now() AS now"])))
        ;; o detalhe e' selado como ele VOLTA do jsonb (uuid vira texto etc.), senao a verificacao nao confere
        registro {:id (random-uuid) :em em :operador-id operador-id :ente-id ente-id :acao acao
                  :detalhe (json/read-value (json/write-value-as-string (or detalhe {})) json/keyword-keys-object-mapper)}
        selo (selo-de anterior registro)]
    (jdbc/execute-one! tx (sql/format {:insert-into :admin_sistema.atuacao
                                       :values [{:id (:id registro) :em em :operador_id operador-id :ente_id ente-id
                                                 :acao acao :detalhe (comum/->jsonb (:detalhe registro)) :selo selo}]}))
    (assoc registro :selo selo)))

(defn- ->registro [r]
  (-> (comum/linha->kebab r)
      (select-keys [:id :em :operador-id :ente-id :acao :detalhe :selo])
      (update :detalhe comum/jsonb->kw)
      (update :em ->instant)))

(defn do-ente
  "A atuacao numa Casa, mais recente primeiro (o que a ficha da Casa mostra), com o nome de quem atuou."
  [conn ente-id limite]
  (mapv #(-> % ->registro (assoc :operador-nome (:operador-nome (comum/linha->kebab %))))
        (jdbc/execute! conn (sql/format {:select [:a.seq :a.id :a.em :a.operador_id :a.ente_id :a.acao :a.detalhe :a.selo
                                                  [:o.nome :operador_nome]]
                                         :from [[:admin_sistema.atuacao :a]]
                                         :left-join [[:admin_sistema.operador :o] [:= :o.id :a.operador_id]]
                                         :where [:= :a.ente_id ente-id]
                                         :order-by [[:a.seq :desc]] :limit limite}))))

(def acoes-de-tentativa
  "As acoes que ABREM um par (ADR-0017, adendo de 05/10/2026): a tentativa vai antes do efeito e o desfecho depois,
  apontando-a em `detalhe.tentativa`. O que abre par aqui e' ato do operador que o registro de efeito unico nao cobre:
  a ENTRADA no console (a sessao nasce entre os dois registros) e comando de linha de comando sobre uma Casa
  (`ia-orcamento`, `reaplicar-login`)."
  #{"entrada-no-console-iniciada" "ia-orcamento-iniciado" "realm-reprovisionamento-iniciado"})

(defn- onde-sem-desfecho
  "A condicao de \"tentativa sem desfecho\" anterior a `antes-de`: uma so' definicao para a conferencia inteira e para a
  lista do console. Desfecho `falhou` TAMBEM aponta a tentativa (`detalhe.tentativa`): par fechado nao e' acusado."
  [^java.time.Instant antes-de]
  [:and
   [:in :a.acao (vec acoes-de-tentativa)]
   [:< :a.em (java.sql.Timestamp/from antes-de)]
   [:not [:exists {:select [1] :from [[:admin_sistema.atuacao :d]]
                   :where [:= [:raw "d.detalhe ->> 'tentativa'"] [:cast :a.id :text]]}]]])

(defn tentativas-sem-desfecho
  "As tentativas anteriores a `antes-de` (Instant) que nenhum registro aponta como desfecho: o ato comecou e a corrente
  nao sabe como terminou (processo caiu, ou o desfecho nao gravou). `antes-de` existe para nao acusar o que ainda esta'
  em curso. Mais antiga primeiro."
  [conn ^java.time.Instant antes-de]
  (mapv ->registro
        (jdbc/execute! conn (sql/format {:select [:a.seq :a.id :a.em :a.operador_id :a.ente_id :a.acao :a.detalhe :a.selo]
                                         :from [[:admin_sistema.atuacao :a]]
                                         :where (onde-sem-desfecho antes-de)
                                         :order-by [[:a.seq :asc]]}))))

(defn conferir-sem-desfecho
  "O que o console do operador mostra (nunca em silencio): o TOTAL das tentativas sem desfecho anteriores a `antes-de` e
  as `limite` MAIS RECENTES delas (a mesma ordem da atuacao na ficha da Casa: o que acabou de acontecer vem primeiro).
  -> {:total n :registros [...]}; `:total` maior que `(count :registros)` = truncado.
  O total e' contado na mesma consulta que traz a lista (janela), entao os dois concordam."
  [conn ^java.time.Instant antes-de limite]
  (let [linhas (jdbc/execute! conn (sql/format {:select [:a.seq :a.id :a.em :a.operador_id :a.ente_id :a.acao :a.detalhe
                                                         :a.selo [[:raw "count(*) OVER ()"] :total]]
                                                :from [[:admin_sistema.atuacao :a]]
                                                :where (onde-sem-desfecho antes-de)
                                                :order-by [[:a.seq :desc]] :limit limite}))]
    {:total (if-let [r (first linhas)] (long (:total (comum/linha->kebab r))) 0)
     :registros (mapv ->registro linhas)}))

(defn verificar-corrente
  "Recalcula a corrente inteira. {:integra? true} ou {:integra? false :quebra-em <id do 1o registro que nao confere>}."
  [conn]
  (loop [anterior "" [r & resto] (jdbc/execute! conn (sql/format {:select [:seq :id :em :operador_id :ente_id :acao
                                                                           :detalhe :selo]
                                                                  :from [:admin_sistema.atuacao]
                                                                  :order-by [[:seq :asc]]}))]
    (if-not r
      {:integra? true}
      (let [reg (->registro r)]
        (if (= (:selo reg) (selo-de anterior reg))
          (recur (:selo reg) resto)
          {:integra? false :quebra-em (:id reg)})))))
