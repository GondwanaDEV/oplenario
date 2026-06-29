(ns oplenario.legislativo.db.autografo
  "Persistencia do AUTOGRAFO (F3.8a) — artefato legal APPEND-ONLY (o trigger congela; correcao = artefato
  novo via fluxo auditado). `gerar!` e' atomico: numera gapless (kernel/sequencial, escopo 'autografo:ano'
  do ente da SESSAO) + insere, na MESMA tx (rollback nao deixa buraco no contador). Funcoes sobre a `tx`
  do tenant (RLS isola); HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :proposicao_id :numero :ano :texto_versao_id :destinatario_texto :destinatario_id
   :enviado_em :prazo_resposta_em])

(defn gerar!
  "Gera o autografo da proposicao aprovada: numera gapless (escopo 'autografo:ano') e insere — atomico na tx.
  A UNIQUE (ente_id, proposicao_id) barra um segundo autografo p/ a mesma proposicao. Devolve {:id :numero}
  (o numero so existe pos-commit). `texto-versao-id` = a versao 'redacao_final' aprovada (eixo B)."
  [tx {:keys [id ente-id proposicao-id ano texto-versao-id destinatario-texto destinatario-id
              prazo-resposta-em created-by]}]
  (let [num (sequencial/proximo! tx (str "autografo:" ano))]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.autografo
                   :values [{:id id :ente_id ente-id :proposicao_id proposicao-id :numero num :ano ano
                             :texto_versao_id texto-versao-id :destinatario_texto destinatario-texto
                             :destinatario_id destinatario-id :prazo_resposta_em prazo-resposta-em
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :numero num}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.autografo]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn buscar-por-proposicao [tx ente-id proposicao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.autografo]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))))
