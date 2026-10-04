(ns oplenario.participacao.db.anexo
  "Persistencia de 'participacao.anexo' (mig 20261004000184) — os arquivos do ATENDIMENTO ao cidadao. APPEND-ONLY
  (Inv.10): SO `inserir!` + selects (sem UPDATE/DELETE; a migration nega o grant + trg_participacao_anexo_append_only).
  O protocolo e' POLIMORFICO (`objeto_tipo` + `objeto_id`, a mesma forma de prazo_ativo/prorrogacao) — nao ha FK, entao o
  chamador (o Repo) so' grava depois de conferir que o protocolo existe NESTA Casa. `enviado_por` e' de auditoria e
  fica no dominio: o contrato de saida (adapters/out) o filtra. HoneySQL schema-qualified; ente_id em TODA query. IMPL
  atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :objeto_tipo :objeto_id :origem :nome :tipo_midia :bytes :sha256 :chave_objeto :enviado_em :enviado_por])

(defn travar!
  "Serializa os anexos do MESMO protocolo nesta tx (trava consultiva de transacao — a tabela e' INSERT-only, sem GRANT de
  UPDATE para um `FOR UPDATE`): dois envios ao mesmo tempo nao passam juntos do limite."
  [tx ente-id objeto-tipo objeto-id]
  (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(hashtextextended(?, 2026100400184))"
                         (str "anexos-do-atendimento:" ente-id ":" objeto-tipo ":" objeto-id)]))

(defn inserir!
  "Registra um anexo (append-only). Devolve o mapa kebab (RETURNING)."
  [tx {:keys [id ente-id objeto-tipo objeto-id origem nome tipo-midia bytes sha256 chave-objeto enviado-por]}]
  {:pre [(some? ente-id) (some? id) (some? objeto-id) (some? objeto-tipo) (some? origem) (some? nome)
         (some? tipo-midia) (some? bytes) (some? sha256) (some? chave-objeto) (some? enviado-por)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.anexo
                  :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id :origem origem
                            :nome nome :tipo_midia tipo-midia :bytes bytes :sha256 sha256 :chave_objeto chave-objeto
                            :enviado_por enviado-por}]
                  :returning cols}))))

(defn listar-do-objeto
  "Os anexos de UM protocolo, na ordem em que chegaram."
  [tx ente-id objeto-tipo objeto-id]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.anexo]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]]
                  :order-by [[:enviado_em :asc] [:id :asc]]}))))

(defn listar-por-objetos
  "Os anexos de VARIOS protocolos do MESMO tipo numa UNICA consulta (a lista 'meus protocolos' le tudo sem uma ida ao
  banco por item). `ids` vazio -> [] sem consultar (IN () nao e' SQL valido)."
  [tx ente-id objeto-tipo ids]
  {:pre [(some? ente-id) (some? objeto-tipo)]}
  (if (empty? ids)
    []
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format {:select cols :from [:participacao.anexo]
                    :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:in :objeto_id ids]]
                    :order-by [[:enviado_em :asc] [:id :asc]]})))))

(defn contar-da-origem
  "Quantos anexos de uma `origem` (`casa` | `requerente`) este protocolo ja' tem: o limite de 5 e' POR ORIGEM."
  [tx ente-id objeto-tipo objeto-id origem]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id) (#{"casa" "requerente"} origem)]}
  (long (:n (jdbc/execute-one! tx
              (sql/format {:select [[[:count :*] :n]] :from [:participacao.anexo]
                           :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]
                                   [:= :origem origem]]})
              {:builder-fn rs/as-unqualified-maps}))))

(defn buscar
  "UM anexo, pelo protocolo a que pertence (um id de anexo de OUTRO protocolo da mesma Casa nao casa)."
  [tx ente-id objeto-tipo objeto-id anexo-id]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id) (some? anexo-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.anexo]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]
                          [:= :id anexo-id]]}))))
