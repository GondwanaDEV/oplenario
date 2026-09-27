(ns oplenario.sessoes.db.ata-rascunho
  "Persistencia do PONTEIRO do rascunho de ata da IA (Faixa A / A.6b, mig 0087) — funcoes sobre a `tx` do tenant (RLS
  isola). O TEXTO do rascunho vive na IA (§22.3.4); aqui so' os fatos de cada solicitacao (solicitado -> pronto |
  falhou), uma linha por fato, append-only. A situacao atual de uma solicitacao e' o fato mais recente dela."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :solicitacao_id :sessao_id :situacao :solicitado_por :rascunho_id :modelo_llm_id :prompt_versao :incerteza
   :n_citacoes :n_citacoes_conferidas :n_paragrafos_sem_fonte :n_pontos_a_confirmar :categoria_erro :detalhe_erro
   :retentavel :ocorrido_em])

(defn solicitar!
  "Grava o fato 'solicitado' (a secretaria pediu). Devolve {:solicitacao-id}."
  [tx {:keys [ente-id sessao-id solicitacao-id solicitado-por ocorrido-em]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.ata_rascunho
                 :values [{:ente_id ente-id :solicitacao_id solicitacao-id :sessao_id sessao-id :situacao "solicitado"
                           :solicitado_por solicitado-por :ocorrido_em ocorrido-em}]}))
  {:solicitacao-id solicitacao-id})

(defn registrar!
  "Grava o fato que a IA devolveu ('pronto' | 'falhou'). A solicitacao TEM de existir para esta sessao (o core nao
  confia: fato de solicitacao desconhecida ou de outra sessao -> `:validacao/rascunho-fora-da-sessao`). Devolve {:id}."
  [tx {:keys [ente-id sessao-id solicitacao-id] :as m}]
  (let [s (jdbc/execute-one! tx (sql/format {:select [:sessao_id] :from [:sessoes.ata_rascunho]
                                             :where [:and [:= :ente_id ente-id] [:= :solicitacao_id solicitacao-id]
                                                     [:= :situacao "solicitado"]]}))]
    (when-not (= sessao-id (:ata_rascunho/sessao_id s))
      (throw (ex-info "rascunho de ata de solicitacao que nao pertence a sessao"
                      {:tipo :validacao/rascunho-fora-da-sessao :sessao-id sessao-id :solicitacao-id solicitacao-id}))))
  (let [id (random-uuid)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :sessoes.ata_rascunho
                   :values [{:ente_id ente-id :id id :solicitacao_id solicitacao-id :sessao_id sessao-id
                             :situacao (:situacao m) :rascunho_id (:rascunho-id m) :modelo_llm_id (:modelo-llm-id m)
                             :prompt_versao (:prompt-versao m) :incerteza (:incerteza m)
                             :n_citacoes (:n-citacoes m) :n_citacoes_conferidas (:n-citacoes-conferidas m)
                             :n_paragrafos_sem_fonte (:n-paragrafos-sem-fonte m)
                             :n_pontos_a_confirmar (:n-pontos-a-confirmar m)
                             :categoria_erro (:categoria-erro m) :detalhe_erro (:detalhe-erro m)
                             :retentavel (:retentavel m) :ocorrido_em (:ocorrido-em m)}]}))
    {:id id}))

(defn ultimo-da-sessao
  "A situacao ATUAL da solicitacao mais recente da sessao: o fato mais novo dela, com `:solicitado-em` e
  `:solicitado-por` do pedido. nil = nunca se pediu rascunho."
  [tx ente-id sessao-id]
  (when-let [pedido (comum/linha->kebab
                     (jdbc/execute-one! tx
                       (sql/format {:select colunas :from [:sessoes.ata_rascunho]
                                    :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                                            [:= :situacao "solicitado"]]
                                    :order-by [[:ocorrido_em :desc] [:id :desc]] :limit 1})))]
    (let [atual (comum/linha->kebab
                 (jdbc/execute-one! tx
                   (sql/format {:select colunas :from [:sessoes.ata_rascunho]
                                :where [:and [:= :ente_id ente-id] [:= :solicitacao_id (:solicitacao-id pedido)]]
                                :order-by [[[:case [:= :situacao "solicitado"] 0 :else 1] :desc]] :limit 1})))]
      (assoc atual :solicitado-em (:ocorrido-em pedido) :solicitado-por (:solicitado-por pedido)))))

(defn buscar-pronto
  "O ponteiro 'pronto' do rascunho `rascunho-id` DESTA sessao, ou nil — a prova de que o rascunho pertence a sessao
  que o ator pode ver (o core nunca pede a IA um id que ele mesmo nao registrou)."
  [tx ente-id sessao-id rascunho-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:sessoes.ata_rascunho]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :rascunho_id rascunho-id]
                          [:= :situacao "pronto"]]
                  :limit 1}))))
