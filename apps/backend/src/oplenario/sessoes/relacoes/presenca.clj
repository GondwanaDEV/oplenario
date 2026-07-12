(ns oplenario.sessoes.relacoes.presenca
  "Funcoes de RELACAO que o sessoes e' dono (§22.6 eixo C / §22.5.3 disc.5): a presenca DERIVADA. Aqui vivem
  as consultas-fato sobre presenca_evento — `esta-presente-em?` (a funcao canonica do §22.6) e os agregadores
  de quorum `presentes-plenario` / `presentes-remoto`. A presenca corrente NUNCA e' materializada: e' o ULTIMO
  evento por vereador ate um instante (DISTINCT ON vereador, ordem canonica), com o desempate de mesmo instante
  pela precedencia de fonte (materializada na coluna `fonte_precedencia`).

  Camada de RELACAO (espelha cadastros/relacoes/cadastro): escreve HoneySQL direto, NAO importa o `db/` do
  modulo (ADR-0001 §3-bis). Assinatura `(fn tx arg…)`: a `tx` do tenant entra 1a (FORCE RLS isola a Casa;
  `ente` NAO esta no DSL — a Casa = a tx). O motor de DSL (regras de votacao por materia — quorum) alcanca os
  agregadores POR NOME via o registry/injecao da F2 (`resolver-para`), nunca por import (§22.10). As assinaturas
  tipadas vivem no catalogo (FUNCOES-RELACAO: presentes_plenario/remoto (SessaoId, Instante) -> Inteiro); o
  assert de costura do boot (RegistroFatos) casa estas fns com aquelas assinaturas."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

;; Ordem canonica do "ultimo evento por vereador": mais recente primeiro, desempatando MESMO instante pela
;; precedencia da fonte (manual>painel>inferida, materializada em `fonte_precedencia` — generated stored,
;; indexada) e por fim id. UMA constante p/ todos os caminhos — divergir seria incoerencia de quorum.
(def ^:private ordem-corrente
  [[:ocorrido_em :desc] [:fonte_precedencia :desc] [:id :desc]])

(def ^:private positivos (vec (sort logic/tipos-presenca-positiva)))

(defn esta-presente-em?
  "A funcao canonica (§22.6 eixo C): o vereador esta presente na sessao em `instante`? = o tipo do seu ultimo
  evento (ate `instante`) e' positivo (entrada/retorno/mudanca_modalidade). Sem evento ate la = ausente.
  WHERE fixa o vereador -> basta a linha mais recente na ordem canonica (LIMIT 1). RLS isola a Casa."
  [tx sessao-id vereador-id instante]
  (let [row (-> (jdbc/execute-one! tx
                  (sql/format {:select [:tipo]
                               :from [:sessoes.presenca_evento]
                               :where [:and [:= :sessao_id sessao-id] [:= :vereador_id vereador-id]
                                       [:<= :ocorrido_em instante]]
                               :order-by ordem-corrente
                               :limit 1}))
                comum/linha->kebab)]
    (boolean (and row (logic/presente-por-tipo? (:tipo row))))))

(defn- ultimos-eventos-q
  "Subquery: o ULTIMO evento por vereador ate `instante` (DISTINCT ON vereador), na ordem canonica. Projeta
  vereador/tipo/modalidade — base dos agregadores de quorum. RLS isola a Casa (sem filtro `ente_id`: a
  assinatura de relacao nao carrega `ente`)."
  [sessao-id instante]
  {:select-distinct-on [[:vereador_id] :vereador_id :tipo :modalidade]
   :from [:sessoes.presenca_evento]
   :where [:and [:= :sessao_id sessao-id] [:<= :ocorrido_em instante]]
   :order-by (into [[:vereador_id :asc]] ordem-corrente)})

(defn- contar-presentes [tx sessao-id instante modalidade]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:count :*] :n]]
                     :from [[(ultimos-eventos-q sessao-id instante) :u]]
                     :where [:and [:in :u.tipo positivos] [:= :u.modalidade modalidade]]}))
      comum/linha->kebab :n))

(defn presentes-plenario
  "Quorum presencial: nº de vereadores cujo ultimo evento ate `instante` e' presente em modalidade 'plenario'.
  Agregador exposto a DSL do motor de votacao."
  [tx sessao-id instante]
  (contar-presentes tx sessao-id instante "plenario"))

(defn presentes-remoto
  "Quorum remoto: idem, modalidade 'remoto'."
  [tx sessao-id instante]
  (contar-presentes tx sessao-id instante "remoto"))

;; nome canonico (= assinatura no catalogo) -> fn de relacao. C3: esta_presente_em passou a expor a relacao de
;; LEITURA tambem a DSL (nao so os agregadores) — a policy fina do meu-voto (legislativo) resolve por ela.
(def relacoes
  {"presentes_plenario" presentes-plenario
   "presentes_remoto"   presentes-remoto
   "esta_presente_em"   esta-presente-em?})
