(ns oplenario.sessoes.db.sessao-publica
  "As sessoes que o PORTAL do cidadao pode mostrar, para o portal de VOTACOES (frente 'portal-votacoes-publicas').
  A regra e' a do livro de atas e da pauta oficial — transmissao publica E nao secreta (`logic/ata-no-portal?`) —
  e a decisao mora AQUI, em `sessoes`: o host entrega os ids a quem precisa (legislativo), que nao conhece sessao.
  Funcoes sobre a `tx` do tenant (RLS isola); ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :tipo_sessao :numero_sequencial :estado :transmite_publica :agendada_para :aberta_em :encerrada_em])

(defn- publicas [linhas]
  ;; o SQL ja' filtra; a regra pura confere de novo (fail-closed: se um dia as duas divergirem, vale a mais estrita)
  (filterv logic/ata-no-portal? linhas))

(defn publicas-da-casa
  "TODAS as sessoes publicas e nao secretas da Casa (sem teto: e' o conjunto que filtra as votacoes, nao uma lista
  de tela). {:id :tipo-sessao :numero-sequencial :estado :agendada-para :aberta-em :encerrada-em}."
  [tx ente-id]
  (publicas
   (comum/linhas->kebab
    (jdbc/execute! tx (sql/format {:select colunas :from [:sessoes.sessao]
                                   :where [:and [:= :ente_id ente-id] [:= :transmite_publica true]
                                           [:<> :tipo_sessao [:inline "secreta"]]]})))))

(defn publica
  "Uma sessao publica e nao secreta da Casa, ou nil (inexistente, de outra Casa, secreta ou fechada ao publico)."
  [tx ente-id sessao-id]
  (first
   (publicas
    (comum/linhas->kebab
     (jdbc/execute! tx (sql/format {:select colunas :from [:sessoes.sessao]
                                    :where [:and [:= :ente_id ente-id] [:= :id sessao-id]]}))))))
