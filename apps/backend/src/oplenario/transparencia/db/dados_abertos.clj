(ns oplenario.transparencia.db.dados-abertos
  "Leituras INTEIRAS do read-model publico para os DADOS ABERTOS (Decreto 8.777/2016 + LAI art. 8 §3: formato
  aberto, legivel por maquina). Sem teto de proposito: um dataset aberto cortado em 200 linhas nao e' o dataset —
  o volume de uma Casa (milhares de linhas) cabe numa resposta. Ordem ESTAVEL (a mesma a cada download, para quem
  compara versoes do arquivo). Funcoes sobre a `tx` do tenant (RLS isola)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn proposicoes [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:proposicao_id :tipo :sequencial :ano :urn_lex :ementa :autor_tipo :autor_texto :estado
                           :projetado_em :atualizado_em]
                  :from [:transparencia.materia]
                  :where [:= :ente_id ente-id]
                  :order-by [[:ano :asc] [:tipo :asc] [:sequencial :asc]]}))))

(defn normas [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:norma_id :tipo_norma :numero :ano :urn :ementa :publicado_em :veiculo_publicacao
                           :proposicao_id]
                  :from [:transparencia.norma]
                  :where [:= :ente_id ente-id]
                  :order-by [[:ano :asc] [:tipo_norma :asc] [:numero :asc]]}))))

(defn votos-nominais
  "Os votos NOMINAIS publicos (voto secreto nunca chega aqui: o ramo secreto do evento nao carrega vereador), com
  a materia votada quando ela foi projetada."
  [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:v.votacao_id :v.ocorrido_em :v.proposicao_id [:m.tipo :materia_tipo]
                           [:m.sequencial :materia_sequencial] [:m.ano :materia_ano] :v.vereador_id :v.voto]
                  :from [[:transparencia.voto_parlamentar :v]]
                  :left-join [[:transparencia.materia :m] [:and [:= :m.ente_id :v.ente_id]
                                                           [:= :m.proposicao_id :v.proposicao_id]]]
                  :where [:= :v.ente_id ente-id]
                  :order-by [[:v.ocorrido_em :asc] [:v.votacao_id :asc] [:v.vereador_id :asc]]}))))

(defn- resumo-de [tx ente-id tabela coluna-data]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select [[[:count :*] :linhas] [[:max coluna-data] :atualizado_em]]
                                      :from [tabela] :where [:= :ente_id ente-id]}))))

(defn resumo
  "{dataset {:linhas :atualizado-em}} — a ultima PROJECAO de cada dataset (quando o portal soube do dado)."
  [tx ente-id]
  {:proposicoes    (resumo-de tx ente-id :transparencia.materia :atualizado_em)
   :legislacao     (resumo-de tx ente-id :transparencia.norma :projetado_em)
   :votos-nominais (resumo-de tx ente-id :transparencia.voto_parlamentar :projetado_em)})
