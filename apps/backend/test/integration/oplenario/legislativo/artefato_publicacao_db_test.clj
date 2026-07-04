(ns oplenario.legislativo.artefato-publicacao-db-test
  "INTEGRACAO (PG real): F6c Slice 4a — db/ do artefato de publicacao oficial ('DO-lite'). APPEND-ONLY IMUTAVEL:
  re-geracao = NOVA versao (inserir-versionada! MAX+1 atomico); sem UPDATE/DELETE (grant so' SELECT+INSERT). RLS
  isola por ente_id (FORCE). A FK (ente_id, norma_id) -> legislativo.norma exige uma norma REAL do tenant."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.artefato-publicacao :as artefato]
            [oplenario.legislativo.db.autografo :as autografo]
            [oplenario.legislativo.db.norma :as norma]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao-executiva :as exec]
            [oplenario.legislativo.models.artefato-publicacao :as mod]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

;; cria uma norma PUBLICADA real (satisfaz a FK (ente_id, norma_id)) e devolve o norma-id.
(defn- norma-publicada! [tx ente]
  (let [pid (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                       :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"}))
        {aid :id} (autografo/gerar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :ano 2026
                                        :texto-versao-id (random-uuid) :destinatario-texto "Prefeito"})
        {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})
        _ (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancionado" :updated-by nil :lock-version 0})
        {nid :id} (norma/promulgar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :autografo-id aid
                                        :tipo-norma "lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                                        :data-promulgacao (LocalDate/of 2026 6 28) :ementa "Dispoe sobre X"
                                        :texto-versao-id (random-uuid)})]
    (norma/publicar! tx {:id nid :ente-id ente :veiculo-publicacao "Diario Oficial do Municipio"
                         :updated-by nil :lock-version 0})
    nid))

(defn- artefato-base [ente norma-id]
  {:id (random-uuid) :ente-id ente :norma-id norma-id :spec-versao "do-lite-v0"
   :content-type "text/plain; charset=utf-8" :hash "sha256:abc" :objeto-store-ref "publicacoes/x.bin"
   :assinatura-algoritmo "STUB-ICP-v0" :assinatura-b64 "QUJD" :assinado-por nil})

(deftest insere-versionada-e-busca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [nid (norma-publicada! tx ente)
              r (artefato/inserir-versionada! tx (artefato-base ente nid))]
          (is (= 1 (:versao r)) "primeira versao")
          (is (= nid (:norma-id r)))
          (is (= "STUB-ICP-v0" (:assinatura-algoritmo r)))
          (is (nil? (:assinado-por r)) "assinado_por NULL nesta fatia (ator = borda autenticada, Slice 4b)")
          (is (some? (:criado-em r)) "carimba criado_em (DEFAULT do banco)")
          (is (m/validate mod/ArtefatoPublicacao r) "bate o model ArtefatoPublicacao")
          (let [b (artefato/buscar tx ente (:id r))]
            (is (= (:id r) (:id b)) "buscar devolve a linha")
            (is (= (:hash r) (:hash b)))
            (is (= 1 (:versao b))))
          (is (true? (artefato/existe? tx ente (:id r))))
          (is (false? (artefato/existe? tx ente (random-uuid)))))))))

(deftest re-geracao-incrementa-versao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [nid (norma-publicada! tx ente)]
          (is (= 1 (:versao (artefato/inserir-versionada! tx (artefato-base ente nid)))))
          (is (= 2 (:versao (artefato/inserir-versionada! tx (artefato-base ente nid)))) "MAX+1 atomico")
          (is (= [1 2] (mapv :versao (artefato/listar-por-norma tx ente nid))) "historico por versao ASC"))))))

(deftest fk-exige-norma-real
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? Exception
              (artefato/inserir-versionada! tx (artefato-base ente (random-uuid))))
            "norma_id inexistente -> viola a FK (nenhum artefato orfao apontando norma inexistente)")))))

(deftest rls-isola-por-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid) id-a (atom nil)]
    (tenancy/com-tenant* *ds* ente-a
      (fn [tx]
        (let [nid (norma-publicada! tx ente-a)
              r (artefato/inserir-versionada! tx (artefato-base ente-a nid))]
          (reset! id-a (:id r)))))
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx]
        (is (nil? (artefato/buscar tx ente-b @id-a)) "tenant B nao ve o artefato de A (RLS)")
        (is (false? (artefato/existe? tx ente-b @id-a)))))))
