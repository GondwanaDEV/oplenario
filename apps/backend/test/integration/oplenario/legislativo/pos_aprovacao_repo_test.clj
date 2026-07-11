(ns oplenario.legislativo.pos-aprovacao-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis) no POS-APROVACAO (Onda B Slice 7,
  F3.8a) — `buscar-pos-aprovacao` (leitura composta, NUMA UNICA tx, sem short-circuit no nil do autografo)
  e `gerar-autografo-e-abrir-tramitacao!` (acao composta: autografo/gerar! + tramitacao-executiva/iniciar!
  NUMA UNICA tx, atomico). Os db/ individuais ja' tem cobertura de dominio completa em
  pos_aprovacao_db_test.clj — aqui cobrimos so' a COMPOSICAO nova do Repo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- protocolar! [ente]
  (:id (repo/transacao *repo* ente
         (fn [tx] (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                        :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))))

;; ========================= buscar-pos-aprovacao (leitura composta) =========================

(deftest buscar-pos-aprovacao-sem-autografo-ainda
  (let [ente (random-uuid) pid (protocolar! ente)
        r (repo/buscar-pos-aprovacao *repo* ente pid)]
    (is (nil? (:autografo r)) "proposicao existe mas ainda nao gerou autografo")
    (is (nil? (:tramitacao-executiva r)))))

(deftest buscar-pos-aprovacao-proposicao-inexistente-tambem-devolve-mapa-nil
  ;; Repo/buscar-pos-aprovacao NAO checa a proposicao (spec: sem short-circuit no nil do autografo) — a
  ;; decisao 404-vs-corpo-parcial e' do CONTROLLER (buscar-pos-aprovacao pre-checa a proposicao antes).
  (let [r (repo/buscar-pos-aprovacao *repo* (random-uuid) (random-uuid))]
    (is (= {:autografo nil :tramitacao-executiva nil} r))))

(deftest buscar-pos-aprovacao-com-autografo-e-tramitacao
  (let [ente (random-uuid) pid (protocolar! ente)
        gerado (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
                 {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
                  :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})
        r (repo/buscar-pos-aprovacao *repo* ente pid)]
    (is (some? (:autografo r)))
    (is (= (:autografo-id gerado) (:id (:autografo r))))
    (is (= (:numero gerado) (:numero (:autografo r))))
    (is (some? (:tramitacao-executiva r)))
    (is (= (:tramitacao-executiva-id gerado) (:id (:tramitacao-executiva r))))
    (is (= "aguardando" (:estado (:tramitacao-executiva r))))))

;; ========================= gerar-autografo-e-abrir-tramitacao! (acao composta atomica) =========================

(deftest gerar-autografo-e-abrir-tramitacao-numera-e-abre-atomico
  (let [ente (random-uuid) pid (protocolar! ente)
        r (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
            {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
             :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})]
    (is (= 1 (:numero r)) "primeiro autografo do ano 2026 neste ente")
    (is (some? (:autografo-id r)))
    (is (some? (:tramitacao-executiva-id r)))
    (let [aut (repo/buscar-autografo *repo* ente (:autografo-id r))
          tram (repo/buscar-tramitacao-executiva *repo* ente (:tramitacao-executiva-id r))]
      (is (= pid (:proposicao-id aut)))
      (is (= (:autografo-id r) (:autografo-id tram)))
      (is (= "aguardando" (:estado tram))))))

(deftest gerar-autografo-e-abrir-tramitacao-numera-gapless-entre-proposicoes
  (let [ente (random-uuid) pid-a (protocolar! ente) pid-b (protocolar! ente)
        ra (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
             {:id (random-uuid) :proposicao-id pid-a :ano 2026 :texto-versao-id (random-uuid)
              :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})
        rb (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
             {:id (random-uuid) :proposicao-id pid-b :ano 2026 :texto-versao-id (random-uuid)
              :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})]
    (is (= [1 2] [(:numero ra) (:numero rb)]))))

(deftest gerar-autografo-e-abrir-tramitacao-proposicao-duplicada-lanca-sem-numero-orfao
  ;; a UNIQUE (ente_id, proposicao_id) do autografo barra um segundo autografo p/ a mesma proposicao — a
  ;; tentativa falha NAO deixa buraco no numerador gapless (kernel/sequencial e' transacional; mesmo teste
  ;; de regressao de protocolar-documento-lock-version-desatualizado-lanca-e-rollback-nao-deixa-buraco).
  (let [ente (random-uuid) pid (protocolar! ente)]
    (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
      {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
       :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})
    (is (thrown? Exception
          (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
            {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
             :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})))
    (let [outro-pid (protocolar! ente)
          r (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
              {:id (random-uuid) :proposicao-id outro-pid :ano 2026 :texto-versao-id (random-uuid)
               :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})]
      (is (= 2 (:numero r))
          "gapless: a tentativa falha (rollback) NAO consumiu numero — o proximo sucesso e' 2, nao 3"))))
