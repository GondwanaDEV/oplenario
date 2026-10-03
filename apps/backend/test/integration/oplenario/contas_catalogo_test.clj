(ns oplenario.contas-catalogo-test
  "INTEGRACAO (PG real): ADR-0021 Parte B no catalogo de acoes — o agente PROPOE registrar a prestacao e a notificacao
  (a secretaria confirma em /propostas e a MESMA entrada roda como ela) e le as contas da Casa."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.components.repositorio-contas :as repo-contas]
            [oplenario.migracao :as migracao]
            [oplenario.propostas :as propostas])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(def ^:private relogio (tempo/relogio-fixo (Instant/parse "2026-10-03T15:00:00Z")))

(defn- cenario []
  (let [ente (random-uuid) pessoa (random-uuid) fin (random-uuid)
        rl (repo-leg/->RepoLegislativoPg *c* (outbox/bus))
        ri (repo-ia/map->RepoIntegracaoIAPg {:datasource *c*})]
    {:ente ente :pessoa pessoa :fin fin :repo rl :repo-ia ri
     :deps {:repo-legislativo rl
            :resolver-comissoes (fn [_ ids] (into {} (keep #(when (= % fin) [% "Comissão de Finanças"])) ids))
            :comissoes-vigentes (fn [_] [{:id fin :nome "Comissão de Finanças"}])
            :resolver-municipio (constantly {:uf "CE" :municipio-nome "Baturité"})
            :membros-da-casa (constantly 13)
            :relogio relogio
            :registrar-chamada (catalogo/registrador ri)
            :propor (propostas/propositor ri relogio)
            :marcar-terceiro (propostas/marcador-de-terceiro ri)}}))

(defn- secretaria [{:keys [ente pessoa]}]
  {:identidade-id pessoa :ente-id ente :papeis #{"secretario"}
   :via {:agente "assistente-da-casa" :execucao-id (random-uuid) :publico :secretaria :classes #{:leitura :ato}
         :institucional? false}})

(defn- tela [{:keys [ente pessoa]}] {:identidade-id pessoa :ente-id ente :papeis #{"secretario"}})
(defn- deps-tela [{:keys [deps repo-ia]}] {:repo-integracao-ia repo-ia :relogio relogio :deps-catalogo deps})

(deftest o-agente-propoe-o-registro-e-a-notificacao-a-secretaria-confirma
  (let [{:keys [ente fin deps repo] :as c} (cenario)
        r (catalogo/executar! deps (secretaria c) "registrar_prestacao_de_contas"
                              {"tipo" "governo_prefeito" "exercicio" 2024 "responsavel" "Francisco Almeida Rocha"
                               "recebida-em" "2026-08-01" "parecer-previo" "favoravel" "comissao-autora-id" (str fin)})]
    (testing "propor nao registra nada; a proposta diz em palavras o que vai acontecer"
      (is (= "aguardando_confirmacao" (:estado r)))
      (is (empty? (repo-contas/prestacoes repo ente)))
      (let [p (repo-ia/proposta (:repo-ia c) ente (parse-uuid (:proposta-id r)))]
        (is (re-find #"Registrar as contas do Prefeito de 2024" (:titulo p)))
        (is (re-find #"Comissão de Finanças" (:texto p)))))
    (testing "comissao que nao e' da Casa: invalido ja' na proposta"
      (is (= :validacao/invalido
             (try (catalogo/executar! deps (secretaria c) "registrar_prestacao_de_contas"
                                      {"tipo" "governo_prefeito" "exercicio" 2023 "responsavel" "X"
                                       "recebida-em" "2026-08-01" "parecer-previo" "favoravel"
                                       "comissao-autora-id" (str (random-uuid))})
                  nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))))
    (let [p (propostas/confirmar! (deps-tela c) (tela c) (parse-uuid (:proposta-id r)))
          pid (get-in p [:resultado :id])]
      (testing "confirmada: a prestacao existe, com o PDL, como a tela faria"
        (is (= "confirmada" (:estado p)))
        (is (= "aguardando_notificacao" (get-in p [:resultado :estado])))
        (is (re-matches #"PDL \d{3}/2026" (get-in p [:resultado :proposicao :rotulo]))))
      (testing "a notificacao tambem e' proposta e confirmada"
        (let [r2 (catalogo/executar! deps (secretaria c) "registrar_notificacao_das_contas"
                                     {"prestacao-id" pid "notificado-em" "2026-10-01" "meio" "Ofício"})
              p2 (propostas/confirmar! (deps-tela c) (tela c) (parse-uuid (:proposta-id r2)))]
          (is (= "prazo_de_defesa" (get-in p2 [:resultado :estado])))
          (is (= "2026-10-16" (get-in p2 [:resultado :prazo-defesa-ate])))))
      (testing "o agente le as contas da Casa e a ficha (quorum sobre 13 membros: 9)"
        (is (= [pid] (map :id (:prestacoes (catalogo/executar! deps (secretaria c) "contas_da_casa" {})))))
        (is (= {:base-membros 13 :necessarios-para-rejeitar 9}
               (:quorum (catalogo/executar! deps (secretaria c) "prestacao_de_contas" {"prestacao-id" pid}))))))))
