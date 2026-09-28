(ns oplenario.sessoes.db.ata
  "Persistencia da ATA publicada (Faixa A / A.6, mig 0086) — funcoes sobre a `tx` do tenant (RLS isola). Append-only:
  publicar de novo = nova versao (retificacao). A versao e' MAX+1 na mesma tx; duas publicacoes simultaneas colidem no
  UNIQUE (ente, sessao, versao) e a segunda vira `:conflito/ata-versao` (409), nunca duas atas com o mesmo numero."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(def ^:private meta-cols
  [:id :sessao_id :versao :origem_redacao :conteudo_sha256 :motivo_retificacao :rascunho_id :modelo_llm_id
   :prompt_versao :proporcao_alterada :publicada_por :publicada_em])

(defn publicar!
  "Insere a proxima versao da ata da sessao. A regra da RETIFICACAO (versao > 1 exige motivo; a primeira versao nao
  guarda motivo) e' decidida AQUI, sobre a versao calculada na mesma tx — decidir antes, fora da tx, deixava duas
  primeiras publicacoes simultaneas virarem uma 'retificacao' sem motivo (CHECK do banco -> 500). Devolve a linha."
  [tx {:keys [ente-id sessao-id motivo-retificacao] :as m}]
  (let [versao (inc (or (:max (jdbc/execute-one! tx (sql/format {:select [[[:max :versao] :max]]
                                                                  :from [:sessoes.ata]
                                                                  :where [:and [:= :ente_id ente-id]
                                                                          [:= :sessao_id sessao-id]]})))
                        0))
        motivo (when (> versao 1) (some-> motivo-retificacao str/trim not-empty))]
    (when (and (> versao 1) (nil? motivo))
      (throw (ex-info "a ata ja' foi publicada: para retificar, informe o motivo"
                      {:tipo :validacao/retificacao-sem-motivo :sessao-id sessao-id})))
    (try
      (comum/linha->kebab
       (jdbc/execute-one! tx
         (sql/format {:insert-into :sessoes.ata
                      :values [{:ente_id ente-id :sessao_id sessao-id :versao versao
                                :origem_redacao (:origem-redacao m) :texto (:texto m)
                                :conteudo_sha256 (:conteudo-sha256 m) :motivo_retificacao motivo
                                :rascunho_id (:rascunho-id m) :modelo_llm_id (:modelo-llm-id m)
                                :prompt_versao (:prompt-versao m) :proporcao_alterada (:proporcao-alterada m)
                                :publicada_por (:publicada-por m)}]
                      :returning meta-cols})))
      (catch PSQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "outra versao da ata foi publicada ao mesmo tempo" {:tipo :conflito/ata-versao
                                                                              :sessao-id sessao-id}))
          (throw e))))))

(defn listar-versoes
  "Metadados de todas as versoes (sem o texto), mais recente primeiro."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx (sql/format {:select meta-cols :from [:sessoes.ata]
                                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                                  :order-by [[:versao :desc]]}))))

(defn atual
  "A versao vigente (a mais alta), com o texto, ou nil."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select (conj meta-cols :texto) :from [:sessoes.ata]
                                      :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                                      :order-by [[:versao :desc]] :limit 1}))))

(defn versao
  "A versao `versao` da ata da sessao, com o texto, ou nil."
  [tx ente-id sessao-id versao]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select (conj meta-cols :texto) :from [:sessoes.ata]
                                      :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                                              [:= :versao versao]]}))))

(defn livro
  "O LIVRO DE ATAS da Casa: uma linha por sessao com ata publicada — a versao VIGENTE (metadados, sem texto), os dados
  da sessao que a situam e o ato de LEITURA dessa ata no plenario, se ja' houve (a primeira). `so-publicas?` restringe
  as sessoes de transmissao publica e nao secretas (o portal do cidadao); a visibilidade do servidor e' decidida no
  controller, por linha. Ordem: sessao mais recente primeiro (pelo inicio: aberta_em, ou a data agendada)."
  [tx ente-id so-publicas?]
  (let [linhas (comum/linhas->kebab
                (jdbc/execute! tx
                  (sql/format
                   {:select-distinct-on [[:a.sessao_id]
                                         :a.sessao_id :a.versao :a.origem_redacao :a.conteudo_sha256 :a.publicada_em
                                         :s.tipo_sessao :s.numero_sequencial :s.aberta_em :s.encerrada_em
                                         :s.agendada_para :s.transmite_publica
                                         [:l.modo :leitura_modo] [:l.registrada_em :leitura_registrada_em]
                                         [:l.ata_versao :leitura_ata_versao]]
                    :from [[:sessoes.ata :a]]
                    :join [[:sessoes.sessao :s] [:and [:= :s.ente_id :a.ente_id] [:= :s.id :a.sessao_id]]]
                    :left-join [[:sessoes.leitura_ata :l] [:and [:= :l.ente_id :a.ente_id]
                                                           [:= :l.ata_sessao_id :a.sessao_id]]]
                    :where (cond-> [:and [:= :a.ente_id ente-id]]
                             so-publicas? (conj [:= :s.transmite_publica true]
                                                [:<> :s.tipo_sessao [:inline "secreta"]]))
                    :order-by [:a.sessao_id [:a.versao :desc] [:l.registrada_em :asc]]})))
        inicio #(str (or (:aberta-em %) (:agendada-para %)))]
    (vec (sort-by (juxt inicio (comp str :sessao-id)) #(compare %2 %1) linhas))))

(defn primeira-leitura
  "O ato de LEITURA da ata desta sessao no plenario (a primeira, se mais de uma sessao a leu), ou nil."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select [:modo :registrada_em :ata_versao] :from [:sessoes.leitura_ata]
                                      :where [:and [:= :ente_id ente-id] [:= :ata_sessao_id sessao-id]]
                                      :order-by [[:registrada_em :asc]] :limit 1}))))
