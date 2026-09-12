(ns oplenario.transparencia.repositorio-guarda-test
  "UNIT (puro, sem PG) — frente 'relay-tolerante'. Prova a CLASSIFICACAO de `payload-malformado?`
  (whitelist fechado de forma-do-dado vs. o resto, que tem de propagar) e o gate de `:ente-id` ausente
  em `projetar-evento!`, que nao toca a `tx` (o `nil?` reprova ANTES de `tenancy/set-tenant!`) — por
  isso da' p/ testar sem Postgres. O head-of-line de verdade (relay + Postgres real) esta' em
  test/integration/oplenario/transparencia/relay_tolerante_test.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [oplenario.transparencia.components.repositorio :as repo]))

(def ^:private payload-malformado? #'repo/payload-malformado?)

(deftest payload-malformado-reconhece-as-quatro-classes-medidas
  (testing "as classes que o dispatch e os 27 :pre de transparencia/db/ de fato lancam"
    (is (true? (payload-malformado? (AssertionError. "Assert failed: (some? proposicao-id)")))
        "os {:pre ...} de transparencia/db/* (causa medida do incidente)")
    (is (true? (payload-malformado? (IllegalArgumentException. "Invalid UUID string: abc")))
        "UUID/fromString com string mal-formada")
    (is (true? (payload-malformado? (NumberFormatException. "For input string: \"abc\"")))
        "subclasse de IllegalArgumentException (:ano nao-numerico, por exemplo)")
    (is (true? (payload-malformado? (NullPointerException.)))
        "UUID/fromString(nil) ou acesso a campo ausente que o dispatch le direto")
    (is (true? (payload-malformado? (ClassCastException. "class java.lang.Long cannot be cast to class java.lang.String")))
        "valor do tipo errado no jsonb"))
  (testing "o marcador explicito de ex-data (nao casamento de mensagem)"
    (is (true? (payload-malformado? (ex-info "evento sem ente-id" {:transparencia/payload-malformado? true})))
        "o gate de ente-id ausente marca a excecao explicitamente, sem depender do texto da mensagem")
    (is (false? (payload-malformado? (ex-info "qualquer outra falha de dominio" {:erro :outra-coisa})))
        "um ex-info QUALQUER, sem o marcador, NAO e' malformado por default (evita blacklist disfarcada)"))
  (testing "o whitelist e' FECHADO — o resto propaga, inclusive falhas plausiveis de infra"
    (is (false? (payload-malformado? (java.sql.SQLException. "conexao caiu")))
        "SQLException (driver/infra) nao e' forma-de-dado — tem de propagar, nao ser descartado")
    (is (false? (payload-malformado? (java.util.concurrent.TimeoutException.)))
        "timeout tambem nao e' forma-de-dado")
    (is (false? (payload-malformado? (RuntimeException. "erro generico e desconhecido")))
        "uma classe NOVA e desconhecida fica de fora por default — o seguro e' propagar o que nao se reconhece, nao engolir")))

(deftest projetar-evento-tolera-evento-sem-ente-id-sem-tocar-a-tx
  ;; `ente-id` nil e' o mesmo racional ja' documentado em `fan-out-notificacao!` (shared.outbox.ente_id
  ;; e' NULLABLE): um evento supratenant/malformado nao pode propagar `set-tenant!: ente-id nao pode
  ;; ser nil` e envenenar o relay. Como o gate reprova ANTES de `tenancy/set-tenant!`, a `tx` passada
  ;; aqui nunca e' usada — pode ser qualquer valor, inclusive um que explodiria se fosse tocado.
  (with-log
    (is (nil? (repo/projetar-evento! ::tx-nunca-tocada
                                     {:tipo "proposicao.protocolada" :ente-id nil :payload {}
                                      :idempotency-key "chave-teste" :id 42}))
        "descarta e devolve normalmente — nao lanca")
    (is (logged? 'oplenario.transparencia.components.repositorio :error Throwable #"sem ente-id")
        "o descarte fica LOGADO em :error (nao :info) e nomeia a razao")
    (is (logged? 'oplenario.transparencia.components.repositorio :error Throwable #"chave-teste")
        "o log carrega a idempotency-key — quem le acha a linha exata no shared.outbox")
    (is (logged? 'oplenario.transparencia.components.repositorio :error Throwable #"42")
        "o log carrega o :outbox-id (a PK da linha)")))
