(ns oplenario.tempo-real.adapters.in.evento-sse
  "Gate de ENTRADA `request HTTP -> model` da conexao SSE (§22.10 adapters/in, ADR-0001) — dividido por DIRECAO
  (sob adapters/in/). Chamado SO pelo diplomat/. Coage os parametros da ABERTURA da conexao: o :id da sessao
  (path-param) e o cursor de replay (header Last-Event-ID). Fail-closed na borda."
  (:require [clojure.string :as str])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn id-param->uuid
  "Path-param :id (string) -> UUID. Malformado = requisicao invalida (`:validacao/invalido` -> 400 na borda),
  nunca erro interno (500)."
  [s]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (throw (ex-info "id de sessao invalido" {:tipo :validacao/invalido :campo :id})))))

(defn last-event-id->cursor
  "Header Last-Event-ID (string) -> cursor de replay (long). Ausente/invalido -> 0 (replay desde o inicio da
  janela). Fail-soft: um id corrompido na reconexao nunca quebra a abertura — so reseta o cursor (a janela e'
  limitada de qualquer forma; pior caso = recebe o que ja viu, e o cliente deduplica por id)."
  [s]
  ;; `\d+` so garante digitos, nao tamanho: um id acima de Long.MAX faria parse-long LANCAR (-> 500), quebrando
  ;; o fail-soft prometido. <=19 digitos = cabe num long positivo (review seg MINOR-1).
  (let [t (some-> s str/trim)]
    (if (and t (re-matches #"\d+" t) (<= (count t) 19)) (parse-long t) 0)))
