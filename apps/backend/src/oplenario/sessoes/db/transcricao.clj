(ns oplenario.sessoes.db.transcricao
  "Persistencia do PONTEIRO da transcricao (Faixa A / A.3, mig 0085, ADR-0008) — funcoes sobre a `tx` do tenant
  (RLS isola). O TEXTO da transcricao vive na IA (§22.3.4); aqui so' a situacao (concluida|falhou), as metricas e
  os modelos usados, uma linha por evento recebido. Append-only: transcrever de novo = linha nova."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :sessao_id :segmento_id :situacao :transcricao_id :versao :idioma :duracao_s :n_trechos
   :cobertura_atribuida :modelo_asr :modelo_diarizacao :categoria_erro :detalhe_erro :retentavel
   :ocorrido_em :recebido_em])

(defn registrar!
  "Grava o ponteiro. O segmento TEM de estar vinculado a `sessao-id` (a IA so' transcreve o que o core promoveu,
  mas o core nao confia: evento de outra sessao/segmento solto -> `:validacao/transcricao-fora-da-sessao`).
  Devolve {:id}."
  [tx {:keys [ente-id sessao-id segmento-id] :as m}]
  (let [seg (jdbc/execute-one! tx (sql/format {:select [:sessao_id] :from [:sessoes.gravacao_segmento]
                                               :where [:and [:= :ente_id ente-id] [:= :id segmento-id]]}))]
    (when-not (= sessao-id (:gravacao_segmento/sessao_id seg))
      (throw (ex-info "transcricao de segmento que nao pertence a sessao"
                      {:tipo :validacao/transcricao-fora-da-sessao :sessao-id sessao-id :segmento-id segmento-id}))))
  (let [id (random-uuid)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :sessoes.transcricao_sessao
                   :values [{:ente_id ente-id :id id :sessao_id sessao-id :segmento_id segmento-id
                             :situacao (:situacao m) :transcricao_id (:transcricao-id m) :versao (:versao m)
                             :idioma (:idioma m) :duracao_s (:duracao-s m) :n_trechos (:n-trechos m)
                             :cobertura_atribuida (:cobertura-atribuida m) :modelo_asr (:modelo-asr m)
                             :modelo_diarizacao (:modelo-diarizacao m) :categoria_erro (:categoria-erro m)
                             :detalhe_erro (:detalhe-erro m) :retentavel (:retentavel m)
                             :ocorrido_em (:ocorrido-em m)}]}))
    {:id id}))

(defn listar-da-sessao
  "Os ponteiros da sessao, mais recentes primeiro (a tela mostra a situacao atual de cada segmento)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:sessoes.transcricao_sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:ocorrido_em :desc] [:recebido_em :desc]]}))))

(defn buscar-da-sessao
  "O ponteiro da transcricao `transcricao-id` DESTA sessao (concluida), ou nil. E' a prova de que a transcricao
  pertence a sessao que o ator pode ver — o core nunca pede a IA um id que ele mesmo nao registrou."
  [tx ente-id sessao-id transcricao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:sessoes.transcricao_sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                          [:= :transcricao_id transcricao-id] [:= :situacao "concluida"]]
                  :limit 1}))))
