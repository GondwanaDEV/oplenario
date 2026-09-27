(ns oplenario.rotas-integracao-ia-test
  "INTEGRACAO (PG real): os seams do HOST para a fronteira com a IA (ADR-0008) — `rotas/contexto-para-ia` (sessao,
  segmentos e falas, com o nome do orador na composicao DA DATA da sessao) e `rotas/abrir-gravacao-para-ia`
  (so' gravacao vinculada; restrita nunca)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.db.gravacao :as gravacao]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.io ByteArrayInputStream)
           (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo-sessoes/map->RepoSessoesPg {:datasource c})]
        (try (t) (finally (component/stop c)))))))

(def ana #uuid "40000000-0000-0000-0000-000000000004")

(defn- cadastros-fake [datas]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (roster-da-casa [_ _ente data]
      (swap! datas conj data)
      [{:vereador-id ana :nome "Ana Maria Ribeiro" :nome-parlamentar "Ana Ribeiro" :estado-mandato "vigente"}])))

(defn- sessao! [ente tipo]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                :tipo-sessao tipo :modalidade "presencial"
                                :agendada-para (Instant/parse "2026-09-22T21:00:00Z")})))))

(defn- segmento! [ente sid restrito?]
  (let [id (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (gravacao/registrar-segmento! tx {:id id :ente-id ente :sessao-id sid :iniciou-em (Instant/parse "2026-09-22T20:50:00Z")
                                                  :motivo-inicio "inicio_sessao" :container-bruto-uri (str "gravacao/" id)
                                                  :audio-hash "ab12" :fonte-ingestao "gravacao_local_pos_sessao"
                                                  :acesso-restrito restrito?})))
    id))

(defn- fala! [ente sid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute-one! tx ["INSERT INTO sessoes.fala_executada (ente_id, sessao_id, orador_id, tipo_fala, fase,
                                      iniciou_em, encerrou_em, tempo_efetivamente_usado_segundos, efetivado_em)
                                     VALUES (?, ?, ?, 'principal', 'expediente', ?, ?, 300, now())"
                                    ente sid ana (Instant/parse "2026-09-22T21:10:00Z")
                                    (Instant/parse "2026-09-22T21:15:00Z")]))))

(deftest contexto-com-falas-e-nomes-da-data-da-sessao
  (let [ente (random-uuid) sid (sessao! ente "ordinaria") seg (segmento! ente sid false) datas (atom [])]
    (fala! ente sid)
    (let [c (rotas/contexto-para-ia *repo* (cadastros-fake datas) ente sid)]
      (is (= sid (get-in c [:sessao :id])))
      (is (= [seg] (map :id (:segmentos c))))
      (is (= [[ana (Instant/parse "2026-09-22T21:10:00Z")]] (map (juxt :orador-id :iniciou-em) (:falas c))))
      (is (= {ana "Ana Ribeiro"} (:nomes c)) "o nome parlamentar")
      (is (= [(LocalDate/parse "2026-09-22")] @datas)
          "a composicao DA DATA da sessao (18h de Fortaleza), nunca a de hoje"))
    (is (nil? (rotas/contexto-para-ia *repo* (cadastros-fake (atom [])) (random-uuid) sid))
        "outro tenant nao enxerga a sessao (RLS)")))

(defn- store [abertos]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify os/ObjetoStore
    (abrir [_ chave] (swap! abertos conj chave) (ByteArrayInputStream. (.getBytes "bruto")))))

(deftest abrir-gravacao-so-vinculada-e-nunca-restrita
  (let [ente (random-uuid) sid (sessao! ente "ordinaria") abertos (atom [])
        seg (segmento! ente sid false) restrita (segmento! ente sid true) solta (segmento! ente nil false)]
    (let [r (rotas/abrir-gravacao-para-ia *repo* (store abertos) ente seg)]
      (is (= "bruto" (slurp (:stream r))))
      (is (= "ab12" (:audio-hash r))))
    (is (= :restrita (rotas/abrir-gravacao-para-ia *repo* (store abertos) ente restrita)))
    (is (nil? (rotas/abrir-gravacao-para-ia *repo* (store abertos) ente solta)) "sem sessao: a IA nao a conhece")
    (is (nil? (rotas/abrir-gravacao-para-ia *repo* (store abertos) (random-uuid) seg)) "outro tenant")
    (is (= [(str "gravacao/" seg)] @abertos) "o store so' foi aberto para a gravacao liberada")))
