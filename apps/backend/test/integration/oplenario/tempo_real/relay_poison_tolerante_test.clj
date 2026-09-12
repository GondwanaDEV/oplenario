(ns oplenario.tempo-real.relay-poison-tolerante-test
  "INTEGRACAO (PG real) — frente 'relay-poison-tolerante'. `tempo_real/consumer.clj` e' consumidor do MESMO
  relay compartilhado por todos os modulos (§22.9); antes desta frente o handler nao tinha try/catch NENHUM,
  entao um `voto.registrado` com modalidade fora de {nominal,secreta} (o gate de sigilo de
  `tempo_real/projecao.clj`, que fica intacto) lancava DENTRO da tx do relay — POISON (reprocessado a cada
  tick para sempre), bloqueando HEAD-OF-LINE todo evento de id maior, de QUALQUER modulo, de TODOS os
  tenants.

  A prova que fecha esta frente: (1) um evento malformado de id N nao bloqueia um evento VALIDO de id N+1 no
  MESMO `drenar!` (head-of-line); (2) o payload descartado NAO VAZA no canal — a lacuna publicada carrega
  `:dados {}`, nunca `:vereador-id`/`:voto`; (3) o descarte fica LOGADO identificando a linha exata; (4)
  falha de INFRA (nao forma-de-dado) atravessa o guard sem ser engolida."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.tempo-real.canais :as canais]
            [oplenario.tempo-real.components :as trc]
            [oplenario.tempo-real.consumer :as consumer]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(use-fixtures :each
  (fn [t]
    (jdbc/execute! *ds* ["TRUNCATE shared.outbox, shared.evento_consumido"])
    (t)))

(defn- gravar!
  "Grava `ev` (envelope CRU de `eventos/evento` — sem a validacao Malli de `events/votacao.clj`, de proposito:
  e' o unico jeito de por no shared.outbox um `voto.registrado` com modalidade fora de {nominal,secreta}, ja
  que `VotoRegistradoPayload` e' um `:multi` FECHADO que barraria isto na fonte) numa tx PROPRIA — cada
  INSERT commita sozinho, entao a ordem de chamada decide a ordem de `id` (BIGINT GENERATED ALWAYS AS
  IDENTITY), que e' a ordem que o relay drena (`ORDER BY id`)."
  [ev]
  (jdbc/with-transaction [tx *ds*] (eventos/emitir! (outbox/bus) tx ev)))

(defn- voto-malformado [ente sid ver-id]
  (eventos/evento "voto.registrado" ente
                   {:votacao-id (random-uuid) :sessao-id sid :modalidade "eletronica"
                    :vereador-id ver-id :voto "sim"}))

(defn- sessao-transicionou [ente sid]
  (eventos/evento "sessao.transicionou" ente {:sessao-id sid :para "aberta"}))

(defn- pendentes []
  (:contagem (jdbc/execute-one! *ds*
               ["SELECT count(*)::int AS contagem FROM shared.outbox WHERE processed_at IS NULL"])))

(deftest evento-malformado-nao-trava-a-cabeca-da-fila-para-o-proximo
  (let [ente     (random-uuid)
        sid      (random-uuid)
        store    (trc/canal-store-memoria)
        registro (consumer/registro store)
        ev-malformado (voto-malformado ente sid (random-uuid))
        ev-valido     (sessao-transicionou ente sid)]
    ;; ORDEM importa: grava o malformado PRIMEIRO (id menor) e so' depois o valido (id maior) — e' essa ordem
    ;; que faz a asserção seguinte provar HEAD-OF-LINE, nao so' "nao lanca".
    (gravar! ev-malformado)
    (gravar! ev-valido)
    (with-log
      (is (= 2 (outbox/drenar! *ds* registro))
          "drena os DOIS numa passada so' — o malformado nao interrompe `drenar!` no meio")
      (is (logged? 'oplenario.tempo-real.consumer :error clojure.lang.ExceptionInfo #"payload malformado")
          "o descarte fica OBSERVAVEL em :error (nunca :info — isto e' anomalia)")
      (is (logged? 'oplenario.tempo-real.consumer :error clojure.lang.ExceptionInfo #"voto\.registrado")
          "o log nomeia o tipo do evento descartado"))
    ;; A ASSERCAO QUE PROVA O HEAD-OF-LINE: o evento de id MAIOR (sessao.transicionou) foi de fato
    ;; PROJETADO e chegou ao canal — se o relay ainda travasse na cabeca da fila (o defeito antigo), esta
    ;; mensagem jamais existiria.
    (let [msgs (trc/ler-desde store (canais/canal-plenario sid) 0)]
      (is (some #(= "sessao.transicionou" (:tipo %)) msgs)
          "o evento seguinte (id maior) foi projetado — a fila nao ficou presa no malformado"))
    (is (zero? (pendentes))
        "as DUAS linhas saem marcadas processed_at — nenhuma fica pendente p/ retry eterno (nao virou poison)")))

(deftest o-log-identifica-a-linha-exata-do-outbox-descartada
  (let [ente     (random-uuid)
        sid      (random-uuid)
        store    (trc/canal-store-memoria)
        registro (consumer/registro store)
        ev       (voto-malformado ente sid (random-uuid))]
    (gravar! ev)
    (let [linha (jdbc/execute-one! *ds*
                  ["SELECT id FROM shared.outbox WHERE idempotency_key = ?" (:idempotency-key ev)])]
      (with-log
        (outbox/drenar! *ds* registro)
        (is (logged? 'oplenario.tempo-real.consumer :error clojure.lang.ExceptionInfo
                     (re-pattern (str (:outbox/id linha))))
            "o log carrega o :outbox-id (a PK da linha exata que descartou)")
        (is (logged? 'oplenario.tempo-real.consumer :error clojure.lang.ExceptionInfo (re-pattern (str ente)))
            "o log carrega o ente-id")))))

(deftest sigilo-a-lacuna-nao-vaza-o-payload-malformado
  ;; A asserção que deixaria o Daouda acordado: o payload descartado NAO VAZA no canal. Escrito com um
  ;; payload que CONTEM :vereador-id/:voto — sem isto a asserção nao teria como reprovar.
  (let [ente     (random-uuid)
        sid      (random-uuid)
        ver-id   (random-uuid)
        store    (trc/canal-store-memoria)
        registro (consumer/registro store)
        ev       (voto-malformado ente sid ver-id)]
    (gravar! ev)
    (outbox/drenar! *ds* registro)
    (let [msgs   (trc/ler-desde store (canais/canal-plenario sid) 0)
          lacuna (first (filter #(= canais/tipo-lacuna (:tipo %)) msgs))
          bruto  (pr-str msgs)]
      (is (some? lacuna) "a lacuna chegou ao canal plenario da sessao")
      (is (= {} (:dados lacuna)) "a lacuna carrega :dados {} — nada do payload malformado atravessa")
      (is (= ente (:ente-id lacuna)) "a lacuna carrega o ente-id (defesa-em-profundidade, como qualquer mensagem)")
      (is (not (re-find (re-pattern (str ver-id)) bruto))
          "o :vereador-id do payload malformado NAO aparece em nenhuma mensagem publicada no canal")
      (is (not (re-find #"\"sim\"" bruto))
          "o :voto do payload malformado NAO aparece em nenhuma mensagem publicada no canal"))))

(defn- store-infra-fora
  "CanalStore cujo `publicar!` SEMPRE lanca — simula Valkey/rede fora. Um `reify` proprio (nao
  `with-redefs` em `trc/publicar!`) DE PROPOSITO: `CanalStoreMemoria` implementa o protocolo INLINE no
  `defrecord` (canal_store_valkey_test.clj vizinho confirma o mesmo padrao p/ a impl Valkey), entao a
  chamada `(comp/publicar! store ...)` despacha pelo metodo Java gerado diretamente na instancia (fast
  path de protocolo) — NUNCA passa pela Var `publicar!`, e `with-redefs` so' troca a raiz da Var. Um tipo
  proprio que IMPLEMENTA `CanalStore` e sempre lanca e' o jeito que de fato intercepta a chamada."
  []
  (reify trc/CanalStore
    (publicar! [_ _canal _mensagem] (throw (java.net.ConnectException. "valkey caiu (simulado)")))
    (ler-desde [_ _canal _apos-seq] [])))

(deftest erro-de-infra-propaga-em-vez-de-ser-descartado
  ;; O contraste que fecha a garantia: uma falha que NAO e' forma-de-payload (aqui, Valkey/rede fora,
  ;; simulada) tem de ATRAVESSAR o guard e chegar ao relay, que reverte a tx e retem o evento p/ retry (o
  ;; comportamento CORRETO p/ indisponibilidade transitoria). Um guard que a engolisse converteria "o Valkey
  ;; caiu por 2s" em "o evento sumiu para sempre".
  (let [ente     (random-uuid)
        sid      (random-uuid)
        registro (consumer/registro (store-infra-fora))
        ev       (sessao-transicionou ente sid)]
    (gravar! ev)
    (is (thrown? java.net.ConnectException (outbox/drenar! *ds* registro))
        "ConnectException (infra) PROPAGA — o guard nao e' um catch-Throwable disfarcado")
    (is (= 1 (pendentes))
        "a linha fica pendente (a tx do relay reverteu) — nao foi silenciosamente descartada")))
