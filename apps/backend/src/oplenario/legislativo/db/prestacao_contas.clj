(ns oplenario.legislativo.db.prestacao-contas
  "Persistencia do JULGAMENTO DAS CONTAS (ADR-0021 Parte B, mig 20261003000183): a prestacao, os documentos (insert-only),
  e o parametro de prazos da Casa. Funcoes sobre a `tx` do tenant (RLS isola). O estado da prestacao NAO e' coluna (logic/contas/estado); aqui so' se grava o que
  aconteceu: o registro (com o prazo de julgamento congelado), a notificacao (com o prazo de defesa congelado), a
  defesa juntada e — so' pelo encerramento da votacao do PDL — o resultado. A regra de votacao das contas mora em
  `db/regra_votacao` (junto com a da emenda a LOM)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.logic.contas :as contas]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :tipo :exercicio :responsavel :recebida_em :processo_tce :parecer_previo :proposicao_id
   :notificado_em :notificacao_meio :prazo_defesa_ate :defesa_juntada_em :prazo_julgamento_ate :resultado
   :votacao_id :julgada_em :situacao_tce :criado_em :atualizado_em :lock_version])

(defn- consulta [ente-id & onde]
  {:select colunas :from [:legislativo.prestacao_contas]
   :where (into [:and [:= :ente_id ente-id]] onde)})

(defn buscar [tx ente-id id]
  (comum/linha->kebab (jdbc/execute-one! tx (sql/format (consulta ente-id [:= :id id])))))

(defn buscar-com-lock
  "A prestacao sob `FOR UPDATE`: notificar e juntar defesa leem o estado e escrevem na mesma tx, serializados."
  [tx ente-id id]
  (comum/linha->kebab (jdbc/execute-one! tx (sql/format (assoc (consulta ente-id [:= :id id]) :for :update)))))

(defn da-proposicao
  "A prestacao cujo PDL e' `proposicao-id`, ou nil (a materia nao e' de contas)."
  [tx ente-id proposicao-id]
  (comum/linha->kebab (jdbc/execute-one! tx (sql/format (consulta ente-id [:= :proposicao_id proposicao-id])))))

(defn listar
  "As prestacoes da Casa: as mais recentes primeiro (o governo antes da Mesa no mesmo exercicio)."
  [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx (sql/format (assoc (consulta ente-id) :order-by [[:exercicio :desc] [:tipo :desc]])))))

(defn existe? [tx ente-id tipo exercicio]
  (some? (jdbc/execute-one! tx (sql/format {:select [1] :from [:legislativo.prestacao_contas]
                                            :where [:and [:= :ente_id ente-id] [:= :tipo tipo]
                                                    [:= :exercicio exercicio]]}))))

(defn inserir!
  "Registra a prestacao (o PDL, quando ha', ja' foi protocolado nesta tx). Devolve o id."
  [tx {:keys [id ente-id tipo exercicio responsavel recebida-em processo-tce parecer-previo proposicao-id
              prazo-julgamento-ate situacao-tce por]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.prestacao_contas
                 :values [{:id id :ente_id ente-id :tipo tipo :exercicio exercicio :responsavel responsavel
                           :recebida_em recebida-em :processo_tce processo-tce :parecer_previo parecer-previo
                           :proposicao_id proposicao-id :prazo_julgamento_ate prazo-julgamento-ate
                           :situacao_tce situacao-tce :criado_por por :atualizado_por por}]}))
  id)

(defn atualizar!
  "PATCH de `processo-tce`/`situacao-tce` — so' as chaves PRESENTES em `m` mudam (nil limpa o campo)."
  [tx ente-id id m por]
  (let [campos (cond-> {}
                 (contains? m :processo-tce) (assoc :processo_tce (:processo-tce m))
                 (contains? m :situacao-tce) (assoc :situacao_tce (:situacao-tce m)))]
    (when (seq campos)
      (jdbc/execute-one! tx
        (sql/format {:update :legislativo.prestacao_contas
                     :set (assoc campos :atualizado_por por :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1])
                     :where [:and [:= :ente_id ente-id] [:= :id id]]})))
    (buscar tx ente-id id)))

(defn notificar!
  "Grava a notificacao e o prazo de defesa CONGELADO (calculado pelo chamador sobre o parametro de hoje)."
  [tx ente-id id {:keys [notificado-em meio prazo-defesa-ate por]}]
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.prestacao_contas
                 :set {:notificado_em notificado-em :notificacao_meio meio :prazo_defesa_ate prazo-defesa-ate
                       :atualizado_por por :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                 :where [:and [:= :ente_id ente-id] [:= :id id] [:= :notificado_em nil]]})))

(defn marcar-defesa-juntada!
  "A PRIMEIRA defesa juntada fixa a data (as seguintes so' acrescentam documento). So' governo tem defesa."
  [tx ente-id id por]
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.prestacao_contas
                 :set {:defesa_juntada_em [:now] :atualizado_por por :atualizado_em [:now]
                       :lock_version [:+ :lock_version 1]}
                 :where [:and [:= :ente_id ente-id] [:= :id id] [:= :tipo [:inline "governo_prefeito"]]
                         [:= :defesa_juntada_em nil]]})))

;; ---------------- documentos (insert-only) ----------------

(def ^:private colunas-documento
  [:id :prestacao_id :tipo :nome :tipo_midia :tamanho_bytes :sha256 :chave_objeto :criado_em])

(defn inserir-documento!
  [tx {:keys [id ente-id prestacao-id tipo nome tipo-midia tamanho-bytes sha256 chave-objeto por]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :legislativo.prestacao_contas_documento
                  :values [{:id id :ente_id ente-id :prestacao_id prestacao-id :tipo tipo :nome nome
                            :tipo_midia tipo-midia :tamanho_bytes tamanho-bytes :sha256 sha256
                            :chave_objeto chave-objeto :criado_por por}]
                  :returning colunas-documento}))))

(defn documentos [tx ente-id prestacao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx (sql/format {:select colunas-documento :from [:legislativo.prestacao_contas_documento]
                                  :where [:and [:= :ente_id ente-id] [:= :prestacao_id prestacao-id]]
                                  :order-by [[:criado_em :asc] [:id :asc]]}))))

(defn documento [tx ente-id prestacao-id doc-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select colunas-documento :from [:legislativo.prestacao_contas_documento]
                                      :where [:and [:= :ente_id ente-id] [:= :prestacao_id prestacao-id]
                                              [:= :id doc-id]]}))))

;; ---------------- o PDL e a votacao ----------------

(defn proposicao-resumo
  "{:id :tipo :ano :sequencial :estado} do PDL, para o rotulo e o estado na ficha."
  [tx ente-id proposicao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select [:id :tipo :ano :sequencial :estado] :from [:legislativo.proposicoes]
                                      :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]]}))))

(defn votacao-totais
  "O placar gravado no encerramento: {:id :total-sim :total-nao :total-abstencao :base-membros}."
  [tx ente-id votacao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select [:id :total_sim :total_nao :total_abstencao :base_membros]
                                      :from [:legislativo.votacoes]
                                      :where [:and [:= :ente_id ente-id] [:= :id votacao-id]]}))))

(defn registrar-julgamento!
  "Chamado pelo ENCERRAMENTO da votacao, na mesma tx: se a votacao encerrada `v` e' a do PDL de uma prestacao ainda sem
  resultado, grava o resultado (aprovada = parecer rejeitado; rejeitada = parecer mantido), a votacao e a hora. Votacao
  de outra materia, ou prestacao ja' julgada (uma segunda votacao nao reescreve o julgamento): nada. Devolve a
  prestacao julgada, ou nil."
  [tx ente-id {:keys [id objeto-tipo objeto-id estado resultado]}]
  (when (and (= "proposicao" objeto-tipo) (= "encerrada" estado) resultado)
    (some-> (jdbc/execute-one! tx
              (sql/format {:update :legislativo.prestacao_contas
                           :set {:resultado (contas/resultado-da-votacao resultado) :votacao_id id :julgada_em [:now]
                                 :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :proposicao_id objeto-id]
                                   [:= :tipo [:inline "governo_prefeito"]] [:= :resultado nil]]
                           :returning [:id]}))
            comum/linha->kebab)))

;; ---------------- parametro da Casa ----------------

(defn parametros
  "A linha da Casa ({:prazo-defesa-dias :prazo-julgamento-dias}) ou nil (vale o padrao)."
  [tx ente-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select [:prazo_defesa_dias :prazo_julgamento_dias]
                                      :from [:legislativo.parametro_contas]
                                      :where [:= :ente_id ente-id]}))))

(defn salvar-parametros!
  "UPSERT dos prazos da Casa. Devolve a linha gravada."
  [tx ente-id {:keys [prazo-defesa-dias prazo-julgamento-dias por]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.parametro_contas
                 :values [{:ente_id ente-id :prazo_defesa_dias prazo-defesa-dias
                           :prazo_julgamento_dias prazo-julgamento-dias :atualizado_por por}]
                 :on-conflict [:ente_id]
                 :do-update-set {:prazo_defesa_dias :excluded.prazo_defesa_dias
                                 :prazo_julgamento_dias :excluded.prazo_julgamento_dias
                                 :atualizado_por :excluded.atualizado_por
                                 :atualizado_em [:now]}}))
  (parametros tx ente-id))
