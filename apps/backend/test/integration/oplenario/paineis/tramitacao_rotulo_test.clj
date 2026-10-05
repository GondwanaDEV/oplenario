(ns oplenario.paineis.tramitacao-rotulo-test
  "INTEGRACAO (PG real) — o NOME que o rito da Casa da' a etapa (`template_estado.nome`) no quadro de tramitacao, no
  painel da Mesa e na aba Tramitacao da ficha interna. Antes os tres usavam so' o rotulo fixo por chave de estado
  (`rotularEstado` no front), enquanto a lista interna e a ficha ja' diziam o nome da Casa. Prova, pelo Repo real do
  legislativo e pelo relay: o quadro projeta o rotulo que `proposicao.protocolada`/`transicionou` ja' carregam
  (`estado-nome`/`para-nome`); estado que o rito nao declara zera o rotulo (nunca fica o da etapa anterior); o painel
  da Mesa leva o rotulo por estado; o historico da ficha diz o nome de origem e de destino; e o backfill da migration
  reconstroi EXATAMENTE o que o caminho por evento projeta."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.adapters.out.ficha-materia :as ficha-out]
            [oplenario.legislativo.components.repositorio :as leg]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.wire.out.ficha-materia :as ficha-wire]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.paineis.adapters.out.mesa :as mesa-out]
            [oplenario.paineis.adapters.out.tramitacao :as board-out]
            [oplenario.paineis.components.repositorio :as paineis]
            [oplenario.paineis.diplomat.consumers :as consumers])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)
(def ^:dynamic *paineis* nil)
(def ^:dynamic *registro-fatos* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *leg* (leg/->RepoLegislativoPg c (outbox/bus)) *paineis* (paineis/->RepoPaineisPg c)
                *registro-fatos* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))

(defn- rito!
  "Rito cujo NOME difere da CHAVE, com um destino (`sem_nome_no_rito`) que o rito NAO declara como estado — e' o caso
  em que o rotulo tem de ZERAR. Fixture ilustrativa, nao regulacao real."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [tid (random-uuid)]
        (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_fixture" :versao 1
                                  :nome "Rito [FIXTURE]" :estado-inicial "recebida_na_mesa"})
        (doseq [[ch nome] [["recebida_na_mesa" "Recebida pela Mesa Diretora"]
                           ["analise_comissoes" "Em análise nas comissões"]]]
          (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nome :terminal false}))
        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "recebida_na_mesa"
                                   :para-estado "analise_comissoes" :gatilho "despachar" :guarda nil :ordem 1})
        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "analise_comissoes"
                                   :para-estado "sem_nome_no_rito" :gatilho "sumir" :guarda nil :ordem 1})
        tid))))

(defn- protocolar! [ente]
  (:id (leg/protocolar! *leg* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                    :municipio-nome "Fortaleza" :ementa "Dispoe sobre as hortas"
                                    :autor-tipo "vereador" :autor-texto "Fulana"})))

(defn- transicionar! [ente tid pid gatilho]
  (is (true? (:transicionou? (leg/transicionar! *leg* ente *registro-fatos*
                               {:proposicao-id pid :template-id tid :gatilho gatilho
                                :agora (LocalDate/of 2026 3 1)})))))

(defn- rotulos-no-quadro [ente]
  (into {} (map (juxt :proposicao-id :rotulo-estado)) (:itens (paineis/tramitacao-board *paineis* ente))))

(deftest o-quadro-guarda-o-nome-que-o-rito-da-a-etapa
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (testing "no protocolo: o nome do estado inicial no rito"
      (is (= {pid "Recebida pela Mesa Diretora"} (rotulos-no-quadro ente))))
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (testing "na transicao: o nome do destino"
      (is (= {pid "Em análise nas comissões"} (rotulos-no-quadro ente))))
    (testing "a rota do quadro devolve o campo"
      (let [wire (board-out/tramitacao-board->wire (paineis/tramitacao-board *paineis* ente))]
        (is (= ["Em análise nas comissões"] (mapv :rotulo-estado (:itens wire))))))
    (testing "o painel da Mesa leva o rotulo por estado"
      (let [rollups (paineis/dashboard-mesa *paineis* ente)
            wire (mesa-out/mesa->wire rollups {:indisponivel true} {:indisponivel true} {:indisponivel true}
                                      {:indisponivel true})]
        (is (= [{:estado "analise_comissoes" :n 1 :rotulo-estado "Em análise nas comissões"}]
               (get-in wire [:tramitacao :por-estado])))))
    (transicionar! ente tid pid "sumir")
    (drenar!)
    (testing "destino que o rito nao declara: o rotulo ZERA (nunca fica o da etapa anterior)"
      (is (= {pid nil} (rotulos-no-quadro ente))))))

(deftest sem-rito-o-rotulo-fica-nulo-e-a-tela-usa-o-fixo
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    (is (= {pid nil} (rotulos-no-quadro ente)))
    (let [rollups (paineis/dashboard-mesa *paineis* ente)]
      (is (= [nil] (mapv :rotulo-estado (:tramitacao rollups)))))))

(deftest o-painel-da-mesa-nao-escolhe-entre-dois-nomes-para-o-mesmo-estado
  ;; dois ritos podem dar nomes diferentes a mesma chave: o painel agrupa por chave, entao nao ha' UM nome certo
  (let [ente (random-uuid) pid-a (random-uuid) pid-b (random-uuid)]
    (jdbc/with-transaction [tx *ds*]
      (doseq [[pid nome] [[pid-a "Na Mesa"] [pid-b "Recebida"]]]
        (paineis/projetar-evento! tx {:tipo "proposicao.protocolada" :ente-id ente
                                      :payload {:proposicao-id (str pid) :tipo "projeto_lei" :ano 2026 :sequencial 1
                                                :urn-lex "urn:x" :ementa "E" :estado "recebida" :estado-nome nome}})))
    (is (= [{:estado "recebida" :n 2 :rotulo-estado nil}]
           (mapv #(select-keys % [:estado :n :rotulo-estado]) (:tramitacao (paineis/dashboard-mesa *paineis* ente)))))))

(deftest o-historico-da-ficha-diz-o-nome-de-origem-e-de-destino
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (transicionar! ente tid pid "despachar")
    (transicionar! ente tid pid "sumir")
    (let [ficha (leg/ficha-completa-da-proposicao *leg* ente pid)
          wire (mapv #'ficha-out/tramitacao-item->wire (:tramitacao ficha))]
      (is (every? #(m/validate ficha-wire/HistoricoTramitacaoItemOut %) wire))
      (is (= [["Recebida pela Mesa Diretora" "Em análise nas comissões"]
              ["Em análise nas comissões" nil]]
             (mapv (juxt :de-nome :para-nome) wire))
          "estado que o rito nao declara sai nulo; a tela cai no rotulo fixo"))))

;; ---------- o backfill da migration == o caminho por evento ----------

(def ^:private statements-da-migration
  (delay
    (->> (str/split (slurp (clojure.java.io/resource "migrations/20261005000400-paineis-tramitacao-rotulo-estado.up.sql"))
                    #"--;;")
         (map #(str/trim (str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %)))))
         (remove str/blank?))))

(deftest o-backfill-reconstroi-exatamente-o-que-o-caminho-por-evento-projeta
  (let [ente (random-uuid) tid (rito! ente)
        parada (protocolar! ente) andou (protocolar! ente) sumiu (protocolar! ente)]
    (transicionar! ente tid andou "despachar")
    (transicionar! ente tid sumiu "despachar")
    (transicionar! ente tid sumiu "sumir")
    (drenar!)
    (let [tudo (fn [tx] (jdbc/execute! tx ["SELECT proposicao_id, estado, rotulo_estado FROM paineis.tramitacao
                                            WHERE ente_id = ? ORDER BY proposicao_id" ente]))
          rodar! (fn [tx] (doseq [s @statements-da-migration] (jdbc/execute! tx [s]))
                   (jdbc/execute! tx ["ALTER TABLE paineis.tramitacao NO FORCE ROW LEVEL SECURITY"]))]
      (jdbc/with-transaction [tx *ds* {:rollback-only true}]
        (jdbc/execute! tx ["ALTER TABLE paineis.tramitacao NO FORCE ROW LEVEL SECURITY"])
        (let [antes (tudo tx)]
          (is (= #{"Recebida pela Mesa Diretora" "Em análise nas comissões" nil}
                 (set (map :tramitacao/rotulo_estado antes))))
          (testing "rodar o backfill POR CIMA do que o evento ja' projetou nao muda nada"
            (rodar! tx)
            (is (= antes (tudo tx))))
          (testing "apagado o rotulo, o backfill o reconstroi IGUAL"
            (jdbc/execute! tx ["UPDATE paineis.tramitacao SET rotulo_estado = NULL WHERE ente_id = ?" ente])
            (rodar! tx)
            (is (= antes (tudo tx))))
          (testing "projecao atrasada (estado do quadro diferente do da materia): o backfill nao inventa"
            (jdbc/execute! tx ["UPDATE paineis.tramitacao SET rotulo_estado = NULL, estado = 'recebida_na_mesa'
                                WHERE ente_id = ? AND proposicao_id = ?" ente andou])
            (rodar! tx)
            (is (nil? (:tramitacao/rotulo_estado
                       (first (jdbc/execute! tx ["SELECT rotulo_estado FROM paineis.tramitacao
                                                  WHERE ente_id = ? AND proposicao_id = ?" ente andou])))))))))))
