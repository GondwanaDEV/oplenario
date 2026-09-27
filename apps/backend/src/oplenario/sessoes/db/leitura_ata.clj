(ns oplenario.sessoes.db.leitura-ata
  "Persistencia da LEITURA DA ATA ANTERIOR (Faixa A / A.7, mig 0088) — funcoes sobre a `tx` do tenant (RLS isola).
  Uma leitura por sessao (UNIQUE): registrar de novo vira `:conflito/leitura-registrada` (409), nunca duas."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :sessao_id :ata_sessao_id :ata_versao :ata_conteudo_sha256 :modo :registrada_por :registrada_em])

(defn sessao-anterior
  "A sessao cuja ata esta' sessao le: a mais recente da Casa, ANTERIOR a esta (pelo inicio: aberta_em, ou a data
  agendada), que gera ata regimental e ja' acabou. Sessao secreta so' e' 'anterior' de outra secreta — a ata sigilosa
  nunca e' lida em sessao publica. nil = nao ha'."
  [tx ente-id {:keys [id tipo-sessao aberta-em agendada-para]}]
  (let [inicio (or aberta-em agendada-para)]
    (when inicio
      (comum/linha->kebab
       (jdbc/execute-one! tx
         (sql/format {:select [:id :tipo_sessao :numero_sequencial :aberta_em :encerrada_em]
                      :from [:sessoes.sessao]
                      :where [:and [:= :ente_id ente-id] [:<> :id id] [:= :gera_ata_regimental true]
                              [:in :estado ["encerrada" "arquivada"]]
                              [:< [:coalesce :aberta_em :agendada_para] inicio]
                              (when-not (= "secreta" tipo-sessao) [:<> :tipo_sessao "secreta"])]
                      :order-by [[[:coalesce :aberta_em :agendada_para] :desc] [:id :desc]]
                      :limit 1}))))))

(defn da-sessao
  "A leitura registrada nesta sessao, ou nil."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select colunas :from [:sessoes.leitura_ata]
                                      :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]}))))

(defn registrar!
  "Grava o ato de leitura. Devolve a linha."
  [tx {:keys [ente-id sessao-id ata-sessao-id ata-versao ata-conteudo-sha256 modo registrada-por]}]
  (try
    (comum/linha->kebab
     (jdbc/execute-one! tx
       (sql/format {:insert-into :sessoes.leitura_ata
                    :values [{:ente_id ente-id :sessao_id sessao-id :ata_sessao_id ata-sessao-id
                              :ata_versao ata-versao :ata_conteudo_sha256 ata-conteudo-sha256 :modo modo
                              :registrada_por registrada-por}]
                    :returning colunas})))
    (catch PSQLException e
      (if (= "23505" (.getSQLState e))
        (throw (ex-info "a leitura da ata desta sessao ja' foi registrada" {:tipo :conflito/leitura-registrada
                                                                            :sessao-id sessao-id}))
        (throw e)))))
