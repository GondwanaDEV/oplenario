(ns oplenario.paineis.relay-observavel-test
  "INTEGRACAO (PG real) — frente 'relay-observavel', Fatia 1 (o buraco real): `set-tenant!` vivia FORA do
  try em `projetar-evento!` — um evento com `ente-id` NIL (shared.outbox.ente_id e' NULLABLE) fazia
  `set-tenant!` lancar ANTES do catch, envenenando o relay COMPARTILHADO: a tx do evento reverte, o evento
  fica pendente PARA SEMPRE, e `drenar!` (kernel/outbox.clj, sem try em `drenar-um!`) propaga a excecao e
  para de processar — head-of-line block de TODO evento de id MAIOR, de QUALQUER tenant. A prova: um
  evento poison (ente-id nil) de id MENOR seguido de um evento real de id MAIOR — `drenar!` nao lanca, e o
  evento seguinte E' projetado (mesma forma da prova de `transparencia.relay-tolerante-test`)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as repo]
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
