(ns oplenario.participacao.db.complemento
  "Persistencia de 'participacao.complemento' (mig 20261004000185) — o COMPLEMENTO DA RESPOSTA: o texto que a secretaria
  acrescenta a um protocolo que a Casa ja' respondeu, depois da janela de anexos. APPEND-ONLY (Inv.10): SO `inserir!` +
  selects (sem UPDATE/DELETE; a migration nega o grant + trg_participacao_complemento_append_only). O protocolo e'
  POLIMORFICO (`objeto_tipo` + `objeto_id`, a mesma forma de anexo/prorrogacao): nao ha FK, entao o chamador (o
  controller) so' grava depois de conferir que o protocolo existe NESTA Casa e ja' foi respondido. `complementado-por` e'
  de auditoria e fica no dominio: o contrato de saida do portal o filtra. HoneySQL schema-qualified; ente_id em TODA
  query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :objeto_tipo :objeto_id :corpo :complementado_em :complementado_por])

(defn inserir!
  "Registra um complemento (append-only). Devolve o mapa kebab (RETURNING). `complementado-em` e' o instante do RELOGIO da
  aplicacao (o mesmo que a janela de anexos vai ler)."
  [tx {:keys [id ente-id objeto-tipo objeto-id corpo complementado-em complementado-por]}]
  {:pre [(some? ente-id) (some? id) (some? objeto-tipo) (some? objeto-id) (some? corpo)
         (some? complementado-em) (some? complementado-por)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.complemento
                  :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id :corpo corpo
                            :complementado_em complementado-em :complementado_por complementado-por}]
                  :returning cols}))))

(defn listar-do-objeto
  "Os complementos de UM protocolo, na ordem em que chegaram (`seq`, a ordem de insercao)."
  [tx ente-id objeto-tipo objeto-id]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.complemento]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]]
                  :order-by [[:seq :asc]]}))))

(defn listar-por-objetos
  "Os complementos de VARIOS protocolos do MESMO tipo numa UNICA consulta (a lista 'meus protocolos' le tudo sem uma ida ao
  banco por item). `ids` vazio -> [] sem consultar (IN () nao e' SQL valido)."
  [tx ente-id objeto-tipo ids]
  {:pre [(some? ente-id) (some? objeto-tipo)]}
  (if (empty? ids)
    []
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format {:select cols :from [:participacao.complemento]
                    :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:in :objeto_id ids]]
                    :order-by [[:seq :asc]]})))))
