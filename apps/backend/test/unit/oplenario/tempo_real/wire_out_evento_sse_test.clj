(ns oplenario.tempo-real.wire-out-evento-sse-test
  "UNIT (sem Postgres, sem Valkey) — `wire/out/evento-sse` + `adapters/out/evento-sse/mensagem->frame`.
  Frente 'truncamento-familia' sitio (d): `canais/tipo-lacuna` e' um tipo SINTETICO (nunca passa pelo
  outbox), injetado direto por `CanalStoreValkey/ler-desde` quando o replay encontra uma entrada
  corrompida. Se o enum de `:tipo` do wire nao o incluir, `mensagem->frame` LANCA ('evento SSE viola o
  contrato', bug de servidor -> 500) — o que faria o proprio SINAL DE LACUNA derrubar o stream inteiro
  no meio de um replay real, o oposto do que a fatia existe para consertar. Este ns e' o guard de
  REGRESSAO dessa costura."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.tempo-real.adapters.out.evento-sse :as adapters-out]
            [oplenario.tempo-real.canais :as canais]))

(deftest tipo-lacuna-esta-no-enum-de-saida
  (is (contains? canais/tipos-emitidos-ao-cliente canais/tipo-lacuna)
      "o sinal sintetico precisa estar no MESMO set que alimenta o enum do wire — senao emiti-lo 500a"))

(deftest mensagem-de-lacuna-vira-frame-sse-sem-lancar
  (let [frame (adapters-out/mensagem->frame {:tipo canais/tipo-lacuna :dados {} :seq 999})]
    (is (= canais/tipo-lacuna (:name frame)) "o `event:` do SSE e' o tipo sintetico")
    (is (= "999" (:id frame)) "o `id:` (Last-Event-ID) e' a seq preservada da entrada corrompida")
    (is (= "{}" (:data frame)) "o `data:` nunca carrega o payload corrompido")))

(deftest mensagem-de-tipo-desconhecido-ainda-lanca
  ;; guard NEGATIVO: prova que o enum tem DENTES (nao virou :any) — um tipo fora do set continua 500,
  ;; que e' o comportamento correto para um bug de PRODUTOR novo.
  (is (thrown? clojure.lang.ExceptionInfo
        (adapters-out/mensagem->frame {:tipo "isto-nao-existe" :dados {} :seq 1}))))
