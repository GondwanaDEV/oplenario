(ns oplenario.integracao-ia.relay-tolerante-test
  "INTEGRACAO (PG real) — frente 'relay-tolerante': o consumidor `integracao_ia` (promocao ao feed da IA) roda
  DENTRO da tx do relay COMPARTILHADO por todas as Casas. Um evento malformado que o fizesse lancar travaria o
  barramento inteiro (poison + head-of-line de TODO evento de id maior, de QUALQUER tenant).

  O defeito reproduzido: `shared.outbox.ente_id` e' NULLABLE, e `promover-em-tx!` repassava o `:ente-id` nil ao
  INSERT do feed (`integracao_ia.evento_saida.ente_id NOT NULL`) -> SQLException dentro da tx do relay.

  A prova e' o HEAD-OF-LINE (principio do padrao, memoria `oplenario-relay-poison`): com o malformado na fila,
  o evento SEGUINTE, de id maior, e' promovido. Sem essa asserção nada esta provado."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.integracao-ia.components.repositorio :as repo]
            [oplenario.integracao-ia.diplomat.consumers :as consumers]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/map->RepoIntegracaoIAPg {:datasource c})]
        (try (t) (finally (component/stop c)))))))

(defn- gravar!
  "Grava `ev` (envelope CRU de `eventos/evento`) numa tx PROPRIA: cada INSERT commita sozinho, entao a ordem de
  chamada decide a ordem de `id`, que e' a ordem que o relay drena (`ORDER BY id`). Fora de `com-tenant*` de
  proposito: evento de `ente-id` nil nao pode ser gravado por `com-tenant*`."
  [ev]
  (jdbc/with-transaction [tx *ds*] (eventos/emitir! (outbox/bus) tx ev)))

(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))

(defn- feed-do-ente [ente]
  (loop [cursor 0 acc []]
    (let [pag (repo/listar-eventos *repo* cursor 500)]
      (if (empty? pag)
        (filterv #(= ente (:ente-id %)) acc)
        (recur (:seq (peek (vec pag))) (into acc pag))))))

(defn- pendentes-do-tipo [tipo]
  (:n (jdbc/execute-one! *ds* ["SELECT count(*) AS n FROM shared.outbox WHERE tipo = ? AND processed_at IS NULL" tipo]
                         {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))

(deftest evento-promovivel-com-ente-id-nil-nao-bloqueia-o-relay-e-o-seguinte-e-promovido
  (let [ente (random-uuid) pid (random-uuid)
        poison (eventos/evento "proposicao.protocolada" nil {:numero 7})
        bom    (eventos/evento "proposicao.protocolada" ente
                 {:proposicao-id pid :ementa "Dispoe sobre a merenda escolar."})]
    ;; ORDEM importa: o poison PRIMEIRO (id menor) e so' depois o bom (id maior)
    (gravar! poison)
    (gravar! bom)
    ;; o `nil` depois de `(drenar!)` descarta a contagem (inteiro, nunca nil): sem ele a asserção reprovaria SEMPRE,
    ;; provando o instrumento e nao o codigo (familia `oplenario-armadilhas-de-instrumento`).
    (with-log
      (is (nil? (try (drenar!) nil (catch Throwable e e)))
          "drenar! NAO lanca com um evento de ente-id nil na fila")
      (is (logged? 'oplenario.integracao-ia.components.repositorio :warn
                   #"ente-id ausente")
          "o descarte nao e' silencio: log :warn nomeando o evento descartado"))
    (is (= 0 (pendentes-do-tipo "proposicao.protocolada"))
        "o poison foi drenado (marcado processado), nao fica reprocessando para sempre")
    (is (= [pid] (mapv #(java.util.UUID/fromString (get-in % [:payload :proposicao-id])) (feed-do-ente ente)))
        "o evento SEGUINTE (id maior) FOI promovido ao feed — sem head-of-line block")))

(deftest evento-suspenso-ou-reativado-sem-ente-id-nao-bloqueia-o-relay
  ;; os dois handlers de cota ja' pulavam o ente-id nil (`when ente-id`); a prova trava o comportamento: o
  ;; seguinte de id maior (uma proposicao) e' promovido.
  (let [ente (random-uuid) pid (random-uuid)]
    (gravar! (eventos/evento "admin_sistema.casa.suspensa" nil {}))
    (gravar! (eventos/evento "admin_sistema.casa.reativada" nil {}))
    (gravar! (eventos/evento "proposicao.protocolada" ente {:proposicao-id pid :ementa "Institui a semana da agua."}))
    (is (nil? (try (drenar!) nil (catch Throwable e e))))
    (is (= 1 (count (feed-do-ente ente))) "o evento depois dos supratenant FOI promovido")))
