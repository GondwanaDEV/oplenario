(ns oplenario.sessoes.db.presenca
  "Persistencia da PRESENCA (§22.6 eixo C, F4.3a) — funcoes sobre a `tx` do tenant (RLS isola). presenca_evento
  e' APPEND-ONLY (registrar-evento! / listar-eventos = auditoria). A presenca DERIVADA (esta-presente-em? + os
  agregadores de quorum) vive em sessoes/relacoes/presenca (camada de relacao, F4.3b — ADR-0001 §3-bis: o db/
  nao e' importado por relacoes; ambos escrevem HoneySQL). justificativa_ausencia e' ato apartado com state
  machine (decidir-justificativa! = CAS + transicao validada). HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

;; ---------- presenca_evento (append-only) ----------

(defn registrar-evento!
  "Grava um evento de presenca (append-only). `ocorrido-em` = instante de DOMINIO (quando ocorreu); o
  efetivado_em=now() e' o instante de AUDIT. Valida tipo/modalidade/fonte (fail-closed). Devolve {:id}."
  [tx {:keys [id ente-id sessao-id vereador-id tipo modalidade fonte ocorrido-em created-by]}]
  (logic/validar-tipo-evento tipo)
  (logic/validar-modalidade-presenca modalidade)
  (logic/validar-fonte fonte)
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.presenca_evento
                 :values [{:id id :ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                           :tipo tipo :modalidade modalidade :fonte fonte :ocorrido_em ocorrido-em
                           :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn listar-eventos
  "Todos os eventos da sessao em ordem cronologica (auditoria; a presenca corrente e' derivada, nao listada)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :ente_id :sessao_id :vereador_id :tipo :modalidade :fonte :ocorrido_em :efetivado_em]
                  :from [:sessoes.presenca_evento]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:ocorrido_em :asc] [:id :asc]]}))))

;; ---------- justificativa_ausencia (ato apartado, state machine) ----------

(defn criar-justificativa!
  "Cria a justificativa de ausencia 'pendente' (a UNIQUE barra segunda p/ o mesmo vereador na sessao). Devolve {:id}."
  [tx {:keys [id ente-id sessao-id vereador-id motivo created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.justificativa_ausencia
                 :values [{:id id :ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                           :estado "pendente" :motivo motivo :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn buscar-justificativa [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :ente_id :sessao_id :vereador_id :estado :motivo :decidido_por :decidido_em :lock_version]
                  :from [:sessoes.justificativa_ausencia]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:sessoes.justificativa_ausencia]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn decidir-justificativa!
  "Decide a justificativa: estado alvo aprovada|indeferida (terminal), via maquina logic/transicao-justificativa-valida?
  (fail-closed) com CAS por lock_version, carimbando decisor + instante. Lanca em estado-alvo invalido, transicao
  invalida, conflito de lock ou inexistente. Devolve {:de :para}."
  [tx {:keys [ente-id id estado decidido-por lock-version]}]
  (when-not (contains? logic/estados-justificativa-terminais estado)
    (throw (ex-info "decidir-justificativa!: estado alvo deve ser aprovada|indeferida" {:estado estado})))
  (when (nil? decidido-por)
    (throw (ex-info "decidir-justificativa!: decidido-por e' obrigatorio (trilha de quem decidiu)" {:id id})))
  (let [{atual :estado db-lock :lock-version} (estado+lock tx ente-id id)]
    (when (nil? atual)
      (throw (ex-info "decidir-justificativa!: justificativa inexistente" {:id id :ente-id ente-id})))
    (when (not= db-lock lock-version)
      (throw (ex-info "decidir-justificativa!: conflito de lock_version" {:id id :esperado lock-version :atual db-lock})))
    (when-not (logic/transicao-justificativa-valida? atual estado)
      (throw (ex-info "decidir-justificativa!: transicao de estado invalida" {:id id :de atual :para estado})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :sessoes.justificativa_ausencia
                           :set {:estado estado :decidido_por decidido-por :decidido_em [:now]
                                 :updated_by decidido-por :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "decidir-justificativa!: conflito de lock_version ou inexistente" {:id id :lock-version lock-version})))
      {:de atual :para estado})))
