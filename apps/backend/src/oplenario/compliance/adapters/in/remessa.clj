(ns oplenario.compliance.adapters.in.remessa
  "Gate de ENTRADA `wire/in -> models` da remessa (§22.10 adapters/in, ADR-0001 §3) — chamado SO pelo
  diplomat/. Valida e COAGE a representacao externa (JSON: strings) p/ o dominio, defendendo a borda
  (fail-closed -> 400). O nucleo (controllers/logic) so ve dados ja validados (uuid, estado conhecido).
  F5.5b: o ciclo da remessa (validar/submeter = so o :id de path; registrar-resposta = :id + {estado})."
  (:require [oplenario.compliance.logic :as logic])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn id-param->uuid
  "Path-param :id (string) -> UUID. Malformado = requisicao invalida (`:validacao/invalido` -> 400 na
  borda), nunca erro interno (500)."
  [s]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _ (invalido! "id de remessa invalido" {:campo :id}))))

(defn resposta->estado
  "Corpo externo {estado: 'aceita'|'rejeitada'} (json-params, chaves STRING) -> a string validada. O conjunto
  valido e' `logic/estado-resposta-tce?` (FONTE UNICA — nao replicar na borda; review clj M1). Corpo ausente
  / nao-objeto / estado fora do conjunto -> 400 (nunca chega ao Repo; 'rascunho'/'validada'/'submetida' nao
  sao respostas do TCE)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {estado}" {:campo :corpo}))
  (let [estado (get json-params "estado")]
    (when-not (logic/estado-resposta-tce? estado)
      (invalido! "estado de resposta invalido (so 'aceita' | 'rejeitada')" {:campo :estado}))
    estado))
