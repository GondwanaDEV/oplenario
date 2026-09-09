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

(defn reconciliar!
  "Levanta o contador do `escopo` ate' pelo menos `piso`, para o ente da SESSAO. Devolve o valor final.
  NUNCA abaixa: `GREATEST` — rebaixar reintroduziria exatamente a colisao que esta fn existe para matar.

  Existe porque `proximo!` nao tem como saber que ja' ha linhas numeradas que ele nao emitiu. Isso
  acontece em dois casos reais: (1) acervo legado importado por INSERT direto, com a numeracao original
  da Casa preservada; (2) o contador perdido enquanto as linhas numeradas sobrevivem (foi o que um
  `TRUNCATE shared.sequencial` de fixture causou na Casa da demo — ver `sequencial-lint-test`).

  Nos dois casos o efeito e' o mesmo e NAO se cura sozinho: `proximo!` volta a 1, colide na UNIQUE
  `(ente, ano, sequencial)` da tabela do modulo, e a colisao aborta a transacao inteira — revertendo o
  proprio incremento do contador junto. O contador nunca ultrapassa a colisao, entao toda escrita
  numerada daquele escopo devolve 500 para sempre. `reconciliar!` e' o piso explicito que quebra o laco.

  `piso` = o MAIOR sequencial ja' gravado no escopo (o chamador, que conhece a sua tabela, e' quem o le
  — o kernel nao importa modulo). Idempotente. Exige app.ente_id setado, como `proximo!`."
  [conn escopo piso]
  (let [row (jdbc/execute-one! conn
                               ["INSERT INTO shared.sequencial (ente_id, escopo, valor)
                                 VALUES (NULLIF(current_setting('app.ente_id', true), '')::uuid, ?, ?)
                                 ON CONFLICT (ente_id, escopo)
                                 DO UPDATE SET valor = GREATEST(shared.sequencial.valor, EXCLUDED.valor)
                                 RETURNING valor"
                                escopo piso])]
    (or (:sequencial/valor row)
        (throw (ex-info "sequencial/reconciliar!: RETURNING vazio (tx abortada?)" {:escopo escopo :piso piso})))))
