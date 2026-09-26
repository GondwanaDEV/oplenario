(ns oplenario.sessoes.db.tribuna
  "Persistencia da TRIBUNA (§22.6 eixo F) — funcoes sobre a `tx` do tenant (RLS isola). F4.5a: `inscricao_oradores`
  e' a camada de INTENCAO (intencao != execucao). `inscrever!` numera a fila (ordem = max+1 por sessao+fase);
  `desistir!` move inscrita -> desistencia (terminal) via maquina + CAS por lock_version. HoneySQL schema-qualified;
  ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols-inscricao
  [:id :ente_id :sessao_id :vereador_id :origem_inscricao :fase :proposicao_ref_id :estado :ordem :lock_version])

;; ---------- inscricao_oradores (intencao) ----------

(defn- proxima-ordem
  "ordem = max+1 da FILA por (sessao, fase). Sem FOR UPDATE: sob concorrencia (varios pedidos intra-sessao ao
  vivo) duas inscricoes podem colidir na mesma `ordem` — decisao deliberada (espelha pauta_item, F4.2a): `ordem`
  e' sort hint, nao identidade; o desempate por `criado_em`/`id` em listar-inscricoes mantem ordenacao estavel."
  [tx ente-id sessao-id fase]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:+ [:coalesce [:max :ordem] 0] 1] :prox]] :from [:sessoes.inscricao_oradores]
                     :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :fase fase]]}))
      :prox))

(defn inscrever!
  "Inscreve um orador (camada de INTENCAO). Valida origem/fase (fail-closed); numera a fila por (sessao, fase).
  Nasce 'inscrita'. Devolve {:id :ordem}."
  [tx {:keys [id ente-id sessao-id vereador-id origem-inscricao fase proposicao-ref-id created-by]}]
  (logic/validar-origem-inscricao origem-inscricao)
  (logic/validar-fase fase)
  (when (nil? created-by)
    (throw (ex-info "inscrever!: created-by e' obrigatorio (trilha de quem inscreveu)" {:id id})))
  (let [ordem (proxima-ordem tx ente-id sessao-id fase)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :sessoes.inscricao_oradores
                   :values [{:id id :ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                             :origem_inscricao origem-inscricao :fase fase :proposicao_ref_id proposicao-ref-id
                             :estado "inscrita" :ordem ordem :created_by created-by :efetivado_em [:now]}]}))
    {:id id :ordem ordem}))

(defn buscar-inscricao
  "Busca uma inscricao por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil (not-found)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols-inscricao :from [:sessoes.inscricao_oradores]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-inscricoes
  "Inscricoes da sessao (fila), ordenadas por fase e por `ordem` dentro da fase (desempate por criacao)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-inscricao :from [:sessoes.inscricao_oradores]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:fase :asc] [:ordem :asc] [:criado_em :asc] [:id :asc]]}))))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:sessoes.inscricao_oradores]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn desistir!
  "Move a inscricao para 'desistencia' (terminal) via maquina logic/transicao-inscricao-valida? (fail-closed)
  com CAS por lock_version. Lanca em transicao invalida (ja desistiu), conflito de lock ou inexistente.
  Devolve {:de :para}."
  [tx {:keys [ente-id id lock-version updated-by]}]
  (when (nil? updated-by)
    (throw (ex-info "desistir!: updated-by e' obrigatorio (trilha de quem registrou a desistencia)" {:id id})))
  ;; :tipo :conflito/inscricao em TODOS os modos de falha (inexistente / lock-stale / ja-desistiu): a borda HTTP
  ;; os mapeia a 409 (espelha :conflito/transicao da sessao). Sem o tag, virariam 500 (review da borda F4 eixo A/G).
  (let [{atual :estado db-lock :lock-version} (estado+lock tx ente-id id)]
    (when (nil? atual)
      (throw (ex-info "desistir!: inscricao inexistente" {:tipo :conflito/inscricao :id id :ente-id ente-id})))
    (when (not= db-lock lock-version)
      (throw (ex-info "desistir!: conflito de lock_version" {:tipo :conflito/inscricao :id id :esperado lock-version :atual db-lock})))
    (when-not (logic/transicao-inscricao-valida? atual "desistencia")
      (throw (ex-info "desistir!: transicao de estado invalida" {:tipo :conflito/inscricao :id id :de atual :para "desistencia"})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :sessoes.inscricao_oradores
                           :set {:estado "desistencia" :updated_by updated-by :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "desistir!: conflito de lock_version ou inexistente" {:tipo :conflito/inscricao :id id :lock-version lock-version})))
      {:de atual :para "desistencia"})))

;; ---------- fala_executada + cronometro (execucao, F4.5b) ----------

(def ^:private cols-fala
  [:id :ente_id :sessao_id :inscricao_id :orador_id :tipo_fala :fala_pai_id :fase :proposicao_ref_id
   :iniciou_em :encerrou_em :tempo_efetivamente_usado_segundos :tempo_concedido_segundos :lock_version])

(def ^:private cols-cronometro
  [:id :ente_id :fala_id :tipo :ocorrido_em :segundos_adicionais])

(defn- logar-cronometro!
  "Insere um evento append-only no cronometro da fala (mesma tx do ato). `id` opcional — quando o caller precisa
  do id de volta (registrar-evento-cronometro!) ele o passa; iniciar/encerrar nao precisam e deixam gerar."
  [tx {:keys [id ente-id fala-id tipo ocorrido-em segundos-adicionais created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.fala_cronometro_evento
                 :values [{:id (or id (random-uuid)) :ente_id ente-id :fala_id fala-id :tipo tipo
                           :ocorrido_em ocorrido-em :segundos_adicionais segundos-adicionais
                           :created_by created-by :efetivado_em [:now]}]})))

(defn tempo-regimental
  "Segundos que o regimento DESTA Casa da' a um `tipo-fala` na `fase`, ou nil (a Casa nao configurou — a fala
  corre sem limite, como antes da mig 0081). Le as (no maximo duas) linhas candidatas — a da fase e a generica
  (fase NULL) — e deixa `logic/escolher-tempo-regimental` (pura) decidir: a especifica vence. RLS isola."
  [tx ente-id fase tipo-fala]
  (logic/escolher-tempo-regimental
   (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:fase :segundos] :from [:sessoes.tempo_regimental]
                   :where [:and [:= :ente_id ente-id] [:= :tipo_fala tipo-fala]
                           [:or [:= :fase fase] [:= :fase nil]]]})))))

(defn definir-tempo-regimental!
  "Define o tempo regimental de (fase, tipo-fala) nesta Casa — `fase` nil = a linha generica ('em qualquer
  fase'). Substitui a linha anterior do MESMO par (DELETE + INSERT na mesma tx, em vez de ON CONFLICT: o indice
  unico e' de EXPRESSAO, COALESCE(fase,''), e a inferencia do ON CONFLICT com parametro nao casa com ele).
  Valida o vocabulario e segundos > 0 antes do CHECK do banco (fail-closed). Devolve {:segundos}."
  [tx {:keys [ente-id fase tipo-fala segundos referencia-normativa created-by]}]
  (logic/validar-tipo-fala tipo-fala)
  (when (some? fase) (logic/validar-fase fase))
  (when-not (and (int? segundos) (pos? segundos))
    (throw (ex-info "definir-tempo-regimental!: segundos deve ser inteiro > 0" {:segundos segundos})))
  (jdbc/execute-one! tx
    (sql/format {:delete-from :sessoes.tempo_regimental
                 :where [:and [:= :ente_id ente-id] [:= :tipo_fala tipo-fala]
                         [:= :fase fase]]}))  ; fase nil -> IS NULL (HoneySQL)
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.tempo_regimental
                 :values [{:ente_id ente-id :fase fase :tipo_fala tipo-fala :segundos segundos
                           :referencia_normativa referencia-normativa :created_by created-by
                           :efetivado_em [:now]}]}))
  {:segundos segundos})

(defn iniciar-fala!
  "Inicia uma fala (execucao): INSERE a fala (em curso) e LOGA o evento 'iniciada' (ocorrido_em = iniciou-em),
  atomico. Valida tipo-fala/fase + nil-guard de auditoria (fail-closed); a coerencia aparte<->fala_pai_id e' o
  CHECK da migration. `inscricao-id`/`fala-pai-id`/`proposicao-ref-id` opcionais.

  TEMPO-LIMITE (mig 0081): `tempo-concedido-segundos` informado pela Mesa vence; ausente, vale o regimental da
  Casa para (fase, tipo) (`tempo-regimental`, na MESMA tx); nenhum dos dois = nil (sem limite). O valor e'
  FOTOGRAFADO na fala — reconfigurar a Casa depois nao muda a fala de quem ja' esta na tribuna. Devolve
  {:id :tempo-concedido-segundos}."
  [tx {:keys [id ente-id sessao-id inscricao-id orador-id tipo-fala fala-pai-id fase proposicao-ref-id
              iniciou-em created-by tempo-concedido-segundos]}]
  (logic/validar-tipo-fala tipo-fala)
  (logic/validar-fase fase)
  (when (nil? created-by)
    (throw (ex-info "iniciar-fala!: created-by e' obrigatorio (trilha de quem registrou a fala)" {:id id})))
  (let [concedido (or tempo-concedido-segundos (tempo-regimental tx ente-id fase tipo-fala))]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :sessoes.fala_executada
                   :values [{:id id :ente_id ente-id :sessao_id sessao-id :inscricao_id inscricao-id
                             :orador_id orador-id :tipo_fala tipo-fala :fala_pai_id fala-pai-id :fase fase
                             :proposicao_ref_id proposicao-ref-id :iniciou_em iniciou-em
                             :tempo_concedido_segundos concedido
                             :created_by created-by :efetivado_em [:now]}]}))
    (logar-cronometro! tx {:ente-id ente-id :fala-id id :tipo "iniciada" :ocorrido-em iniciou-em
                           :created-by created-by})
    {:id id :tempo-concedido-segundos concedido}))

(defn registrar-evento-cronometro!
  "Registra um evento MANUAL do cronometro (pausada|retomada|aparte_concedido|tempo_adicional_concedido) —
  iniciada/encerrada sao do ciclo da fala (iniciar-fala!/encerrar-fala!). Valida tipo + coerencia de
  segundos_adicionais + nil-guard (fail-closed). Append-only. Devolve {:id}."
  [tx {:keys [ente-id fala-id tipo ocorrido-em segundos-adicionais created-by]}]
  (logic/validar-evento-cronometro tipo segundos-adicionais)
  (when (nil? created-by)
    (throw (ex-info "registrar-evento-cronometro!: created-by e' obrigatorio (trilha de auditoria)" {:fala-id fala-id})))
  (let [eid (random-uuid)]
    (logar-cronometro! tx {:id eid :ente-id ente-id :fala-id fala-id :tipo tipo :ocorrido-em ocorrido-em
                           :segundos-adicionais segundos-adicionais :created-by created-by})
    {:id eid}))

(defn buscar-fala
  "Busca uma fala por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil (not-found)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols-fala :from [:sessoes.fala_executada]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-falas-da-sessao
  "Falas da sessao em ordem cronologica de iniciou_em (read-model do painel + ancora de diarizacao)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-fala :from [:sessoes.fala_executada]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:iniciou_em :asc] [:id :asc]]}))))

(defn fala-em-curso
  "A fala ATUALMENTE em curso da sessao — `encerrou_em IS NULL`, o mesmo filtro que `encerrar-fala!` exige
  (`fala-para-encerrar` acima) para aceitar encerrar. DUAS falas abertas ao MESMO TEMPO e' o caso NORMAL
  do aparte, nao um dado incoerente: `iniciar-fala!` NAO tem guarda contra abrir um aparte com a fala-mae
  ainda sem `encerrar-fala!` (`apartes-via-fala-pai` em `tribuna_db_test.clj` grava exatamente isso), e a
  Mesa ao vivo tipicamente so' encerra a fala principal, nao o aparte. Por isso a query nao pega
  'a unica aberta' — pega a MAIS RECENTE por `iniciou_em` (desempate estavel por `id`), a mesma regra que
  o reducer do SSE aplica no canal (`fala.iniciada` de um aparte SUBSTITUI o orador atual): durante o
  aparte, o aparteante e' quem esta com a palavra na tela, e quando ele nao encerra, e' quem continua
  aparecendo — decisao explicita, nao lacuna. Devolve o mapa kebab-case ou nil (ninguem com a palavra
  agora — o read-model da tribuna projeta `orador-atual` nil neste caso)."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols-fala :from [:sessoes.fala_executada]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :encerrou_em nil]]
                  :order-by [[:iniciou_em :desc] [:id :desc]]
                  :limit 1}))))

(defn listar-apartes
  "Apartes de uma fala-mae (reconstroi 'fala principal com apartes'), em ordem cronologica."
  [tx ente-id fala-pai-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-fala :from [:sessoes.fala_executada]
                  :where [:and [:= :ente_id ente-id] [:= :fala_pai_id fala-pai-id]]
                  :order-by [[:iniciou_em :asc] [:id :asc]]}))))

(defn listar-eventos-cronometro
  "Eventos do cronometro da fala em ordem cronologica (a projecao do cronometro le daqui)."
  [tx ente-id fala-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-cronometro :from [:sessoes.fala_cronometro_evento]
                  :where [:and [:= :ente_id ente-id] [:= :fala_id fala-id]]
                  :order-by [[:ocorrido_em :asc] [:id :asc]]}))))

(defn- fala-para-encerrar
  "Le a fala p/ encerrar (FOR UPDATE serializa): iniciou_em, encerrou_em, lock. Valida existencia, lock e que a
  fala AINDA esta em curso (encerrou_em IS NULL) — encerrar duas vezes corromperia o tempo computado.
  :tipo :conflito/fala em TODOS os modos de falha (inexistente / lock-stale / ja-encerrada): a borda HTTP os
  mapeia a 409 (espelha :conflito/inscricao da desistencia). Sem o tag, virariam 500 (review da borda F4 eixo F)."
  [tx ente-id id lock-version]
  (let [{:keys [iniciou-em encerrou-em] db-lock :lock-version}
        (-> (jdbc/execute-one! tx
              (sql/format {:select [:iniciou_em :encerrou_em :lock_version] :from [:sessoes.fala_executada]
                           :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
            comum/linha->kebab)]
    (when (nil? iniciou-em)
      (throw (ex-info "encerrar-fala!: fala inexistente" {:tipo :conflito/fala :id id :ente-id ente-id})))
    (when (not= db-lock lock-version)
      (throw (ex-info "encerrar-fala!: conflito de lock_version" {:tipo :conflito/fala :id id :esperado lock-version :atual db-lock})))
    (when (some? encerrou-em)
      (throw (ex-info "encerrar-fala!: fala ja encerrada" {:tipo :conflito/fala :id id})))
    iniciou-em))

(defn encerrar-fala!
  "Encerra a fala: crava encerrou_em + COMPUTA o tempo efetivamente usado a partir dos eventos do cronometro
  (PROJECAO, nao snapshot) e LOGA o evento 'encerrada', atomico. CAS por lock_version + guard encerrou_em IS NULL
  (encerra uma vez). nil-guard de auditoria. Devolve {:id :tempo-efetivamente-usado-segundos}."
  [tx {:keys [ente-id id encerrou-em lock-version updated-by]}]
  (when (nil? updated-by)
    (throw (ex-info "encerrar-fala!: updated-by e' obrigatorio (trilha de quem encerrou)" {:id id})))
  (let [iniciou-em (fala-para-encerrar tx ente-id id lock-version)
        eventos    (listar-eventos-cronometro tx ente-id id)
        tempo      (logic/tempo-efetivo-segundos iniciou-em encerrou-em eventos)
        r (jdbc/execute-one! tx
            (sql/format {:update :sessoes.fala_executada
                         :set {:encerrou_em encerrou-em :tempo_efetivamente_usado_segundos tempo
                               :updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]
                                 [:= :encerrou_em nil]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "encerrar-fala!: conflito de lock_version, ja encerrada ou inexistente" {:tipo :conflito/fala :id id})))
    (logar-cronometro! tx {:ente-id ente-id :fala-id id :tipo "encerrada" :ocorrido-em encerrou-em
                           :created-by updated-by})
    {:id id :tempo-efetivamente-usado-segundos tempo}))

;; ---------- decisao_mesa (questao de ordem, append-only — F4.5c) ----------

(def ^:private cols-decisao
  [:id :ente_id :sessao_id :fala_id :presidente_id :questao :decisao :fundamentacao :decidido_em])

(defn registrar-decisao-mesa!
  "Registra a decisao do presidente sobre questao de ordem (ato regimental p/ a ata, APPEND-ONLY). nil-guard de
  auditoria em created-by/presidente-id (fail-closed); questao/decisao nao-vazias sao o CHECK da migration.
  `fala-id` opcional. Devolve {:id}."
  [tx {:keys [id ente-id sessao-id fala-id presidente-id questao decisao fundamentacao decidido-em created-by]}]
  (when (nil? created-by)
    (throw (ex-info "registrar-decisao-mesa!: created-by e' obrigatorio (trilha de auditoria)" {:id id})))
  (when (nil? presidente-id)
    (throw (ex-info "registrar-decisao-mesa!: presidente-id e' obrigatorio (quem decidiu)" {:id id})))
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.decisao_mesa
                 :values [{:id id :ente_id ente-id :sessao_id sessao-id :fala_id fala-id
                           :presidente_id presidente-id :questao questao :decisao decisao
                           :fundamentacao fundamentacao :decidido_em decidido-em
                           :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn buscar-decisao-mesa
  "Busca uma decisao da mesa por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil (not-found)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols-decisao :from [:sessoes.decisao_mesa]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-decisoes-mesa
  "Decisoes da mesa da sessao em ordem cronologica (composicao da ata)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-decisao :from [:sessoes.decisao_mesa]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:decidido_em :asc] [:id :asc]]}))))
