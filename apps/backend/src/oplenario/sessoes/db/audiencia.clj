(ns oplenario.sessoes.db.audiencia
  "Persistencia da AUDIENCIA PUBLICA (ADR-0021 Parte A, mig 182) — funcoes sobre a `tx` do tenant (RLS isola): a linha
  1:1 da audiencia (`sessoes.audiencia`) e as inscricoes de cidadao (`sessoes.inscricao_cidadao`). A inscricao nasce
  com o protocolo do contador gapless da Casa e a ORDEM max+1, as duas DEPOIS de travar a linha da audiencia (`FOR
  UPDATE`): duas inscricoes simultaneas nunca disputam a mesma ordem, e o 'inscricoes abertas' e' lido sob a trava. As
  transicoes sao UPDATE condicional no estado de origem (CAS pelo estado: quem perdeu a corrida recebe nil)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.sessoes.logic.audiencia :as logic-aud])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(def ^:private colunas-audiencia
  [:sessao_id :comissao_id :tema :local :proposicao_id :finalidade :referencia :tempo_fala_segundos
   :inscricoes_abertas :criado_em :atualizado_em])

(def ^:private colunas-inscricao
  [:id :sessao_id :ano :numero :protocolo :origem :identidade_id :nome :fala_como :entidade :tema :ordem :estado
   :chamada_em :encerrada_em :tempo_usado_segundos :registrada_por :criado_em])

(defn inserir!
  "A linha 1:1 da audiencia, na tx do agendamento da sessao."
  [tx {:keys [ente-id sessao-id comissao-id tema local proposicao-id finalidade referencia tempo-fala-segundos
              created-by]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :sessoes.audiencia
                  :values [(cond-> {:ente_id ente-id :sessao_id sessao-id :comissao_id comissao-id :tema tema
                                    :local local :proposicao_id proposicao-id :finalidade finalidade
                                    :referencia referencia :created_by created-by}
                             tempo-fala-segundos (assoc :tempo_fala_segundos tempo-fala-segundos))]
                  :returning colunas-audiencia}))))

(defn sessao-com-trava-compartilhada
  "O que decide se a inscricao/fala cabe (tipo, estado, capability), com a linha da sessao em `FOR SHARE` — a Mesa nao
  encerra a sessao entre a conferencia e a escrita (mesmo desenho de `db/sessao/janela-para-registro`)."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select [:id :ente_id :tipo_sessao :estado :aceita_inscricao_cidadao]
                                      :from [:sessoes.sessao]
                                      :where [:and [:= :ente_id ente-id] [:= :id sessao-id]]
                                      :for :share}))))

(defn buscar
  "A audiencia da sessao, ou nil (a sessao nao e' audiencia)."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select colunas-audiencia :from [:sessoes.audiencia]
                                      :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]}))))

(defn travar
  "A audiencia com a linha TRAVADA ate' o fim da tx (`FOR UPDATE`) — serializa inscricoes e chamadas da mesma
  audiencia. nil = nao e' audiencia."
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select colunas-audiencia :from [:sessoes.audiencia]
                                      :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                                      :for :update}))))

(defn atualizar!
  "Os dados que a Mesa ajusta depois de agendar (`campos` ⊆ tempo-fala-segundos, inscricoes-abertas, local; chave
  presente com nil = limpar o local). Devolve a audiencia, ou nil."
  [tx ente-id sessao-id campos updated-by]
  (let [sets (cond-> {:updated_by updated-by :atualizado_em [:now]}
               (contains? campos :tempo-fala-segundos) (assoc :tempo_fala_segundos (:tempo-fala-segundos campos))
               (contains? campos :inscricoes-abertas) (assoc :inscricoes_abertas (:inscricoes-abertas campos))
               (contains? campos :local) (assoc :local (:local campos)))]
    (comum/linha->kebab
     (jdbc/execute-one! tx (sql/format {:update :sessoes.audiencia :set sets
                                        :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                                        :returning colunas-audiencia})))))

(defn inscricoes-da-sessao
  "Todas as inscricoes da audiencia, na ordem."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx (sql/format {:select colunas-inscricao :from [:sessoes.inscricao_cidadao]
                                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                                  :order-by [[:ordem :asc]]}))))

(defn buscar-inscricao [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select colunas-inscricao :from [:sessoes.inscricao_cidadao]
                                      :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn alguem-falando?
  [tx ente-id sessao-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [[1 :um]] :from [:sessoes.inscricao_cidadao]
                                            :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                                                    [:= :estado "falando"]]}))))

(defn- proxima-ordem [tx ente-id sessao-id]
  (-> (jdbc/execute-one! tx (sql/format {:select [[[:coalesce [:max :ordem] 0] :m]] :from [:sessoes.inscricao_cidadao]
                                         :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]}))
      comum/linha->kebab :m long inc))

(defn inserir-inscricao!
  "Grava a inscricao: protocolo gapless (`inscricao_audiencia:<ano>`) + ordem max+1, na tx do chamador — que JA'
  travou a audiencia (`travar`). O mesmo cidadao duas vezes (sem ter desistido) bate no indice unico parcial e vira
  `:conflito/audiencia` (409), nunca 500. Devolve a inscricao."
  [tx {:keys [ente-id sessao-id ano origem identidade-id nome fala-como entidade tema registrada-por]}]
  (let [numero (sequencial/proximo! tx (logic-aud/escopo-protocolo ano))
        ordem (proxima-ordem tx ente-id sessao-id)]
    (try
      (comum/linha->kebab
       (jdbc/execute-one! tx
         (sql/format {:insert-into :sessoes.inscricao_cidadao
                      :values [{:ente_id ente-id :id (random-uuid) :sessao_id sessao-id :ano ano :numero numero
                                :protocolo (logic-aud/protocolo ano numero) :origem origem
                                :identidade_id identidade-id :nome nome :fala_como fala-como :entidade entidade
                                :tema tema :ordem ordem :registrada_por registrada-por}]
                      :returning colunas-inscricao})))
      (catch PSQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "voce ja' esta' inscrito nesta audiencia" {:tipo :conflito/audiencia :motivo :ja-inscrito}))
          (throw e))))))

(defn transicionar-inscricao!
  "Move a inscricao `id` de `de` para `para` (UPDATE condicional no estado de origem), com os marcos `sets` (chamada_em,
  encerrada_em, tempo_usado_segundos). Devolve a inscricao, ou nil (nao estava em `de` — perdeu a corrida). A segunda
  fala simultanea bate no indice unico `uma_falando` e vira `:conflito/audiencia`."
  [tx ente-id id de para sets]
  (try
    (comum/linha->kebab
     (jdbc/execute-one! tx
       (sql/format {:update :sessoes.inscricao_cidadao
                    :set (merge {:estado para :atualizado_em [:now]} sets)
                    :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado de]]
                    :returning colunas-inscricao})))
    (catch PSQLException e
      (if (= "23505" (.getSQLState e))
        (throw (ex-info "ja' ha' um cidadao com a palavra: encerre a fala antes de chamar o proximo"
                        {:tipo :conflito/audiencia :motivo :alguem-falando}))
        (throw e)))))

;; ---------- o portal e a area da cidada ----------

(def ^:private colunas-resumo
  [:s.id :s.estado :s.agendada_para :s.aberta_em :s.encerrada_em :s.modalidade :s.aceita_inscricao_cidadao
   :s.tipo_sessao :s.transmite_publica :a.comissao_id :a.tema :a.local :a.finalidade :a.referencia
   :a.tempo_fala_segundos :a.inscricoes_abertas :a.proposicao_id])

(defn- base-publica [ente-id]
  [:and [:= :s.ente_id ente-id] [:= :s.tipo_sessao "audiencia_publica"] [:= :s.transmite_publica true]])

(defn audiencias-publicas
  "O portal: {:proximas (agendada|aberta|suspensa, a mais proxima primeiro) :realizadas (encerrada|arquivada, as
  `teto-realizadas-no-portal` mais recentes)} — so' audiencias de transmissao publica."
  [tx ente-id]
  (let [ler (fn [estados ordem limite]
              (comum/linhas->kebab
               (jdbc/execute! tx (sql/format (cond-> {:select colunas-resumo
                                                      :from [[:sessoes.sessao :s]]
                                                      :join [[:sessoes.audiencia :a]
                                                             [:and [:= :a.ente_id :s.ente_id] [:= :a.sessao_id :s.id]]]
                                                      :where [:and (base-publica ente-id) [:in :s.estado estados]]
                                                      :order-by ordem}
                                               limite (assoc :limit limite))))))]
    {:proximas (ler (vec logic-aud/estados-que-aceitam-inscricao)
                    [[[:coalesce :s.aberta_em :s.agendada_para] :asc] [:s.id :asc]] nil)
     :realizadas (ler (vec logic-aud/estados-realizada)
                      [[[:coalesce :s.aberta_em :s.agendada_para] :desc] [:s.id :desc]]
                      logic-aud/teto-realizadas-no-portal)}))

(defn ata-publicada?
  "A sessao tem ata publicada (qualquer versao)?"
  [tx ente-id sessao-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [[1 :um]] :from [:sessoes.ata]
                                            :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                                            :limit 1}))))

(defn inscricoes-da-identidade
  "A area da cidada: as inscricoes dela nesta Casa (todas as audiencias), a mais recente primeiro, com o que situa a
  audiencia (tema, comissao, data)."
  [tx ente-id identidade-id]
  (comum/linhas->kebab
   (jdbc/execute! tx (sql/format {:select [:i.id :i.protocolo :i.sessao_id :i.ordem :i.estado :i.criado_em :i.tema
                                           [:a.tema :tema_audiencia] :a.comissao_id :s.agendada_para]
                                  :from [[:sessoes.inscricao_cidadao :i]]
                                  :join [[:sessoes.audiencia :a] [:and [:= :a.ente_id :i.ente_id] [:= :a.sessao_id :i.sessao_id]]
                                         [:sessoes.sessao :s] [:and [:= :s.ente_id :i.ente_id] [:= :s.id :i.sessao_id]]]
                                  :where [:and [:= :i.ente_id ente-id] [:= :i.identidade_id identidade-id]]
                                  :order-by [[:i.criado_em :desc]]
                                  :limit 200}))))
