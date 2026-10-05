(ns oplenario.legislativo.db.votacao-publica
  "As votacoes ENCERRADAS que o PORTAL do cidadao publica (frente 'portal-votacoes-publicas'). So' leitura, e so'
  do que ja' e' fato consumado: `estado = 'encerrada'` (a aberta e a anulada nunca saem) e com sessao (a votacao
  administrativa, fora de plenario, nao tem sessao para ser publica). QUAIS sessoes sao publicas NAO e' decidido
  aqui — `sessoes` e' o dono dessa regra, e o host entrega a lista de ids (§22.10: nenhum modulo le o schema do
  outro). Funcoes sobre a `tx` do tenant (RLS isola); ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.votacao :as votacao]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :objeto_tipo :objeto_id :modalidade :quorum_tipo :resultado
   :total_sim :total_nao :total_abstencao :base_membros :sessao_id [:atualizado_em :encerrada_em]])

(defn- onde-encerradas-das-sessoes [ente-id sessao-ids]
  [:and [:= :ente_id ente-id] [:= :estado [:inline "encerrada"]] [:in :sessao_id (vec sessao-ids)]])

(defn encerradas-das-sessoes
  "{:votacoes [...] :total n} das votacoes encerradas cujas sessoes estao em `sessao-ids`, a mais recente primeiro
  (instante do encerramento, desempate pelo id para a ordem ser estavel entre paginas). `limite`/`deslocamento`
  paginam; `:total` conta o MESMO predicado sem pagina. Sem sessoes -> vazio, sem consulta."
  [tx ente-id sessao-ids limite deslocamento]
  (if (empty? sessao-ids)
    {:votacoes [] :total 0}
    (let [onde (onde-encerradas-das-sessoes ente-id sessao-ids)]
      {:votacoes (comum/linhas->kebab
                  (jdbc/execute! tx (sql/format {:select colunas :from [:legislativo.votacoes] :where onde
                                                 :order-by [[:atualizado_em :desc] [:id :asc]]
                                                 :limit limite :offset deslocamento})))
       :total (:total (comum/linha->kebab
                       (jdbc/execute-one! tx (sql/format {:select [[[:count :*] :total]]
                                                          :from [:legislativo.votacoes] :where onde}))))})))

(defn ids-das-sessoes
  "#{votacao-id} das votacoes (qualquer estado) cujas sessoes estao em `sessao-ids`. Sem sessoes -> `#{}`, sem consulta.
  Nao filtra por estado de proposito: quem usa e' a leitura publica de VOTO POR VEREADOR, e o voto ja' esta' projetado
  desde que foi registrado; o que decide se ele sai e' a sessao ser publica, nao o estado da votacao."
  [tx ente-id sessao-ids]
  (if (empty? sessao-ids)
    #{}
    (into #{}
          (map :id)
          (jdbc/execute! tx (sql/format {:select [:id] :from [:legislativo.votacoes]
                                         :where [:and [:= :ente_id ente-id] [:in :sessao_id (vec sessao-ids)]]})
                         {:builder-fn rs/as-unqualified-maps}))))

(defn encerrada
  "Uma votacao ENCERRADA e com sessao, com os votos NOMINAIS quando a modalidade e' nominal — ou nil. Votos so'
  de votacao nominal: o voto secreto mora noutra tabela, sem vereador (sigilo no schema), e a simbolica nao
  registra voto individual; para essas `:votos` vem vazio, nunca consultado."
  [tx ente-id id]
  (when-let [v (comum/linha->kebab
                (jdbc/execute-one! tx (sql/format {:select colunas :from [:legislativo.votacoes]
                                                   :where [:and [:= :ente_id ente-id] [:= :id id]
                                                           [:= :estado [:inline "encerrada"]]
                                                           [:is-not :sessao_id nil]]})))]
    (assoc v :votos (if (= "nominal" (:modalidade v))
                      (mapv #(select-keys % [:vereador-id :voto]) (votacao/votos-da-votacao tx ente-id id))
                      []))))
