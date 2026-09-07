(ns oplenario.legislativo.parecer-controllers-test
  "Unit (Repo FAKE) — Onda C4: meu-parecer-editor/meu-emitir-parecer respeitam o guard de posse
  (relator-do-parecer?) ANTES de ler/escrever; emitir-parecer repassa o assinador pro Repo. E, desde o
  conserto do defeito #11 do ledger de prontidao, que os dois caminhos de leitura do editor nomeiam a
  comissao via o `resolver-comissoes` injetado pelo host (§22.5.3)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]))

(defn- fake-repo [{:keys [relator? parecer emitido]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (relator-do-parecer? [_ _ente-id _vereador-id _parecer-id] relator?)
    (buscar-parecer-para-editor [_ _ente-id _id] parecer)
    (emitir-parecer! [_ _ente-id _registro m] (reset! emitido m) parecer)))

(def ^:private sem-nomes (constantly {}))
(defn- ator [] {:ente-id (random-uuid) :identidade-id (random-uuid)})

(deftest meu-parecer-editor-nil-quando-nao-e-o-relator
  (let [repo (fake-repo {:relator? false :parecer {:parecer {:id "x"}}})]
    (is (nil? (controllers/meu-parecer-editor repo (fn [_ _] (random-uuid)) sem-nomes
                                              (ator) (random-uuid))))))

(deftest meu-parecer-editor-nil-quando-ator-sem-vinculo
  (let [repo (fake-repo {:relator? true :parecer {:parecer {:id "x"}}})]
    (is (nil? (controllers/meu-parecer-editor repo (fn [_ _] nil) sem-nomes
                                              (ator) (random-uuid))))))

(deftest meu-parecer-editor-devolve-quando-e-o-relator
  (let [ccj (random-uuid)
        repo (fake-repo {:relator? true :parecer {:parecer {:id "x" :comissao-id ccj}}})]
    (is (= {:parecer {:id "x" :comissao-id ccj :comissao-nome "Comissão de Constituição e Justiça"}}
           (controllers/meu-parecer-editor repo (fn [_ _] (random-uuid))
                                           (constantly {ccj "Comissão de Constituição e Justiça"})
                                           (ator) (random-uuid))))))

;; Defeito #11 do ledger de prontidao — o UUID que aparecia no subtitulo e no rail de /parecer/:id.
(deftest buscar-parecer-editor-nomeia-a-comissao
  (let [ccj (random-uuid)
        pedidos (atom nil)
        resolver (fn [_ente-id ids] (reset! pedidos ids) {ccj "Comissão de Educação"})
        repo (fake-repo {:parecer {:parecer {:id "x" :comissao-id ccj} :objeto {:id "o"}}})
        r (controllers/buscar-parecer-editor repo resolver (random-uuid) (random-uuid))]
    (is (= "Comissão de Educação" (get-in r [:parecer :comissao-nome])))
    (is (= [ccj] (vec @pedidos)))
    (is (= {:id "o"} (:objeto r)) "o resto do agregado passa intacto")))

(deftest buscar-parecer-editor-comissao-nao-resolvida-vira-nil-e-nao-lanca
  (let [repo (fake-repo {:parecer {:parecer {:id "x" :comissao-id (random-uuid)}}})
        r (controllers/buscar-parecer-editor repo sem-nomes (random-uuid) (random-uuid))]
    (is (nil? (get-in r [:parecer :comissao-nome])))
    (is (contains? (:parecer r) :comissao-nome) "a chave existe sempre")))

(deftest buscar-parecer-editor-nil-quando-o-parecer-nao-existe
  (let [chamou (atom false)
        repo (fake-repo {:parecer nil})]
    (is (nil? (controllers/buscar-parecer-editor repo (fn [_ _] (reset! chamou true) {})
                                                 (random-uuid) (random-uuid))))
    (is (false? @chamou) "404 nao paga uma transacao de cadastros")))

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
