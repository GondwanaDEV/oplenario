(ns oplenario.legislativo.controllers-proposicao-test
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]))

(defn- fake-repo [& {:keys [protocolar editar detalhe ficha]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (protocolar! [_ _ente-id p] (protocolar p))
    (editar-proposicao! [_ _ente-id m] (editar m))
    (buscar-proposicao-detalhe [_ _ente-id id] (detalhe id))
    (ficha-completa-da-proposicao [_ _ente-id id] (ficha id))))

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

(deftest buscar-ficha-materia-nil-quando-nao-existe
  (let [repo (fake-repo :ficha (fn [_id] {:proposicao nil :texto nil :tramitacao [] :apensadas []
                                           :emendas [] :pareceres []}))]
    (is (nil? (controllers/buscar-ficha-materia repo (random-uuid) (random-uuid))))))

(deftest buscar-ficha-materia-devolve-a-composicao-quando-existe
  ;; review MENOR fe-9-ficha-materia: a extracao de :texto-inline e' responsabilidade do CONTROLLER (mesma
  ;; disciplina de buscar-proposicao-ficha, Slice 2) — :texto sai daqui ja' como string/nil, nunca o mapa
  ;; de dominio cru (o diplomat so' compoe adapters/out prontos, nunca decide nome de campo do model).
  (let [repo (fake-repo :ficha (fn [_id] {:proposicao {:id "p"} :texto {:texto-inline "## Art. 1o"}
                                           :tramitacao [{:gatilho "despachar"}] :apensadas []
                                           :emendas [] :pareceres []}))
        r (controllers/buscar-ficha-materia repo (random-uuid) (random-uuid))]
    (is (= {:id "p"} (:proposicao r)))
    (is (= "## Art. 1o" (:texto r)))
    (is (= [{:gatilho "despachar"}] (:tramitacao r)))))
