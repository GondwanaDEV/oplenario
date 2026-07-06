(ns oplenario.legislativo.controllers-proposicao-test
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]))

(defn- fake-repo [& {:keys [protocolar editar detalhe]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (protocolar! [_ _ente-id p] (protocolar p))
    (editar-proposicao! [_ _ente-id m] (editar m))
    (buscar-proposicao-detalhe [_ _ente-id id] (detalhe id))))

(deftest criar-proposicao-mescla-uf-municipio-do-resolver
  (let [recebido (atom nil)
        repo (fake-repo :protocolar (fn [p] (reset! recebido p) {:id (:id p) :sequencial 1 :urn-lex "urn:x"}))
        resolver (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})]
    (controllers/criar-proposicao repo resolver (random-uuid) {:id (random-uuid) :tipo "projeto_lei"})
    (is (= "CE" (:uf @recebido)))
    (is (= "Fortaleza" (:municipio-nome @recebido)))))

(deftest buscar-proposicao-ficha-nil-quando-nao-existe
  (let [repo (fake-repo :detalhe (fn [_id] {:proposicao nil :texto nil}))]
    (is (nil? (controllers/buscar-proposicao-ficha repo (random-uuid) (random-uuid))))))

(deftest buscar-proposicao-ficha-extrai-texto-inline
  (let [repo (fake-repo :detalhe (fn [_id] {:proposicao {:id "p"} :texto {:texto-inline "## Art. 1o"}}))]
    (is (= "## Art. 1o" (:texto (controllers/buscar-proposicao-ficha repo (random-uuid) (random-uuid)))))))

(deftest editar-proposicao-repassa-ao-repo
  (let [repo (fake-repo :editar (fn [m] {:id (:id m)}))
        id (random-uuid)]
    (is (= {:id id} (controllers/editar-proposicao repo (random-uuid) {:id id})))))
