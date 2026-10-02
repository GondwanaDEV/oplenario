(ns oplenario.admin-sistema.db.ente
  "O REGISTRO DE CASAS (12.1, ADR-0016) — a tabela que EMITE o ente_id. SUPRATENANT (sem RLS): o console le todas; o
  relay ativa a Casa quando o 1o administrador entra. Ciclo: provisionar -> ativo -> suspenso -> ativo (ADR-0018 fatia 1)
  e suspenso (encerramento em curso) -> encerrado, sem volta (fatia 2; o banco tambem recusa, mig 0175). Toda transicao
  e' UPDATE condicional ao estado de origem (CAS): quem chega depois nao transiciona de novo."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:ente_id :nome :nome_curto :uf :municipio_ibge :municipio_nome :estado :criado_em :atualizado_em
   :provisionada_por :primeiro_admin_identidade_id :primeiro_admin_email :convite_enviado_em :ativada_em
   :motivo_restricao :restrita_desde :suspensao_agendada
   ;; ADR-0018 (fatia 2): a Casa encerrada
   :encerrada_em :destino_acervo_url :apagamento
   ;; mig 0177: a Casa fecha quando o apagamento comeca + o lease de uma execucao por Casa
   :apagamento_iniciado_em :apagamento_em_execucao_desde])

(defn- ->instant [v] (if (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v) v))

(defn- ->casa [r]
  (when r
    (-> (reduce #(update %1 %2 ->instant) (comum/linha->kebab r)
                [:criado-em :atualizado-em :convite-enviado-em :ativada-em :restrita-desde :encerrada-em
                 :apagamento-iniciado-em :apagamento-em-execucao-desde])
        (update :apagamento comum/jsonb->kw))))

(defn inserir!
  [conn {:keys [ente-id nome nome-curto uf municipio-ibge municipio-nome provisionada-por primeiro-admin-email]}]
  (jdbc/execute-one! conn (sql/format {:insert-into :admin_sistema.ente
                                       :values [{:ente_id ente-id :nome nome :nome_curto nome-curto :uf uf
                                                 :municipio_ibge municipio-ibge :municipio_nome municipio-nome
                                                 :estado "provisionar" :provisionada_por provisionada-por
                                                 :primeiro_admin_email primeiro-admin-email}]})))

(defn por-id [conn ente-id]
  (->casa (jdbc/execute-one! conn (sql/format {:select colunas :from [:admin_sistema.ente] :where [:= :ente_id ente-id]}))))

(defn listar [conn]
  (mapv ->casa (jdbc/execute! conn (sql/format {:select colunas :from [:admin_sistema.ente]
                                                :order-by [[:criado_em :desc] [:ente_id :asc]]}))))

(defn marcar-primeiro-admin! [conn ente-id identidade-id]
  (jdbc/execute-one! conn (sql/format {:update :admin_sistema.ente
                                       :set {:primeiro_admin_identidade_id identidade-id :atualizado_em [:now]}
                                       :where [:= :ente_id ente-id]})))

(defn marcar-convite! [conn ente-id]
  (jdbc/execute-one! conn (sql/format {:update :admin_sistema.ente
                                       :set {:convite_enviado_em [:now] :atualizado_em [:now]}
                                       :where [:= :ente_id ente-id]})))

(defn ativar!
  "provisionar -> ativo, so' uma vez (UPDATE condicional). true = ativou agora."
  [conn ente-id]
  (some? (jdbc/execute-one! conn (sql/format {:update :admin_sistema.ente
                                              :set {:estado "ativo" :ativada_em [:now] :atualizado_em [:now]}
                                              :where [:and [:= :ente_id ente-id] [:= :estado "provisionar"]]
                                              :returning [:ente_id]}))))

;; ---- ADR-0018: a restricao da Casa ----

(defn- atualizar-se! [conn ente-id condicao set]
  (->casa (jdbc/execute-one! conn (sql/format {:update :admin_sistema.ente
                                               :set (assoc set :atualizado_em [:now])
                                               :where [:and [:= :ente_id ente-id] condicao]
                                               :returning colunas}))))

(defn suspender!
  "-> `suspenso` com o motivo, desde agora. De `ativo` (qualquer motivo) ou, so' para o encerramento, tambem de uma
  Casa ja' suspensa (o motivo passa a ser o encerramento). Devolve a Casa, ou nil se o estado nao permitia."
  [conn ente-id motivo]
  (atualizar-se! conn ente-id
                 (if (= "encerramento_em_curso" motivo)
                   [:in :estado ["ativo" "suspenso"]]
                   [:= :estado "ativo"])
                 {:estado "suspenso" :motivo_restricao motivo :restrita_desde [:now] :suspensao_agendada nil}))

(defn reativar!
  "`suspenso` -> `ativo`. Devolve a Casa, ou nil se nao estava suspensa."
  [conn ente-id]
  (atualizar-se! conn ente-id [:= :estado "suspenso"]
                 {:estado "ativo" :motivo_restricao nil :restrita_desde nil :suspensao_agendada nil}))

(defn agendar-suspensao!
  "Marca a suspensao aprovada que espera a sessao em curso encerrar (a Casa segue `ativo`)."
  [conn ente-id pedido-id]
  (atualizar-se! conn ente-id [:and [:= :estado "ativo"] [:= :suspensao_agendada nil]]
                 {:suspensao_agendada pedido-id}))

(defn cancelar-agendamento!
  [conn ente-id]
  (atualizar-se! conn ente-id [:!= :suspensao_agendada nil] {:suspensao_agendada nil}))

;; ---- ADR-0018 (fatia 2): encerrar ----

(defn encerrar!
  "`suspenso` com o encerramento em curso -> `encerrado`, com a data e o resumo do apagamento. O motivo sai (so' a Casa
  suspensa tem motivo). Devolve a Casa, ou nil se ela nao estava com o encerramento em curso."
  [conn ente-id encerrada-em resumo]
  (atualizar-se! conn ente-id [:and [:= :estado "suspenso"] [:= :motivo_restricao "encerramento_em_curso"]]
                 {:estado "encerrado" :motivo_restricao nil :suspensao_agendada nil :encerrada_em encerrada-em
                  :apagamento (comum/->jsonb resumo) :apagamento_em_execucao_desde nil}))

(def validade-da-execucao-seg
  "O lease de uma execucao do apagamento vence depois disto: a instancia que caiu no meio nao trava a retomada."
  (* 15 60))

(defn reservar-apagamento!
  "Reserva a execucao do apagamento desta Casa (lease entre instancias, mig 0177) e, na 1a vez, FECHA a Casa
  (`apagamento_iniciado_em`, que nao muda mais). Devolve a Casa, ou nil se outra execucao esta' rodando (lease vigente)
  ou a Casa nao esta' com o encerramento em curso."
  [conn ente-id ^java.time.Instant agora]
  (atualizar-se! conn ente-id
                 [:and [:= :estado "suspenso"] [:= :motivo_restricao "encerramento_em_curso"]
                  [:or [:= :apagamento_em_execucao_desde nil]
                   [:< :apagamento_em_execucao_desde (.minusSeconds agora validade-da-execucao-seg)]]]
                 {:apagamento_em_execucao_desde agora
                  :apagamento_iniciado_em [:coalesce :apagamento_iniciado_em agora]}))

(defn liberar-apagamento!
  "Solta o lease (a execucao terminou sem encerrar: interrompida ou com pendencia). A Casa segue FECHADA."
  [conn ente-id]
  (atualizar-se! conn ente-id [:and [:<> :estado "encerrado"] [:<> :apagamento_em_execucao_desde nil]]
                 {:apagamento_em_execucao_desde nil}))

(defn definir-destino-acervo!
  "Para onde foi o acervo publico (https), ou nil para tirar. So' numa Casa com o encerramento em curso ou encerrada."
  [conn ente-id url]
  (atualizar-se! conn ente-id [:or [:= :estado "encerrado"]
                               [:and [:= :estado "suspenso"] [:= :motivo_restricao "encerramento_em_curso"]]]
                 {:destino_acervo_url url}))
