(ns oplenario.transparencia.portal-republicar-rito-test
  "INTEGRACAO (PG real) — a carga do rito da faixa do portal para as materias anteriores a mig 20261005000262
  (`oplenario.portal-republicar-rito`): a materia que nao teve evento depois dela ficou com `rito` NULL e no mapa fixo.
  A carga grava o MESMO rito que o evento levaria, so' onde falta; e' idempotente; materia sem rito na Casa segue
  sem; o rito ja' projetado por um evento nunca e' trocado."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.portal-republicar-rito :as carga]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.diplomat.consumers :as consumers])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)
(def ^:dynamic *tr* nil)
(def ^:dynamic *reg* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *leg* (legislativo-repo/->RepoLegislativoPg c (outbox/bus))
                *tr* (transparencia-repo/->RepoTransparenciaPg c) *reg* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))

(defn- rito!
  "Rito de fixture em linha (recebida -> analise -> pronta); vocabulario ilustrativo, nao regulacao real."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [tid (random-uuid)]
        (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_da_carga" :versao 1
                                  :nome "Rito [FIXTURE]" :estado-inicial "recebida_na_mesa"})
        (doseq [[ch nome] [["recebida_na_mesa" "Recebida pela Mesa Diretora"]
                           ["analise_comissoes" "Em análise nas comissões"]
                           ["pronta_plenario" "Pronta para o Plenário"]]]
          (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nome :terminal false}))
        (doseq [[de para gatilho] [["recebida_na_mesa" "analise_comissoes" "despachar"]
                                   ["analise_comissoes" "pronta_plenario" "parecer"]]]
          (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado de :para-estado para
                                     :gatilho gatilho :guarda nil :ordem 1}))
        tid))))

(defn- protocolar! [ente]
  (:id (legislativo-repo/protocolar! *leg* ente
         {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
          :ementa "Materia anterior ao rito no evento" :autor-tipo "vereador" :autor-texto "Fulano de Tal"})))

(defn- rito-no-portal [ente pid] (:rito (transparencia-repo/buscar-materia *tr* ente pid)))

(defn- apagar-rito!
  "Deixa a materia como estava antes da mig 20261005000262: projetada, sem rito."
  [ente pid]
  (tenancy/com-tenant* *ds* ente
    #(jdbc/execute! % ["UPDATE transparencia.materia SET rito = NULL WHERE ente_id = ? AND proposicao_id = ?" ente pid])))

(deftest a-carga-grava-o-mesmo-rito-que-o-evento-levaria-so-onde-falta
  (let [ente (random-uuid) tid (rito! ente)
        antiga (protocolar! ente) atual (protocolar! ente)]
    (is (true? (:transicionou? (legislativo-repo/transicionar! *leg* ente *reg*
                                 {:proposicao-id antiga :template-id tid :gatilho "despachar"
                                  :agora (LocalDate/of 2026 3 1) :ator-id (random-uuid)}))))
    (drenar!)
    (let [do-evento (rito-no-portal ente antiga)
          da-outra (rito-no-portal ente atual)]
      (is (= "analise_comissoes" (get-in do-evento [:atual :chave])) "o evento levou o rito com a atual no destino")
      (apagar-rito! ente antiga)
      (is (nil? (rito-no-portal ente antiga)) "a materia antiga ficou no mapa fixo")
      (testing "a carga grava na antiga o MESMO rito do evento e nao toca na que ja' tem"
        (is (= {:sem-rito 1 :gravadas 1 :sem-rito-na-casa 0} (carga/republicar-rito! *ds* ente)))
        (is (= do-evento (rito-no-portal ente antiga)))
        (is (= da-outra (rito-no-portal ente atual))))
      (testing "rodar de novo nao muda nada"
        (is (= {:sem-rito 0 :gravadas 0 :sem-rito-na-casa 0} (carga/republicar-rito! *ds* ente)))))))

(deftest materia-sem-rito-na-casa-segue-no-mapa-fixo
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    (is (nil? (rito-no-portal ente pid)) "Casa sem rito cadastrado: o evento ja' veio sem")
    (is (= {:sem-rito 1 :gravadas 0 :sem-rito-na-casa 1} (carga/republicar-rito! *ds* ente)))
    (is (nil? (rito-no-portal ente pid)))))

(deftest a-carga-nao-sobrescreve-o-rito-que-um-evento-gravou-depois
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (apagar-rito! ente pid)
    ;; entre a leitura dos ids e a gravacao, um evento novo projeta o rito mais recente: a carga nao o troca
    (legislativo-repo/transicionar! *leg* ente *reg*
      {:proposicao-id pid :template-id tid :gatilho "despachar" :agora (LocalDate/of 2026 3 1) :ator-id (random-uuid)})
    (drenar!)
    (let [do-evento (rito-no-portal ente pid)]
      (is (false? (transparencia-repo/gravar-rito-ausente! *tr* ente pid
                    (assoc do-evento :atual (first (:etapas do-evento))))))
      (is (= do-evento (rito-no-portal ente pid))))))
