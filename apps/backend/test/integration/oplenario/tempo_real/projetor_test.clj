(ns oplenario.tempo-real.projetor-test
  "§22.6 eixo G (G2) — o PROJETOR de tempo real. PURO: roteamento evento->canais, projecao wire/out, e a
  CanalStore (seq monotonica por canal + replay via Last-Event-ID). INTEGRACAO: a cadeia ponta a ponta — o
  Repo de sessoes EMITE o evento no shared.outbox (G1), o relay DRENA e despacha ao consumidor do projetor, que
  PUBLICA na CanalStore (G2). 'SSE e' projecao do bus interno'."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.tempo-real.canais :as canais]
            [oplenario.tempo-real.components :as trc]
            [oplenario.tempo-real.consumer :as consumer]
            [oplenario.tempo-real.projecao :as projecao]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; ---------- PURO: roteamento ----------

(deftest rotas-por-tipo
  (is (= ["sessao/S/plenario"]
         (canais/rotas-do-evento {:tipo "fala.iniciada" :payload {:sessao-id "S"}}))
      "evento de sessao roteia p/ o canal plenario da sessao")
  (is (= []
         (canais/rotas-do-evento {:tipo "gravacao.segmento-captado" :payload {:sessao-id "S"}}))
      "fronteira core->IA NAO e' SSE (nao roteia)"))

;; ---------- PURO: projecao wire/out ----------

(deftest projetar-monta-mensagem
  (let [msgs (projecao/projetar {:tipo "fala.iniciada" :ente-id "E" :payload {:sessao-id "S" :orador-id "O"}})]
    (is (= [{:canal "sessao/S/plenario" :ente-id "E" :tipo "fala.iniciada"
             :dados {:sessao-id "S" :orador-id "O"}}] msgs)
        "uma mensagem por canal, carregando ente-id (defesa-em-profundidade) + tipo + dados")))

;; ---------- PURO: CanalStore (seq monotonica + replay) ----------

(deftest canal-store-seq-e-replay
  (let [s (trc/canal-store-memoria)]
    (is (= {:canal "c" :seq 1} (trc/publicar! s "c" {:tipo "a" :dados {}})) "1a mensagem do canal = seq 1")
    (is (= {:canal "c" :seq 2} (trc/publicar! s "c" {:tipo "b" :dados {}})) "seq monotonica por canal")
    (is (= {:canal "d" :seq 1} (trc/publicar! s "d" {:tipo "x" :dados {}})) "seq e' POR canal (d comeca em 1)")
    (is (= ["b"] (mapv :tipo (trc/ler-desde s "c" 1))) "replay desde Last-Event-ID seq=1 -> so a posterior")
    (is (= ["a" "b"] (mapv :tipo (trc/ler-desde s "c" 0))) "ler-desde 0 = tudo, em ordem de seq")))

;; ---------- INTEGRACAO: a cadeia ponta a ponta (Repo -> outbox -> relay -> projetor -> canal) ----------

(deftest evento-de-dominio-flui-ate-o-canal
  (let [ente     (random-uuid)
        store    (trc/canal-store-memoria)
        registro (consumer/registro store)
        sid      (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                         :tipo-sessao "ordinaria" :modalidade "presencial"}))]
    (repo/transicionar-sessao! *repo* ente {:id sid :para "aberta" :updated-by (random-uuid) :lock-version 0})
    (outbox/drenar! *ds* registro)                          ; o relay drena o outbox e despacha ao projetor
    (let [msgs (trc/ler-desde store (canais/canal-plenario sid) 0)]
      (is (= 1 (count msgs)) "o sessao.transicionou chegou ao canal plenario da sessao")
      (is (= "sessao.transicionou" (:tipo (first msgs))) "a mensagem carrega o tipo do evento")
      (is (= 1 (:seq (first msgs))) "seq monotonica atribuida na publicacao")
      (is (= ente (:ente-id (first msgs))) "a mensagem carrega o ente-id do envelope (defesa-em-profundidade)")
      (is (= "aberta" (get-in (first msgs) [:dados :para])) "os dados sao o payload projetado do evento"))))
