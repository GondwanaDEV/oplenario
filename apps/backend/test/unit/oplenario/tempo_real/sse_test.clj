(ns oplenario.tempo-real.sse-test
  "§22.6 eixo G (G3) — o ENDPOINT SSE, parte testavel sem Pedestal/streaming: o CHECKLIST de autorizacao da
  conexao (canais.clj) + a projecao da mensagem em frame SSE (adapters/out) + o iterador de replay/incremento
  (controller, sobre a CanalStore). O wiring Pedestal (start-event-stream + dispatch loop) e' glue fino coberto
  pelos testes de borda (sse_borda_test) nos caminhos de RECUSA (que terminam antes do switch async)."
  (:require [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.tempo-real.adapters.in.evento-sse :as adapters-in]
            [oplenario.tempo-real.adapters.out.evento-sse :as adapters-out]
            [oplenario.tempo-real.controllers :as controllers]
            [oplenario.tempo-real.components :as trc]
            [oplenario.tempo-real.logic :as logic]))

(defn- sessao [ente-id transmite?]
  {:id (random-uuid) :ente-id ente-id :transmite-publica transmite? :estado "aberta"})

;; ---------- checklist de authz (puro): mesma casa + sessao nao-secreta ----------

(deftest pode-assistir-plenario-happy
  (let [ente (random-uuid)]
    (is (true? (logic/pode-assistir-plenario? {:ente-id ente} (sessao ente true)))
        "ator da mesma Casa + sessao com transmissao publica -> pode assistir")))

(deftest pode-assistir-plenario-sessao-secreta
  (let [ente (random-uuid)]
    (is (false? (logic/pode-assistir-plenario? {:ente-id ente} (sessao ente false)))
        "sessao secreta (transmite_publica=false) -> NAO pode assistir (item 2 do checklist)")))

(deftest pode-assistir-plenario-cross-ente
  (is (false? (logic/pode-assistir-plenario? {:ente-id (random-uuid)} (sessao (random-uuid) true)))
      "sessao de OUTRA Casa -> NAO pode assistir (item 1: posse de tenant, defesa-em-profundidade)"))

;; ---------- adapters/out: mensagem da CanalStore -> frame SSE ----------

(deftest mensagem-vira-frame-sse
  (let [msg   {:ente-id (random-uuid) :tipo "sessao.transicionou" :dados {:sessao-id "S" :para "aberta"} :seq 7}
        frame (adapters-out/mensagem->frame msg)]
    (is (= "sessao.transicionou" (:name frame)) "o tipo do evento vira o nome do evento SSE (event:)")
    (is (= "7" (:id frame)) "a seq do canal vira o id SSE (Last-Event-ID na reconexao)")
    (is (= {:sessao-id "S" :para "aberta"} (json/read-value (:data frame) json/keyword-keys-object-mapper))
        "o data e' o payload (dados) serializado em JSON")))

(deftest frame-rejeita-mensagem-sem-tipo
  (is (thrown? Exception (adapters-out/mensagem->frame {:dados {} :seq 1}))
      "mensagem sem :tipo viola o contrato EventoSse = bug de servidor (nunca frame malformado)"))

(deftest frame-rejeita-campo-sensivel
  ;; review seg MINOR-3: defesa-em-profundidade anti-vazamento — um produtor novo que inclua dado sensivel em
  ;; :dados barra na borda de saida (nunca vai no fio), em vez de depender so da convencao do produtor.
  (is (thrown? clojure.lang.ExceptionInfo
               (adapters-out/mensagem->frame {:tipo "sessao.transicionou" :seq 1 :dados {:cpf "111"}}))
      "campo sensivel (:cpf) em :dados -> LANCA (bug de produtor), nunca emite o frame"))

;; ---------- adapters/in: Last-Event-ID fail-soft ----------

(deftest cursor-id-gigante-e-fail-soft
  ;; review seg MINOR-1: id so-digitos porem acima de Long.MAX nao pode virar 500 (parse-long lancaria) — a
  ;; promessa do docstring e' fail-soft (reseta o cursor p/ 0 na reconexao).
  (is (= 0 (adapters-in/last-event-id->cursor (apply str (repeat 40 \9))))
      "Last-Event-ID gigante (> Long.MAX) -> 0 (fail-soft), nunca excecao")
  (is (= 7 (adapters-in/last-event-id->cursor "7")) "id valido normal -> o cursor")
  (is (= 0 (adapters-in/last-event-id->cursor nil)) "ausente -> 0 (replay desde o inicio da janela)"))

;; ---------- controller: autorizar-plenario (consulta injetada + check! fino) ----------

(deftest autorizar-plenario-ok
  (let [ente (random-uuid) sid (random-uuid)
        s    (sessao ente true)
        consultar (fn [e id] (when (and (= e ente) (= id sid)) s))]
    (is (= s (controllers/autorizar-plenario consultar {:ente-id ente} sid))
        "sessao da Casa + publica -> devolve a sessao (segue p/ o stream)")))

(deftest autorizar-plenario-ator-nil-nega
  ;; review seg MINOR-2: fail-closed auto-suficiente — sem depender da pre-condicao do interceptor de auth, um
  ;; ator nil NEGA (nunca delega a consultar-sessao com ente-id nil).
  (let [consultar (fn [_ _] {:ente-id (random-uuid) :transmite-publica true})]
    (is (authz/negado? (try (controllers/autorizar-plenario consultar nil (random-uuid))
                            (catch clojure.lang.ExceptionInfo e e)))
        "ator nil -> negacao de autorizacao (fail-closed), nunca consulta com ente-id nil")))

(deftest autorizar-plenario-inexistente-devolve-nil
  (let [consultar (fn [_ _] nil)]
    (is (nil? (controllers/autorizar-plenario consultar {:ente-id (random-uuid)} (random-uuid)))
        "sessao inexistente no tenant -> nil (a borda traduz p/ 404)")))

(deftest autorizar-plenario-secreta-nega
  (let [ente (random-uuid)
        consultar (fn [_ _] (sessao ente false))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (controllers/autorizar-plenario consultar {:ente-id ente} (random-uuid)))
        "sessao secreta -> policy.check NEGA (a borda traduz p/ 403)")
    (is (authz/negado? (try (controllers/autorizar-plenario consultar {:ente-id ente} (random-uuid))
                            (catch clojure.lang.ExceptionInfo e e)))
        "a excecao e' uma negacao de autorizacao (:autorizacao/negado)")))

;; ---------- controller: enviar-desde (replay/incremento sobre a CanalStore) ----------

(deftest enviar-desde-replay-tudo
  (let [store (trc/canal-store-memoria)
        _ (trc/publicar! store "c" {:ente-id "E" :tipo "a" :dados {}})
        _ (trc/publicar! store "c" {:ente-id "E" :tipo "b" :dados {}})
        enviadas (atom [])
        [cursor vivo?] (controllers/enviar-desde store "c" 0 (fn [m] (swap! enviadas conj (:tipo m)) true))]
    (is (= ["a" "b"] @enviadas) "replay desde 0 envia todas em ordem de seq")
    (is (= 2 cursor) "o cursor avanca p/ a maior seq enviada")
    (is (true? vivo?) "todas entregues -> canal vivo")))

(deftest enviar-desde-so-incremento
  (let [store (trc/canal-store-memoria)
        _ (trc/publicar! store "c" {:ente-id "E" :tipo "a" :dados {}})
        _ (trc/publicar! store "c" {:ente-id "E" :tipo "b" :dados {}})
        _ (trc/publicar! store "c" {:ente-id "E" :tipo "c" :dados {}})
        enviadas (atom [])
        [cursor _] (controllers/enviar-desde store "c" 2 (fn [m] (swap! enviadas conj (:tipo m)) true))]
    (is (= ["c"] @enviadas) "a partir do cursor 2 so a seq 3 e' nova (Last-Event-ID)")
    (is (= 3 cursor) "cursor avanca p/ 3")))

(deftest enviar-desde-para-no-canal-morto
  (let [store (trc/canal-store-memoria)
        _ (trc/publicar! store "c" {:ente-id "E" :tipo "a" :dados {}})
        _ (trc/publicar! store "c" {:ente-id "E" :tipo "b" :dados {}})
        enviadas (atom [])
        ;; enviar! devolve false (canal fechado) na 1a -> para imediatamente
        [_ vivo?] (controllers/enviar-desde store "c" 0 (fn [m] (swap! enviadas conj (:tipo m)) false))]
    (is (= ["a"] @enviadas) "ao detectar o canal morto (enviar! false) para de enviar")
    (is (false? vivo?) "vivo?=false sinaliza desconexao -> o loop encerra")))
