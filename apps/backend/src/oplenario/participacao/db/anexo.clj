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

(def ^:private cols-base
  [:id :objeto_tipo :objeto_id :origem :nome :tipo_midia :bytes :sha256 :chave_objeto :enviado_em :enviado_por
   :substitui_anexo_id])

(def ^:private cols
  "As colunas do anexo + a RETIRADA (LEFT JOIN `anexo_retirada`): `retirado-em`, `retirado-por` e `motivo-da-retirada` sao nil
  enquanto o anexo esta vigente. + a SUBSTITUICAO: `substitui-anexo-id` (este anexo e' a troca de qual) e `substituido-por`
  (qual anexo o trocou: a relacao inversa, `s`). O que sai para a tela e' filtrado no adapters/out (o motivo so' vai ao balcao)."
  [:a.id :a.objeto_tipo :a.objeto_id :a.origem :a.nome :a.tipo_midia :a.bytes :a.sha256 :a.chave_objeto :a.enviado_em
   :a.enviado_por :a.substitui_anexo_id
   [:r.retirado_em :retirado_em] [:r.retirado_por :retirado_por] [:r.motivo :motivo_da_retirada]
   [:s.id :substituido_por]])

(def ^:private de-anexo-com-retirada
  ;; `s` = o anexo que SUBSTITUI este (no maximo um: indice unico parcial), sem duplicar linha
  {:from [[:participacao.anexo :a]]
   :left-join [[:participacao.anexo_retirada :r] [:and [:= :r.ente_id :a.ente_id] [:= :r.anexo_id :a.id]]
               [:participacao.anexo :s] [:and [:= :s.ente_id :a.ente_id] [:= :s.substitui_anexo_id :a.id]]]})

(defn travar!
  "Serializa os anexos do MESMO protocolo nesta tx (trava consultiva de transacao — a tabela e' INSERT-only, sem GRANT de
  UPDATE para um `FOR UPDATE`): dois envios ao mesmo tempo nao passam juntos do limite."
  [tx ente-id objeto-tipo objeto-id]
  (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(hashtextextended(?, 2026100400184))"
                         (str "anexos-do-atendimento:" ente-id ":" objeto-tipo ":" objeto-id)]))

(defn inserir!
  "Registra um anexo (append-only). Devolve o mapa kebab (RETURNING). `enviado-em` (opcional) e' o instante do RELOGIO da
  aplicacao: a cota de 24 h e a janela falam o mesmo relogio; sem ele, o default do banco (`now()`). `substitui-anexo-id`
  (opcional) = este anexo e' a TROCA de outro do mesmo protocolo (a retirada do antigo e' gravada pelo chamador, na mesma tx)."
  [tx {:keys [id ente-id objeto-tipo objeto-id origem nome tipo-midia bytes sha256 chave-objeto enviado-por enviado-em
              substitui-anexo-id]}]
  {:pre [(some? ente-id) (some? id) (some? objeto-id) (some? objeto-tipo) (some? origem) (some? nome)
         (some? tipo-midia) (some? bytes) (some? sha256) (some? chave-objeto) (some? enviado-por)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.anexo
                  :values [(cond-> {:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id :origem origem
                                    :nome nome :tipo_midia tipo-midia :bytes bytes :sha256 sha256 :chave_objeto chave-objeto
                                    :enviado_por enviado-por}
                             enviado-em (assoc :enviado_em enviado-em)
                             substitui-anexo-id (assoc :substitui_anexo_id substitui-anexo-id))]
                  :returning cols-base}))))

(defn listar-do-objeto
  "Os anexos de UM protocolo, na ordem em que chegaram (`seq`, a ordem de insercao; com a retirada, se houve)."
  [tx ente-id objeto-tipo objeto-id]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format (merge de-anexo-com-retirada
                        {:select cols
                         :where [:and [:= :a.ente_id ente-id] [:= :a.objeto_tipo objeto-tipo] [:= :a.objeto_id objeto-id]]
                         :order-by [[:a.seq :asc]]})))))

(defn listar-por-objetos
  "Os anexos de VARIOS protocolos do MESMO tipo numa UNICA consulta (a lista 'meus protocolos' le tudo sem uma ida ao
  banco por item). `ids` vazio -> [] sem consultar (IN () nao e' SQL valido)."
  [tx ente-id objeto-tipo ids]
  {:pre [(some? ente-id) (some? objeto-tipo)]}
  (if (empty? ids)
    []
    (comum/linhas->kebab
     (jdbc/execute! tx
       (sql/format (merge de-anexo-com-retirada
                          {:select cols
                           :where [:and [:= :a.ente_id ente-id] [:= :a.objeto_tipo objeto-tipo] [:in :a.objeto_id ids]]
                           :order-by [[:a.seq :asc]]}))))))

(defn contar-da-origem
  "Quantos anexos VIGENTES de uma `origem` (`casa` | `requerente`) este protocolo ja' tem: o limite de 5 e' POR ORIGEM, e o
  anexo retirado devolve a vaga."
  [tx ente-id objeto-tipo objeto-id origem]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id) (#{"casa" "requerente"} origem)]}
  (long (:n (jdbc/execute-one! tx
              (sql/format (merge de-anexo-com-retirada
                                 {:select [[[:count :*] :n]]
                                  :where [:and [:= :a.ente_id ente-id] [:= :a.objeto_tipo objeto-tipo] [:= :a.objeto_id objeto-id]
                                          [:= :a.origem origem] [:= :r.anexo_id nil]]}))
              {:builder-fn rs/as-unqualified-maps}))))

(defn somar-bytes-do-requerente
  "Quantos bytes ESTA identidade anexou (origem `requerente`) nesta Casa desde `desde` — a cota de disco. Conta tambem o que
  foi retirado depois: retirar nao devolve espaco (o arquivo ja' subiu e ocupou a cota)."
  [tx ente-id identidade-id desde]
  {:pre [(some? ente-id) (some? identidade-id) (some? desde)]}
  (long (:n (jdbc/execute-one! tx
              (sql/format {:select [[[:coalesce [:sum :bytes] 0] :n]] :from [:participacao.anexo]
                           :where [:and [:= :ente_id ente-id] [:= :origem "requerente"] [:= :enviado_por identidade-id]
                                   [:>= :enviado_em desde]]})
              {:builder-fn rs/as-unqualified-maps}))))

(defn achar-igual
  "O anexo VIGENTE (nao retirado) deste protocolo, desta `origem`, com o mesmo `sha256` — o reenvio idempotente. nil se nao ha."
  [tx ente-id objeto-tipo objeto-id origem sha256]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id) (some? origem) (some? sha256)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format (merge de-anexo-com-retirada
                        {:select cols
                         :where [:and [:= :a.ente_id ente-id] [:= :a.objeto_tipo objeto-tipo] [:= :a.objeto_id objeto-id]
                                 [:= :a.origem origem] [:= :a.sha256 sha256] [:= :r.anexo_id nil]]
                         :order-by [[:a.seq :asc]]
                         :limit 1})))))

(defn buscar
  "UM anexo, pelo protocolo a que pertence (um id de anexo de OUTRO protocolo da mesma Casa nao casa), com a retirada se houve."
  [tx ente-id objeto-tipo objeto-id anexo-id]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id) (some? anexo-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format (merge de-anexo-com-retirada
                        {:select cols
                         :where [:and [:= :a.ente_id ente-id] [:= :a.objeto_tipo objeto-tipo] [:= :a.objeto_id objeto-id]
                                 [:= :a.id anexo-id]]})))))

(defn retirar!
  "Registra a RETIRADA do anexo `anexo-id` (append-only). Idempotente: uma retirada por anexo — retirar de novo nao grava uma
  segunda linha (`ON CONFLICT DO NOTHING`), e o chamador le o anexo de volta (com a retirada que ficou, a primeira)."
  [tx {:keys [ente-id anexo-id retirado-em retirado-por motivo]}]
  {:pre [(some? ente-id) (some? anexo-id) (some? retirado-em) (some? retirado-por) (some? motivo)]}
  (jdbc/execute-one! tx
    ["INSERT INTO participacao.anexo_retirada (ente_id, anexo_id, retirado_em, retirado_por, motivo)
      VALUES (?, ?, ?, ?, ?) ON CONFLICT (ente_id, anexo_id) DO NOTHING"
     ente-id anexo-id retirado-em retirado-por motivo]))

(defn chaves-da-casa
  "Toda chave de blob que a Casa tem em `participacao.anexo`, com `retirado?` (ha' linha em `anexo_retirada`) .
  Para a reconciliacao com o object storage: o anexo RETIRADO nao tem blob de proposito. So' SELECT, sem limite: a
  comparacao precisa do conjunto inteiro."
  [tx ente-id]
  {:pre [(some? ente-id)]}
  (mapv (fn [r] {:chave (:chave r) :retirado? (boolean (:retirado r))})
        (jdbc/execute! tx
          (sql/format {:select [[:a.chave_objeto :chave] [[:is-not :r.anexo_id nil] :retirado]]
                       :from [[:participacao.anexo :a]]
                       :left-join [[:participacao.anexo_retirada :r] [:and [:= :r.ente_id :a.ente_id] [:= :r.anexo_id :a.id]]]
                       :where [:= :a.ente_id ente-id]
                       :order-by [[:a.chave_objeto :asc]]})
          {:builder-fn rs/as-unqualified-maps})))
