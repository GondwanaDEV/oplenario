(ns oplenario.legislativo.db.subscricao
  "Requerimento COLETIVO (fatia 2c, mig 0084; feature 3.17): a PROPOSTA de requerimento que espera as subscricoes
  dos coautores antes do protocolo, e as SUBSCRICOES (um convite por coautor).

  O desenho (autoria-apoiamento.html) manda: coautoria e' ato voluntario, cada coautor confirma com a propria
  assinatura, e quem ainda nao confirmou quando o autor protocola NAO CONSTA do protocolo. Por isso:
  - o texto e' congelado na proposta — os coautores assinam exatamente os bytes que o autor protocola;
  - responder (confirmar/recusar) e protocolar travam a PROPOSTA primeiro (FOR UPDATE), sempre na mesma
    ordem: um coautor confirmando no mesmo instante do protocolo ou entra antes, ou encontra a proposta ja'
    protocolada — nunca fica meio dentro.

  Quem e' quem chega RESOLVIDO do host (§22.5.3): o autor e o coautor como `vereador-id` do login; este ns so'
  confere se o vereador-id do login e' o autor/o convidado."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]))

(set! *warn-on-reflection* true)

(def teto-coautores
  "Teto defensivo de convites por proposta (uma Casa grande tem ~55 cadeiras; o teto cobre a Casa inteira)."
  60)

(defn- conflito! [tipo msg info] (throw (ex-info msg (assoc info :tipo tipo))))

(defn criar-proposta!
  "Grava a proposta (texto congelado) + um convite 'pendente' por coautor, numa tx. `coautores` = [{:id :nome}]
  ja' validados pelo controller (membros da Casa, sem repetir, sem o autor)."
  [tx {:keys [ente-id id autor-vereador-id autor-identidade-id autor-nome modelo-id tipo-requerimento ementa texto
              coautores]}]
  (let [linha (comum/linha->kebab
               (jdbc/execute-one! tx
                 (sql/format {:insert-into :legislativo.requerimento_proposta
                              :values [{:ente_id ente-id :id id :autor_vereador_id autor-vereador-id
                                        :autor_identidade_id autor-identidade-id :autor_nome autor-nome
                                        :modelo_id modelo-id :tipo_requerimento tipo-requerimento
                                        :ementa ementa :texto texto}]
                              :returning [:criada_em]})))]
    (jdbc/execute! tx
      (sql/format {:insert-into :legislativo.subscricao_requerimento
                   :values (mapv (fn [c] {:ente_id ente-id :id (random-uuid) :proposta_id id
                                          :vereador_id (:id c) :vereador_nome (:nome c)})
                                 coautores)}))
    {:id id :criada-em (:criada-em linha)}))

(defn- proposta-travada [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:*] :from [:legislativo.requerimento_proposta]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]
                  :for :update}))))

(defn- subscricoes [tx ente-id proposta-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :vereador_id :vereador_nome :estado :convidada_em :respondida_em
                           :assinatura_algoritmo]
                  :from [:legislativo.subscricao_requerimento]
                  :where [:and [:= :ente_id ente-id] [:= :proposta_id proposta-id]]
                  :order-by [[:vereador_nome :asc] [:id :asc]]}))))

(defn buscar-proposta
  "A proposta + as subscricoes, ou nil. So' devolve a quem PARTICIPA dela (o autor ou um convidado) —
  `vereador-id` do login; para os demais, nil (a borda responde 404, nao vaza que a proposta existe)."
  [tx ente-id id vereador-id]
  (when-let [p (comum/linha->kebab
                (jdbc/execute-one! tx
                  (sql/format {:select [:*] :from [:legislativo.requerimento_proposta]
                               :where [:and [:= :ente_id ente-id] [:= :id id]]})))]
    (let [subs (subscricoes tx ente-id id)]
      (when (or (= vereador-id (:autor-vereador-id p)) (some #(= vereador-id (:vereador-id %)) subs))
        (assoc p :subscricoes subs)))))

(defn responder!
  "O coautor CONFIRMA (assina o texto congelado) ou RECUSA o convite. Trava a proposta, depois o convite.
  Conflitos (-> 409): `:conflito/proposta-protocolada` (o autor ja' protocolou; o convite virou 'nao consta'),
  `:conflito/subscricao-respondida` (ja' respondeu — o duplo clique cai aqui). nil = nao ha' convite para este
  vereador nesta proposta (-> 404)."
  [tx {:keys [ente-id proposta-id vereador-id identidade-id acao assinador]}]
  (when-let [p (proposta-travada tx ente-id proposta-id)]
    (when-let [s (comum/linha->kebab
                  (jdbc/execute-one! tx
                    (sql/format {:select [:id :estado] :from [:legislativo.subscricao_requerimento]
                                 :where [:and [:= :ente_id ente-id] [:= :proposta_id proposta-id]
                                         [:= :vereador_id vereador-id]]
                                 :for :update})))]
      (when (not= "aguardando_subscricoes" (:estado p))
        (conflito! :conflito/proposta-protocolada "o requerimento ja' foi protocolado" {:proposta-id proposta-id}))
      (when (not= "pendente" (:estado s))
        (conflito! :conflito/subscricao-respondida "voce ja' respondeu a este convite"
                   {:proposta-id proposta-id :estado (:estado s)}))
      (let [confirmar? (= acao :confirmar)
            assinatura (when confirmar?
                         (assinador-icp/assinar assinador (.getBytes ^String (:texto p) "UTF-8")))
            linha (comum/linha->kebab
                   (jdbc/execute-one! tx
                     (sql/format {:update :legislativo.subscricao_requerimento
                                  :set (cond-> {:estado (if confirmar? "confirmada" "recusada")
                                                :respondida_em [:now]}
                                         confirmar? (assoc :assinado_por identidade-id
                                                           :assinatura_algoritmo (:algoritmo assinatura)
                                                           :assinatura_b64 (:assinatura-b64 assinatura)))
                                  :where [:and [:= :ente_id ente-id] [:= :id (:id s)]]
                                  :returning [:estado :respondida_em :assinatura_algoritmo]})))]
        {:proposta-id proposta-id :estado (:estado linha) :respondida-em (:respondida-em linha)
         :assinatura-algoritmo (:assinatura-algoritmo linha)}))))

(defn travar-para-protocolo!
  "Para o protocolo: trava a proposta e confere que ela e' DO AUTOR e ainda espera. nil = inexistente ou de
  outro autor (-> 404, nao vaza). Lanca `:conflito/proposta-protocolada` se ja' foi."
  [tx ente-id proposta-id autor-vereador-id]
  (when-let [p (proposta-travada tx ente-id proposta-id)]
    (when (= autor-vereador-id (:autor-vereador-id p))
      (when (not= "aguardando_subscricoes" (:estado p))
        (conflito! :conflito/proposta-protocolada "este requerimento ja' foi protocolado" {:proposta-id proposta-id}))
      p)))

(defn fechar-com-protocolo!
  "Depois do protocolo (MESMA tx): a proposta aponta a proposicao e os convites ainda pendentes viram 'nao
  consta' — ficam registrados, mas fora do protocolo. Devolve os coautores CONFIRMADOS (os que constam)."
  [tx ente-id proposta-id proposicao-id]
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.requerimento_proposta
                 :set {:estado "protocolada" :proposicao_id proposicao-id :protocolada_em [:now]}
                 :where [:and [:= :ente_id ente-id] [:= :id proposta-id]]}))
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.subscricao_requerimento
                 :set {:estado "nao_consta" :respondida_em [:now]}
                 :where [:and [:= :ente_id ente-id] [:= :proposta_id proposta-id] [:= :estado "pendente"]]}))
  (filterv #(= "confirmada" (:estado %)) (subscricoes tx ente-id proposta-id)))

(defn convites-pendentes
  "Os convites que ESPERAM a resposta deste vereador (proposta ainda aberta), mais antigos primeiro."
  [tx ente-id vereador-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [[:p.id :proposta-id] :p.ementa :p.tipo_requerimento :p.autor_nome [:s.convidada_em :convidada-em]]
                  :from [[:legislativo.subscricao_requerimento :s]]
                  :join [[:legislativo.requerimento_proposta :p]
                         [:and [:= :p.ente_id :s.ente_id] [:= :p.id :s.proposta_id]]]
                  :where [:and [:= :s.ente_id ente-id] [:= :s.vereador_id vereador-id] [:= :s.estado "pendente"]
                          [:= :p.estado "aguardando_subscricoes"]]
                  :order-by [[:s.convidada_em :asc]]
                  :limit teto-coautores}))))

(defn propostas-abertas-do-autor
  "As propostas deste autor que ainda esperam subscricoes (para ele acompanhar e protocolar), com a contagem."
  [tx ente-id autor-vereador-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     ["SELECT p.id, p.ementa, p.tipo_requerimento, p.criada_em,
              count(*) FILTER (WHERE s.estado = 'confirmada') AS confirmadas,
              count(*) FILTER (WHERE s.estado = 'pendente')   AS pendentes,
              count(*) FILTER (WHERE s.estado = 'recusada')   AS recusadas
         FROM legislativo.requerimento_proposta p
         JOIN legislativo.subscricao_requerimento s ON s.ente_id = p.ente_id AND s.proposta_id = p.id
        WHERE p.ente_id = ? AND p.autor_vereador_id = ? AND p.estado = 'aguardando_subscricoes'
        GROUP BY p.id, p.ementa, p.tipo_requerimento, p.criada_em
        ORDER BY p.criada_em DESC
        LIMIT 50" ente-id autor-vereador-id])))

(defn coautores-da-proposicao
  "Os coautores que CONSTAM de uma proposicao protocolada (subscricao confirmada), por nome — para a ficha."
  [tx ente-id proposicao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:s.vereador_id :s.vereador_nome [:s.respondida_em :assinado-em] :s.assinatura_algoritmo]
                  :from [[:legislativo.subscricao_requerimento :s]]
                  :join [[:legislativo.requerimento_proposta :p]
                         [:and [:= :p.ente_id :s.ente_id] [:= :p.id :s.proposta_id]]]
                  :where [:and [:= :p.ente_id ente-id] [:= :p.proposicao_id proposicao-id]
                          [:= :s.estado "confirmada"]]
                  :order-by [[:s.vereador_nome :asc]]}))))
