(ns oplenario.fluxo-portal-test
  "E2E da BORDA HTTP do portal (F6c Slice 1, feature 16.5) — a vertical de rota ponta-a-ponta (adapters/in ->
  controller -> repo -> adapters/out -> wire/out). DB-free: RepoTransparencia FAKE (reify), mesmo precedente
  de fluxo_ouvidoria_test. TODA rota e' PUBLICA (sem auth, sem it/globais de erro/authz necessarios) — so'
  prova o contrato de wire (200/404) e o fail-closed do :ente malformado (400)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.transparencia.components.repositorio :as repo-transparencia]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))

(defn- fake-repo
  [{:keys [buscar-materia listar-materias buscar-norma norma-da-materia listar-normas]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-transparencia/RepoTransparencia
    (buscar-materia [_ _ente _pid] buscar-materia)
    (listar-materias [_ _ente _excl] listar-materias)
    (buscar-norma [_ _ente _nid] buscar-norma)
    (norma-da-materia [_ _ente _pid] norma-da-materia)
    (listar-normas [_ _ente] listar-normas)))

(defn- service-fn [repo]
  (let [rotas (transparencia-http/rotas {:repo-transparencia repo
                                         :resolver-ente-publico transparencia-http/resolver-ente-publico-uuid})]
    (-> (http/servico (config/carregar) rotas it/globais)
        ph/create-server ::ph/service-fn)))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(def ^:private ente (random-uuid))
(def ^:private pid (random-uuid))
(def ^:private nid (random-uuid))

(def ^:private materia-fixture
  {:proposicao-id pid :tipo "projeto_lei" :ano 2026 :sequencial 1
   :urn-lex "urn:lex:br;ce;fortaleza:projeto.lei:2026;1" :ementa "Dispoe sobre X"
   :autor-tipo "vereador" :autor-texto "Fulano de Tal" :estado "protocolada"})

(def ^:private norma-fixture
  {:norma-id nid :proposicao-id pid :tipo-norma "lei" :numero 1 :ano 2026
   :urn "urn:lex:br;ce;fortaleza:lei:2026-06-28;1" :ementa "Dispoe sobre X"
   :publicado-em t0 :veiculo-publicacao "Diario Oficial do Municipio"})

;; ---------- GET /portal/casa/:ente/materias ----------

(deftest listar-materias-200
  (let [repo (fake-repo {:listar-materias [materia-fixture]})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias"))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 1 (count body)))
    (is (= "Fulano de Tal" (:autor-texto (first body))))
    (is (not (contains? (first body) :norma)) "listagem NAO embute a norma (so' a ficha)")))

(deftest listar-materias-ente-malformado-400
  (let [repo (fake-repo {:listar-materias []})
        r    (pt/response-for (service-fn repo) :get "/portal/casa/nao-e-uuid/materias")]
    (is (= 400 (:status r)) "ente malformado -> 400 fail-closed (nunca vaza cross-tenant)")))

;; ---------- GET /portal/casa/:ente/materias/:proposicao_id ----------

(deftest ficha-materia-200-com-norma
  (let [repo (fake-repo {:buscar-materia materia-fixture :norma-da-materia norma-fixture})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias/" pid))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "protocolada" (:estado body)))
    (is (= "lei" (get-in body [:norma :tipo-norma])) "a ficha liga proposicao -> lei")))

(deftest ficha-materia-sem-norma-200
  (let [repo (fake-repo {:buscar-materia materia-fixture :norma-da-materia nil})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias/" pid))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (nil? (:norma body)) "materia ainda nao virou lei -> :norma ausente/nil")))

(deftest ficha-materia-404
  (let [repo (fake-repo {:buscar-materia nil})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/materias/" pid))]
    (is (= 404 (:status r)))))

;; ---------- GET /portal/casa/:ente/legislacao(/:norma_id) ----------

(deftest listar-normas-200
  (let [repo (fake-repo {:listar-normas [norma-fixture]})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao"))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "Diario Oficial do Municipio" (:veiculo-publicacao (first body))))))

(deftest buscar-norma-200
  (let [repo (fake-repo {:buscar-norma norma-fixture})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao/" nid))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= "urn:lex:br;ce;fortaleza:lei:2026-06-28;1" (:urn body)))))

(deftest buscar-norma-404
  (let [repo (fake-repo {:buscar-norma nil})
        r    (pt/response-for (service-fn repo) :get (str "/portal/casa/" ente "/legislacao/" nid))]
    (is (= 404 (:status r)))))
