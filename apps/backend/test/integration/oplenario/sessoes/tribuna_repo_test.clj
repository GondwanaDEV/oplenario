(ns oplenario.sessoes.tribuna-repo-test
  "INTEGRACAO (PG real): `repo/tribuna-da-sessao` (`components/repositorio.clj:530-543`) via o Component
  REAL (`RepoSessoesPg`, mesmo padrao de `sessoes_eventos_repo_test.clj`) -- nao o fake que
  `tribuna_http_in_test.clj` usa. Achado I3 da revisao adversarial da Task 1: os 15 testes de
  `tribuna_http_in_test.clj` falsificam o protocolo `RepoSessoes` inteiro, entao a UMA transacao, o
  curto-circuito de sessao inexistente e o escopo por `ente-id`/`fala-id` das quatro leituras nunca sao
  exercitados contra Postgres. Este ns fecha essa rede.

  Achado I1 (mesma revisao) tambem fecha aqui: `marcos-da-fala-em-curso-aparecem-de-fala-anterior-nao`
  (em `tribuna_http_in_test.clj`) nao podia provar o escopo por `fala-id` de
  `repositorio.clj:539` (`(tribuna/listar-eventos-cronometro tx ente-id (:id fala))`) porque o fake nunca
  executa aquela linha -- `tribuna-da-sessao-marcos-nao-vazam-de-fala-anterior` abaixo prova isso com
  DUAS falas de verdade."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo])
  (:import (java.time Instant)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(def ^:private f0 (Instant/parse "2026-06-29T14:00:00Z"))
(defn- mais [^Instant t s] (.plusSeconds t s))

(defn- agendar! [ente]
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"})))

;; ---------- sessao inexistente -> nil, curto-circuito ----------

(deftest tribuna-da-sessao-sessao-inexistente-e-nil
  (let [ente (random-uuid)]
    (is (nil? (repo/tribuna-da-sessao *repo* ente (random-uuid)))
        "sessao inexistente -> nil, sem tocar fala/cronometro/inscricoes")))

;; ---------- a UMA tx compoe corretamente os 4 insumos (sessao + fala-em-curso + marcos DAQUELA fala +
;; fila) via o Repo REAL -- o que os 15 testes com fake nunca executam ----------

(deftest tribuna-da-sessao-compoe-sessao-fala-marcos-e-fila
  (let [ente (random-uuid)
        sid  (agendar! ente)
        {iid :id} (repo/inscrever! *repo* ente {:id (random-uuid) :sessao-id sid :vereador-id (random-uuid)
                                                :origem-inscricao "pre_sessao_secretaria" :fase "expediente"
                                                :created-by (random-uuid)})
        {fid :id} (repo/iniciar-fala! *repo* ente {:id (random-uuid) :sessao-id sid :orador-id (random-uuid)
                                                   :tipo-fala "principal" :fase "ordem_do_dia" :iniciou-em f0
                                                   :inscricao-id iid :created-by (random-uuid)})]
    (repo/registrar-evento-cronometro! *repo* ente {:fala-id fid :tipo "pausada" :ocorrido-em (mais f0 30)
                                                    :created-by (random-uuid)})
    (let [r (repo/tribuna-da-sessao *repo* ente sid)]
      (is (= sid (:id (:sessao r))) "a sessao lida nesta MESMA tx e' a certa")
      (is (= fid (:id (:fala-em-curso r))) "a fala em curso e' a que foi iniciada")
      ;; `iniciar-fala!` loga 'iniciada' na tabela (alem do manual 'pausada' registrado abaixo) --
      ;; ordem cronologica por ocorrido_em: 'iniciada' (em iniciou-em) antes de 'pausada' (30s depois).
      (is (= ["iniciada" "pausada"] (mapv :tipo (:marcos r))) "os marcos sao os da fala em curso")
      (is (= [iid] (mapv :id (:inscricoes r))) "a fila traz a inscricao registrada"))))

;; ---------- escopo por FALA-ID (achado I1): marcos de uma fala ANTERIOR nao vazam p/ a fala em curso ----------

(deftest tribuna-da-sessao-marcos-nao-vazam-de-fala-anterior
  ;; Cenario do achado I1: vereador A fala, recebe um `tempo_adicional_concedido`, encerra. Vereador B
  ;; comeca a falar. Se `repositorio.clj:539` trocasse `(:id fala)` por `sessao-id` (ou qualquer consulta
  ;; que listasse os eventos da SESSAO em vez da FALA), o telao somaria ao cronometro de B os segundos
  ;; concedidos a A.
  (let [ente (random-uuid)
        sid  (agendar! ente)
        {fa :id} (repo/iniciar-fala! *repo* ente {:id (random-uuid) :sessao-id sid :orador-id (random-uuid)
                                                  :tipo-fala "principal" :fase "ordem_do_dia" :iniciou-em f0
                                                  :created-by (random-uuid)})]
    (repo/registrar-evento-cronometro! *repo* ente {:fala-id fa :tipo "tempo_adicional_concedido"
                                                    :segundos-adicionais 120 :ocorrido-em (mais f0 20)
                                                    :created-by (random-uuid)})
    (repo/encerrar-fala! *repo* ente {:id fa :encerrou-em (mais f0 60) :lock-version 0 :updated-by (random-uuid)})
    (let [{fb :id} (repo/iniciar-fala! *repo* ente {:id (random-uuid) :sessao-id sid :orador-id (random-uuid)
                                                    :tipo-fala "principal" :fase "ordem_do_dia"
                                                    :iniciou-em (mais f0 90) :created-by (random-uuid)})]
      (repo/registrar-evento-cronometro! *repo* ente {:fala-id fb :tipo "pausada" :ocorrido-em (mais f0 100)
                                                      :created-by (random-uuid)})
      (let [r (repo/tribuna-da-sessao *repo* ente sid)]
        (is (= fb (:id (:fala-em-curso r))) "a fala em curso agora e' a de B")
        ;; 'iniciada' e' o log automatico de `iniciar-fala!` p/ fb; 'pausada' e' o manual registrado
        ;; acima -- os DOIS sao de B. O que NAO pode aparecer e' 'tempo_adicional_concedido' (de A).
        (is (= ["iniciada" "pausada"] (mapv :tipo (:marcos r)))
            "os marcos sao SO' os de B -- o tempo_adicional_concedido de A nao aparece aqui")))))

;; ---------- escopo por ENTE-ID: uma sessao de outro ente nao vaza ----------

(deftest tribuna-da-sessao-nao-vaza-entre-entes
  (let [ente-a (random-uuid)
        ente-b (random-uuid)
        sid    (agendar! ente-a)]
    (is (nil? (repo/tribuna-da-sessao *repo* ente-b sid))
        "sessao de ente-a, pedida com o ente-id de ente-b, nao e' vista (RLS + WHERE ente_id)")))
