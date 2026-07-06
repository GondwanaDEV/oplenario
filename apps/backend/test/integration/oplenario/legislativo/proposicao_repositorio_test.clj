(ns oplenario.legislativo.proposicao-repositorio-test
  "Onda B Slice 2 — prova a COMPOSICAO numa unica tx: protocolar!+texto, editar-proposicao!+texto,
  buscar-proposicao-detalhe. Repo real (Postgres+bus), sem HTTP — mesmo padrao de
  votacao_eventos_repo_test.clj (`->RepoLegislativoPg` construido direto com o Component de datasource
  JA STARTADO + `outbox/bus`, sem passar pelo `repositorio`/Stuart Sierra `using` — esse fio so' e'
  montado pelo `oplenario.sistema` em producao)."
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

(deftest protocolar-com-texto-cria-e-promove-versao-na-mesma-tx
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :texto "## Art. 1o"})
        {:keys [proposicao texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (= "vigente" (:estado-versao texto)))
    (is (= "## Art. 1o" (:texto-inline texto)))
    (is (= (:id r) (:id proposicao)))))

(deftest protocolar-sem-texto-nao-cria-versao
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X"})
        {:keys [texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (nil? texto))))

(deftest protocolar-com-texto-grande-demais-lanca
  (let [ente (random-uuid) grande (apply str (repeat 40000 "a"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"objeto_store"
                          (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                                          :uf "CE" :municipio-nome "Fortaleza" :ementa "X"
                                                          :texto grande})))))

(deftest editar-proposicao-com-texto-promove-nova-versao-origem-edicao
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :texto "## Art. 1o"})
        ;; protocolar!+texto ja' bombeou lock_version p/ 1 (texto/promover! reaponta o pointer, +1) — le' o
        ;; lock-version ATUAL em vez de assumir 0 (assuncao estaria estala/errada por causa da composicao).
        lock-atual (:lock-version (:proposicao (repo/buscar-proposicao-detalhe *repo* ente (:id r))))]
    (repo/editar-proposicao! *repo* ente {:id (:id r) :lock-version lock-atual :ementa "Y" :texto "## Art. 1o (rev)"
                                          :updated-by (random-uuid)})
    (let [{:keys [proposicao texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
      (is (= "Y" (:ementa proposicao)))
      (is (= "## Art. 1o (rev)" (:texto-inline texto)))
      (is (= "edicao" (:origem-versao texto))))))
