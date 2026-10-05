(ns oplenario.transparencia.db.movimentacao
  "Persistencia de 'transparencia.materia_movimentacao' (mig 20261005000202) — a linha do tempo PUBLICA da materia:
  cada movimentacao com a DATA e o NOME da etapa no rito da Casa. Read-model PROJETADO de `proposicao.protocolada`
  (a abertura) e `proposicao.transicionou` (cada movimentacao), dentro da tx do relay (FORCE RLS isola; ente_id em
  TODA query). Append-only: so' INSERT (ON CONFLICT DO NOTHING) e SELECT.

  So' o que e' publico entra aqui — QUANDO e EM QUE ETAPA. Quem despachou, o gatilho, o contexto e qualquer parecer
  ficam no legislativo e NUNCA viajam neste evento para ca'. A chave da etapa (`etapa_chave`, texto livre por Casa) e'
  guardada so' para a idempotencia e nunca sai nas leituras abaixo."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def teto-listagem
  "Teto server-side da lista (anti unbounded-read; mesmo racional de `db.materia/teto-listagem`). Uma materia tem
  dezenas de movimentacoes no pior caso real; passou disso a rota devolve as MAIS RECENTES e o total, e a tela diz que
  cortou."
  100)

(defn registrar!
  "Projeta UMA movimentacao. So' grava se a materia ja' esta' no read-model do portal (`transparencia.materia`): materia
  que o portal nao mostra nao ganha historico. Idempotente pela chave natural (ente, materia, instante, etapa): o mesmo
  fato projetado de novo — redrive com idempotency-key nova, ou transicao que o backfill da migration ja' cobriu — nao
  duplica. Devolve `{:proposicao-id ...}` se gravou; nil se a materia nao esta' no portal OU a linha ja' existia
  (quem chama distingue com `materia-no-portal?` se precisar). TOLERANTE: nunca lanca por dado ausente — o relay e'
  COMPARTILHADO; a guarda de forma do payload mora na fronteira de despacho (components/repositorio)."
  [tx {:keys [ente-id proposicao-id ocorrido-em etapa-chave etapa inicial?]}]
  (when (jdbc/execute-one! tx
          (sql/format {:select [[1 :um]] :from [:transparencia.materia]
                       :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))
    (let [r (jdbc/execute-one! tx
              (sql/format {:insert-into :transparencia.materia_movimentacao
                           :values [{:ente_id ente-id :proposicao_id proposicao-id :ocorrido_em ocorrido-em
                                     :etapa_chave etapa-chave :etapa etapa :inicial (boolean inicial?)}]
                           :on-conflict [:ente_id :proposicao_id :ocorrido_em :etapa_chave]
                           :do-nothing []}))]
      (when-not (zero? (:next.jdbc/update-count r 0))
        {:proposicao-id proposicao-id}))))

(defn materia-no-portal?
  "A materia esta' no read-model publico? (a mesma regra da ficha: existir em `transparencia.materia`)."
  [tx ente-id proposicao-id]
  (boolean
   (jdbc/execute-one! tx
     (sql/format {:select [[1 :um]] :from [:transparencia.materia]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))))

(defn listar
  "As movimentacoes da materia, da MAIS RECENTE para a mais antiga, no teto (`teto-listagem`). Empate de instante: a
  abertura (protocolo) vem por ultimo, e a chave da etapa desempata para a ordem nao depender do plano de execucao.
  So' as colunas publicas: instante, rotulo da etapa e se e' a abertura — mais a chave da etapa, que o adapter de
  saida usa para dizer se a linha e' votacao (`logic/desfecho/votacao?`) e NUNCA repassa."
  [tx ente-id proposicao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:ocorrido_em :etapa :inicial :etapa_chave] :from [:transparencia.materia_movimentacao]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]
                  :order-by [[:ocorrido_em :desc] [:inicial :asc] [:etapa_chave :asc]]
                  :limit teto-listagem}))))

(defn resumo
  "O que a lista sozinha nao diz, SEM teto, no MESMO predicado: quantas movimentacoes existem (`:total`), se a
  abertura (protocolo) esta' entre elas (`:completo?` — sem ela o historico comeca no meio) e o instante da mais
  antiga (`:desde`)."
  [tx ente-id proposicao-id]
  (let [r (comum/linha->kebab
           (jdbc/execute-one! tx
             (sql/format {:select [[[:count :*] :total]
                                   [[:coalesce [:bool_or :inicial] false] :completo]
                                   [[:min :ocorrido_em] :desde]]
                          :from [:transparencia.materia_movimentacao]
                          :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]})))]
    {:total (:total r) :completo? (boolean (:completo r)) :desde (:desde r)}))
