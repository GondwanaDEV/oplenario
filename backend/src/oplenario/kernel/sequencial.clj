(ns oplenario.kernel.sequencial
  "Numeracao canonica gapless por LINHA-CONTADOR (§22.9 Eixo 2). UPSERT atomico: o contador anda
  so ao commitar -> rollback NAO deixa buraco (ao contrario de SEQUENCE nativa). TENANT-scoped: o
  ente_id vem do GUC app.ente_id (nao do caller -> ninguem bumpa/le o contador de outro ente); RLS
  isola. Use DENTRO de com-tenant* (tenant setado). Kernel — nao importa modulo."
  (:require [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(defn proximo!
  "Proximo valor gapless do `escopo` (ex.: 'lei:2026') para o ente da SESSAO. Comeca em 1.
  Exige app.ente_id setado (senao o ente_id seria NULL e o INSERT falha — fail-closed)."
  [conn escopo]
  (let [row (jdbc/execute-one! conn
                               ["INSERT INTO shared.sequencial (ente_id, escopo, valor)
                                 VALUES (NULLIF(current_setting('app.ente_id', true), '')::uuid, ?, 1)
                                 ON CONFLICT (ente_id, escopo)
                                 DO UPDATE SET valor = shared.sequencial.valor + 1
                                 RETURNING valor"
                                escopo])]
    (or (:sequencial/valor row)
        (throw (ex-info "sequencial/proximo!: RETURNING vazio (tx abortada?)" {:escopo escopo})))))
