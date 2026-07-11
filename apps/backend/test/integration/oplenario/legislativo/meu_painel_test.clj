(ns oplenario.legislativo.meu-painel-test
  "INTEGRACAO (PG real) — borda `/meu` do vereador (Onda C1). Namespace estendido pelas proximas tasks
  (3/4: ciencia + HTTP); Task 1 cobre o host resolvendo identidade->vereador-id por inversao de
  dependencia (§22.5.3, exceção nomeada — mesma forma de `membros-da-casa`), sem o `legislativo` importar
  `cadastros`. Task 2 (esta) cobre o READ `meu-painel` — 'minhas proposicoes' (autor_tipo='vereador' AND
  autor_id=V) + 'meus pareceres' (relator_id=V), sempre com `ente_id` no WHERE (Inv.1), fixture via os
  atos REAIS do Repo (`protocolar!`/`iniciar-parecer!`), nao INSERT cru — exercita o caminho de verdade."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo-legislativo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]))

(def ^:dynamic *repo-cadastros* nil)
(def ^:dynamic *repo-legislativo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo-cadastros* (repo-cadastros/->RepoCadastrosPg c)
                *repo-legislativo* (repo-legislativo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; ---------- fixtures (atos REAIS do Repo — nao INSERT cru, exercita o caminho de verdade) ----------

(defn- protocolar!
  "Protocola uma proposicao de autoria do `autor-id` (vereador) neste ente."
  [ente autor-id]
  (:id (repo-legislativo/protocolar! *repo-legislativo* ente
         {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
          :ementa "Dispoe sobre X" :autor-tipo "vereador" :autor-id autor-id})))

(defn- montar-template-parecer!
  "Template MINIMO de sujeito 'parecer' (db/parecer/criar! so' le' `estado_inicial`/`sujeito` do
  template — sem precisar de estados/transicoes, ao contrario do engine de transicionar-parecer!).
  `criar-template!` nao devolve `:id` (execute-one! cru, mesmo padrao de parecer-repo-test); o `tid` e'
  gerado aqui e devolvido direto."
  [ente]
  (let [tid (random-uuid)]
    (repo-legislativo/criar-template! *repo-legislativo* ente
      {:id tid :chave "parecer_ccj" :versao 1 :sujeito "parecer"
       :nome "Parecer CCJ [FIXTURE]" :estado-inicial "aguardando_designacao"})
    tid))

(deftest resolver-vereador-resolve-identidade->vereador-id-no-ente
  (let [ente (random-uuid)
        identidade (random-uuid)
        vereador-id (random-uuid)]
    (repo-cadastros/criar-vereador! *repo-cadastros* ente
      {:id vereador-id :ente-id ente :identidade-id identidade :nome "Fulana" :nome-parlamentar "Fulana"})
    (is (= vereador-id (rotas/resolver-vereador *repo-cadastros* ente identidade)))))

(deftest resolver-vereador-devolve-nil-quando-identidade-sem-cadastro-neste-ente
  (let [ente (random-uuid)
        identidade-sem-cadastro (random-uuid)]
    (is (nil? (rotas/resolver-vereador *repo-cadastros* ente identidade-sem-cadastro)))))

;; ========================= Task 2: repo/meu-painel (proposicoes + pareceres) =========================

(deftest meu-painel-proposicoes-do-autor-mais-recente-primeiro
  (let [ente (random-uuid) vereador (random-uuid)
        pid-antiga (protocolar! ente vereador)
        pid-recente (protocolar! ente vereador)
        r (repo-legislativo/meu-painel *repo-legislativo* ente vereador)]
    (is (= [pid-recente pid-antiga] (mapv :id (:proposicoes r)))
        "ordenadas mais-recente-primeiro (atualizado_em desc)")
    (is (= [] (:pareceres r)))
    (is (= [] (:ciencias r)) "Task 3 preenche; nesta task fica sempre vazio")))

(deftest meu-painel-proposicoes-so-do-proprio-autor-neste-ente
  (let [ente (random-uuid) vereador-a (random-uuid) vereador-b (random-uuid)
        pid-a (protocolar! ente vereador-a)]
    (protocolar! ente vereador-b)
    (is (= [pid-a] (mapv :id (:proposicoes (repo-legislativo/meu-painel *repo-legislativo* ente vereador-a))))
        "so' a proposicao de autoria do proprio vereador aparece")))

(deftest meu-painel-pareceres-do-relator
  (let [ente (random-uuid) vereador (random-uuid)
        tid (montar-template-parecer! ente)
        pid (protocolar! ente vereador)
        {pcid :id} (repo-legislativo/iniciar-parecer! *repo-legislativo* ente
                     {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid
                      :comissao-id (random-uuid) :template-id tid :relator-id vereador})
        r (repo-legislativo/meu-painel *repo-legislativo* ente vereador)]
    (is (= [pcid] (mapv :id (:pareceres r))))))

(deftest meu-painel-pareceres-so-do-proprio-relator
  (let [ente (random-uuid) relator-a (random-uuid) relator-b (random-uuid)
        tid (montar-template-parecer! ente)
        pid (protocolar! ente relator-a)]
    (repo-legislativo/iniciar-parecer! *repo-legislativo* ente
      {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid
       :comissao-id (random-uuid) :template-id tid :relator-id relator-b})
    (is (= [] (:pareceres (repo-legislativo/meu-painel *repo-legislativo* ente relator-a)))
        "parecer de outro relator nao aparece")))

(deftest meu-painel-isola-por-ente-mesmo-com-o-mesmo-vereador-id
  ;; o MESMO vereador-id (coincidencia de valor) usado como autor em DOIS entes distintos: o painel de um
  ;; ente NUNCA enxerga a proposicao do outro (ente_id no WHERE, nao so' autor_id — Inv.1).
  (let [ente-a (random-uuid) ente-b (random-uuid) vereador (random-uuid)
        pid-a (protocolar! ente-a vereador)]
    (protocolar! ente-b vereador)
    (is (= [pid-a] (mapv :id (:proposicoes (repo-legislativo/meu-painel *repo-legislativo* ente-a vereador))))
        "o ente-a so' ve a propria proposicao, mesmo com autor_id repetido no ente-b")))

;; ========================= Task 2: controllers/meu-painel (resolucao ator -> vereador-id) =========================

(deftest controller-meu-painel-devolve-painel-vazio-quando-resolver-vereador-e-nil
  ;; papel vereador sem cadastro vinculado (`resolver-vereador` nil) -> painel VAZIO, nao lanca (o repo
  ;; nem chega a ser tocado: `repo-legislativo` passado como nil comprova que nao ha chamada nesse ramo).
  (let [ator {:ente-id (random-uuid) :identidade-id (random-uuid)}
        resolver-vereador (fn [_ente-id _identidade-id] nil)]
    (is (= {:proposicoes [] :pareceres [] :ciencias []}
           (controllers/meu-painel nil resolver-vereador ator)))))

(deftest controller-meu-painel-delega-ao-repo-quando-resolver-vereador-resolve
  (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)
        pid (protocolar! ente vereador)
        ator {:ente-id ente :identidade-id identidade}
        resolver-vereador (fn [e i] (when (and (= e ente) (= i identidade)) vereador))]
    (is (= [pid] (mapv :id (:proposicoes (controllers/meu-painel *repo-legislativo* resolver-vereador ator)))))))
