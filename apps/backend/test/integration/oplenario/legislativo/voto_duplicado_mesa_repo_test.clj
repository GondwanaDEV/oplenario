(ns oplenario.legislativo.voto-duplicado-mesa-repo-test
  "INTEGRACAO (PG real) — T2 grupo A achado #1 (ledger de prontidao Fase 8): `RepoLegislativoPg/registrar-
  voto!` (a rota da MESA registrando votos nominais em nome dos vereadores) NAO tratava o UNIQUE
  `votos_ente_id_votacao_id_vereador_id_key` — o 2o voto do MESMO vereador na MESMA votacao subia como
  PSQLException CRUA ate' o interceptor global -> 500 ('erro interno'). O irmao self-service
  `registrar-meu-voto!` JA' tinha o catch (`:conflito/voto-duplicado`) — este teste PROVA que a rota da Mesa
  agora espelha exatamente o mesmo comportamento, contra Postgres real (o UNIQUE e' constraint de banco;
  falsificar o Repo deixaria o gate sem rede — nunca provaria a exception real).

  Metodo (P1 do protocolo de verificacao): o teste tem de poder REPROVAR. `voto-duplicado-mesa-nao-vira-500`
  falha se o catch for removido do Repo (a PSQLException `duplicate key` sobe crua e o `is` de `:conflito/
  voto-duplicado` reprova; documentado na linha do RED abaixo)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Materia p/ voto duplicado"})))

(defn- abrir-nominal! [ente pid]
  (let [vid (random-uuid)]
    (repo/abrir-votacao! *repo* ente
      {:id vid :objeto-tipo "proposicao" :objeto-id pid :modalidade "nominal" :quorum-tipo "maioria_simples"})
    vid))

(deftest voto-duplicado-mesa-nao-vira-500
  (let [ente (random-uuid)
        pid  (protocolar! ente)
        vid  (abrir-nominal! ente pid)
        ver  (random-uuid)]
    ;; 1o voto (sim) -> registra normalmente.
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id ver :voto "sim"})
    ;; 2o voto do MESMO vereador na MESMA votacao (duplo-clique/retry do secretario) -> UNIQUE do banco.
    ;; ANTES do fix: `PSQLException` (SQLState 23505) subia CRUA daqui — `(catch clojure.lang.ExceptionInfo
    ;; e ...)` abaixo NUNCA capturava, e o teste reprovava com a excecao vazando do proprio `is`:
    ;;   org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint
    ;;   "votos_ente_id_votacao_id_vereador_id_key"
    ;; (linha do RED confirmada manualmente: remover o `try/catch` de `registrar-voto!` em
    ;; components/repositorio.clj reproduz esta excecao saindo do `is` abaixo.)
    (let [conflito? (try
                       (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id ver :voto "nao"})
                       false
                       (catch clojure.lang.ExceptionInfo e
                         (= :conflito/voto-duplicado (:tipo (ex-data e)))))]
      (is conflito? "2o voto do mesmo vereador -> :conflito/voto-duplicado (ex-info tratada, nunca PSQLException crua)"))))
