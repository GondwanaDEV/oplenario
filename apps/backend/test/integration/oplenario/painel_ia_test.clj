(ns oplenario.painel-ia-test
  "INTEGRACAO (PG real): B.9 / ADR-0014 — o ORCAMENTO de IA da Casa e o PAINEL da Casa. O operador define o orcamento
  (historico append-only; a definicao vai a IA no feed, na mesma transacao); o painel do admin da Casa mostra o
  orcamento, o consumo que o satelite mede e o que as pessoas fizeram com notas e propostas no mes — e, com a IA fora,
  mostra o que o core sabe, sem inventar gasto."
  (:require [oplenario.suporte-cpf :refer [cpf-valido]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.diplomat.http.in :as paineis-http]
            [oplenario.paineis.logic.ia :as logic-ia])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(defn- repo-integracao [] (repo-ia/map->RepoIntegracaoIAPg {:datasource *c*}))
(defn- repo-legislativo [] (repo-leg/->RepoLegislativoPg *c* (outbox/bus)))
(defn- repo-identidade [] (assoc (repo-id/repositorio) :datasource *c*))

(defn- erro [f] (try (f) nil (catch Exception e (or (:tipo (ex-data e)) :excecao))))

;; ---------- o orcamento ----------

(deftest o-operador-define-e-a-ia-recebe-no-feed
  (let [ente (random-uuid)
        ri (repo-integracao)]
    (is (nil? (repo-ia/orcamento-atual ri ente)) "sem orcamento, a Casa so' mede")
    (let [d1 (repo-ia/definir-orcamento! ri {:ente-id ente :mensal 100M :teto-duro 120M :moeda "USD"
                                             :definido-por "operador"})
          _ (Thread/sleep 5)
          d2 (repo-ia/definir-orcamento! ri {:ente-id ente :mensal 200.5M :teto-duro 250M :moeda "USD"
                                             :definido-por "operador"})
          feed (->> (repo-ia/listar-eventos ri 0 100000)
                    (filter #(and (= "OrcamentoIADefinido" (:tipo %)) (= ente (:ente-id %)))))]
      (is (= (:id d2) (:id (repo-ia/orcamento-atual ri ente))) "a definicao mais recente vale")
      (is (= [(str "OrcamentoIADefinido:v1:" (:id d1)) (str "OrcamentoIADefinido:v1:" (:id d2))] (mapv :chave feed)))
      (is (= {:mensal "200.5000" :teto-duro "250.0000" :moeda "USD"}
             (select-keys (:payload (last feed)) [:mensal :teto-duro :moeda]))
          "valores como texto decimal: o satelite le Decimal, sem float")
      (is (string? (:definido-em (:payload (last feed))))))
    (testing "teto abaixo do mensal nao entra; o historico nao se reescreve"
      (is (some? (erro #(repo-ia/definir-orcamento! ri {:ente-id ente :mensal 100M :teto-duro 50M :moeda "USD"
                                                         :definido-por "operador"}))))
      (is (some? (erro #(jdbc/execute! (:ds *c*) ["UPDATE integracao_ia.orcamento_ia SET mensal = 1"])))))
    (testing "o orcamento e' da Casa"
      (is (nil? (repo-ia/orcamento-atual ri (random-uuid)))))))

;; ---------- o mes do painel ----------

(deftest o-mes-civil-da-casa
  (is (= {:mes "2026-09" :desde (Instant/parse "2026-09-01T03:00:00Z") :ate (Instant/parse "2026-10-01T03:00:00Z")}
         (logic-ia/mes nil (Instant/parse "2026-10-01T02:00:00Z")))
      "01/10 as 02h UTC ainda e' setembro em Fortaleza")
  (is (= "2026-02" (:mes (logic-ia/mes "2026-02" (Instant/now)))))
  (is (= :validacao/invalido (erro #(logic-ia/mes "fevereiro" (Instant/now))))))

;; ---------- o painel ----------

(defn- pessoa! [ente & papeis]
  (let [iid (random-uuid)]
    (id/inserir! (:ds *c*) {:id iid :cpf (cpf-valido) :nome "Pessoa da Casa"})
    (tenancy/com-tenant* (:ds *c*) ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid
                         :tipo (if (some #{"admin_ente"} papeis) "admin_ente" "servidor")})
        (doseq [p papeis] (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel p}))))
    iid))

(defn- nota! [ente]
  (let [p (tenancy/com-tenant* (:ds *c*) ente
            (fn [tx] (proposicao/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                                 :uf "CE" :municipio-nome "Baturite" :ementa "Hortas escolares."
                                                 :autor-texto "Ver. Ana"})))]
    (controllers/registrar-nota-tecnica! (repo-legislativo) {:ente-id ente :via {:agente "conferencia-normativa"
                                                                                 :execucao-id (random-uuid)}}
                                         {:proposicao-id (:id p) :texto "Nota." :incerteza "normal" :modelo "fake:x"})))

(defn- proposta! [ente expira-em]
  (repo-ia/criar-proposta! (repo-integracao)
                           {:ente-id ente :execucao-id (random-uuid) :identidade-id (random-uuid)
                            :agente "assistente-da-casa" :ferramenta "protocolar_requerimento" :entrada {} :titulo "t"
                            :texto "x" :ritual "assinatura" :contaminada-por []
                            :expira-em (java.sql.Timestamp/from ^Instant expira-em)}))

(def ^:private consumo-do-satelite
  {:mes "2026-09" :estado "aviso" :gasto "85.5" :moeda "USD" :parcial true :execucoes 3
   :orcamento {:mensal "100" :teto-duro "120" :moeda "USD"}
   :por-operacao [{:operacao "ata.redigir" :execucoes 2 :indisponiveis 0 :custo "85.5" :aprovados 0 :editados 1
                   :descartados 0 :erros-reportados 1}
                  {:operacao "agente.responder" :execucoes 1 :indisponiveis 1 :custo "0"}]})

(defn- servico [consumo-ia]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (repo-identidade))
        ri (repo-integracao) rl (repo-legislativo)
        agora (Instant/now)]
    (-> (http/servico (config/carregar)
                      (paineis-http/rotas
                       {:auth auth :repo-paineis nil
                        :relogio (reify tempo/Relogio (agora [_] agora))
                        :ia {:orcamento-ia #(repo-ia/orcamento-atual ri %)
                             :consumo-ia consumo-ia
                             :desfechos-ia (fn [ente desde ate]
                                             {:notas (repo-leg/contar-notas-tecnicas rl ente desde ate)
                                              :propostas (repo-ia/contar-propostas ri ente desde ate)})}})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- pedir [svc ente pessoa]
  (let [r (pt/response-for svc :get "/paineis/ia"
                           :headers {"Authorization" (str "Bearer " (json/write-value-as-string
                                                                     {:sub "u" :ente-id (str ente)
                                                                      :identidade-id (str pessoa)}))})]
    {:status (:status r) :corpo (json/read-value (:body r) json/keyword-keys-object-mapper)}))

(deftest o-painel-da-ia-da-casa
  (let [ente (random-uuid)
        admin (pessoa! ente "admin_ente")
        secretaria (pessoa! ente "secretario")
        n1 (nota! ente)
        _ (nota! ente)
        _ (controllers/decidir-nota-tecnica! (repo-legislativo) {:ente-id ente :identidade-id admin} (:id n1)
                                             {:desfecho "descartada"})
        _ (proposta! ente (.plusSeconds (Instant/now) 3600))
        _ (proposta! ente (.minusSeconds (Instant/now) 3600))]
    (repo-ia/definir-orcamento! (repo-integracao) {:ente-id ente :mensal 100M :teto-duro 120M :moeda "USD"
                                                   :definido-por "operador"})
    (testing "com a IA de pe'"
      (let [{:keys [status corpo]} (pedir (servico (fn [_ _] consumo-do-satelite)) ente admin)]
        (is (= 200 status))
        (is (= {:mensal "100.0000" :teto-duro "120.0000" :moeda "USD"} (dissoc (:orcamento corpo) :definido-em))
            "o orcamento vem do core")
        (is (= ["aviso" "85.5" true 3] ((juxt :estado :gasto :parcial :execucoes) corpo)) "o consumo vem do satelite")
        (is (= {:operacao "agente.responder" :execucoes 1 :indisponiveis 1 :custo "0" :aprovados 0 :editados 0
                :descartados 0 :erros-reportados 0}
               (second (:por-capacidade corpo))))
        (is (= {:pendentes 1 :aproveitadas 0 :descartadas 1} (:notas-tecnicas corpo)))
        (is (= {:aguardando 1 :confirmadas 0 :recusadas 0 :expiradas 1} (:propostas corpo))
            "a proposta vencida conta como expirada mesmo sem ninguem te-la aberto")))
    (testing "com a IA fora: o que o core sabe aparece, o gasto nao e' inventado"
      (let [{:keys [status corpo]} (pedir (servico (fn [_ _] (throw (ex-info "fora" {:tipo :ia/indisponivel}))))
                                          ente admin)]
        (is (= 200 status))
        (is (false? (:consumo-disponivel corpo)))
        (is (nil? (:gasto corpo)))
        (is (some? (:orcamento corpo)))
        (is (= 1 (:pendentes (:notas-tecnicas corpo))))))
    (testing "o painel e' do admin da Casa"
      (is (= 403 (:status (pedir (servico (fn [_ _] consumo-do-satelite)) ente secretaria)))))))
