(ns oplenario.legislativo.db.resumo
  "Persistencia do RESUMO CIDADAO de uma proposicao (Faixa A / A.8, mig 0090) — funcoes sobre a `tx` do tenant (RLS
  isola). Dois registros append-only: o PONTEIRO do rascunho que a IA redigiu (o texto vive na IA, §22.3.4) e as
  VERSOES publicadas pela secretaria (o que vai para o portal)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(def ^:private colunas-rascunho
  [:id :proposicao_id :situacao :rascunho_id :texto_base_sha256 :modelo_llm_id :prompt_versao :incerteza
   :n_citacoes :n_citacoes_conferidas :n_paragrafos_sem_fonte :categoria_erro :detalhe_erro :retentavel :ocorrido_em])

(def ^:private meta-versao
  [:id :proposicao_id :versao :conteudo_sha256 :texto_base_sha256 :origem_redacao :rascunho_id :modelo_llm_id
   :prompt_versao :publicado_por :publicado_em])

(defn- existe-proposicao? [tx ente-id proposicao-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [1] :from [:legislativo.proposicoes]
                                            :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]]}))))

(defn registrar-rascunho!
  "Grava o fato que a IA devolveu ('pronto' | 'falhou'). A proposicao TEM de existir no tenant (o core nao confia:
  fato de proposicao desconhecida -> `:validacao/resumo-sem-proposicao`, e a caixa de entrada nao queima a chave).
  Devolve {:id}."
  [tx {:keys [ente-id proposicao-id] :as m}]
  (when-not (existe-proposicao? tx ente-id proposicao-id)
    (throw (ex-info "resumo de proposicao que nao existe nesta Casa"
                    {:tipo :validacao/resumo-sem-proposicao :proposicao-id proposicao-id})))
  (let [id (random-uuid)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.resumo_rascunho
                   :values [{:ente_id ente-id :id id :proposicao_id proposicao-id :situacao (:situacao m)
                             :rascunho_id (:rascunho-id m) :texto_base_sha256 (:texto-base-sha256 m)
                             :modelo_llm_id (:modelo-llm-id m) :prompt_versao (:prompt-versao m)
                             :incerteza (:incerteza m) :n_citacoes (:n-citacoes m)
                             :n_citacoes_conferidas (:n-citacoes-conferidas m)
                             :n_paragrafos_sem_fonte (:n-paragrafos-sem-fonte m)
                             :categoria_erro (:categoria-erro m) :detalhe_erro (:detalhe-erro m)
                             :retentavel (:retentavel m) :ocorrido_em (:ocorrido-em m)}]}))
    {:id id}))

(defn ultimo-rascunho
  "O fato mais recente do rascunho da proposicao (pronto ou falhou), ou nil (a IA ainda nao redigiu)."
  [tx ente-id proposicao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas-rascunho :from [:legislativo.resumo_rascunho]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]
                  :order-by [[:ocorrido_em :desc] [:registrado_em :desc]] :limit 1}))))

(defn buscar-pronto
  "O ponteiro 'pronto' do rascunho `rascunho-id` DESTA proposicao, ou nil — a prova de que o rascunho e' dela (o core
  nunca pede a IA um id que ele mesmo nao registrou)."
  [tx ente-id proposicao-id rascunho-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas-rascunho :from [:legislativo.resumo_rascunho]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id] [:= :rascunho_id rascunho-id]
                          [:= :situacao "pronto"]]
                  :limit 1}))))

(defn publicar!
  "Insere a proxima versao do resumo publicado da proposicao. Devolve os metadados da versao. Duas publicacoes
  simultaneas -> a segunda cai no UNIQUE (ente, proposicao, versao) -> `:conflito/resumo-versao`."
  [tx {:keys [ente-id proposicao-id] :as m}]
  (let [versao (inc (or (:max (jdbc/execute-one! tx (sql/format {:select [[[:max :versao] :max]]
                                                                  :from [:legislativo.resumo_cidadao]
                                                                  :where [:and [:= :ente_id ente-id]
                                                                          [:= :proposicao_id proposicao-id]]})))
                        0))]
    (try
      (comum/linha->kebab
       (jdbc/execute-one! tx
         (sql/format {:insert-into :legislativo.resumo_cidadao
                      :values [{:ente_id ente-id :proposicao_id proposicao-id :versao versao :texto (:texto m)
                                :conteudo_sha256 (:conteudo-sha256 m) :texto_base_sha256 (:texto-base-sha256 m)
                                :origem_redacao (:origem-redacao m) :rascunho_id (:rascunho-id m)
                                :modelo_llm_id (:modelo-llm-id m) :prompt_versao (:prompt-versao m)
                                :publicado_por (:publicado-por m)}]
                      :returning meta-versao})))
      (catch PSQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "outra versao do resumo foi publicada ao mesmo tempo"
                          {:tipo :conflito/resumo-versao :proposicao-id proposicao-id}))
          (throw e))))))

(defn atual
  "A versao publicada mais recente, com o texto, ou nil."
  [tx ente-id proposicao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select (conj meta-versao :texto) :from [:legislativo.resumo_cidadao]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]
                  :order-by [[:versao :desc]] :limit 1}))))

(defn listar-versoes
  "Metadados de todas as versoes publicadas (sem o texto), mais recente primeiro."
  [tx ente-id proposicao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx (sql/format {:select meta-versao :from [:legislativo.resumo_cidadao]
                                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]
                                  :order-by [[:versao :desc]]}))))
