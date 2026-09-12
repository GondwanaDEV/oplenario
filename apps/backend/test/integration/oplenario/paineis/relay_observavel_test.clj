(ns oplenario.paineis.relay-observavel-test
  "INTEGRACAO (PG real) — frente 'relay-observavel'. Duas provas:

  FATIA 1 (o buraco real): `set-tenant!` vivia FORA do try em `projetar-evento!` — um evento com
  `ente-id` NIL (shared.outbox.ente_id e' NULLABLE) fazia `set-tenant!` lancar ANTES do catch,
  envenenando o relay COMPARTILHADO: a tx do evento reverte, o evento fica pendente PARA SEMPRE, e
  `drenar!` (kernel/outbox.clj, sem try em `drenar-um!`) propaga a excecao e para de processar —
  head-of-line block de TODO evento de id MAIOR, de QUALQUER tenant. A prova: um evento poison (ente-id
  nil) de id MENOR seguido de um evento real de id MAIOR — `drenar!` nao lanca, e o evento seguinte E'
  projetado (mesma forma da prova de `transparencia.relay-tolerante-test`).

  FATIA 2 (as duas classes de log): payload malformado (dado externo, ex.: data ilegivel) loga :warn;
  qualquer OUTRA excecao (aqui, uma falha de infra SIMULADA — driver/conexao, o mesmo racional de
  `transparencia.relay-tolerante-test/erro-de-infra-propaga-em-vez-de-ser-descartado`, so' que AQUI o
  guard TOLERA em vez de propagar — decisao deste ns, nao estreitada por esta frente) loga :error
  nomeando explicitamente que o evento NAO foi projetado e NAO sera' reprocessado. As duas carregam o
  `:id` (PK de shared.outbox) no log — e' o que torna a linha achavel sem SELECT de adivinhacao."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as repo]
            [oplenario.paineis.db.pendencia :as db-pendencia]
            [oplenario.paineis.diplomat.consumers :as consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- gravar!
  "Grava `ev` (envelope CRU de `eventos/evento`) numa tx PROPRIA — cada INSERT commita sozinho, entao a
  ordem de chamada decide a ordem de `id` (BIGINT GENERATED ALWAYS AS IDENTITY), que e' a ordem que o
  relay drena (`ORDER BY id`). Fora de `com-tenant*` de proposito: um evento de `ente-id` nil nao pode
  ser gravado por `com-tenant*` (que exigiria tenant so' p/ ESCREVER a linha do outbox; shared.outbox nao
  leva tenant-RLS — mig 20260620000009, infra/transporte)."
  [ev]
  (jdbc/with-transaction [tx *ds*] (eventos/emitir! (outbox/bus) tx ev)))

(defn- drenar! []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- outbox-id-do-ente [ente]
  (:outbox/id (jdbc/execute-one! *ds* ["SELECT id FROM shared.outbox WHERE ente_id = ?" ente])))

;; ---------- Fatia 1: head-of-line ----------

(deftest evento-com-ente-id-nil-nao-bloqueia-o-relay-e-o-seguinte-e-projetado
  (let [ente-real (random-uuid) pid (random-uuid)
        ev-poison (eventos/evento "participacao.pedido_esic.protocolado" nil
                    {:pedido-id (str (random-uuid)) :protocolo "POISON-ENTE-NIL"
                     :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-24"})
        ev-real   (eventos/evento "participacao.pedido_esic.protocolado" ente-real
                    {:pedido-id (str pid) :protocolo "ESIC-APOS-POISON"
                     :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-24"})]
    ;; ORDEM importa: grava o poison PRIMEIRO (id menor) e so' depois o real (id maior).
    (gravar! ev-poison)
    (gravar! ev-real)
    ;; `(try (drenar!) nil (catch Throwable e e))`: o `nil` DEPOIS de `(drenar!)` descarta o retorno REAL
    ;; dela (um inteiro — a contagem processada, nunca nil) e vira o valor da via feliz; so' a via de
    ;; excecao devolve o Throwable. Sem o `nil` explicito, `(try (drenar!) ...)` devolveria a CONTAGEM
    ;; (nunca nil mesmo no caminho feliz) e a asserção reprovaria SEMPRE, provando o instrumento, nao o
    ;; codigo (familia `oplenario-armadilhas-de-instrumento`).
    (is (nil? (try (drenar!) nil (catch Throwable e e)))
        "drenar! NAO lanca mesmo com um evento de ente-id nil na fila (Fatia 1)")
    (is (= 1 (count (:pendencias (repo/o-que-vence *repo* ente-real {}))))
        "o evento SEGUINTE (id maior) FOI projetado — sem head-of-line block")))

;; ---------- Fatia 2: as duas classes de log ----------

(deftest payload-malformado-loga-warn-com-o-id-da-linha
  (let [ente (random-uuid)
        ev (eventos/evento "participacao.pedido_esic.protocolado" ente
             {:pedido-id (str (random-uuid)) :protocolo "ESIC-DATA-ILEGIVEL"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "nao-e-uma-data"})]
    (gravar! ev)
    (let [oid (outbox-id-do-ente ente)]
      (with-log
        (drenar!)
        ;; `logged?` de 3-aridade compara o throwable contra `nil` por IGUALDADE — sempre falso quando o
        ;; log de fato carrega uma excecao (a nossa carrega). A 4-aridade com a CLASSE medida
        ;; (`DateTimeParseException`, java.time.format) e' o matcher certo — mesmo idioma do precedente
        ;; `transparencia.relay-tolerante-test` (que usa `AssertionError` na posicao do throwable).
        (is (logged? 'oplenario.paineis.components.repositorio :warn
                     java.time.format.DateTimeParseException #"payload malformado")
            "data ilegivel (DateTimeParseException) e' classificada como FORMA DO PAYLOAD -> :warn")
        (is (logged? 'oplenario.paineis.components.repositorio :warn
                     java.time.format.DateTimeParseException (re-pattern (str oid)))
            "o :id da linha do outbox aparece no log — achavel sem SELECT de adivinhacao")
        (is (not (logged? 'oplenario.paineis.components.repositorio :error Throwable #"NAO projetado"))
            "payload malformado NUNCA loga como perda de infra")))
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))) "nenhuma pendencia foi criada")))

(deftest falha-de-infra-loga-error-nomeando-a-perda-com-o-id-da-linha
  ;; simula falha de INFRA (nao forma do dado): a escrita de db/pendencia lanca uma excecao que NAO esta
  ;; no whitelist de `payload-malformado?` (nem AssertionError/IllegalArgumentException/
  ;; NullPointerException/DateTimeParseException) — mesmo racional (e mesma classe) do contraste de
  ;; `transparencia.relay-tolerante-test/erro-de-infra-propaga-em-vez-de-ser-descartado`. AQUI o guard
  ;; TOLERA (nao propaga — paineis nunca lanca, por desenho); so' o NIVEL do log muda.
  ;; `db-pendencia/marcar-vencida!` e' fn PLANA de nivel superior (nao metodo de protocolo num defrecord
  ;; com impl INLINE) — with-redefs funciona sem cair na armadilha do fast-path de despacho de protocolo
  ;; (medida na frente anterior: with-redefs NAO funciona quando o metodo e' implementado inline no
  ;; defrecord, porque o despacho vai direto ao metodo Java da instancia).
  (let [ente (random-uuid)
        ev (eventos/evento "participacao.prazo.vencido" ente
             {:objeto-tipo "pedido_esic" :objeto-id (str (random-uuid))})]
    (gravar! ev)
    (let [oid (outbox-id-do-ente ente)]
      (with-redefs [db-pendencia/marcar-vencida! (fn [& _] (throw (java.sql.SQLException. "conexao caiu (simulado)")))]
        (with-log
          (is (nil? (try (drenar!) nil (catch Throwable e e)))
              "a falha de infra e' TOLERADA igual — o catch continua Throwable inteiro (NAO estreitar)")
          (is (logged? 'oplenario.paineis.components.repositorio :error
                       java.sql.SQLException #"NAO projetado e NAO sera' reprocessado")
              "SQLException nao reconhecida -> :error nomeando a PERDA, nao um :warn de rotina")
          (is (logged? 'oplenario.paineis.components.repositorio :error
                       java.sql.SQLException (re-pattern (str oid)))
              "o :id da linha do outbox aparece no log — achavel sem SELECT de adivinhacao")
          (is (not (logged? 'oplenario.paineis.components.repositorio :warn Throwable #"payload malformado"))
              "falha de infra NUNCA loga como rotina de dado malformado"))))))
