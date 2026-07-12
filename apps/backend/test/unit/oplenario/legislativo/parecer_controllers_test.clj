(ns oplenario.legislativo.parecer-controllers-test
  "Unit (Repo FAKE) — Onda C4: meu-parecer-editor/meu-emitir-parecer respeitam o guard de posse
  (relator-do-parecer?) ANTES de ler/escrever; emitir-parecer repassa o assinador pro Repo."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]))

(defn- fake-repo [{:keys [relator? parecer emitido]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (relator-do-parecer? [_ _ente-id _vereador-id _parecer-id] relator?)
    (buscar-parecer-para-editor [_ _ente-id _id] parecer)
    (emitir-parecer! [_ _ente-id _registro m] (reset! emitido m) parecer)))

(deftest meu-parecer-editor-nil-quando-nao-e-o-relator
  (let [repo (fake-repo {:relator? false :parecer {:parecer {:id "x"}}})]
    (is (nil? (controllers/meu-parecer-editor repo (fn [_ _] (random-uuid))
                                              {:ente-id (random-uuid) :identidade-id (random-uuid)} (random-uuid))))))

(deftest meu-parecer-editor-nil-quando-ator-sem-vinculo
  (let [repo (fake-repo {:relator? true :parecer {:parecer {:id "x"}}})]
    (is (nil? (controllers/meu-parecer-editor repo (fn [_ _] nil)
                                              {:ente-id (random-uuid) :identidade-id (random-uuid)} (random-uuid))))))

(deftest meu-parecer-editor-devolve-quando-e-o-relator
  (let [repo (fake-repo {:relator? true :parecer {:parecer {:id "x"}}})]
    (is (= {:parecer {:id "x"}}
           (controllers/meu-parecer-editor repo (fn [_ _] (random-uuid))
                                           {:ente-id (random-uuid) :identidade-id (random-uuid)} (random-uuid))))))

(deftest meu-emitir-parecer-nao-chama-o-repo-quando-nao-e-o-relator
  (let [emitido (atom :nao-chamado)
        repo (fake-repo {:relator? false :parecer {:parecer {:id "x"}} :emitido emitido})]
    (is (nil? (controllers/meu-emitir-parecer repo :registro-fake :assinador-fake (fn [_ _] (random-uuid))
                                               {:ente-id (random-uuid) :identidade-id (random-uuid)}
                                               (random-uuid) {:voto-relator "favoravel"})))
    (is (= :nao-chamado @emitido) "emitir-parecer! NUNCA chamado sem posse")))

(deftest emitir-parecer-repassa-o-assinador-pro-repo
  (let [emitido (atom nil)
        repo (fake-repo {:parecer {:id "x"} :emitido emitido})]
    (controllers/emitir-parecer repo :registro-fake :assinador-fake (random-uuid) {:voto-relator "favoravel"})
    (is (= :assinador-fake (:assinador @emitido)))))
