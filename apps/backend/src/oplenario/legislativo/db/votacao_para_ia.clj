(ns oplenario.legislativo.db.votacao-para-ia
  "As votacoes ENCERRADAS de uma sessao, para o contexto da IA (ata com o resultado das votacoes). So' leitura, so'
  fato consumado (`estado = 'encerrada'`: a aberta e a anulada nunca saem) e SEM nenhuma tabela de voto: nem
  `legislativo.votos` nem `votos_secretos` sao lidas aqui — o voto por vereador nao tem caminho ate a IA. O rotulo da
  materia vem de um LEFT JOIN em `legislativo.proposicoes` (mesmo modulo) so' quando o objeto E' a proposicao
  (`objetos-que-carregam-a-materia`): emenda/parecer/requerimento apontam outra tabela e um uuid igual por colisao
  nao pode puxar a votacao para uma materia. Funcoes sobre a `tx` do tenant (RLS isola); ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.votacao :as votacao]))

(set! *warn-on-reflection* true)

(def teto-de-votacoes
  "Votacoes de UMA sessao que o contexto aceita. Uma sessao real tem dezenas; passar disto e' dado errado, nao ata —
  e truncar em silencio daria uma ata sem votacoes que parece completa. Falha ALTO (o trabalho da IA recusa e avisa)."
  500)

(defn encerradas-da-sessao
  "As votacoes encerradas da `sessao-id`, na ordem em que encerraram (desempate pelo id: ordem estavel). Cada linha:
  {:id :objeto-tipo :modalidade :quorum-tipo :resultado :total-sim :total-nao :total-abstencao :base-membros
  :encerrada-em :materia-tipo :materia-ano :materia-sequencial}. Lanca `:integracao-ia/votacoes-demais` acima do teto."
  [tx ente-id sessao-id]
  (let [linhas (comum/linhas->kebab
                (jdbc/execute! tx
                  (sql/format
                   {:select [:v.id :v.objeto_tipo :v.modalidade :v.quorum_tipo :v.resultado :v.total_sim :v.total_nao
                             :v.total_abstencao :v.base_membros [:v.atualizado_em :encerrada_em]
                             [:p.tipo :materia_tipo] [:p.ano :materia_ano] [:p.sequencial :materia_sequencial]]
                    :from [[:legislativo.votacoes :v]]
                    :left-join [[:legislativo.proposicoes :p]
                                [:and [:= :p.ente_id :v.ente_id] [:= :p.id :v.objeto_id]
                                 [:in :v.objeto_tipo votacao/objetos-que-carregam-a-materia-sql]]]
                    :where [:and [:= :v.ente_id ente-id] [:= :v.sessao_id sessao-id]
                            [:= :v.estado [:inline "encerrada"]]]
                    :order-by [[:v.atualizado_em :asc] [:v.id :asc]]
                    :limit (inc teto-de-votacoes)})))]
    (when (> (count linhas) teto-de-votacoes)
      (throw (ex-info "sessao com votacoes demais para o contexto da IA"
                      {:tipo :integracao-ia/votacoes-demais :sessao-id sessao-id :teto teto-de-votacoes})))
    linhas))
