(ns oplenario.sessoes.pauta-materia-unica-test
  "INTEGRACAO (PG real + a cadeia HTTP de `rotas/montar`): a mesma materia nao entra duas vezes ATIVA na pauta da mesma
  sessao (mig 20261004000189). Antes, `POST /sessoes/:id/pauta/itens` aceitava a mesma proposicao N vezes.

  Prova: (1) o HTTP devolve 409 em portugues, nunca 500; (2) a CORRIDA — dois pedidos simultaneos da mesma materia,
  um passa e o outro recebe o conflito (a garantia e' do indice, nao de uma checagem em codigo); (3) o que e'
  legitimo continua passando (item retirado volta, outra materia, a mesma materia em outra sessao, itens de texto
  repetidos, reordenar); (4) a migration NAO muta dado: duplicata LEGADA (anterior ao corte do indice) fica intacta, o indice e'
  criado mesmo assim, e a materia que so' existe num item legado e' barrada pela checagem em codigo (409)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-s]
            [oplenario.sessoes.db.pauta :as pauta])
  (:import (java.util.concurrent CountDownLatch)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *ses* nil)
(def ^:dynamic *svc* nil)

(def secretaria (random-uuid))

(def ^:private sem-qualificar {:builder-fn rs/as-unqualified-maps})

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"secretario"}})))

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (let [ses (repo-s/->RepoSessoesPg c (outbox/bus))]
        (binding [*ds* (:ds c) *ses* ses
                  *svc* (-> (http/servico (config/carregar)
                                          (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade)
                                                         :repo-sessoes ses :objeto-store nil})
                                          it/globais)
                            ph/create-server ::ph/service-fn)]
          (try (t) (finally (component/stop c))))))))

(defn- cab [ente]
  {"authorization" (str "Bearer " (json/write-value-as-string
                                    {:sub "u" :ente-id (str ente) :identidade-id (str secretaria)}))
   "Content-Type" "application/json"})

(defn- chamar [ente metodo url corpo]
  (let [r (apply pt/response-for *svc* metodo url
                 (concat [:headers (cab ente)] (when corpo [:body (json/write-value-as-string corpo)])))]
    {:status (:status r)
     :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(defn- sessao! [ente]
  (:id (repo-s/agendar-sessao! *ses* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                           :tipo-sessao "ordinaria" :modalidade "presencial"
                                           :agendada-para (java.time.Instant/parse "2026-10-07T12:00:00Z")})))

(defn- incluir [ente sid prop & [fase]]
  (chamar ente :post (str "/sessoes/" sid "/pauta/itens")
          {"fase" (or fase "ordem_do_dia") "tipo-item" "proposicao" "proposicao-id" (str prop)}))

(defn- ativos
  "Itens ATIVOS da pauta da sessao, lidos do banco (a verdade, nao a resposta HTTP)."
  [ente sid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (jdbc/execute! tx ["SELECT i.id, i.proposicao_id FROM sessoes.pauta_item i
                            JOIN sessoes.pauta_sessao p ON p.ente_id = i.ente_id AND p.id = i.pauta_sessao_id
                           WHERE i.ente_id = ? AND p.sessao_id = ? AND i.ativo" ente sid] sem-qualificar))))

;; ---------- 1. o HTTP ----------

(deftest incluir-a-mesma-materia-duas-vezes-devolve-409-em-portugues
  (let [ente (random-uuid) sid (sessao! ente) prop (random-uuid)
        a (incluir ente sid prop)
        b (incluir ente sid prop)]
    (is (= 201 (:status a)) "a primeira inclusao passa")
    (is (= 409 (:status b)) "a segunda e' conflito — nao 500")
    (is (= "Esta matéria já está na pauta desta sessão." (get-in b [:corpo :erro])))
    (is (= 1 (count (ativos ente sid))) "so' um item ativo no banco")
    (testing "em outra fase tambem: a materia e' uma por sessao"
      (is (= 409 (:status (incluir ente sid prop "expediente")))))))

(deftest o-que-e-legitimo-continua-passando
  (let [ente (random-uuid) sid (sessao! ente) outra-sessao (sessao! ente) prop (random-uuid)
        item (:id (:corpo (incluir ente sid prop)))]
    (testing "outra materia na mesma sessao"
      (is (= 201 (:status (incluir ente sid (random-uuid))))))
    (testing "a mesma materia em OUTRA sessao (1a e 2a discussao)"
      (is (= 201 (:status (incluir ente outra-sessao prop)))))
    (testing "itens sem materia se repetem"
      (let [texto {"fase" "expediente" "tipo-item" "leitura" "texto-descricao" "Leitura do ofício 12/2026"}
            url   (str "/sessoes/" sid "/pauta/itens")]
        (is (= 201 (:status (chamar ente :post url texto))))
        (is (= 201 (:status (chamar ente :post url texto))))))
    (testing "reordenar o item nao colide com o proprio indice"
      (let [lv (-> (ativos ente sid) first :id) ; so' para garantir que ha' item
            it (tenancy/com-tenant* *ds* ente
                 #(jdbc/execute-one! % ["SELECT ordem, lock_version FROM sessoes.pauta_item WHERE ente_id = ? AND id = ?"
                                        ente (parse-uuid item)] sem-qualificar))]
        (is (some? lv))
        (is (= 200 (:status (chamar ente :patch (str "/sessoes/" sid "/pauta/itens/" item)
                                    {"nova-ordem" (+ 5 (:ordem it)) "lock-version" (:lock_version it)}))))))
    (testing "retirada o item, a materia pode voltar (e so' uma vez de novo)"
      (let [r (chamar ente :delete (str "/sessoes/" sid "/pauta/itens/" item)
                      {"tipo" "exclusao" "lock-version" 1})]
        (is (= 200 (:status r)) (pr-str r)))
      (is (= 201 (:status (incluir ente sid prop))) "volta")
      (is (= 409 (:status (incluir ente sid prop))) "mas so' uma vez"))))

;; ---------- 2. a corrida ----------

(deftest duas-inclusoes-simultaneas-nao-passam-as-duas
  (doseq [_ (range 8)]
    (let [ente (random-uuid) sid (sessao! ente) prop (random-uuid)
          pronto (CountDownLatch. 2) largada (CountDownLatch. 1)
          tentar (fn []
                   (future
                     (.countDown pronto) (.await largada)
                     (try (repo-s/adicionar-item-na-sessao! *ses* ente
                            {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia" :tipo-item "proposicao"
                             :proposicao-id prop})
                          :ok
                          (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e))))))
          fs [(tentar) (tentar)]]
      (.await pronto) (.countDown largada)
      (let [res (frequencies (map deref fs))]
        (is (= {:ok 1 :conflito/pauta-materia-duplicada 1} res)
            "um passa, o outro recebe o conflito de dominio (e nao um 500 cru do banco)")
        (is (= 1 (count (ativos ente sid))))))))

(deftest o-indice-e-a-garantia-mesmo-sem-a-checagem-do-codigo
  ;; o INSERT direto (por fora de `adicionar-item!`) tambem e' barrado: a garantia e' do banco.
  (let [ente (random-uuid) sid (sessao! ente) prop (random-uuid)]
    (repo-s/adicionar-item-na-sessao! *ses* ente {:id (random-uuid) :sessao-id sid :fase "ordem_do_dia"
                                                  :tipo-item "proposicao" :proposicao-id prop})
    (let [pid (tenancy/com-tenant* *ds* ente #(:id (pauta/buscar-pauta-por-sessao % ente sid)))]
      (is (thrown-with-msg? Exception #"uq_pauta_item_materia_ativa"
            (tenancy/com-tenant* *ds* ente
              #(jdbc/execute-one! % ["INSERT INTO sessoes.pauta_item (id, ente_id, pauta_sessao_id, fase, tipo_item,
                                                                       proposicao_id, ordem, efetivado_em)
                                      VALUES (gen_random_uuid(), ?, ?, 'ordem_do_dia', 'proposicao', ?, 9, now())"
                                     ente pid prop])))))))

;; ---------- 3. a migration NAO muta dado: duplicata LEGADA fica como esta' ----------

(defn- statements-da-migration []
  (->> (str/split (slurp (io/resource "migrations/20261004000189-sessoes-pauta-materia-unica.up.sql")) #"--;;")
       (map str/trim)
       (remove str/blank?)))

(defn- aplicar-migration! []
  (jdbc/with-transaction [tx *ds*]
    (doseq [s (statements-da-migration)] (jdbc/execute-one! tx [s]))))

(defn- legado!
  "Item ANTERIOR ao corte do indice (criado_em 2026-09-01), inserido por fora de `adicionar-item!` — como os que
  ja' existem em bases antigas. `ativo` explicito."
  [ente sid prop ativo]
  (let [id (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      #(jdbc/execute-one! % ["INSERT INTO sessoes.pauta_item (id, ente_id, pauta_sessao_id, fase, tipo_item,
                                                               proposicao_id, ordem, ativo, efetivado_em, criado_em)
                              VALUES (?, ?, ?, 'ordem_do_dia', 'proposicao', ?, 1, ?, now(), timestamptz '2026-09-01 12:00:00+00')"
                             id ente (:id (pauta/garantir-pauta! % {:ente-id ente :sessao-id sid})) prop ativo]))
    id))

(defn- estado-das-linhas [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      {:itens (jdbc/execute! tx ["SELECT id, ativo, lock_version, atualizado_em, ordem FROM sessoes.pauta_item
                                   WHERE ente_id = ? ORDER BY id" ente] sem-qualificar)
       :alteracoes (:n (jdbc/execute-one! tx ["SELECT count(*) AS n FROM sessoes.pauta_alteracao WHERE ente_id = ?" ente]
                                          sem-qualificar))})))

(defn- indice-existe? []
  (some? (jdbc/execute-one! *ds* ["SELECT 1 FROM pg_indexes WHERE schemaname = 'sessoes'
                                    AND indexname = 'uq_pauta_item_materia_ativa'"])))

(deftest a-migration-nao-altera-duplicata-legada-e-cria-o-indice
  (let [ente (random-uuid) sid (sessao! ente) dup (random-uuid)]
    ;; estado "de antes": sem o indice, com a materia duplicada em itens legados (e um retirado)
    (jdbc/execute-one! *ds* ["DROP INDEX IF EXISTS sessoes.uq_pauta_item_materia_ativa"])
    (try
      (legado! ente sid dup true) (legado! ente sid dup true) (legado! ente sid dup true) (legado! ente sid (random-uuid) false)
      ;; duplicata RECENTE (criado_em = agora, sem o indice): com corte de data fixa ela cairia dentro do indice e o
      ;; CREATE INDEX derrubaria o deploy. O corte e' o instante da migration, entao ela tambem fica de fora.
      (let [recente (random-uuid)
            pid (tenancy/com-tenant* *ds* ente #(:id (pauta/garantir-pauta! % {:ente-id ente :sessao-id sid})))]
        (dotimes [_ 2]
          (tenancy/com-tenant* *ds* ente
            #(jdbc/execute-one! % ["INSERT INTO sessoes.pauta_item (id, ente_id, pauta_sessao_id, fase, tipo_item,
                                                                     proposicao_id, ordem, efetivado_em)
                                    VALUES (gen_random_uuid(), ?, ?, 'ordem_do_dia', 'proposicao', ?, 5, now())"
                                   ente pid recente]))))
      (let [antes (estado-das-linhas ente)]
        (is (= 6 (count (:itens antes))))
        (aplicar-migration!)
        (is (indice-existe?) "o CREATE INDEX nao falha numa base com duplicata legada")
        (is (= antes (estado-das-linhas ente))
            "nenhuma linha mudou (ativo, lock_version, atualizado_em), nenhuma foi apagada, e nenhuma linha nova em pauta_alteracao")
        (is (= 5 (count (filter :ativo (:itens (estado-das-linhas ente))))) "as 3 legadas e as 2 recentes seguem ativas"))
      (finally (aplicar-migration!)))))

(deftest materia-que-so-existe-num-item-legado-da-409
  (let [ente (random-uuid) sid (sessao! ente) prop (random-uuid)]
    (legado! ente sid prop true)
    (is (indice-existe?))
    (let [r (incluir ente sid prop)]
      (is (= 409 (:status r)) "o indice nao ve o item antigo; quem barra e' a checagem em codigo")
      (is (= "Esta matéria já está na pauta desta sessão." (get-in r [:corpo :erro]))))
    (is (= 1 (count (ativos ente sid))) "nada novo entrou")
    (testing "retirado o legado, a materia pode voltar"
      (tenancy/com-tenant* *ds* ente
        #(jdbc/execute-one! % ["UPDATE sessoes.pauta_item SET ativo = false WHERE ente_id = ? AND proposicao_id = ?" ente prop]))
      (is (= 201 (:status (incluir ente sid prop)))))))
