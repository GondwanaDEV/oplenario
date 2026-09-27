(ns oplenario.sessoes.leitura-ata-db-test
  "INTEGRACAO (PG real) — a LEITURA DA ATA ANTERIOR (Faixa A / A.7, mig 0088): qual e' a sessao anterior (a mais
  recente que gera ata e ja' acabou; secreta so' para secreta), o ato uma vez por sessao, so' sobre versao publicada
  e vigente, append-only."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.db.ata :as ata]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- sessao! [ente tipo estado quando]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [id (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                         :tipo-sessao tipo :agendada-para (Instant/parse quando)}))]
        (when (not= estado "agendada")
          (jdbc/execute! tx ["UPDATE sessoes.sessao SET estado = ?, aberta_em = ?::timestamptz,
                                encerrada_em = CASE WHEN ? IN ('encerrada','arquivada') THEN ?::timestamptz END
                              WHERE ente_id = ? AND id = ?"
                             estado quando estado quando ente id]))
        id))))

(defn- publicar! [ente sid texto]
  (tenancy/com-tenant* *ds* ente
    #(ata/publicar! % {:ente-id ente :sessao-id sid :texto texto :origem-redacao "redigida_externamente"
                       :conteudo-sha256 (str "sha256:" (hash texto)) :publicada-por (random-uuid)
                       :motivo-retificacao "correcao"})))

(defn- rs [] (repo/map->RepoSessoesPg {:datasource {:ds *ds*}}))
(defn- buscar [ente id] (tenancy/com-tenant* *ds* ente #(sessao/buscar % ente id)))

(deftest a-sessao-anterior-e-a-ata-vigente
  (let [ente (random-uuid)
        s10 (sessao! ente "ordinaria" "encerrada" "2026-09-10T18:00:00Z")
        s12 (sessao! ente "ordinaria" "encerrada" "2026-09-12T18:00:00Z")
        _ (sessao! ente "solene" "encerrada" "2026-09-13T18:00:00Z")
        _ (sessao! ente "secreta" "encerrada" "2026-09-14T18:00:00Z")
        hoje (sessao! ente "ordinaria" "aberta" "2026-09-15T18:00:00Z")]
    (testing "sem ata publicada: a anterior aparece, a ata nao"
      (let [r (repo/leitura-da-ata (rs) ente (buscar ente hoje))]
        (is (= s12 (get-in r [:anterior :id])) "a mais recente que gera ata; solene nao gera, secreta nao vale")
        (is (nil? (:ata r)))))
    (publicar! ente s12 "Ata v1")
    (publicar! ente s12 "Ata v2")
    (let [r (repo/leitura-da-ata (rs) ente (buscar ente hoje))]
      (is (= [2 "Ata v2"] ((juxt :versao :texto) (:ata r))) "so' a vigente se le"))
    (testing "sessao secreta le a secreta anterior"
      (let [sec (sessao! ente "secreta" "aberta" "2026-09-16T18:00:00Z")]
        (is (not= s12 (get-in (repo/leitura-da-ata (rs) ente (buscar ente sec)) [:anterior :id])))))
    (is (nil? (:anterior (repo/leitura-da-ata (rs) ente (buscar ente s10)))) "a primeira nao tem anterior")))

(deftest registra-uma-vez-so-e-so-a-versao-vigente
  (let [ente (random-uuid)
        ant (sessao! ente "ordinaria" "encerrada" "2026-09-12T18:00:00Z")
        hoje (sessao! ente "ordinaria" "aberta" "2026-09-15T18:00:00Z")
        _ (publicar! ente ant "Ata v1")
        s (buscar ente hoje)
        registrar #(repo/registrar-leitura-ata! (rs) ente (merge {:sessao s :ata-sessao-id ant :ata-versao 1
                                                                   :modo "dispensada" :registrada-por (random-uuid)} %))
        tipo #(try (%) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e))))]
    (is (= :conflito/ata-mudou (tipo #(registrar {:ata-versao 2}))) "versao que nao existe")
    (publicar! ente ant "Ata v2")
    (is (= :conflito/ata-mudou (tipo #(registrar {}))) "v1 deixou de ser a vigente")
    (let [l (registrar {:ata-versao 2})]
      (is (= ["dispensada" 2] ((juxt :modo :ata-versao) l)))
      (is (re-find #"^sha256:" (:ata-conteudo-sha256 l)) "o hash do texto lido fica gravado"))
    (is (= :conflito/leitura-registrada (tipo #(registrar {:ata-versao 2 :modo "presencial"}))))
    (is (= "dispensada" (get-in (repo/leitura-da-ata (rs) ente s) [:leitura :modo])))
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente
                             #(jdbc/execute! % ["DELETE FROM sessoes.leitura_ata WHERE ente_id = ?" ente])))
        "append-only")))
