(ns oplenario.legislativo.db.distribuicao
  "O caminho da materia pelas comissoes (ADR-0019 Eixo 6): encaminhar a materia a uma ou mais comissoes = abrir um
  parecer_comissao por comissao, no estado inicial do rito de parecer DA CASA (dado, nao codigo), com o relator ja'
  designado ou sem ele. Funcoes sobre a `tx` do tenant (RLS isola). Reusa `parecer/criar!` (que prova o template e
  o objeto) — a distribuicao so' escolhe o template e evita abrir duas vezes."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(defn template-de-parecer
  "O rito de parecer ativo da Casa (sujeito 'parecer'): a maior versao. nil = a Casa nao configurou o rito de parecer."
  [tx ente-id]
  (:id (comum/linha->kebab
         (jdbc/execute-one! tx
           (sql/format {:select [:id] :from [:legislativo.template_tramitacao]
                        :where [:and [:= :ente_id ente-id] [:= :sujeito "parecer"] [:= :ativo true]]
                        :order-by [[:versao :desc] [:criado_em :desc]] :limit 1})))))

(defn parecer-em-curso
  "O parecer NAO terminal da `comissao-id` sobre a materia, se houver (a comissao ja' recebeu a materia)."
  [tx ente-id proposicao-id comissao-id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :comissao_id :relator_id :estado]
                   :from [:legislativo.pareceres]
                   :where [:and [:= :ente_id ente-id] [:= :objeto_tipo "proposicao"] [:= :objeto_id proposicao-id]
                           [:= :comissao_id comissao-id]
                           [:not-in :estado (vec logic/estados-parecer-terminais)]]
                   :order-by [[:criado_em :desc]] :limit 1}))))

(defn abrir!
  "Abre o parecer da `comissao-id` sobre a materia, com `relator-id` (ou sem). Se a comissao ja' tem parecer em curso
  sobre a materia, devolve ESSE (`:ja-existia true`) em vez de abrir outro. Rito de parecer nao configurado ->
  lanca (a borda traduz). Devolve {:id :estado :comissao-id :relator-id :ja-existia}."
  [tx {:keys [ente-id proposicao-id comissao-id relator-id created-by]}]
  (if-let [existente (parecer-em-curso tx ente-id proposicao-id comissao-id)]
    (assoc existente :ja-existia true)
    (let [template-id (template-de-parecer tx ente-id)]
      (when (nil? template-id)
        (throw (ex-info "rito de parecer nao configurado nesta Casa" {:ente-id ente-id :erro :sem-rito-de-parecer})))
      (let [id (random-uuid)
            {:keys [estado]} (parecer/criar! tx {:id id :ente-id ente-id :objeto-tipo "proposicao"
                                                 :objeto-id proposicao-id :comissao-id comissao-id
                                                 :relator-id relator-id :template-id template-id
                                                 :created-by created-by})]
        {:id id :estado estado :comissao-id comissao-id :relator-id relator-id :ja-existia false}))))

(defn designar-relator!
  "Define (ou troca) o relator de um parecer NAO terminal. Devolve {:id :relator-id}, ou nil se o parecer nao existe
  ou ja' terminou (a troca de relator so' faz sentido enquanto a comissao ainda nao decidiu)."
  [tx ente-id parecer-id relator-id por]
  (when-let [p (comum/linha->kebab
                 (jdbc/execute-one! tx
                   (sql/format {:select [:id :estado :lock_version] :from [:legislativo.pareceres]
                                :where [:and [:= :ente_id ente-id] [:= :id parecer-id]] :for :update})))]
    (when-not (contains? logic/estados-parecer-terminais (:estado p))
      (parecer/designar-relator! tx {:id parecer-id :ente-id ente-id :relator-id relator-id
                                     :updated-by por :lock-version (:lock-version p)})
      {:id parecer-id :relator-id relator-id})))
