(ns oplenario.legislativo.db.parecer-juridico
  "Persistencia do PARECER JURIDICO da Casa (ADR-0019, mig 20260930000111): o pedido (sobre uma materia ou uma
  consulta avulsa) e o parecer do advogado, do rascunho a assinatura. Funcoes sobre a `tx` do tenant (RLS isola).
  O parecer ASSINADO e' imutavel (trigger); corrigir = parecer novo que o SUBSTITUI. Nada aqui move a materia no
  rito: o parecer e' opinativo."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (java.sql Date Timestamp)
           (java.time Instant LocalDate)))

(set! *warn-on-reflection* true)

(defn- ->instant [v] (if (instance? Timestamp v) (.toInstant ^Timestamp v) v))
(defn- ->dia [v] (if (instance? Date v) (.toLocalDate ^Date v) v))

;; ---------------- o pedido ----------------

(def ^:private colunas-pedido
  [:pd.id :pd.proposicao_id :pd.assunto :pd.prazo :pd.origem :pd.pedido_por :pd.em_nome_de :pd.estado
   :pd.criado_em :pd.atualizado_em
   [:p.tipo :materia_tipo] [:p.sequencial :materia_sequencial] [:p.ano :materia_ano] [:p.ementa :materia_ementa]])

(defn- consulta-pedidos [ente-id & onde]
  {:select colunas-pedido
   :from [[:legislativo.pedido_parecer_juridico :pd]]
   :left-join [[:legislativo.proposicoes :p] [:and [:= :p.ente_id :pd.ente_id] [:= :p.id :pd.proposicao_id]]]
   :where (into [:and [:= :pd.ente_id ente-id]] onde)})

(defn- ->pedido [linha]
  (some-> linha comum/linha->kebab
          (update :prazo ->dia)
          (update :criado-em ->instant)
          (update :atualizado-em ->instant)))

(defn existe-proposicao? [tx ente-id proposicao-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [1] :from [:legislativo.proposicoes]
                                            :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]]}))))

(defn buscar-pedido [tx ente-id id]
  (->pedido (jdbc/execute-one! tx (sql/format (consulta-pedidos ente-id [:= :pd.id id])))))

(defn criar-pedido!
  "Registra o pedido. Materia informada que nao existe nesta Casa -> nil. Devolve o pedido lido de volta."
  [tx {:keys [ente-id proposicao-id assunto prazo origem pedido-por em-nome-de]}]
  (when (or (nil? proposicao-id) (existe-proposicao? tx ente-id proposicao-id))
    (let [id (random-uuid)]
      (jdbc/execute-one! tx
        (sql/format {:insert-into :legislativo.pedido_parecer_juridico
                     :values [{:id id :ente_id ente-id :proposicao_id proposicao-id :assunto assunto
                               :prazo (when prazo (Date/valueOf ^LocalDate prazo)) :origem origem :pedido_por pedido-por
                               :em_nome_de em-nome-de}]}))
      (buscar-pedido tx ente-id id))))

(defn listar-pedidos
  "A fila: os pedidos no `estado` (nil = todos), os mais antigos primeiro."
  [tx ente-id estado limite]
  (mapv ->pedido
        (jdbc/execute! tx (sql/format (-> (apply consulta-pedidos ente-id (when estado [[:= :pd.estado estado]]))
                                          (assoc :order-by [[:pd.criado_em :asc] [:pd.id :asc]] :limit limite))))))

(defn cancelar-pedido!
  "Cancela um pedido PENDENTE. Ja' atendido ou cancelado (ou de outra Casa) -> nil."
  [tx ente-id id por]
  (when (jdbc/execute-one! tx
          (sql/format {:update :legislativo.pedido_parecer_juridico
                       :set {:estado "cancelado" :cancelado_por por :cancelado_em [:now] :atualizado_em [:now]}
                       :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado "pendente"]]
                       :returning [:id]}))
    (buscar-pedido tx ente-id id)))

(defn- mudar-estado-do-pedido! [tx ente-id id de para]
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.pedido_parecer_juridico
                 :set {:estado para :atualizado_em [:now]}
                 :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado de]]})))

;; ---------------- o parecer ----------------

(def ^:private substituido
  "O parecer foi substituido por outro JA' ASSINADO (o rascunho do substituto nao apaga o vigente)."
  [[:exists {:select [1] :from [[:legislativo.parecer_juridico :s]]
             :where [:and [:= :s.ente_id :pj.ente_id] [:= :s.substitui_id :pj.id] [:= :s.estado [:inline "assinado"]]]}]
   :substituido])

(def ^:private colunas-parecer
  [:pj.id :pj.pedido_id :pj.proposicao_id :pj.estado :pj.relatorio :pj.fundamentacao :pj.conclusao :pj.substitui_id
   :pj.numero :pj.ano :pj.assinado_em :pj.assinatura_nome :pj.assinatura_oab :pj.assinatura_qualificacao
   :pj.criado_em substituido])

(defn- consulta-pareceres [ente-id & onde]
  {:select colunas-parecer
   :from [[:legislativo.parecer_juridico :pj]]
   :where (into [:and [:= :pj.ente_id ente-id]] onde)})

(defn- ->parecer [linha]
  (some-> linha comum/linha->kebab
          (update :assinado-em ->instant)
          (update :criado-em ->instant)))

(defn buscar-parecer [tx ente-id id]
  (->parecer (jdbc/execute-one! tx (sql/format (consulta-pareceres ente-id [:= :pj.id id])))))

(defn parecer-corrente
  "O parecer que a tela do pedido mostra: o rascunho em curso, senao o ultimo assinado."
  [tx ente-id pedido-id]
  (->parecer (jdbc/execute-one! tx
               (sql/format (assoc (consulta-pareceres ente-id [:= :pj.pedido_id pedido-id])
                                  :order-by [[[:= :pj.estado [:inline "rascunho"]] :desc] [:pj.criado_em :desc]]
                                  :limit 1)))))

(defn pareceres-dos-pedidos
  "O parecer corrente de cada pedido de `ids`, numa consulta so' (a fila). Devolve {pedido-id parecer}."
  [tx ente-id ids]
  (if (empty? ids)
    {}
    (->> (jdbc/execute! tx (sql/format (assoc (consulta-pareceres ente-id [:in :pj.pedido_id (vec ids)])
                                              :order-by [[:pj.criado_em :asc]])))
         (map ->parecer)
         ;; do mais antigo ao mais novo: o ultimo vence, exceto que o rascunho em curso vence sempre
         (reduce (fn [m p]
                   (let [atual (get m (:pedido-id p))]
                     (if (and atual (= "rascunho" (:estado atual)) (not= "rascunho" (:estado p)))
                       m
                       (assoc m (:pedido-id p) p))))
                 {}))))

(defn salvar-rascunho!
  "Cria ou atualiza o RASCUNHO do pedido (um por pedido). Devolve o parecer corrente."
  [tx ente-id pedido-id autor-id {:keys [relatorio fundamentacao conclusao]}]
  (let [existente (jdbc/execute-one! tx
                    (sql/format {:select [:id] :from [:legislativo.parecer_juridico]
                                 :where [:and [:= :ente_id ente-id] [:= :pedido_id pedido-id] [:= :estado "rascunho"]]
                                 :for :update}))]
    (if existente
      (jdbc/execute-one! tx
        (sql/format {:update :legislativo.parecer_juridico
                     :set {:relatorio relatorio :fundamentacao fundamentacao :conclusao conclusao
                           :atualizado_em [:now]}
                     :where [:and [:= :ente_id ente-id] [:= :id (:parecer_juridico/id existente)]]}))
      (let [proposicao-id (:proposicao-id (buscar-pedido tx ente-id pedido-id))]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :legislativo.parecer_juridico
                       :values [{:ente_id ente-id :pedido_id pedido-id :proposicao_id proposicao-id
                                 :relatorio relatorio :fundamentacao fundamentacao :conclusao conclusao
                                 :autor_id autor-id}]}))))
    (parecer-corrente tx ente-id pedido-id)))

(defn assinar!
  "Assina o RASCUNHO `parecer-id`: numero/ano sequenciais da Casa (serializados por advisory lock), snapshot de quem
  assinou e o pedido vira 'atendido' — tudo numa tx. Nao e' rascunho (ja' assinado, ou de outra Casa) -> nil."
  [tx ente-id parecer-id {:keys [por nome oab qualificacao]}]
  (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(hashtextextended(?, 7162600019))"
                         (str "parecer-juridico:" ente-id)])
  (let [ano (:ano (jdbc/execute-one! tx ["SELECT extract(year FROM (now() AT TIME ZONE 'America/Fortaleza'))::int AS ano"]))
        numero (:n (jdbc/execute-one! tx ["SELECT coalesce(max(numero), 0) + 1 AS n FROM legislativo.parecer_juridico
                                           WHERE ente_id = ? AND ano = ?" ente-id ano]))
        r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.parecer_juridico
                         :set {:estado "assinado" :numero numero :ano ano :assinado_por por :assinado_em [:now]
                               :assinatura_nome nome :assinatura_oab oab :assinatura_qualificacao qualificacao
                               :atualizado_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :id parecer-id] [:= :estado "rascunho"]]
                         :returning [:pedido_id]}))]
    (when r
      (mudar-estado-do-pedido! tx ente-id (:parecer_juridico/pedido_id r) "pendente" "atendido")
      (buscar-parecer tx ente-id parecer-id))))

(defn substituir!
  "Abre um RASCUNHO que substitui o parecer assinado vigente do pedido (copia o texto) e reabre o pedido. Sem assinado
  vigente (nunca assinou, ou ja' ha' um substituto em curso) -> nil."
  [tx ente-id pedido-id autor-id]
  (when-let [vigente (jdbc/execute-one! tx
                       (sql/format {:select [:pj.id :pj.proposicao_id :pj.relatorio :pj.fundamentacao :pj.conclusao]
                                    :from [[:legislativo.parecer_juridico :pj]]
                                    :where [:and [:= :pj.ente_id ente-id] [:= :pj.pedido_id pedido-id]
                                            [:= :pj.estado "assinado"]
                                            [:not [:exists {:select [1] :from [[:legislativo.parecer_juridico :s]]
                                                            :where [:and [:= :s.ente_id :pj.ente_id]
                                                                    [:= :s.substitui_id :pj.id]]}]]]
                                    :order-by [[:pj.criado_em :desc]] :limit 1}))]
    (let [v (comum/linha->kebab vigente)]
      (jdbc/execute-one! tx
        (sql/format {:insert-into :legislativo.parecer_juridico
                     :values [{:ente_id ente-id :pedido_id pedido-id :proposicao_id (:proposicao-id v)
                               :relatorio (:relatorio v) :fundamentacao (:fundamentacao v) :conclusao (:conclusao v)
                               :substitui_id (:id v) :autor_id autor-id}]}))
      (mudar-estado-do-pedido! tx ente-id pedido-id "atendido" "pendente")
      (parecer-corrente tx ente-id pedido-id))))

;; ---------------- por materia (ficha e portal) ----------------

(defn pareceres-assinados-da-materia
  "Os pareceres ASSINADOS sobre a materia, o mais novo primeiro (com `:substituido`)."
  [tx ente-id proposicao-id]
  (mapv ->parecer
        (jdbc/execute! tx (sql/format (assoc (consulta-pareceres ente-id [:= :pj.proposicao_id proposicao-id]
                                                                 [:= :pj.estado "assinado"])
                                             :order-by [[:pj.assinado_em :desc] [:pj.id :asc]])))))

(defn pedidos-abertos-da-materia [tx ente-id proposicao-id]
  (mapv ->pedido
        (jdbc/execute! tx (sql/format (assoc (consulta-pedidos ente-id [:= :pd.proposicao_id proposicao-id]
                                                               [:= :pd.estado "pendente"])
                                             :order-by [[:pd.criado_em :asc]])))))

(defn materia-deliberada?
  "A materia chegou a um estado TERMINAL do rito (aprovada, rejeitada, arquivada...): o dado do rito diz, nao um nome
  de estado. Materia sem rito nunca 'terminou'."
  [tx ente-id proposicao-id]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [1] :from [[:legislativo.proposicoes :p]]
                        :join [[:legislativo.template_estado :e]
                               [:and [:= :e.ente_id :p.ente_id] [:= :e.template_id :p.template_id]
                                [:= :e.chave :p.estado]]]
                        :where [:and [:= :p.ente_id ente-id] [:= :p.id proposicao-id] [:= :e.terminal true]]}))))

(defn publicos-da-materia
  "O que o portal mostra: so' DEPOIS da deliberacao (LAI art. 7 §3, ADR-0019 Eixo 4), e so' o parecer vigente."
  [tx ente-id proposicao-id]
  (if (materia-deliberada? tx ente-id proposicao-id)
    (filterv (complement :substituido) (pareceres-assinados-da-materia tx ente-id proposicao-id))
    []))

;; ---------------- composicoes (uma tx cada; devolvem `{:erro kw}` em vez de lancar) ----------------

(defn pedido-completo
  "O pedido com o parecer corrente (o rascunho em curso ou o ultimo assinado), com texto."
  [tx ente-id id]
  (when-let [p (buscar-pedido tx ente-id id)]
    (assoc p :parecer (parecer-corrente tx ente-id id))))

(defn fila
  "Os pedidos no `estado` com o parecer corrente de cada um (SEM o texto: a fila nao precisa dele)."
  [tx ente-id estado limite]
  (let [pedidos (listar-pedidos tx ente-id estado limite)
        pareceres (pareceres-dos-pedidos tx ente-id (map :id pedidos))]
    (mapv (fn [p] (assoc p :parecer (some-> (get pareceres (:id p)) (dissoc :relatorio :fundamentacao)))) pedidos)))

(defn salvar-do-pedido!
  "Grava o RASCUNHO do parecer do pedido. Erros: :nao-encontrado; :pedido-cancelado; :ja-assinado (o vigente esta
  assinado: corrigir e' a substituicao)."
  [tx ente-id pedido-id autor-id texto]
  (if-let [p (buscar-pedido tx ente-id pedido-id)]
    (case (:estado p)
      "cancelado" {:erro :pedido-cancelado}
      "atendido"  {:erro :ja-assinado}
      (do (salvar-rascunho! tx ente-id pedido-id autor-id texto)
          {:pedido (pedido-completo tx ente-id pedido-id)}))
    {:erro :nao-encontrado}))

(defn assinar-do-pedido!
  "Assina o rascunho do pedido. Erros: :nao-encontrado; :pedido-cancelado; :ja-assinado; :sem-rascunho;
  :incompleto (falta relatorio, fundamentacao ou conclusao)."
  [tx ente-id pedido-id assinante]
  (if-let [p (buscar-pedido tx ente-id pedido-id)]
    (let [corrente (parecer-corrente tx ente-id pedido-id)]
      (cond
        (= "cancelado" (:estado p)) {:erro :pedido-cancelado}
        (= "atendido" (:estado p))  {:erro :ja-assinado}
        (not= "rascunho" (:estado corrente)) {:erro :sem-rascunho}
        (or (str/blank? (:relatorio corrente))
            (str/blank? (:fundamentacao corrente))
            (nil? (:conclusao corrente))) {:erro :incompleto}
        :else (do (assinar! tx ente-id (:id corrente) assinante)
                  {:pedido (pedido-completo tx ente-id pedido-id)})))
    {:erro :nao-encontrado}))

(defn substituir-do-pedido!
  "Abre o parecer que substitui o assinado. Erros: :nao-encontrado; :pedido-cancelado; :sem-assinado (nao ha' o que
  substituir, ou ja' ha' um substituto em curso)."
  [tx ente-id pedido-id autor-id]
  (if-let [p (buscar-pedido tx ente-id pedido-id)]
    (if (= "cancelado" (:estado p))
      {:erro :pedido-cancelado}
      (if (substituir! tx ente-id pedido-id autor-id)
        {:pedido (pedido-completo tx ente-id pedido-id)}
        {:erro :sem-assinado}))
    {:erro :nao-encontrado}))
