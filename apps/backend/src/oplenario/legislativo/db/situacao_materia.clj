(ns oplenario.legislativo.db.situacao-materia
  "ADR-0019 fatia 3: a SITUACAO DE PARECER de um lote de materias — quantos pareceres de comissao ja' foram emitidos,
  quantos estao em andamento e quantos pedidos de parecer juridico seguem pendentes. E' o que a tela de publicar a pauta
  (modulo `sessoes`, pelo seam do host) precisa para AVISAR, nunca bloquear. Tres agregacoes numa tx; ente_id em toda
  query (a RLS ja' isola)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def teto-materias
  "Teto do lote (convencao da casa: 2000 na pauta). Acima disso nao e' pauta de sessao, e' abuso da leitura."
  2000)

(defn- inline-set [xs] (mapv (fn [x] [:inline x]) (sort xs)))

(defn situacao-de-parecer
  "{proposicao-id {:pareceres-emitidos n :pareceres-em-andamento n :pedidos-juridicos-pendentes n}} das materias do
  lote que EXISTEM nesta Casa (id de fora nao volta — o chamador nao inventa aviso para ele). Emitido = um dos
  `logic/estados-parecer-emitidos`; em andamento = qualquer estado nao terminal. Pareceres sobre EMENDA nao entram:
  o requisito da pauta e' o parecer sobre a materia."
  [tx ente-id ids]
  (let [ids (vec (set ids))]
    (when (> (count ids) teto-materias)
      (throw (ex-info "lote de materias acima do teto" {:tipo :limite/materias-excedido
                                                        :medido (count ids) :teto teto-materias})))
    (if (empty? ids)
      {}
      (let [existentes (map :id
                            (comum/linhas->kebab (jdbc/execute! tx (sql/format {:select [:id] :from [:legislativo.proposicoes]
                                                           :where [:and [:= :ente_id ente-id] [:in :id ids]]}))))
            pareceres (comum/linhas->kebab (jdbc/execute! tx
                        (sql/format {:select [:objeto_id
                                              [[:filter [:count :*] {:where [:in :estado (inline-set logic/estados-parecer-emitidos)]}]
                                               :emitidos]
                                              [[:filter [:count :*] {:where [:not-in :estado (inline-set logic/estados-parecer-terminais)]}]
                                               :em_andamento]]
                                     :from [:legislativo.pareceres]
                                     :where [:and [:= :ente_id ente-id] [:= :objeto_tipo [:inline "proposicao"]]
                                             [:in :objeto_id ids]]
                                     :group-by [:objeto_id]})))
            pedidos (comum/linhas->kebab (jdbc/execute! tx
                      (sql/format {:select [:proposicao_id [[:count :*] :pendentes]]
                                   :from [:legislativo.pedido_parecer_juridico]
                                   :where [:and [:= :ente_id ente-id] [:= :estado [:inline "pendente"]]
                                           [:in :proposicao_id ids]]
                                   :group-by [:proposicao_id]})))
            por-parecer (into {} (map (fn [r] [(:objeto-id r) r])) pareceres)
            por-pedido  (into {} (map (fn [r] [(:proposicao-id r) (:pendentes r)])) pedidos)]
        (into {} (map (fn [id]
                        (let [pa (get por-parecer id)]
                          [id {:pareceres-emitidos (long (or (:emitidos pa) 0))
                               :pareceres-em-andamento (long (or (:em-andamento pa) 0))
                               :pedidos-juridicos-pendentes (long (or (get por-pedido id) 0))}])))
              existentes)))))
