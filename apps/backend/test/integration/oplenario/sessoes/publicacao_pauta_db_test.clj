(ns oplenario.sessoes.publicacao-pauta-db-test
  "INTEGRACAO (PG real): ADR-0019 fatia 3 — publicar a pauta pelo Repo-Component de SESSOES. Prova: a regra da Casa
  (padrao sem linha, upsert, CHECK, isolada por Casa na RLS); o ATO numa tx (vazia recusa; 1a = publicacao_inicial com
  a-titulo e avisos gravados; sem mudanca recusa; republicacao exige justificativa; a pauta que mudou entre a conferencia
  e o ato recusa); a versao anterior segue no historico; e a pauta oficial do portal (so' sessao publica e nao secreta,
  so' versao publica)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.components.repositorio-publicacao :as rp]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- sessao! [ente tipo]
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao tipo :modalidade "presencial"
                                          :agendada-para (java.time.Instant/parse "2026-10-07T12:00:00Z")})))

(defn- materia! [ente sid prop]
  (repo/adicionar-item-na-sessao! *repo* ente {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia"
                                               :tipo-item "proposicao" :proposicao-id prop}))

(defn- recusa [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:tipo :motivo]))))

(deftest a-regra-da-casa
  (let [ente (random-uuid) outra (random-uuid)]
    (is (nil? (rp/regra-da-pauta *repo* ente)) "sem linha: o padrao (a secretaria publica) vale no controller")
    (let [r (rp/definir-regra-da-pauta! *repo* ente {:quem-publica "presidente" :antecedencia-minima-horas 24})]
      (is (= ["presidente" 24] ((juxt :quem-publica :antecedencia-minima-horas) r))))
    (testing "upsert: a segunda vez troca"
      (rp/definir-regra-da-pauta! *repo* ente {:quem-publica "mesa" :antecedencia-minima-horas nil})
      (is (= ["mesa" nil] ((juxt :quem-publica :antecedencia-minima-horas) (rp/regra-da-pauta *repo* ente)))))
    (testing "RLS: a regra de uma Casa nao aparece na outra"
      (is (nil? (rp/regra-da-pauta *repo* outra)))
      (is (empty? (tenancy/com-tenant* *ds* outra
                    #(jdbc/execute! % ["SELECT * FROM sessoes.regra_pauta WHERE ente_id = ?" ente])))))
    (testing "o banco recusa quem publica fora do vocabulario e antecedencia fora do intervalo"
      (is (thrown? Exception (rp/definir-regra-da-pauta! *repo* ente {:quem-publica "vereador"})))
      (is (thrown? Exception (rp/definir-regra-da-pauta! *repo* ente {:quem-publica "mesa" :antecedencia-minima-horas 0}))))))

(deftest publicar-numera-congela-e-guarda-o-historico
  (let [ente (random-uuid)
        sid  (sessao! ente "ordinaria")
        p1   (random-uuid)
        avisos-de (fn [itens] (mapv (fn [it] {:tipo "sem-parecer-comissao" :item-id (:id it)
                                              :proposicao-id (:proposicao-id it)}) itens))]
    (testing "pauta vazia nao se publica"
      (is (= {:tipo :conflito/publicacao-pauta :motivo :pauta-vazia}
             (recusa #(rp/publicar-pauta! *repo* ente {:sessao-id sid :a-titulo "secretaria"})))))
    (materia! ente sid p1)
    (let [v1 (rp/publicar-pauta! *repo* ente {:sessao-id sid :a-titulo "secretaria" :avisos-de avisos-de
                                              :proposicoes-conferidas #{p1} :created-by ente})]
      (is (= [1 "publicacao_inicial" true "secretaria" nil]
             ((juxt :numero-versao :tipo-versao :publica :publicada-a-titulo :justificativa) v1)))
      (is (= [{:tipo "sem-parecer-comissao" :proposicao-id (str p1)}]
             (mapv #(select-keys % [:tipo :proposicao-id]) (:avisos v1))) "os avisos da tela ficam na versao")
      (is (= 1 (count (:snapshot v1))))
      (is (= ente (:created-by v1)))
      (is (some? (:publicado-em v1))))
    (testing "sem mudanca, nao ha' o que republicar"
      (is (= :sem-alteracao (:motivo (recusa #(rp/publicar-pauta! *repo* ente {:sessao-id sid :a-titulo "secretaria"
                                                                            :justificativa "de novo"}))))))
    (let [p2 (random-uuid)]
      (materia! ente sid p2)
      (testing "a pauta que mudou entre a conferencia (os avisos) e o ato: recusa"
        (is (= :pauta-mudou (:motivo (recusa #(rp/publicar-pauta! *repo* ente {:sessao-id sid :a-titulo "secretaria"
                                                                             :justificativa "incluida a p2"
                                                                             :proposicoes-conferidas #{p1}}))))))
      (testing "republicar exige dizer o que mudou"
        (is (= :justificativa-obrigatoria
               (:motivo (recusa #(rp/publicar-pauta! *repo* ente {:sessao-id sid :a-titulo "secretaria"}))))))
      (let [v2 (rp/publicar-pauta! *repo* ente {:sessao-id sid :a-titulo "presidente" :justificativa "incluida a p2"
                                                :proposicoes-conferidas #{p1 p2}})]
        (is (= [2 "republicacao" "incluida a p2" "presidente" []]
               ((juxt :numero-versao :tipo-versao :justificativa :publicada-a-titulo :avisos) v2)))
        (is (= 2 (count (:snapshot v2))))))
    (testing "a versao anterior segue no historico; a leitura da tela traz as duas e a pauta viva"
      (let [{:keys [itens versoes regra]} (rp/publicacao-da-pauta *repo* ente sid)]
        (is (nil? regra))
        (is (= 2 (count itens)))
        (is (= [1 2] (mapv :numero-versao versoes)))))
    (testing "a trava: dois atos simultaneos sobre a mesma pauta nao geram duas versoes iguais"
      (materia! ente sid (random-uuid))
      (let [r (doall (pmap (fn [_] (try (rp/publicar-pauta! *repo* ente {:sessao-id sid :a-titulo "secretaria"
                                                                         :justificativa "mais uma"})
                                        (catch clojure.lang.ExceptionInfo e (:motivo (ex-data e)))))
                           (range 2)))]
        (is (= 1 (count (filter map? r))) "uma publica")
        (is (= [:sem-alteracao] (remove map? r)) "a outra ve a versao da primeira")))))

(deftest a-pauta-oficial-do-portal
  (let [ente (random-uuid)
        publica (sessao! ente "ordinaria")
        secreta (sessao! ente "secreta")
        sem-pauta (sessao! ente "ordinaria")
        p (random-uuid)]
    (materia! ente publica p)
    (materia! ente secreta (random-uuid))
    (rp/publicar-pauta! *repo* ente {:sessao-id publica :a-titulo "secretaria"})
    (rp/publicar-pauta! *repo* ente {:sessao-id secreta :a-titulo "secretaria"})
    (let [linhas (rp/pautas-publicas *repo* ente)
          por-id (into {} (map (juxt :id identity)) linhas)]
      (is (= #{publica sem-pauta} (set (keys por-id))) "a secreta nunca aparece; a agendada sem pauta aparece")
      (is (= [1 1] ((juxt :versao :itens) (por-id publica))))
      (is (nil? (:versao (por-id sem-pauta))) "'pauta ainda nao publicada' e' dado, nao ausencia"))
    (let [{:keys [vigente versoes]} (rp/pauta-oficial *repo* ente publica)]
      (is (= 1 (:numero-versao vigente)))
      (is (= [(str p)] (mapv (comp str :proposicao-id) (:snapshot vigente))))
      (is (= [1] (mapv :numero-versao versoes))))
    (is (nil? (:vigente (rp/pauta-oficial *repo* ente sem-pauta))))))
