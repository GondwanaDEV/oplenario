(ns oplenario.sessoes.db.chamada
  "Persistencia do ATO DE CHAMADA CONDUZIDA (§22.6 eixo C, Etapa 2d) — funcoes sobre a `tx` do tenant (RLS
  isola). `chamada_conduzida` e' APPEND-ONLY: registra QUANDO a chamada foi conduzida, QUEM a conduziu, e
  quantos MEMBROS A CASA tinha naquele instante (o denominador do quorum, CONGELADO — resolvido pelo CALLER
  via `logic/membros-da-casa-do-roster`, nunca contado aqui). Ver o cabecalho da migration 0072 para o
  porque de uma tabela propria em vez de estender `incidente_processual`. HoneySQL schema-qualified; ente_id
  em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :sessao_id :conduzida_por :membros_da_casa :ocorrido_em :registrado_em])

(defn registrar!
  "Registra o ato de chamada conduzida (append-only). Valida `membros-da-casa` como GUARDA DE PROFUNDIDADE
  (fail-closed antes do INSERT — a borda ja' o computou pelo MESMO roster que a leitura da chamada usa) +
  nil-guard de auditoria (`created-by`, trilha de quem conduziu). Devolve {:id :ocorrido-em :registrado-em}
  por RETURNING — o MESMO par (dominio/audit) de `presenca/registrar-evento!`: enquanto nao existir um tipo
  de evento de RETIFICACAO, e' a unica forma de distinguir 'a chamada aconteceu as 14h' de 'o secretario
  digitou as 14h20 um registro das 14h'."
  [tx {:keys [id ente-id sessao-id conduzida-por membros-da-casa ocorrido-em created-by]}]
  (logic/validar-membros-da-casa membros-da-casa)
  (when (nil? created-by)
    (throw (ex-info "registrar!: created-by e' obrigatorio (trilha de auditoria)" {:id id})))
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :sessoes.chamada_conduzida
                  :values [{:id id :ente_id ente-id :sessao_id sessao-id :conduzida_por conduzida-por
                            :membros_da_casa membros-da-casa :ocorrido_em ocorrido-em
                            :created_by created-by :efetivado_em [:now]}]
                  :returning [:id :ocorrido_em :registrado_em]}))))

(defn listar-da-sessao
  "Os atos de chamada conduzida da sessao, em ordem cronologica. Podem ser MAIS DE UM (decisao Etapa 2d /
  A5): a chamada pode ser reconduzida na mesma sessao (apos suspensao, ou para reverificar quorum a pedido
  da Mesa — mesmo racional de `incidente_processual.tipo = 'verificacao_votacao'`), cada ato com o SEU
  denominador congelado. Uma lista VAZIA e' o sinal de 'ninguem conduziu a chamada ainda' — distinto de uma
  lista com um ato e zero presenca_evento ('a chamada aconteceu, a Casa toda faltou')."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:sessoes.chamada_conduzida]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:ocorrido_em :asc] [:id :asc]]}))))
