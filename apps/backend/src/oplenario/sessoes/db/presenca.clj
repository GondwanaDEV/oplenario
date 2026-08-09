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

;; ---------- presenca agregada (read-model barato, FE Onda A1) ----------

(defn- sessoes-encerradas-recentes
  "As `teto` sessoes mais RECENTES do tenant com `estado`='encerrada' e encerrada_em carimbado —
  janela autocontida (nao depende de 'legislativa vigente', que exigiria cruzar cadastros)."
  [tx ente-id teto]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :encerrada_em] :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :estado [:inline "encerrada"]]
                          [:is-not :encerrada_em nil]]
                  :order-by [[:encerrada_em :desc]] :limit teto}))))

(def ^:private positivos (vec (sort logic/tipos-presenca-positiva)))

(defn- presentes-na-sessao
  "Total de vereadores com ULTIMO evento positivo ate' `instante` (qualquer modalidade) — generaliza
  contar-presentes de sessoes/relacoes/presenca (que filtra por modalidade) p/ o agregado cross-sessao.
  `ente-id` filtra tanto a subquery quanto a contagem externa — defense-in-depth mesmo sob RLS (ente_id em
  toda query, ver docstring do ns).

  A subquery vem da fonte CANONICA (`logic/ultimos-eventos-por-vereador-q`), a mesma que o caminho do motor
  de votacao usa: a ordem de desempate do 'ultimo evento por vereador' era transcrita a mao aqui, e divergir
  dela faria a TELA anunciar um quorum e a POLICY usar outro na MESMA sessao."
  [tx ente-id sessao-id instante]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:count :*] :n]]
                     :from [[(logic/ultimos-eventos-por-vereador-q
                              {:sessao-id sessao-id :instante instante :ente-id ente-id
                               :projecao [:vereador_id :tipo :ente_id]})
                             :u]]
                     :where [:and [:= :u.ente_id ente-id] [:in :u.tipo positivos]]}))
      comum/linha->kebab :n))

(defn resumo-presenca
  "Presenca agregada (F7/FE Onda A1, barata): media de presenca das ultimas `teto` sessoes ENCERRADAS do
  tenant. numerador = soma de presentes por sessao; denominador = (n de sessoes) x `membros-da-casa`
  (resolvido pelo CALLER via cadastros, injecao cross-modulo — este ns nao importa cadastros). Devolve
  {:media-percentual :sessoes-consideradas :membros-da-casa} — media nil se nao houve sessao encerrada
  ainda (0/0 e' indefinido, nao 0%). CLAMPED a [0,100] (review final): `membros-da-casa` e' resolvido HOJE,
  mas o numerador conta presenca de sessoes passadas — se a composicao da Casa mudou (vaga aberta/fechada)
  a razao crua pode passar de 100%; a vitrine de comprador (§16.11) mostra uma media, nunca 'mais que
  todo mundo presente'."
  [tx ente-id membros-da-casa teto]
  (let [sessoes (sessoes-encerradas-recentes tx ente-id teto)
        n-sessoes (count sessoes)
        total-presentes (reduce + 0 (map #(presentes-na-sessao tx ente-id (:id %) (:encerrada-em %)) sessoes))]
    {:media-percentual (when (and (pos? n-sessoes) (pos? membros-da-casa))
                         (-> (* 100.0 (/ total-presentes (* n-sessoes membros-da-casa)))
                             Math/round
                             int
                             (max 0)
                             (min 100)))
     :sessoes-consideradas n-sessoes
     :membros-da-casa membros-da-casa}))
