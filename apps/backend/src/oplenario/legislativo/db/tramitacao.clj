(ns oplenario.legislativo.db.tramitacao
  "Persistencia + ENGINE da tramitacao por motor declarativo (eixo C). O regimento e' DADO
  (template/estado/transicao); `transicionar!` carrega as transicoes candidatas (de_estado+gatilho),
  avalia o GUARD de cada uma com o MESMO avaliador do motor (disciplina 5, motor/api/guarda-dsl) e, na
  primeira que passa, grava o historico (append-only) + muda o estado da proposicao — tudo na MESMA tx.
  Importa db/proposicao (db->db mesmo modulo) e motor/api (modulo->motor) — ambos permitidos (§22.10/§3-bis).
  Acao (handler em codigo) e' GRAVADA mas a execucao rica fica p/ F3.3b; aqui o efeito e' a mudanca de estado."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.motor.api :as motor]))

(set! *warn-on-reflection* true)

;; ---- montagem do template (config tenant; usada no onboarding/fixture/import) ----
(defn criar-template!
  "Persiste um template de tramitacao. `:sujeito` ('proposicao' default | 'parecer') e' o discriminador
  do tipo de entidade que o template governa (F3.6a): as tabelas de template sao subject-agnosticas, o
  sujeito e' validado no service do sujeito (ex.: parecer/criar! recusa template de 'proposicao'). Omitir
  = 'proposicao' (preserva os callers do eixo C)."
  [tx {:keys [id ente-id chave versao nome estado-inicial sujeito template-pai-id]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_tramitacao
                 :values [{:id id :ente_id ente-id :chave chave :versao (or versao 1) :nome nome
                           :estado_inicial estado-inicial :sujeito (or sujeito "proposicao")
                           :template_pai_id template-pai-id :efetivado_em [:now]}]})))

(defn criar-estado! [tx {:keys [id ente-id template-id chave nome terminal ordem]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_estado
                 :values [{:id id :ente_id ente-id :template_id template-id :chave chave :nome nome
                           :terminal (boolean terminal) :ordem (or ordem 0) :efetivado_em [:now]}]})))

(defn criar-transicao!
  "Persiste uma transicao do template. GATEIA o GUARD no save (Inv.4, motor/validar-guarda): guard que
  nao parseia NAO entra no banco — a falha de tramitacao sai do caminho critico (rejeitada na config, nao
  no meio de um fluxo). Lanca ex-info :guarda-invalida com a causa do erro de sintaxe."
  [tx {:keys [id ente-id template-id de-estado para-estado gatilho guarda acao ordem]}]
  ;; fail-closed: SO "VALIDA" passa — qualquer outro status (incl. valor inesperado) REJEITA o save
  ;; (quem nao decide, NEGA; mesma postura do check! de authz). String casa a convencao de verificar-fonte.
  (let [{:keys [status erros]} (motor/validar-guarda guarda)]
    (when (not= "VALIDA" status)
      (throw (ex-info "guard da transicao mal-formado (rejeitado no save, Inv.4)"
                      {:erro :guarda-invalida :de-estado de-estado :gatilho gatilho :erros erros}))))
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_transicao
                 :values [{:id id :ente_id ente-id :template_id template-id :de_estado de-estado
                           :para_estado para-estado :gatilho gatilho :guarda guarda :acao acao
                           :ordem (or ordem 0) :efetivado_em [:now]}]})))

(defn transicoes-de
  "Transicoes candidatas (ordenadas) de `de-estado` sob `gatilho` no template."
  [tx ente-id template-id de-estado gatilho]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :template_id :de_estado :para_estado :gatilho :guarda :acao :ordem]
                   :from [:legislativo.template_transicao]
                   :where [:and [:= :ente_id ente-id] [:= :template_id template-id]
                           [:= :de_estado de-estado] [:= :gatilho gatilho]]
                   :order-by [[:ordem :asc] [:id :asc]]}))))

(defn registrar-transicao!
  "Append-only: grava a transicao OCORRIDA no historico (a prova duravel, Inv.10). RETURNING `ocorrido_em`
  (F7 carry): o carimbo REAL da transicao, devolvido p/ `transicionar!` incluir no evento de dominio
  (proposicao.transicionou :ocorrido-em) — a jusante, paineis/tramitacao-board usa isto em vez do momento em
  que o consumer PROJETA, evitando que atraso comum do relay resete o sinal de estagnacao."
  [tx {:keys [id ente-id proposicao-id template-id de-estado para-estado gatilho contexto ator-id]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :legislativo.proposicao_transicao_historico
                  :values [{:id id :ente_id ente-id :proposicao_id proposicao-id :template_id template-id
                            :de_estado de-estado :para_estado para-estado :gatilho gatilho
                            :contexto (some-> contexto comum/->jsonb) :ator_id ator-id :efetivado_em [:now]}]
                  :returning [:ocorrido_em]}))))

(defn historico-da-proposicao [tx ente-id proposicao-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      ;; :contexto incluido (o payload do gatilho — quem/refs externas) p/ a visao de auditoria nao
      ;; perder a carga da transicao (e' persistido por registrar-transicao!).
      (sql/format {:select [:id :proposicao_id :template_id :de_estado :para_estado :gatilho :contexto :ator_id :ocorrido_em]
                   :from [:legislativo.proposicao_transicao_historico]
                   :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]
                   :order-by [[:ocorrido_em :asc]]}))))

(defn- estado+lock
  "Le estado+lock_version da proposicao SOB FOR UPDATE: serializa transicoes concorrentes na MESMA
  proposicao (dois clerks no mesmo ato). Sem isso, ambas leem o mesmo lock_version e a 2a perde o CAS
  com excecao de conflito (saida nao-documentada do contrato). Com o lock, a 2a espera, le o estado
  fresco e reavalia o guard deterministicamente."
  [tx ente-id proposicao-id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:legislativo.proposicoes]
                     :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]]
                     :for :update}))
      comum/linha->kebab))

(defn transicionar!
  "ENGINE da tramitacao (eixo C). Do estado ATUAL da proposicao, sob `gatilho`, escolhe a 1a transicao
  candidata cujo GUARD passa (guard nil = sempre passa; senao avalia via motor/guarda-dsl com a proposicao
  no `amb`) e aplica: historico append-only + muda o estado (CAS por lock_version). Atomico na tx (o caller
  abre via Repo/transacao, passando o RegistroFatos do motor). Devolve {:transicionou? bool :de :para
  :transicao-id} — guard que bloqueia TODAS as candidatas e' resultado normal (transicionou? false), nao erro.
  DISTINTO disso: guard que LANCA (fato ausente no registry, tipo nao-booleano no runtime) e o CAS de
  lock_version (conflito de escrita) PROPAGAM como excecao — o controller distingue: resultado = dominio
  normal; excecao = erro de avaliacao/conflito."
  [tx {:keys [registro ente-id proposicao-id template-id gatilho ator-id contexto agora updated-by]}]
  (let [{:keys [estado lock-version]} (estado+lock tx ente-id proposicao-id)
        candidatas (transicoes-de tx ente-id template-id estado gatilho)
        passa? (fn [t]
                 (or (nil? (:guarda t))
                     ((motor/guarda-dsl {:registro registro :tx tx :expr (:guarda t)
                                         :agora agora :ente-id ente-id})
                      {"proposicao" {:id proposicao-id :estado estado} "contexto" (or contexto {})})))
        escolhida (first (filter passa? candidatas))]
    (if-not escolhida
      {:transicionou? false :de estado :gatilho gatilho}
      (let [{:keys [ocorrido-em]} (registrar-transicao! tx {:id (random-uuid) :ente-id ente-id :proposicao-id proposicao-id
                                                            :template-id template-id :de-estado estado
                                                            :para-estado (:para-estado escolhida)
                                                            :gatilho gatilho :contexto contexto :ator-id ator-id})]
        (proposicao/mudar-estado! tx {:id proposicao-id :ente-id ente-id :estado (:para-estado escolhida)
                                      :updated-by updated-by :lock-version lock-version})
        {:transicionou? true :de estado :para (:para-estado escolhida) :transicao-id (:id escolhida)
         :acao (:acao escolhida) :ocorrido-em ocorrido-em}))))
