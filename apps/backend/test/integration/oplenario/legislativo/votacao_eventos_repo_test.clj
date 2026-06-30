(ns oplenario.legislativo.votacao-eventos-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component do LEGISLATIVO (ADR-0001 §3-bis) para a VOTACAO —
  os caminhos de escrita da votacao emitem os EVENTOS DE TEMPO REAL (`votacao.aberta` / `voto.registrado` /
  `votacao.encerrada`) no shared.outbox na MESMA tx do ato (carry F4, marcado em repositorio.clj l.70;
  atomicidade outbox-com-o-ato §22.9 E2: a linha do evento so existe se a tx commitou). Estes eventos sao a
  FONTE do placar ao vivo (projetor SSE, Slice 2). §22.6: a votacao SECRETA nao expoe o voto individual — o
  `voto.registrado` secreto e' um TICK (sem vereador-id, sem voto). Votacao SEM sessao-id (ato administrativo,
  ex.: apreciacao de veto fora de sessao) NAO emite evento de plenario (sem canal p/ rotear)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo, ente_id, payload::text AS payload FROM shared.outbox
      WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- abrir! [ente objeto-id extra]
  (let [vid (random-uuid)]
    (repo/abrir-votacao! *repo* ente
      (merge {:id vid :objeto-tipo "proposicao" :objeto-id objeto-id
              :modalidade "nominal" :quorum-tipo "maioria_simples"} extra))
    vid))

;; ---------- votacao.aberta ----------

(deftest abrir-votacao-em-sessao-emite-aberta
  (let [ente (random-uuid) sessao (random-uuid) item (random-uuid)
        pid  (protocolar! ente)]
    (is (empty? (eventos-por-tipo ente "votacao.aberta")) "nada antes de abrir")
    (let [vid (abrir! ente pid {:sessao-id sessao :pauta-item-id item})
          evs (eventos-por-tipo ente "votacao.aberta")]
      (is (= 1 (count evs)) "exatamente 1 evento votacao.aberta")
      (let [pl (:payload (first evs))]
          (is (re-find (re-pattern (str vid)) pl) "payload carrega a votacao-id")
          (is (re-find (re-pattern (str sessao)) pl) "payload carrega a sessao-id (rota do canal)")
          (is (re-find (re-pattern (str item)) pl) "payload carrega o pauta-item-id")
          (is (re-find #"nominal" pl) "payload carrega a modalidade")
          (is (re-find #"maioria_simples" pl) "payload carrega o quorum-tipo")
          (is (re-find #"proposicao" pl) "payload carrega o objeto-tipo")))))

(deftest abrir-votacao-sem-sessao-nao-emite
  (let [ente (random-uuid)
        pid  (protocolar! ente)]
    (abrir! ente pid {})  ; sem sessao-id = ato fora de plenario
    (is (empty? (eventos-por-tipo ente "votacao.aberta"))
        "votacao sem sessao-id nao emite evento de plenario (sem canal p/ rotear)")))

;; ---------- voto.registrado (nominal expoe; secreto e' tick) ----------

(deftest voto-nominal-emite-com-identidade
  (let [ente (random-uuid) sessao (random-uuid) ver (random-uuid)
        pid  (protocolar! ente)
        vid  (abrir! ente pid {:sessao-id sessao})]
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id ver :voto "sim"})
    (let [evs (eventos-por-tipo ente "voto.registrado")]
      (is (= 1 (count evs)) "1 evento voto.registrado")
      (let [pl (:payload (first evs))]
        (is (re-find (re-pattern (str ver)) pl) "voto nominal carrega o vereador (placar nominal)")
        (is (re-find #"\"voto\":\s*\"sim\"" pl) "voto nominal carrega o valor do voto")
        (is (re-find (re-pattern (str sessao)) pl) "carrega a sessao-id (rota do canal)")))))

(deftest voto-secreto-e-tick-sem-identidade
  (let [ente (random-uuid) sessao (random-uuid)
        pid  (protocolar! ente)
        vid  (abrir! ente pid {:sessao-id sessao :modalidade "secreta"})]
    (repo/registrar-voto-secreto! *repo* ente {:id (random-uuid) :votacao-id vid :voto "sim"})
    (let [evs (eventos-por-tipo ente "voto.registrado")]
      (is (= 1 (count evs)) "voto secreto tambem emite tick (contador ao vivo)")
      ;; parse o JSON e prova a AUSENCIA das chaves (mais forte que regex por valor — pega "nao"/"abstencao"/null).
      (let [parsed (json/read-value (:payload (first evs)) (json/object-mapper {:decode-key-fn keyword}))]
        (is (not (contains? parsed :vereador-id)) "§22.6: chave vereador-id AUSENTE no tick secreto")
        (is (not (contains? parsed :voto)) "§22.6: chave voto AUSENTE no tick secreto")
        (is (= "secreta" (:modalidade parsed)) "carrega a modalidade (a projecao trata como tick)")
        (is (= (str sessao) (:sessao-id parsed)) "carrega a sessao-id (rota do canal)")))))

(deftest voto-sem-sessao-nao-emite
  (let [ente (random-uuid)
        pid  (protocolar! ente)
        vid  (abrir! ente pid {})]  ; votacao sem sessao-id
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "sim"})
    (is (empty? (eventos-por-tipo ente "voto.registrado"))
        "voto numa votacao fora de plenario nao emite evento")))

;; ---------- guard de modalidade (defesa-em-profundidade do SIGILO §22.6) ----------

(deftest voto-nominal-em-votacao-secreta-barra
  ;; o DB nao amarra legislativo.votos a votacoes.modalidade — um handler que chame registrar-voto! (nominal)
  ;; sobre uma votacao SECRETA vazaria a identidade no outbox. O Repo barra fail-loud (e a tx rola atras).
  (let [ente (random-uuid) sessao (random-uuid)
        pid  (protocolar! ente)
        vid  (abrir! ente pid {:sessao-id sessao :modalidade "secreta"})]
    (is (thrown? Exception
                 (repo/registrar-voto! *repo* ente
                   {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "sim"}))
        "registrar-voto! (nominal) sobre votacao secreta e' rejeitado")
    (is (empty? (eventos-por-tipo ente "voto.registrado"))
        "nenhum evento nominal vazou (a tx rolou atras)")))

(deftest voto-secreto-em-votacao-nominal-barra
  (let [ente (random-uuid) sessao (random-uuid)
        pid  (protocolar! ente)
        vid  (abrir! ente pid {:sessao-id sessao :modalidade "nominal"})]
    (is (thrown? Exception
                 (repo/registrar-voto-secreto! *repo* ente {:id (random-uuid) :votacao-id vid :voto "sim"}))
        "registrar-voto-secreto! sobre votacao nominal e' rejeitado")))

;; ---------- votacao.encerrada ----------

(deftest encerrar-votacao-emite-encerrada-com-totais
  (let [ente (random-uuid) sessao (random-uuid)
        pid  (protocolar! ente)
        vid  (abrir! ente pid {:sessao-id sessao})]
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "sim"})
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "sim"})
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "nao"})
    (repo/encerrar-votacao! *repo* ente {:id vid :base-membros 3 :updated-by nil :lock-version 0})
    (let [evs (eventos-por-tipo ente "votacao.encerrada")]
      (is (= 1 (count evs)) "1 evento votacao.encerrada")
      (let [pl (:payload (first evs))]
        (is (re-find (re-pattern (str vid)) pl) "carrega a votacao-id")
        (is (re-find (re-pattern (str sessao)) pl) "carrega a sessao-id (rota do canal)")
        (is (re-find #"aprovada" pl) "carrega o resultado (2 sim > 1 nao)")
        (is (re-find #"\"total-sim\":\s*2" pl) "carrega o total de sim (aggregate e' publico)")
        (is (re-find #"\"total-nao\":\s*1" pl) "carrega o total de nao")))))
