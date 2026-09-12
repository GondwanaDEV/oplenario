(ns oplenario.tempo-real.canal-store-valkey-test
  "§22.6 eixo G (G3b) — a CanalStore VIVA em Valkey (Carmine streams), atras do MESMO protocolo da impl em
  memoria (G2). Contrato observavel IDENTICO ao `canal-store-memoria` (seq monotonica POR canal + replay via
  Last-Event-ID) + retencao por TEMPO (janela de 5 min). Integracao real: exige o Valkey de pe (compose). O
  endpoint SSE (diplomat) nao muda — so a impl do protocolo. Modela as assercoes de contrato do projetor_test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.tempo-real.components :as trc]
            [taoensso.carmine :as car]))

(def ^:dynamic *store* nil)

(defn- store-vivo
  "Sobe um CanalStoreValkey (start abre o pool) e o derruba no fim. `opts` injeta clock/retencao p/ os testes
  deterministicos de poda."
  ([t] (store-vivo {} t))
  ([opts t]
   (let [s (component/start (trc/canal-store-valkey (config/carregar) opts))]
     (binding [*store* s] (try (t) (finally (component/stop s)))))))

(use-fixtures :each (fn [t] (store-vivo t)))

(defn- canal [] (str "test/" (random-uuid) "/plenario"))   ; canal unico por teste (Valkey compartilhado)

;; ---------- contrato: seq monotonica POR canal + replay (espelha o projetor_test da impl em memoria) ----------

(deftest valkey-seq-e-replay
  (let [c (canal) d (canal)]
    (is (= {:canal c :seq 1} (trc/publicar! *store* c {:tipo "a" :dados {}})) "1a mensagem do canal = seq 1")
    (is (= {:canal c :seq 2} (trc/publicar! *store* c {:tipo "b" :dados {}})) "seq monotonica por canal")
    (is (= {:canal d :seq 1} (trc/publicar! *store* d {:tipo "x" :dados {}})) "seq e' POR canal (d comeca em 1)")
    (is (= ["b"] (mapv :tipo (trc/ler-desde *store* c 1))) "replay desde Last-Event-ID seq=1 -> so a posterior")
    (is (= ["a" "b"] (mapv :tipo (trc/ler-desde *store* c 0))) "ler-desde 0 = tudo, em ordem de seq")))

;; ---------- round-trip fiel da mensagem (Carmine freeze/thaw preserva keyword keys) ----------

(deftest valkey-round-trip-mensagem
  (let [c    (canal)
        ente (random-uuid)]
    (trc/publicar! *store* c {:tipo "sessao.transicionou" :ente-id ente :dados {:sessao-id "S" :para "aberta"}})
    (let [m (first (trc/ler-desde *store* c 0))]
      (is (= "sessao.transicionou" (:tipo m)) "tipo (string) preservado")
      (is (= ente (:ente-id m)) "ente-id (defesa-em-profundidade) preservado")
      (is (= {:sessao-id "S" :para "aberta"} (:dados m)) "dados (map de keyword keys) round-trip fiel")
      (is (= 1 (:seq m)) "seq embutida na mensagem"))))

;; ---------- isolamento entre canais ----------

(deftest valkey-canais-isolados
  (let [c (canal) d (canal)]
    (trc/publicar! *store* c {:tipo "a" :dados {}})
    (trc/publicar! *store* c {:tipo "b" :dados {}})
    (trc/publicar! *store* d {:tipo "z" :dados {}})
    (is (= ["a" "b"] (mapv :tipo (trc/ler-desde *store* c 0))) "canal c isolado")
    (is (= ["z"] (mapv :tipo (trc/ler-desde *store* d 0))) "canal d isolado")))

;; ---------- retencao por TEMPO — janela curta + sleep real (exerce o MESMO caminho de producao) ----------

(deftest valkey-retencao-poda-por-tempo
  ;; janela de 100 ms; "velha" publicada, sleep 250 ms, "nova" publicada -> MINID(agora-100ms) cai DEPOIS do id
  ;; da velha -> Valkey a poda. Margem (250 vs 100) folgada o bastante p/ nao flakar.
  (store-vivo
   {:retencao-ms 100}
   (fn []
     (let [c (canal)]
       (trc/publicar! *store* c {:tipo "velha" :dados {}})
       (Thread/sleep 250)
       (trc/publicar! *store* c {:tipo "nova" :dados {}})
       (is (= ["nova"] (mapv :tipo (trc/ler-desde *store* c 0)))
           "entrada fora da janela e' podada por tempo (MINID); so a recente sobrevive ao replay")))))

;; ---------- Lifecycle: start abre / stop fecha / start idempotente ----------

(deftest valkey-lifecycle-idempotente
  (let [s  (trc/canal-store-valkey (config/carregar))
        s1 (component/start s)
        s2 (component/start s1)                             ; 2o start nao reabre (idempotente)
        k  (canal)]                                         ; canal unico (contador nao herda de run anterior)
    (try
      (is (= {:canal k :seq 1} (trc/publicar! s2 k {:tipo "ok" :dados {}})) "store de pe publica")
      (finally (component/stop s2)))))

;; ---------- guarda nil-conn: usar o store sem start LANCA com contexto (review clj-MAJOR / sec-MINOR-3) ----------

(deftest valkey-uso-sem-start-lanca
  (let [s (trc/canal-store-valkey (config/carregar))]       ; construido, NAO iniciado (conn-atom nil)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao iniciado"
          (trc/publicar! s (canal) {:tipo "x" :dados {}}))
        "publicar! sem start lanca ex-info clara (nao NPE do Carmine)")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao iniciado"
          (trc/ler-desde s (canal) 0))
        "ler-desde sem start lanca ex-info clara")))

;; ---------- defesa-em-profundidade: entrada de forma estranha vira SINAL, nao e' engolida (sec-HIGH-1 +
;; frente 'truncamento-familia' sitio (d): o descarte silencioso deixava o cliente avancar o cursor por
;; cima do buraco como se o replay estivesse integro — sem sinal nem no servidor nem no cliente) ----------

(deftest valkey-mensagem-corrompida-vira-sinal-de-lacuna-sem-perder-o-cursor
  (let [c    (canal)
        conn {:pool :none :spec {:uri (get-in (config/carregar) [:valkey :uri])}}]
    (trc/publicar! *store* c {:tipo "boa" :dados {}})        ; entrada valida (seq 1)
    ;; injeta uma entrada CRUA com "m" nao-map (corrupcao / escrita externa) direto no stream do canal
    (car/wcar conn (car/xadd (str "tr:canal:" c) "*" "m" "isto-nao-e-um-map" "s" "999"))
    (let [msgs (trc/ler-desde *store* c 0)]
      (is (= ["boa" "tempo-real.lacuna"] (mapv :tipo msgs))
          "a entrada corrompida NAO e' descartada — vira um sinal de lacuna que o cliente recebe pelo
           MESMO canal, na ordem de seq")
      (is (= 999 (:seq (last msgs)))
          "a seq da entrada corrompida e' PRESERVADA no sinal: o cursor do cliente avanca sabendo do
           buraco (Last-Event-ID=999), nao silenciosamente por cima dele")
      (is (= {} (:dados (last msgs))) "o sinal nunca carrega o payload corrompido"))))
