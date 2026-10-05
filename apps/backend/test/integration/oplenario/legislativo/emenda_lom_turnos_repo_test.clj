(ns oplenario.legislativo.emenda-lom-turnos-repo-test
  "INTEGRACAO (PG real): os dois turnos da emenda a Lei Organica (CF art. 29) pelo Repo e pelo db/, sem a borda HTTP.
  Prova o que o teste HTTP nao alcanca: quem chama o Repo sem relogio da borda (a semente da demo) fica com o `now()` da
  tx — e o 2o turno logo depois do 1o e' recusado; a rejeicao no 1o turno fecha a materia; o texto aprovado e' o do 2o
  turno; e o fato de rito `aprovada_em_votacao` (relacoes) responde o mesmo que o autografo."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.legislativo.relacoes :as relacoes]
            [oplenario.migracao :as migracao])
  (:import (java.time Duration Instant)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- versao! [tx ente pid rotulo]
  (let [{vid :id} (texto/nova-versao! tx {:id (random-uuid) :ente-id ente :proposicao-id pid
                                          :origem-versao "edicao" :texto-inline rotulo :created-by nil})
        {:keys [lock-version]} (texto/buscar tx ente vid)]
    (texto/promover! tx {:ente-id ente :proposicao-id pid :versao-id vid :updated-by nil :lock-version lock-version})
    vid))

(defn- protocolar! [ente tipo]
  (repo/transacao *repo* ente
    (fn [tx]
      (let [pid (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026
                                           :uf "CE" :municipio-nome "Fortaleza" :ementa "Altera a Lei Organica"}))]
        (versao! tx ente pid "TEXTO DO 1o TURNO")
        pid))))

(defn- abrir-pelo-repo! [ente pid & [extra]]
  (let [vid (random-uuid)]
    (repo/abrir-votacao! *repo* ente (merge {:id vid :objeto-tipo "proposicao" :objeto-id pid :modalidade "nominal"
                                             :quorum-tipo "maioria_qualificada_2_3" :created-by nil}
                                            extra))
    vid))

(defn- votar-e-encerrar! [ente vid votos]
  (doseq [v votos]
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto v :created-by nil}))
  (repo/encerrar-votacao! *repo* ente {:id vid :base-membros 3 :updated-by nil :lock-version 0}))

(defn- recusa [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- aprovada-pelo-fato? [ente pid]
  (repo/transacao *repo* ente (fn [tx] (relacoes/aprovada-em-votacao? tx pid))))

(deftest sem-relogio-da-borda-o-2o-turno-logo-depois-e-recusado
  (let [ente (random-uuid) pid (protocolar! ente "proposta_emenda_lom")]
    (votar-e-encerrar! ente (abrir-pelo-repo! ente pid) ["sim" "sim" "sim"])
    (is (false? (repo/proposicao-aprovada-em-votacao? *repo* ente pid)) "um turno so' nao aprova")
    (is (false? (aprovada-pelo-fato? ente pid)) "o fato de rito responde o mesmo que o autografo")
    (let [d (recusa #(abrir-pelo-repo! ente pid))]
      (is (= :conflito/regra-de-votacao (:tipo d)))
      (is (= :intersticio (:recusa d)))
      (is (some? (:a-partir-de d))))))

(deftest o-texto-aprovado-e-o-do-2o-turno
  (let [ente (random-uuid) pid (protocolar! ente "proposta_emenda_lom")]
    (votar-e-encerrar! ente (abrir-pelo-repo! ente pid) ["sim" "sim" "sim"])
    (let [v2 (repo/transacao *repo* ente (fn [tx] (versao! tx ente pid "TEXTO DO 2o TURNO")))
          t2 (abrir-pelo-repo! ente pid {:aberta-em (.plus (Instant/now) (Duration/ofDays 11))})]
      (votar-e-encerrar! ente t2 ["sim" "sim" "sim"])
      (is (true? (repo/proposicao-aprovada-em-votacao? *repo* ente pid)))
      (is (true? (aprovada-pelo-fato? ente pid)))
      (is (= {:votacao-id t2 :texto-versao-id v2} (repo/aprovacao-vigente *repo* ente pid))
          "o autografo leva o texto votado no 2o turno"))))

(deftest rejeitada-no-1o-turno-fecha-a-materia
  (let [ente (random-uuid) pid (protocolar! ente "proposta_emenda_lom")]
    (votar-e-encerrar! ente (abrir-pelo-repo! ente pid) ["sim" "nao" "nao"])
    (is (false? (repo/proposicao-aprovada-em-votacao? *repo* ente pid)))
    (let [d (recusa #(abrir-pelo-repo! ente pid {:aberta-em (.plus (Instant/now) (Duration/ofDays 30))}))]
      (is (= :rejeitada (:recusa d))))))

(deftest intersticio-pelo-db-o-2o-turno-aberto-cedo-nao-conta
  ;; defesa em profundidade: se uma 2a aprovacao existir aberta antes do intersticio (dado anterior a' regra, ou
  ;; escrita fora do Repo), ela nao aprova — `aprovacao-vigente` refaz a conta pelo instante gravado.
  (let [ente (random-uuid) pid (protocolar! ente "proposta_emenda_lom")]
    (repo/transacao *repo* ente
      (fn [tx]
        (dotimes [_ 2]
          (let [{vid :id} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id pid
                                              :modalidade "nominal" :quorum-tipo "maioria_qualificada_2_3"})]
            (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid :vereador-id (random-uuid)
                                         :voto "sim"})
            (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0})))
        (testing "duas aprovacoes no mesmo dia nao sao os dois turnos"
          (is (false? (votacao/aprovada-em-votacao? tx ente pid))))))))

(deftest projeto-de-lei-sem-regra-segue-como-sempre
  (let [ente (random-uuid) pid (protocolar! ente "projeto_lei")]
    (votar-e-encerrar! ente (abrir-pelo-repo! ente pid {:quorum-tipo "maioria_simples"}) ["sim" "sim" "nao"])
    (is (true? (repo/proposicao-aprovada-em-votacao? *repo* ente pid)))
    (is (true? (aprovada-pelo-fato? ente pid)))
    (testing "e abre outra votacao sem recusa de turno (o PL nao tem regra de turnos)"
      (is (uuid? (abrir-pelo-repo! ente pid {:quorum-tipo "maioria_simples"}))))))
