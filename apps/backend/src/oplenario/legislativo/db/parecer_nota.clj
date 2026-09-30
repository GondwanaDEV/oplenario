(ns oplenario.legislativo.db.parecer-nota
  "\"Usar como rascunho\" (ADR-0019 fatia 2a, Eixo 5): a NOTA TECNICA da IA vira o RASCUNHO do parecer juridico, numa
  unica tx — abre (ou reaproveita) o pedido, cria o rascunho com o texto da nota SEM as marcas de citacao, registra a
  origem e marca a nota como `aproveitada` pelo advogado. O texto da IA nunca vira 'parecer' por si: o rascunho nasce
  sem conclusao e so' o advogado o revisa, assume e assina. Funcoes sobre a `tx` do tenant (RLS isola)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.legislativo.db.nota-tecnica :as nota-tecnica]
            [oplenario.legislativo.db.parecer-juridico :as parecer-juridico]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def assunto-do-pedido "Análise jurídica da matéria")

(def relatorio-inicial
  "Uma linha neutra: o advogado a edita ou apaga. Diz de onde o rascunho veio, sem afirmar nada sobre a matéria."
  "Rascunho iniciado a partir da nota técnica da IA — a revisar.")

(defn- travar-nota!
  "Trava a linha da nota (dois advogados clicando juntos: o segundo espera e ve' a nota ja' aproveitada)."
  [tx ente-id nota-id]
  (jdbc/execute-one! tx (sql/format {:select [:id :estado :proposicao_id] :from [:legislativo.nota_tecnica]
                                     :where [:and [:= :ente_id ente-id] [:= :id nota-id]] :for :update})
                     {:builder-fn rs/as-unqualified-maps}))

(defn- pedido-pendente-sem-rascunho
  "O pedido PENDENTE mais antigo da materia que ainda nao tem rascunho, ja' travado (`FOR UPDATE`). nil = nao ha'."
  [tx ente-id proposicao-id]
  (jdbc/execute-one! tx
    (sql/format {:select [:pd.id] :from [[:legislativo.pedido_parecer_juridico :pd]]
                 :where [:and [:= :pd.ente_id ente-id] [:= :pd.proposicao_id proposicao-id] [:= :pd.estado "pendente"]
                         [:not [:exists {:select [1] :from [[:legislativo.parecer_juridico :pj]]
                                         :where [:and [:= :pj.ente_id :pd.ente_id] [:= :pj.pedido_id :pd.id]
                                                 [:= :pj.estado "rascunho"]]}]]]
                 :order-by [[:pd.criado_em :asc] [:pd.id :asc]] :limit 1 :for :update})
    {:builder-fn rs/as-unqualified-maps}))

(defn- ha-pedido-pendente? [tx ente-id proposicao-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [1] :from [:legislativo.pedido_parecer_juridico]
                                            :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                                                    [:= :estado "pendente"]]}))))

(defn usar-como-rascunho!
  "O advogado `advogado-id` usa a nota `nota-id` como rascunho do parecer. Devolve `{:pedido <pedido com o rascunho>}` ou
  `{:erro kw}`: `:nao-encontrado` (nota inexistente nesta Casa), `:nota-decidida` (ja' aproveitada ou descartada),
  `:ja-ha-rascunho` (todo pedido pendente da materia ja' tem rascunho em curso — o advogado nao perde o que escreveu)."
  [tx ente-id nota-id advogado-id]
  (if-let [travada (travar-nota! tx ente-id nota-id)]
    (if-not (= "pendente" (:estado travada))
      {:erro :nota-decidida}
      (let [nota (nota-tecnica/buscar tx ente-id nota-id)
            proposicao-id (:proposicao-id nota)
            pedido-id (or (:id (pedido-pendente-sem-rascunho tx ente-id proposicao-id))
                          (when-not (ha-pedido-pendente? tx ente-id proposicao-id)
                            (:id (parecer-juridico/criar-pedido!
                                   tx {:ente-id ente-id :proposicao-id proposicao-id :assunto assunto-do-pedido
                                       :origem "nota_tecnica" :pedido-por advogado-id}))))]
        (if-not pedido-id
          {:erro :ja-ha-rascunho}
          (let [texto (logic/texto-limpo (:texto nota))]
            (jdbc/execute-one! tx
              (sql/format {:insert-into :legislativo.parecer_juridico
                           :values [{:ente_id ente-id :pedido_id pedido-id :proposicao_id proposicao-id
                                     :relatorio relatorio-inicial :fundamentacao texto :conclusao nil
                                     :autor_id advogado-id :origem_rascunho "nota_tecnica" :nota_tecnica_id nota-id}]}))
            (nota-tecnica/decidir! tx ente-id nota-id {:estado "aproveitada" :texto-final texto
                                                       :decidida-por advogado-id})
            {:pedido (parecer-juridico/pedido-completo tx ente-id pedido-id)}))))
    {:erro :nao-encontrado}))
