(ns oplenario.kernel.eventos-test
  "EventBus do kernel (§22.10): o envelope de evento de dominio e o protocolo `emitir!`. A parte
  PURA (envelope + idempotency-key) e' F0.1; a impl que grava no shared.outbox na tx e' F0.2."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.eventos :as eventos]))

(deftest evento-carrega-tipo-ente-e-payload
  (let [ente (random-uuid)
        e (eventos/evento "proposicao.protocolada" ente {:numero 1})]
    (is (= "proposicao.protocolada" (:tipo e)) "tipo do evento")
    (is (= ente (:ente-id e)) "ente-id (tenant) do evento")
    (is (= {:numero 1} (:payload e)) "payload do evento")))

(deftest evento-carimba-idempotency-key
  (let [e (eventos/evento "x" (random-uuid) {})]
    (is (string? (:idempotency-key e)) "evento traz uma idempotency-key (dedup no consumidor, §22.9 E2)")))

(deftest eventos-distintos-tem-idempotency-keys-distintas
  (let [ente (random-uuid)]
    (is (not= (:idempotency-key (eventos/evento "x" ente {}))
              (:idempotency-key (eventos/evento "x" ente {})))
        "cada emissao logica e' uma ocorrencia distinta -> chave distinta")))

(deftest evento-aceita-ente-id-nil-supratenant
  (is (nil? (:ente-id (eventos/evento "operador.acao" nil {})))
      "ente-id nil (evento supratenant, ex.: admin_sistema) e' valido"))

(deftest evento-rejeita-args-invalidos
  (is (thrown? AssertionError (eventos/evento 42 (random-uuid) {}))
      "tipo nao-string e' rejeitado")
  (is (thrown? AssertionError (eventos/evento "x" "nao-uuid" {}))
      "ente-id que nao e' uuid nem nil e' rejeitado")
  (is (thrown? AssertionError (eventos/evento "x" (random-uuid) "nao-mapa"))
      "payload nao-mapa e' rejeitado"))
