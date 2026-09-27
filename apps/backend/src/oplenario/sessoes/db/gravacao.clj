(ns oplenario.sessoes.db.gravacao
  "Persistencia da GRAVACAO de sessao (§22.6 eixo D, F4.4b) — funcoes sobre a `tx` do tenant (RLS isola).
  `gravacao_segmento` e' unidade TECNICA do arquivo (registrar-segmento! = captura/ingestao). A vinculacao
  arquivo<->sessao e' Opcao A (upload primeiro, servidor vincula depois): `vincular-segmento!` seta sessao_id
  UMA VEZ (WHERE sessao_id IS NULL + CAS por lock_version). O estado de processamento e' EMERGENTE (sem coluna
  status). HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :sessao_id :iniciou_em :encerrou_em :motivo_inicio :motivo_fim
   :container_bruto_uri :audio_uri :video_uri :audio_hash :fonte_ingestao :acesso_restrito :lock_version])

(defn registrar-segmento!
  "Grava um segmento de gravacao (captura/ingestao). `sessao-id` e' OPCIONAL (Opcao A: o arquivo pode chegar
  do CLI/watch folder antes de o servidor vincular). `acesso-restrito` (default false) DEVE vir true p/ sessao
  secreta — o controlador (eixo G/wire/in) e' responsavel por passa-lo. Valida motivos + fonte (fail-closed).
  RETURNING `lock_version` (ledger de prontidao Fase 8 achado #2): um segmento AINDA NAO vinculado nunca
  aparece em `listar-segmentos-da-sessao` (so' lista os JA' vinculados), entao este recibo e' a UNICA fonte
  do token de CAS que `vincular-segmento!` exige — sem ele, vincular fica impossivel de montar so' pela API.
  Devolve {:id :lock-version}."
  [tx {:keys [id ente-id sessao-id iniciou-em encerrou-em motivo-inicio motivo-fim
              container-bruto-uri audio-uri video-uri audio-hash fonte-ingestao acesso-restrito created-by]}]
  (logic/validar-motivo-inicio motivo-inicio)
  (logic/validar-motivo-fim motivo-fim)
  (logic/validar-fonte-ingestao fonte-ingestao)
  (let [r (comum/linha->kebab
           (jdbc/execute-one! tx
             (sql/format {:insert-into :sessoes.gravacao_segmento
                          :values [{:id id :ente_id ente-id :sessao_id sessao-id
                                    :iniciou_em iniciou-em :encerrou_em encerrou-em
                                    :motivo_inicio motivo-inicio :motivo_fim motivo-fim
                                    :container_bruto_uri container-bruto-uri :audio_uri audio-uri
                                    :video_uri video-uri :audio_hash audio-hash :fonte_ingestao fonte-ingestao
                                    :acesso_restrito (boolean acesso-restrito)
                                    :created_by created-by :efetivado_em [:now]}]
                          :returning [:lock_version]})))]
    {:id id :lock-version (:lock-version r)}))

(defn buscar
  "Busca um segmento por id no tenant (RLS via ente-id no WHERE). Devolve o mapa kebab-case ou nil (not-found)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:sessoes.gravacao_segmento]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-segmentos-da-sessao
  "Segmentos vinculados a `sessao-id`, em ordem cronologica de iniciou_em (read-model do painel da sessao)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:sessoes.gravacao_segmento]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:iniciou_em :asc] [:id :asc]]}))))

(defn vincular-segmento!
  "Vincula o segmento a uma sessao (Opcao A). Vinculo UMA-VEZ: o WHERE sessao_id IS NULL barra re-vincular um
  ja vinculado (CAS por lock_version tambem). `forcar-acesso-restrito` (sigilo §22.6): quando a sessao-alvo e'
  SECRETA o controlador o passa true e o vinculo RE-deriva `acesso_restrito`=true (o flag da ingestao Opcao A
  pode ter vindo false do cliente) — caso contrario a coluna fica intacta. Lanca em conflito/ja-vinculado/
  inexistente (`:tipo :conflito/vinculo` -> a borda mapeia 409, nunca 500). Devolve {:id :sessao-id}."
  [tx {:keys [ente-id id sessao-id lock-version updated-by forcar-acesso-restrito]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :sessoes.gravacao_segmento
                         :set (cond-> {:sessao_id sessao-id :updated_by updated-by :atualizado_em [:now]
                                       :lock_version [:+ :lock_version 1]}
                                forcar-acesso-restrito (assoc :acesso_restrito true))
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]
                                 [:= :sessao_id nil]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "vincular-segmento!: ja vinculada, conflito de lock_version ou inexistente"
                      {:tipo :conflito/vinculo :id id :sessao-id sessao-id :lock-version lock-version})))
    {:id id :sessao-id sessao-id}))

(defn listar-pendentes
  "Segmentos da Casa AINDA SEM sessao (Faixa A / A.2: chegaram do utilitario de captacao e esperam a secretaria
  vincular), mais recentes primeiro. `limite` corta a lista (a tela mostra a fila, nao o arquivo morto)."
  [tx ente-id limite]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:sessoes.gravacao_segmento]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id nil]]
                  :order-by [[:iniciou_em :desc] [:id :asc]]
                  :limit limite}))))

(defn sessoes-candidatas-a-pendentes
  "Sessoes da Casa cujo inicio (aberta_em, ou agendada_para) cai a ate 1 dia das gravacoes pendentes (sem
  sessao) — as candidatas a sugestao de vinculo. So' os campos que a sugestao e a tela usam. Sem pendentes, o
  BETWEEN com NULL nao casa nada: lista vazia."
  [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     [(str "SELECT s.id, s.tipo_sessao, s.numero_sequencial, s.estado, s.agendada_para, s.aberta_em, s.encerrada_em"
           "  FROM sessoes.sessao s,"
           "       (SELECT min(iniciou_em) AS de, max(iniciou_em) AS ate FROM sessoes.gravacao_segmento"
           "         WHERE ente_id = ? AND sessao_id IS NULL) p"
           " WHERE s.ente_id = ?"
           "   AND COALESCE(s.aberta_em, s.agendada_para)"
           "       BETWEEN p.de - interval '1 day' AND p.ate + interval '1 day'")
      ente-id ente-id])))
