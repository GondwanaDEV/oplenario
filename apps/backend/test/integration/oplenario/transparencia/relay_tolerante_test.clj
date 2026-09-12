(ns oplenario.transparencia.relay-tolerante-test
  "INTEGRACAO (PG real) — frente 'relay-tolerante'. O achado, MEDIDO e reproduzido de verdade (ver o
  commit de teste 8ae2902, `oplenario.kernel.outbox-test`): uma linha `proposicao.protocolada` com
  payload `{:numero 7}` no `shared.outbox` (formato que so' um teste ou um redrive escreveria — o
  produtor real e' fail-closed via Malli) estoura `{:pre ...}` em `db.materia/inserir!` DENTRO da tx do
  relay COMPARTILHADO, e o relay (kernel/outbox.clj, sem try em `drenar-um!`) reprocessa a MESMA linha
  para sempre — POISON, bloqueando HEAD-OF-LINE todo evento de id maior, de QUALQUER modulo (medido: 8
  erros num namespace VIZINHO, `paineis.notificacao-test`, so' por drenar DEPOIS).

  A prova que fecha esta frente: um evento malformado de id N nao bloqueia um evento VALIDO de id N+1
  no MESMO `drenar!` — essa e' a asserção que mostra que o head-of-line deixou de existir, nao so' que o
  handler nao lanca."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.components.repositorio :as repo]
            [oplenario.transparencia.db.materia :as db-materia]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(use-fixtures :each
  (fn [t]
    (jdbc/execute! *ds* ["TRUNCATE shared.outbox, shared.evento_consumido, transparencia.materia CASCADE"])
    (t)))

(defn- gravar!
  "Grava `ev` (envelope CRU de `eventos/evento` — sem a validacao Malli dos construtores de
  `events/proposicao.clj`, de proposito: e' o unico jeito de por um payload MALFORMADO no
  shared.outbox, o mesmo caminho que um teste antigo ou uma ferramenta de redrive tomaria) numa
  tx PROPRIA — cada INSERT commita sozinho, entao a ordem de chamada decide a ordem de `id`
  (BIGINT GENERATED ALWAYS AS IDENTITY), que e' a ordem que o relay drena (`ORDER BY id`)."
  [ev]
  (jdbc/with-transaction [tx *ds*] (eventos/emitir! (outbox/bus) tx ev)))

(defn- payload-valido [pid]
  {:proposicao-id (str pid) :tipo "projeto_lei" :ano 2026 :sequencial 1
   :urn-lex (str "urn:lex:br;ce;fortaleza:projeto.lei:2026;" (mod (.getMostSignificantBits ^java.util.UUID pid) 100000))
   :ementa "Dispoe sobre a materia de controle deste teste" :estado "protocolada"})

(defn- registro-do-projetor []
  (outbox/registrar {} "transparencia-portal" "proposicao.protocolada" repo/projetar-evento!))

(deftest evento-malformado-nao-trava-a-cabeca-da-fila-para-o-proximo
  (let [ente-malformado (random-uuid)
        ente-valido     (random-uuid)
        pid-valido      (random-uuid)
        ev-malformado   (eventos/evento "proposicao.protocolada" ente-malformado {:numero 7})
        ev-valido       (eventos/evento "proposicao.protocolada" ente-valido (payload-valido pid-valido))]
    ;; ORDEM importa: grava o malformado PRIMEIRO (id menor) e so' depois o valido (id maior) — e' essa
    ;; ordem que faz a asserção seguinte provar HEAD-OF-LINE, nao so' "nao lanca".
    (gravar! ev-malformado)
    (gravar! ev-valido)
    (with-log
      (is (= 2 (outbox/drenar! *ds* (registro-do-projetor)))
          "drena os DOIS numa passada so' — o malformado nao interrompe `drenar!` no meio")
      (is (logged? 'oplenario.transparencia.components.repositorio :error AssertionError #"payload malformado")
          "o descarte fica OBSERVAVEL em :error (nunca :info — isto e' anomalia)")
      (is (logged? 'oplenario.transparencia.components.repositorio :error AssertionError
                   (re-pattern (:idempotency-key ev-malformado)))
          "o log carrega a idempotency-key da linha exata que foi descartada")
      (is (logged? 'oplenario.transparencia.components.repositorio :error AssertionError #"proposicao.protocolada")
          "o log nomeia o tipo do evento descartado"))
    ;; A ASSERCAO QUE PROVA O HEAD-OF-LINE: o evento de id MAIOR foi de fato PROJETADO — se o relay
    ;; ainda travasse na cabeca da fila (o defeito antigo), esta materia jamais existiria.
    (is (some? (tenancy/com-tenant* *ds* ente-valido #(db-materia/buscar % ente-valido pid-valido)))
        "o evento seguinte (id maior) foi projetado — a fila nao ficou presa no malformado")
    (is (zero? (:contagem (jdbc/execute-one! *ds*
                            ["SELECT count(*)::int AS contagem FROM shared.outbox WHERE processed_at IS NULL"])))
        "as DUAS linhas saem marcadas processed_at — nenhuma fica pendente p/ retry eterno (nao virou poison)")))

(deftest erro-de-infra-propaga-em-vez-de-ser-descartado
  ;; O contraste que fecha a garantia: uma falha que NAO e' forma-de-payload (aqui, simulada — driver/
  ;; conexao/deadlock na vida real) tem de ATRAVESSAR o guard e chegar ao relay, que e' quem decide
  ;; reverter a tx e reter o evento p/ retry (o comportamento CORRETO p/ indisponibilidade transitoria).
  ;; Um guard que a engolisse converteria "o banco caiu por 2s" em "o evento sumiu para sempre".
  (let [ente (random-uuid)
        pid  (random-uuid)
        ev   (eventos/evento "proposicao.protocolada" ente (payload-valido pid))]
    (with-redefs [db-materia/inserir! (fn [_tx _m] (throw (java.sql.SQLException. "conexao caiu (simulado)")))]
      (is (thrown? java.sql.SQLException
            (tenancy/com-tenant* *ds* ente #(repo/projetar-evento! % ev)))
          "SQLException do driver PROPAGA — o guard nao e' um catch-Throwable disfarcado"))))
