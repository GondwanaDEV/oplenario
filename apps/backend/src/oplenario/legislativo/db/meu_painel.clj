(ns oplenario.legislativo.db.meu-painel
  "Leitura escopada do painel do vereador (Onda C1, §11.2/§11.3) — 'minhas proposicoes' (autor_tipo=
  'vereador' AND autor_id=V) e 'meus pareceres' (relator_id=V), SEMPRE com `ente_id` no WHERE (Inv.1;
  `proposicoes` e' hash-particionada por ente_id — `idx_proposicoes_autor` ja' existe p/ este hot-path
  exato). Subconjunto ESTREITO de colunas (mesma disciplina de `proposicao/colunas-resumo`): so' o que o
  painel de fato mostra, nunca `atributos_especificos`/jsonb. `:ciencias` e' preenchido pela Task 3
  (db/meu-painel/ciencias-pendentes, ainda inexistente aqui)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas-proposicao
  [:id :tipo :ano :sequencial :urn_lex :ementa :estado :atualizado_em])

(defn proposicoes-do-autor
  "Proposicoes de autoria do vereador `vereador-id` (autor_tipo='vereador') neste ente — mais recente
  primeiro (atualizado_em desc; :id asc como desempate estavel, mesma disciplina de db/proposicao/listar)."
  [tx ente-id vereador-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas-proposicao :from [:legislativo.proposicoes]
                  :where [:and [:= :ente_id ente-id] [:= :autor_tipo [:inline "vereador"]]
                          [:= :autor_id vereador-id]]
                  :order-by [[:atualizado_em :desc] [:id :asc]]}))))

(def ^:private colunas-parecer
  [:id :objeto_tipo :objeto_id :comissao_id :estado :voto_relator :criado_em])

(defn pareceres-do-relator
  "Pareceres em que o vereador `vereador-id` e' o relator, neste ente — mais recente primeiro (criado_em
  desc; :id asc como desempate estavel)."
  [tx ente-id vereador-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas-parecer :from [:legislativo.pareceres]
                  :where [:and [:= :ente_id ente-id] [:= :relator_id vereador-id]]
                  :order-by [[:criado_em :desc] [:id :asc]]}))))
