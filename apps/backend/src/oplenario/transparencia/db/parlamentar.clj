(ns oplenario.transparencia.db.parlamentar
  "Persistencia das projecoes de ATUACAO PARLAMENTAR do portal (Onda E fatia 2, mig 0064) — voto PUBLICO e
  presenca. Funcoes sobre a `tx` corrente (FORCE RLS isola). ESCRITA chamada pelo consumer dentro da tx do
  relay; LEITURA pelo Repo-Component. Voto SECRETO nunca chega aqui: o payload do evento (uniao discriminada
  por :modalidade) nem carrega identidade no ramo secreto."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private teto-votos
  "Teto server-side da secao 'como votou' (anti unbounded-read; mesmo racional dos tetos de materia/comentario)."
  50)

(defn registrar-voto!
  "Projeta um voto NOMINAL. ON CONFLICT DO NOTHING: idempotente sob redrive (a chave e' de negocio, nao a
  idempotency-key do envelope)."
  [tx {:keys [ente-id votacao-id vereador-id proposicao-id voto ocorrido-em]}]
  {:pre [(some? ente-id) (some? votacao-id) (some? vereador-id) (some? voto) (some? ocorrido-em)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :transparencia.voto_parlamentar
                 :values [{:ente_id ente-id :votacao_id votacao-id :vereador_id vereador-id
                           :proposicao_id proposicao-id :voto voto :ocorrido_em ocorrido-em}]
                 :on-conflict [:ente_id :votacao_id :vereador_id]
                 :do-nothing []})))

(defn registrar-presenca!
  "Projeta o ESTADO ATUAL de presenca por (sessao, vereador). UPSERT: o evento e' log de entrada/saida, a
  vista publica quer o ultimo. `ocorrido_em` do DOMINIO decide — um evento fora de ordem no redrive nao
  sobrescreve um mais recente (mesmo gate de monotonicidade de paineis/db/sli_sessao)."
  [tx {:keys [ente-id sessao-id vereador-id tipo modalidade ocorrido-em]}]
  {:pre [(some? ente-id) (some? sessao-id) (some? vereador-id) (some? tipo) (some? ocorrido-em)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :transparencia.presenca_parlamentar
                 :values [{:ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                           :tipo tipo :modalidade modalidade :ocorrido_em ocorrido-em}]
                 :on-conflict [:ente_id :sessao_id :vereador_id]
                 :do-update-set {:fields {:tipo :excluded.tipo :modalidade :excluded.modalidade
                                          :ocorrido_em :excluded.ocorrido_em}
                                 :where [:< :transparencia.presenca_parlamentar.ocorrido_em :excluded.ocorrido_em]}})))

(defn votos-do-vereador
  "Secao 'como votou': votos PUBLICOS do vereador, mais recentes primeiro, com a ementa da materia (mesmo
  schema — JOIN permitido, nao e' cross-schema)."
  [tx ente-id vereador-id limite]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:v.votacao_id :v.proposicao_id :v.voto :v.ocorrido_em
                           [:m.tipo :materia_tipo] [:m.ano :materia_ano]
                           [:m.sequencial :materia_sequencial] [:m.ementa :materia_ementa]]
                  :from [[:transparencia.voto_parlamentar :v]]
                  :left-join [[:transparencia.materia :m]
                              [:and [:= :m.ente_id :v.ente_id] [:= :m.proposicao_id :v.proposicao_id]]]
                  :where [:and [:= :v.ente_id ente-id] [:= :v.vereador_id vereador-id]]
                  :order-by [[:v.ocorrido_em :desc]]
                  :limit (or limite teto-votos)}))))

(defn resumo-presenca
  "Numero-card de presenca. Denominador = sessoes do ENTE que tiveram chamada (COUNT DISTINCT sessao_id);
  numerador = as em que este vereador consta 'presente'. Devolve os DOIS numeros — a UI mostra a fracao, nunca
  um percentual sem denominador (um 100% de 1 sessao mente por omissao)."
  [tx ente-id vereador-id]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [[[:count [:distinct :sessao_id]] :sessoes_com_chamada]
                           [[:count [:distinct [:case [:and [:= :vereador_id vereador-id]
                                                            [:= :tipo "presente"]]
                                                :sessao_id :else nil]]]
                            :sessoes_presente]]
                  :from [:transparencia.presenca_parlamentar]
                  :where [:= :ente_id ente-id]}))))
