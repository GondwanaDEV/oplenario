(ns oplenario.sessoes.ata-db-test
  "INTEGRACAO (PG real) — a ATA publicada (Faixa A / A.6a, mig 0086): versoes MAX+1, retificacao exige motivo (decidido
  na versao da mesma tx), append-only (nunca muda nem some) e isolamento por Casa (RLS)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.ata :as ata]
            [oplenario.sessoes.db.sessao :as sessao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- nova-sessao! [ente]
  (tenancy/com-tenant* *ds* ente
    #(:id (sessao/agendar! % {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                              :tipo-sessao "ordinaria"}))))

(defn- publicar! [ente sid m]
  (tenancy/com-tenant* *ds* ente
    #(ata/publicar! % (merge {:ente-id ente :sessao-id sid :texto "Ata da sessao." :origem-redacao "redigida_externamente"
                              :conteudo-sha256 "sha256:x" :publicada-por (random-uuid)} m))))

(defn- tipo-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest versoes-e-retificacao
  (let [ente (random-uuid) sid (nova-sessao! ente)
        v1 (publicar! ente sid {:motivo-retificacao "ignorado na primeira"})]
    (is (= 1 (:versao v1)))
    (is (nil? (:motivo-retificacao v1)) "a primeira versao nao e' retificacao: nao guarda motivo")
    (testing "retificar sem motivo e' recusado (nada gravado)"
      (is (= :validacao/retificacao-sem-motivo (tipo-do-erro #(publicar! ente sid {}))))
      (is (= :validacao/retificacao-sem-motivo (tipo-do-erro #(publicar! ente sid {:motivo-retificacao "  "})))))
    (let [v2 (publicar! ente sid {:texto "Ata corrigida." :motivo-retificacao "nome do vereador errado"})]
      (is (= [2 "nome do vereador errado"] [(:versao v2) (:motivo-retificacao v2)])))
    (let [[atual versoes] (tenancy/com-tenant* *ds* ente
                            (fn [tx] [(ata/atual tx ente sid) (ata/listar-versoes tx ente sid)]))]
      (is (= [2 "Ata corrigida."] [(:versao atual) (:texto atual)]) "a vigente e' a mais alta")
      (is (= [2 1] (mapv :versao versoes)))
      (is (not-any? :texto versoes) "o historico nao carrega o texto"))))

(deftest gerada-exige-rascunho
  (let [ente (random-uuid) sid (nova-sessao! ente)]
    (is (thrown? Exception (publicar! ente sid {:origem-redacao "gerada_automaticamente"}))
        "o CHECK do banco: ata partida da IA sem o rascunho de origem nao existe")))

(deftest append-only
  (let [ente (random-uuid) sid (nova-sessao! ente)
        {:keys [id]} (publicar! ente sid {})]
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente
                             #(jdbc/execute! % ["UPDATE sessoes.ata SET texto = 'outra' WHERE ente_id = ? AND id = ?" ente id]))))
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente
                             #(jdbc/execute! % ["DELETE FROM sessoes.ata WHERE ente_id = ? AND id = ?" ente id]))))))

(deftest outra-casa-nao-ve
  (let [ente (random-uuid) sid (nova-sessao! ente)]
    (publicar! ente sid {})
    (is (nil? (tenancy/com-tenant* *ds* (random-uuid) #(ata/atual % ente sid))))))
