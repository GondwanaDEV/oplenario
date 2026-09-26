(ns oplenario.legislativo.db.recebimento
  "Recebimento ASSINADO da tramitacao (fatia 2b, mig 0083) — o recibo de carga: a materia que chega a um estado
  que o rito da Casa marca `exige_recebimento` so' sai dele depois que alguem autorizado RECEBE e assina.

  A pendencia e' DERIVADA, nunca guardada: a ultima movimentacao da materia chegou a um estado que exige
  recebimento, a materia continua nele, e nao ha' recibo para aquela movimentacao. Sem coluna de status para
  divergir da verdade (mesma disciplina de 'estado emergente' da §22.6)."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.motor.api :as motor]))

(set! *warn-on-reflection* true)

(defn- ultima-transicao [tx ente-id proposicao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :de_estado :para_estado :ocorrido_em]
                  :from [:legislativo.proposicao_transicao_historico]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]
                  :order-by [[:ocorrido_em :desc] [:id :desc]]
                  :limit 1}))))

(defn- recebida? [tx ente-id transicao-id]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [1] :from [:legislativo.recebimento_tramitacao]
                        :where [:and [:= :ente_id ente-id] [:= :transicao_id transicao-id]]}))))

(defn- pendente-na-linha
  "A pendencia a partir da linha da materia JA' LIDA (estado + rito). nil = nada a receber."
  [tx ente-id proposicao-id {:keys [estado template-id]}]
  (when template-id
    (let [declarado (proposicao/estado-no-template tx ente-id template-id estado)]
      (when (:exige-recebimento declarado)
        (let [t (ultima-transicao tx ente-id proposicao-id)]
          (when (and t (= estado (:para-estado t)) (not (recebida? tx ente-id (:id t))))
            {:proposicao-id proposicao-id
             :transicao-id (:id t)
             :de-estado (:de-estado t)
             :estado estado
             :estado-nome (:nome declarado)
             :desde (:ocorrido-em t)
             :recebedor (:recebedor declarado)}))))))

(defn pendente
  "O recebimento que a materia espera AGORA, ou nil. {:proposicao-id :transicao-id :de-estado :estado
  :estado-nome :desde :recebedor}."
  [tx ente-id proposicao-id]
  (when-let [p (proposicao/buscar tx ente-id proposicao-id)]
    (pendente-na-linha tx ente-id proposicao-id p)))

(defn pendente-para-transitar
  "Para a engine: dada a linha travada (FOR UPDATE) e o estado declarado, a materia esta' em carga nao
  recebida? Evita reler a proposicao dentro de `transicionar!`."
  [tx ente-id proposicao-id estado-declarado estado]
  (when (:exige-recebimento estado-declarado)
    (let [t (ultima-transicao tx ente-id proposicao-id)]
      (and t (= estado (:para-estado t)) (not (recebida? tx ente-id (:id t)))))))

(defn- conteudo-canonico
  "O que a assinatura cobre: a movimentacao e quem a recebe, numa linha estavel (ordem fixa, sem mapa)."
  ^String [{:keys [proposicao-id transicao-id de-estado estado desde]} recebido-por]
  (str/join "|" ["recebimento-tramitacao/v1" proposicao-id transicao-id de-estado estado desde recebido-por]))

(defn receber!
  "Recebe e ASSINA a movimentacao `transicao-id` da materia. Trava a materia (FOR UPDATE — a mesma trava da
  engine: ninguem a tira do estado entre o 'posso receber' e o recibo), confere que ESTA e' a movimentacao
  pendente, aplica a regra de quem recebe do rito (`recebedor`, DSL, dentro da tx — authz-tx == write-tx,
  achado C3) e grava o recibo append-only.

  Conflitos (-> 409): `:conflito/sem-recebimento-pendente` (nada a receber, ou ja' recebida);
  `:conflito/movimentacao-divergente` (a pessoa viu uma movimentacao e a materia ja' e' outra — nao se
  assina o que nao foi visto). Regra de quem recebe negada -> negacao de autorizacao (403)."
  [tx {:keys [registro ente-id proposicao-id transicao-id ator agora assinador]}]
  (let [linha (proposicao/estado+lock+rito tx ente-id proposicao-id)
        _ (when (nil? linha)
            (throw (ex-info "materia inexistente" {:tipo :conflito/sem-recebimento-pendente
                                                   :proposicao-id proposicao-id})))
        p (pendente-na-linha tx ente-id proposicao-id linha)]
    (when (nil? p)
      (throw (ex-info "nao ha' recebimento pendente para esta materia"
                      {:tipo :conflito/sem-recebimento-pendente :proposicao-id proposicao-id})))
    (when (not= transicao-id (:transicao-id p))
      (throw (ex-info "a movimentacao pendente nao e' a informada"
                      {:tipo :conflito/movimentacao-divergente :proposicao-id proposicao-id
                       :pendente (:transicao-id p) :informada transicao-id})))
    (when-not (str/blank? (:recebedor p))
      (authz/check! ator :legislativo/receber
                    {:tipo "proposicao" :id proposicao-id :estado (:estado p)}
                    (motor/politica-dsl {:registro registro :tx tx :expr (:recebedor p) :agora agora})))
    (let [recebido-por (:identidade-id ator)
          {:keys [algoritmo assinatura-b64]}
          (assinador-icp/assinar assinador (.getBytes (conteudo-canonico p recebido-por) "UTF-8"))
          linha (comum/linha->kebab
                 (jdbc/execute-one! tx
                   (sql/format {:insert-into :legislativo.recebimento_tramitacao
                                :values [{:id (random-uuid) :ente_id ente-id :transicao_id transicao-id
                                          :proposicao_id proposicao-id :estado (:estado p)
                                          :recebido_por recebido-por
                                          :assinatura_algoritmo algoritmo :assinatura_b64 assinatura-b64}]
                                :returning [:id :recebido_em]})))]
      {:id (:id linha)
       :proposicao-id proposicao-id
       :transicao-id transicao-id
       :estado (:estado p)
       :recebido-por recebido-por
       :recebido-em (:recebido-em linha)
       :assinatura-algoritmo algoritmo})))

(defn recebimentos-da-proposicao
  "transicao-id -> {:recebido-por :recebido-em :assinatura-algoritmo}, para o historico mostrar quem recebeu."
  [tx ente-id proposicao-id]
  (into {}
        (map (fn [r] [(:transicao-id r) (dissoc r :transicao-id)]))
        (comum/linhas->kebab
         (jdbc/execute! tx
           (sql/format {:select [:transicao_id :recebido_por :recebido_em :assinatura_algoritmo]
                        :from [:legislativo.recebimento_tramitacao]
                        :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]})))))

(def ^:private teto-pendentes 200)

(defn listar-pendentes
  "As materias da Casa em carga nao recebida: estado atual exige recebimento, a ultima movimentacao chegou nele
  e nao tem recibo. Mais antigas primeiro (quem espera ha' mais tempo). Teto de 200."
  [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     ["SELECT p.id AS proposicao_id, p.tipo, p.sequencial, p.ano, p.ementa, p.estado,
              e.nome AS estado_nome, u.id AS transicao_id, u.de_estado, u.ocorrido_em AS desde,
              (e.recebedor IS NOT NULL) AS restrito
         FROM legislativo.proposicoes p
         JOIN legislativo.template_estado e
           ON e.ente_id = p.ente_id AND e.template_id = p.template_id AND e.chave = p.estado
         JOIN LATERAL (
           SELECT h.id, h.de_estado, h.para_estado, h.ocorrido_em
             FROM legislativo.proposicao_transicao_historico h
            WHERE h.ente_id = p.ente_id AND h.proposicao_id = p.id
            ORDER BY h.ocorrido_em DESC, h.id DESC
            LIMIT 1) u ON u.para_estado = p.estado
        WHERE p.ente_id = ? AND e.exige_recebimento
          AND NOT EXISTS (SELECT 1 FROM legislativo.recebimento_tramitacao r
                           WHERE r.ente_id = p.ente_id AND r.transicao_id = u.id)
        ORDER BY u.ocorrido_em ASC
        LIMIT ?" ente-id teto-pendentes])))
