(ns oplenario.kernel.sequencial-test
  "Numeracao canonica gapless por linha-contador (§22.9 Eixo 2): comeca em 1, incrementa por escopo,
  e' TENANT-scoped (ente do GUC via com-tenant*) e NAO deixa buraco no rollback. Postgres real."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

;; SEM fixture :each. Havia um `TRUNCATE shared.sequencial` aqui — global, sem escopo de tenant e sem
;; truncar as tabelas numeradas junto. Ele apagava o contador de TODOS os entes do banco e deixava as
;; linhas ja' numeradas para tras, quebrando permanentemente a numeracao de qualquer Casa que
;; compartilhasse o banco (ver `oplenario.kernel.sequencial-lint-test`, que hoje e' o gate de CI disso).
;; O TRUNCATE nem era necessario: todo teste deste ns isola por `(random-uuid)` como ente, e a RLS +
;; o GUC `app.ente_id` garantem que um ente nunca le nem bumpa o contador de outro.

(defn- prox [ente escopo]
  (tenancy/com-tenant* *ds* ente (fn [tx] (sequencial/proximo! tx escopo))))

(deftest sequencial-comeca-em-1-e-incrementa-por-escopo
  (let [ente (random-uuid)]
    (is (= 1 (prox ente "lei:2026")) "1o do escopo")
    (is (= 2 (prox ente "lei:2026")) "2o do escopo")
    (is (= 1 (prox ente "decreto:2026")) "escopo distinto comeca em 1")
    (is (= 1 (prox (random-uuid) "lei:2026")) "outro ente tem contador proprio (RLS + ente_id no GUC)")))

(deftest sequencial-e-gapless-no-rollback
  (let [ente (random-uuid)]
    (is (= 1 (prox ente "x")) "commit -> 1")
    (try
      (tenancy/com-tenant* *ds* ente
                           (fn [tx]
                             (sequencial/proximo! tx "x")     ; tomaria 2, mas a tx reverte
                             (throw (ex-info "rollback proposital" {}))))
      (catch clojure.lang.ExceptionInfo _ nil))
    (is (= 2 (prox ente "x")) "apos rollback do '2', o proximo e' 2 de novo — sem buraco (gapless)")))

;; ---------- reconciliacao (achado da T2 grupo B: contador atras das linhas ja' numeradas) ----------
;; `proximo!` sozinho nao tem como saber que ja' existem linhas numeradas que ele nao emitiu — e' o caso
;; de todo acervo legado importado por INSERT direto, e foi o caso da Casa da demo depois que um fixture
;; truncou `shared.sequencial` deixando as linhas para tras. Sem um piso, `proximo!` volta a 1, colide na
;; UNIQUE `(ente, ano, sequencial)`, e a colisao aborta a tx — revertendo o proprio incremento. O
;; contador nunca ultrapassa a colisao: 500 permanente. `reconciliar!` e' o piso explicito.

(defn- recon [ente escopo piso]
  (tenancy/com-tenant* *ds* ente (fn [tx] (sequencial/reconciliar! tx escopo piso))))

(deftest reconciliar-levanta-contador-ate-o-piso
  (let [ente (random-uuid)]
    (is (= 7 (recon ente "projeto_lei:2026" 7)) "sem contador ainda: reconciliar cria no piso")
    (is (= 8 (prox ente "projeto_lei:2026")) "o proximo emitido nao colide com a linha 7 ja' gravada")))

(deftest reconciliar-nunca-abaixa-contador
  (let [ente (random-uuid)]
    (is (= 1 (prox ente "lei:2026")) "contador anda para 1")
    (is (= 2 (prox ente "lei:2026")) "e para 2")
    (is (= 2 (recon ente "lei:2026" 1))
        "piso MENOR que o contador nao rebaixa — rebaixar reintroduziria a colisao que a reconciliacao existe para matar")
    (is (= 3 (prox ente "lei:2026")) "o proximo segue de onde o contador estava")))

(deftest reconciliar-e-idempotente-e-escopado-por-ente
  (let [ente-a (random-uuid) ente-b (random-uuid)]
    (is (= 5 (recon ente-a "mocao:2026" 5)))
    (is (= 5 (recon ente-a "mocao:2026" 5)) "repetir com o mesmo piso nao anda com o contador")
    (is (= 1 (prox ente-b "mocao:2026")) "reconciliar um ente NAO toca o contador de outro (GUC + RLS)")))
