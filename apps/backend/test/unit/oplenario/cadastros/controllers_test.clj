(ns oplenario.cadastros.controllers-test
  "UNITARIO (DB-free) — Task 4: `cadastros.controllers`, fatia de leitura de vereador. Reify um
  `RepoCadastros` FAKE (so os metodos que a fatia exercita: `listar-vereadores`/`ficha-vereador`) — sem
  Postgres. Prova so o pass-through (a agregacao real ja e' coberta no teste do Repo, Task 2)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.cadastros.controllers :as controllers])
  (:import (java.time LocalDate)))

(def ^:private HOJE (LocalDate/of 2026 7 14))

(defn- fake-repo
  "RepoCadastros fake (parcial proposital — so os 2 metodos que a fatia de leitura de vereador exercita).
  `chamadas` (atom []) acumula os args de cada chamada, p/ provar o pass-through exato dos args."
  [listar-fn ficha-fn chamadas]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros/RepoCadastros
    (listar-vereadores [_ ente-id data]
      (swap! chamadas conj [:listar-vereadores ente-id data])
      (listar-fn ente-id data))
    (ficha-vereador [_ ente-id id data]
      (swap! chamadas conj [:ficha-vereador ente-id id data])
      (ficha-fn ente-id id data))))

;; ---------- listar-vereadores: pass-through ----------

(deftest listar-vereadores-passa-adiante-para-o-repo
  (let [ente (random-uuid)
        chamadas (atom [])
        resultado [{:id (random-uuid) :nome "Fulano"}]
        repo (fake-repo (fn [_ _] resultado) (fn [_ _ _] nil) chamadas)]
    (is (= resultado (controllers/listar-vereadores repo ente HOJE))
        "devolve exatamente o que o Repo devolve")
    (is (= [[:listar-vereadores ente HOJE]] @chamadas)
        "chamou o Repo uma vez, com ente-id e data repassados sem alteracao")))

;; ---------- ficha-vereador: nil quando o Repo devolve nil ----------

(deftest ficha-vereador-devolve-nil-quando-o-repo-devolve-nil
  (let [ente (random-uuid) id (random-uuid)
        chamadas (atom [])
        repo (fake-repo (fn [_ _] []) (fn [_ _ _] nil) chamadas)]
    (is (nil? (controllers/ficha-vereador repo ente id HOJE))
        "vereador inexistente -> nil (o Repo ja resolveu isso na tx unica)")
    (is (= [[:ficha-vereador ente id HOJE]] @chamadas))))

;; ---------- ficha-vereador: devolve a ficha composta quando o Repo acha o vereador ----------

(deftest ficha-vereador-devolve-a-ficha-composta-quando-o-repo-encontra
  (let [ente (random-uuid) id (random-uuid)
        chamadas (atom [])
        ficha {:vereador {:id id :nome "Fulano"}
               :mandato {:id (random-uuid)}
               :legislatura {:id (random-uuid)}
               :comissoes [{:id (random-uuid)}]}
        repo (fake-repo (fn [_ _] []) (fn [_ _ _] ficha) chamadas)]
    (is (= ficha (controllers/ficha-vereador repo ente id HOJE))
        "devolve exatamente a ficha composta que o Repo montou (sem tocar/transformar)")
    (is (= [[:ficha-vereador ente id HOJE]] @chamadas))))

;; ---------- Task 5: pass-throughs de escrita ----------

(deftest criar-vereador-passa-adiante-e-devolve-id
  (let [ente (random-uuid) chamadas (atom [])
        m {:id (random-uuid) :ente-id ente :nome "Ana"}
        repo #_{:clj-kondo/ignore [:missing-protocol-method]}
             (reify repo-cadastros/RepoCadastros
               (criar-vereador! [_ e mm] (swap! chamadas conj [:criar e mm]) {:next.jdbc/update-count 1}))]
    (is (= {:id (:id m)} (controllers/criar-vereador repo ente m)) "devolve {:id} com o id gerado")
    (is (= [[:criar ente m]] @chamadas) "repassou ente-id + dominio sem alteracao")))

(deftest editar-vereador-passa-o-update-count
  (let [ente (random-uuid) id (random-uuid)
        repo #_{:clj-kondo/ignore [:missing-protocol-method]}
             (reify repo-cadastros/RepoCadastros
               (atualizar-vereador! [_ _ _ _] 1))]
    (is (= 1 (controllers/editar-vereador repo ente id {:nome "X"})))))

(deftest registrar-mandato-e-licenca-sao-pass-through
  (let [ente (random-uuid) ver (random-uuid) hoje (LocalDate/of 2026 7 14)
        repo #_{:clj-kondo/ignore [:missing-protocol-method]}
             (reify repo-cadastros/RepoCadastros
               (registrar-mandato! [_ _ m] {:id (:id m)})
               (registrar-licenca! [_ _ _ l _] {:id (:id l)}))
        m {:id (random-uuid) :vereador-id ver} l {:id (random-uuid)}]
    (is (= {:id (:id m)} (controllers/registrar-mandato repo ente m)))
    (is (= {:id (:id l)} (controllers/registrar-licenca repo ente ver l hoje)))))
