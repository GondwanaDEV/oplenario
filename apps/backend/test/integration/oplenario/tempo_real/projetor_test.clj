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
      "fronteira core->IA NAO e' SSE (nao roteia)")
  (is (= ["sessao/S/plenario"]
         (canais/rotas-do-evento {:tipo "incidente.registrado" :payload {:sessao-id "S"}}))
      "incidente processual (§16.13) roteia p/ o canal plenario (mesa de conducao ao vivo)"))

;; ---------- PURO: projecao wire/out ----------

(deftest projetar-monta-mensagem
  (let [msgs (projecao/projetar {:tipo "fala.iniciada" :ente-id "E" :payload {:sessao-id "S" :orador-id "O"}})]
    (is (= [{:canal "sessao/S/plenario" :ente-id "E" :tipo "fala.iniciada"
             :dados {:sessao-id "S" :orador-id "O"}}] msgs)
        "uma mensagem por canal, carregando ente-id (defesa-em-profundidade) + tipo + dados")))

;; ---------- PURO: roteamento da VOTACAO (Slice 2 — placar do hemiciclo) ----------

(deftest rotas-da-votacao
  (is (= ["sessao/S/plenario"]
         (canais/rotas-do-evento {:tipo "votacao.aberta" :payload {:sessao-id "S"}}))
      "votacao.aberta roteia p/ o canal plenario da sessao")
  (is (= ["sessao/S/plenario"]
         (canais/rotas-do-evento {:tipo "voto.registrado" :payload {:sessao-id "S"}}))
      "voto.registrado roteia p/ o canal plenario")
  (is (= ["sessao/S/plenario"]
         (canais/rotas-do-evento {:tipo "votacao.encerrada" :payload {:sessao-id "S"}}))
      "votacao.encerrada roteia p/ o canal plenario"))

(deftest item-anunciado-roteia-e-e-consumido
  ;; docs/23 Fatia 4b: o anuncio do item muda a TV para 'Em apreciacao' — tem de chegar ao canal plenario E
  ;; estar registrado no bus (o roteamento sozinho nao entrega nada sem o consumidor).
  (is (= ["sessao/S/plenario"]
         (canais/rotas-do-evento {:tipo "pauta.item-anunciado" :payload {:sessao-id "S"}})))
  (is (some #{"pauta.item-anunciado"} consumer/tipos-consumidos)))

(deftest votacao-no-registro-do-bus
  ;; tipos-consumidos DERIVA de tipos-plenario (fonte unica) — os 3 tipos de votacao tem de estar la,
  ;; senao o roteamento conheceria o evento mas o bus nao o entregaria (drift).
  (is (every? (set consumer/tipos-consumidos)
              ["votacao.aberta" "voto.registrado" "votacao.encerrada"])
      "o projetor consome os 3 eventos de votacao (sem drift entre roteamento e registro no bus)"))

;; ---------- PURO: projecao do PLACAR + SIGILO §22.6 (a armadilha mora AQUI) ----------

(deftest placar-nominal-e-publico
  (let [voto  {:tipo "voto.registrado" :ente-id "E"
               :payload {:votacao-id "V" :sessao-id "S" :modalidade "nominal"
                         :vereador-id "ver-1" :voto "sim"}}
        [msg] (projecao/projetar voto)]
    (is (= "ver-1" (get-in msg [:dados :vereador-id])) "voto NOMINAL expoe quem votou (placar nominal)")
    (is (= "sim" (get-in msg [:dados :voto])) "voto NOMINAL expoe o voto")))

(deftest placar-secreto-e-so-contador
  ;; DEFESA EM PROFUNDIDADE: o contrato do evento (uniao discriminada, events/votacao.clj) ja barra
  ;; identidade no ramo secreta ANTES do outbox. Mas a projecao e' o ULTIMO portao antes do canal: mesmo
  ;; que um tick secreto MALFORMADO carregue identidade (bug upstream), aqui ela e' removida (whitelist).
  (let [tick-malformado {:tipo "voto.registrado" :ente-id "E"
                         :payload {:votacao-id "V" :sessao-id "S" :modalidade "secreta"
                                   :vereador-id "ver-1" :voto "sim"}}
        [msg]           (projecao/projetar tick-malformado)]
    (is (nil? (get-in msg [:dados :vereador-id])) "voto SECRETO NUNCA expoe o vereador (sigilo §22.6)")
    (is (nil? (get-in msg [:dados :voto])) "voto SECRETO NUNCA expoe o voto individual")
    (is (= {:votacao-id "V" :sessao-id "S" :modalidade "secreta"} (:dados msg))
        "secreta projeta SO o tick (contador ao vivo): votacao-id, sessao-id, modalidade")))

(deftest abertura-secreta-passa-modalidade
  ;; votacao.aberta NAO e' voto.registrado: passa direto (sem scrub). Carrega a `modalidade` p/ o cliente
  ;; trocar o render (placar nominal -> contador). AbertaPayload nao tem identidade — pass-through e' seguro.
  (let [[msg] (projecao/projetar {:tipo "votacao.aberta" :ente-id "E"
                                  :payload {:votacao-id "V" :sessao-id "S" :objeto-tipo "proposicao"
                                            :objeto-id "P" :modalidade "secreta" :quorum-tipo "maioria_simples"}})]
    (is (= "secreta" (get-in msg [:dados :modalidade])) "a modalidade chega ao cliente (troca o render do placar)")
    (is (= "proposicao" (get-in msg [:dados :objeto-tipo])) "votacao.aberta passa direto (nao ha identidade a esconder)")))

(deftest voto-registrado-modalidade-inesperada-falha-fechada
  ;; FAIL-CLOSED (review seg): se um voto.registrado chegar a' projecao com modalidade != nominal/secreta (o
  ;; contrato VotoRegistradoPayload e' :multi fechado sobre as duas — isto so ocorreria sob violacao de
  ;; contrato a montante), a projecao LANCA em vez de passar o payload direto e arriscar vazar identidade.
  (is (thrown? clojure.lang.ExceptionInfo
               (projecao/projetar {:tipo "voto.registrado" :ente-id "E"
                                   :payload {:votacao-id "V" :sessao-id "S" :modalidade "eletronica"
                                             :vereador-id "ver-1" :voto "sim"}}))
      "voto.registrado com modalidade fora de {nominal,secreta} -> LANCA (nunca pass-through cego)"))

(deftest encerramento-e-agregado-publico
  ;; o resultado AGREGADO e' publico MESMO na secreta (so o voto individual e' sigiloso).
  (let [enc   {:tipo "votacao.encerrada" :ente-id "E"
               :payload {:votacao-id "V" :sessao-id "S" :resultado "aprovada" :modalidade "secreta"
                         :total-sim 7 :total-nao 2 :total-abstencao 1 :base-membros 11}}
        [msg] (projecao/projetar enc)]
    (is (= "aprovada" (get-in msg [:dados :resultado])) "encerramento carrega o resultado")
    (is (= 7 (get-in msg [:dados :total-sim])) "encerramento carrega os totais (agregado publico na secreta)")))

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
