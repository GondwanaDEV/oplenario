(ns oplenario.legislativo.voto-vereador-integridade-test
  "INTEGRACAO (PG real) — `legislativo.votos.vereador_id` e' GUARD REF (uuid NOT NULL, SEM FK): o vereador mora em
  `cadastros` e FK cross-schema e' proibida (ADR-0001 §6). A integridade e' repartida em duas camadas e este teste
  prova AS DUAS contra o Postgres real:

  1. APLICACAO — `controllers/registrar-voto` (rota da Mesa, `vereador-id` vem do CORPO) com o predicado REAL
     `rotas/vereador-no-roster` (nao um fake, como em `votacao_http_in_test`): vereador inexistente, de OUTRA Casa,
     com mandato cassado ou licenciado -> `:validacao/invalido` (-> 400) e NENHUMA linha em `legislativo.votos`;
     vereador vigente da Casa -> grava. (`meu-voto` — o vereador pelo celular — ja' e' provado contra o banco real em
     `marco_m3_test`: o vereador-id e' resolvido do ator, nunca do corpo.)
  2. BANCO — o CHECK `votos_vereador_id_nao_nulo_chk` (mig 20261004000190): o UUID nulo nunca e' vereador. O teste
     reprova sem a migration (o INSERT direto passaria)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.config :as config]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sess]
            [oplenario.sessoes.logic :as sessoes-logic]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)
           (org.postgresql.util PSQLException)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (referencia/inserir-municipio! (:ds (:datasource s))
        {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
      (referencia/inserir-tribunal! (:ds (:datasource s))
        {:codigo "TCE-CE" :nome "Tribunal de Contas do Estado do Ceara" :uf "CE" :tipo "estadual"})
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(def ^:private HOJE (LocalDate/of 2026 7 11))

(defn- seed-casa!
  "Uma Casa com um vereador de mandato vigente. Devolve {:ente :vereador-id :mandato-id}."
  [repo-cadastros]
  (let [ente (random-uuid) ver (random-uuid) leg (random-uuid) mandato (random-uuid)]
    (repo-cad/criar-ente! repo-cadastros ente {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza"})
    (repo-cad/criar-legislatura! repo-cadastros ente {:id leg :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    (repo-cad/criar-vereador! repo-cadastros ente {:id ver :ente-id ente :identidade-id (random-uuid)
                                                   :nome "Maria Souza" :nome-parlamentar "Maria do Povo"})
    (repo-cad/criar-mandato! repo-cadastros ente {:id mandato :ente-id ente :vereador-id ver :legislatura-id leg
                                                  :partido "PT" :estado "vigente" :natureza "titular"
                                                  :vigencia-inicio (LocalDate/of 2025 1 1)})
    {:ente ente :vereador-id ver :mandato-id mandato}))

(defn- votacao-nominal!
  "Uma votacao nominal aberta na Casa `ente`. Devolve {:sid :vid}."
  [{:keys [repo-legislativo repo-sessoes]} ente]
  (let [pid (:id (repo-leg/protocolar! repo-legislativo ente
                   {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                    :ementa "Materia de teste de integridade do voto"}))
        sid (:id (repo-sess/agendar-sessao! repo-sessoes ente
                   {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"}))
        vid (:id (repo-leg/abrir-votacao! repo-legislativo ente
                   {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :modalidade "nominal"
                    :quorum-tipo "maioria_simples" :sessao-id sid}))]
    {:sid sid :vid vid}))

(defn- mesa-vota!
  "A rota da Mesa (`controllers/registrar-voto`) com o predicado REAL do host. Devolve :ok ou o `:tipo` da ex-info."
  [{:keys [repo-legislativo repo-sessoes repo-cadastros]} ente sid vid vereador-id]
  (try
    (controllers/registrar-voto repo-legislativo
      (fn [e s] (repo-sess/buscar-sessao repo-sessoes e s))
      (fn [sessao] (contains? sessoes-logic/estados-sessao-fechada (:estado sessao)))
      (fn [e v] (rotas/vereador-no-roster repo-cadastros e v HOJE))
      {:ente-id ente :identidade-id (random-uuid)} sid vid
      {:id (random-uuid) :votacao-id vid :voto "sim" :vereador-id vereador-id})
    :ok
    (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(defn- votos [{:keys [repo-legislativo]} ente vid]
  (repo-leg/votos-da-votacao repo-legislativo ente vid))

;; ---------- 1: aplicacao — a rota da Mesa contra o roster REAL ----------

(deftest mesa-grava-voto-de-vereador-vigente-da-casa
  (let [sys *sys* {:keys [ente vereador-id]} (seed-casa! (:repo-cadastros sys))
        {:keys [sid vid]} (votacao-nominal! sys ente)]
    (is (= :ok (mesa-vota! sys ente sid vid vereador-id)))
    (is (= [vereador-id] (mapv :vereador-id (votos sys ente vid))) "voto gravado para o vereador da Casa")))

(deftest mesa-recusa-vereador-inexistente
  (let [sys *sys* {:keys [ente]} (seed-casa! (:repo-cadastros sys))
        {:keys [sid vid]} (votacao-nominal! sys ente)]
    (is (= :validacao/invalido (mesa-vota! sys ente sid vid (random-uuid))))
    (is (empty? (votos sys ente vid)) "nenhum voto persistido")))

(deftest mesa-recusa-vereador-de-outra-casa
  ;; vereador VIGENTE e real — mas da Casa B votando na sessao da Casa A: o id existe, so' nao e' desta Casa.
  (let [sys *sys*
        a (seed-casa! (:repo-cadastros sys))
        b (seed-casa! (:repo-cadastros sys))
        {:keys [sid vid]} (votacao-nominal! sys (:ente a))]
    (is (= :validacao/invalido (mesa-vota! sys (:ente a) sid vid (:vereador-id b))))
    (is (empty? (votos sys (:ente a) vid)) "voto de vereador de outra Casa nao e' gravado")))

(deftest mesa-recusa-vereador-com-mandato-cassado
  (let [sys *sys* {:keys [ente vereador-id mandato-id]} (seed-casa! (:repo-cadastros sys))
        {:keys [sid vid]} (votacao-nominal! sys ente)]
    (repo-cad/mudar-estado-mandato! (:repo-cadastros sys) ente
      {:id mandato-id :estado "cassado" :fim-efetivo (LocalDate/of 2026 6 1)})
    (is (= :validacao/invalido (mesa-vota! sys ente sid vid vereador-id)))
    (is (empty? (votos sys ente vid)))))

(deftest mesa-recusa-vereador-licenciado
  (let [sys *sys* {:keys [ente vereador-id mandato-id]} (seed-casa! (:repo-cadastros sys))
        {:keys [sid vid]} (votacao-nominal! sys ente)]
    (repo-cad/mudar-estado-mandato! (:repo-cadastros sys) ente {:id mandato-id :estado "licenciado"})
    (is (= :validacao/invalido (mesa-vota! sys ente sid vid vereador-id))
        "licenciado compoe o roster marcado, mas nao vota: fora do denominador do quorum")
    (is (empty? (votos sys ente vid)))))

;; ---------- 2: banco — o que o schema garante sozinho ----------

(deftest banco-recusa-vereador-id-nulo
  (let [sys *sys* {:keys [ente]} (seed-casa! (:repo-cadastros sys))
        {:keys [vid]} (votacao-nominal! sys ente)
        nulo #uuid "00000000-0000-0000-0000-000000000000"
        erro (try
               (repo-leg/registrar-voto! (:repo-legislativo sys) ente
                 {:id (random-uuid) :votacao-id vid :vereador-id nulo :voto "sim"})
               nil
               (catch PSQLException e e))]
    (is (some? erro) "o CHECK do banco recusa o UUID nulo como vereador")
    (is (= "23514" (some-> erro .getSQLState)) "check_violation, nao outro erro")
    (is (re-find #"votos_vereador_id_nao_nulo_chk" (str (some-> erro .getMessage))))
    (is (empty? (votos sys ente vid)))))

(deftest banco-nao-tem-fk-cross-schema-em-votos
  ;; documenta a decisao (ADR-0001 §6): nenhuma FK de `legislativo.votos` aponta para outro schema.
  (let [fks (jdbc/execute! (:ds (:datasource *sys*))
              ["SELECT confrelid::regclass::text AS alvo FROM pg_constraint
                 WHERE conrelid = 'legislativo.votos'::regclass AND contype = 'f'"])]
    (is (seq fks) "ha' a FK (ente_id, votacao_id) -> legislativo.votacoes")
    (is (every? #(.startsWith ^String (:alvo %) "legislativo.") fks))))
