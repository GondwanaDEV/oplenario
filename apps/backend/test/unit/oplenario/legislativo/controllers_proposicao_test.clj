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

;; sempre-vinculado/nunca-vinculado: os dois fakes de `vereador-vinculado?` usados pelos testes que nao
;; exercitam o achado I-1/M-1 (mantem os testes de merge/repasse pre-existentes DESACOPLADOS da nova
;; validacao — so' os deftests dedicados abaixo mexem no autor).
(def ^:private sempre-vinculado? (constantly true))

(defn- invalido? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

(deftest criar-proposicao-mescla-uf-municipio-do-resolver
  (let [recebido (atom nil)
        repo (fake-repo :protocolar (fn [p] (reset! recebido p) {:id (:id p) :sequencial 1 :urn-lex "urn:x"}))
        resolver (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})]
    (controllers/criar-proposicao repo resolver sempre-vinculado? (random-uuid) {:id (random-uuid) :tipo "projeto_lei"})
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
    (is (= {:id id} (controllers/editar-proposicao repo sempre-vinculado? (random-uuid) {:id id})))))

;; ---------- achados I-1 (autor-id cru vira autoria PUBLICA sem checar vinculo) + M-1 (par autor-tipo/
;; autor-id incoerente), corrigidos juntos: `validar-autor!` em controllers.clj, ANTES do Repo. ----------

(deftest criar-proposicao-sem-autor-id-nao-chama-vereador-vinculado
  ;; a maioria das proposicoes (autor mesa/comissao/executivo/cidadao, ou vereador so' por autor-texto) NAO
  ;; tem autor-id — a validacao tem que ser NO-OP nesse caso, sem round-trip nenhum ao seam de cadastros.
  (let [chamado? (atom false)
        repo (fake-repo :protocolar (fn [p] {:id (:id p) :sequencial 1 :urn-lex "urn:x"}))
        resolver (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})
        vinculado? (fn [_ente-id _vid] (reset! chamado? true) true)]
    (controllers/criar-proposicao repo resolver vinculado? (random-uuid)
      {:id (random-uuid) :tipo "projeto_lei" :autor-tipo "executivo" :autor-texto "Prefeitura"})
    (is (false? @chamado?) "vereador-vinculado? nunca invocada quando autor-id ausente")))

(deftest criar-proposicao-autor-id-com-autor-tipo-nao-vereador-400
  ;; M-1: autor-id "grudado" numa autoria nao-parlamentar (mesa/comissao/executivo/cidadao) e' incoerente —
  ;; nao pode viajar pro portal publico como autoria de vereador.
  (let [repo (fake-repo :protocolar (fn [_p] (throw (ex-info "nao deveria chegar aqui" {}))))
        resolver (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})]
    (is (invalido? #(controllers/criar-proposicao repo resolver sempre-vinculado? (random-uuid)
                      {:id (random-uuid) :tipo "projeto_lei" :autor-tipo "executivo" :autor-id (random-uuid)})))))

(deftest criar-proposicao-autor-id-sem-vinculo-nesta-casa-400
  ;; I-1: autor-id que NAO resolve a um cadastro de vereador NESTE ente (via `vereador-vinculado?`
  ;; injetada pelo host, inversao de dependencia sobre cadastros) e' rejeitado fail-closed.
  (let [repo (fake-repo :protocolar (fn [_p] (throw (ex-info "nao deveria chegar aqui" {}))))
        resolver (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})
        nunca-vinculado? (constantly false)]
    (is (invalido? #(controllers/criar-proposicao repo resolver nunca-vinculado? (random-uuid)
                      {:id (random-uuid) :tipo "projeto_lei" :autor-tipo "vereador" :autor-id (random-uuid)})))))

(deftest criar-proposicao-autor-id-vereador-vinculado-protocola
  ;; caminho feliz: autor-tipo vereador + autor-id que RESOLVE via vereador-vinculado? -> chega ao Repo.
  (let [recebido (atom nil)
        repo (fake-repo :protocolar (fn [p] (reset! recebido p) {:id (:id p) :sequencial 1 :urn-lex "urn:x"}))
        resolver (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})
        vid (random-uuid)]
    (controllers/criar-proposicao repo resolver sempre-vinculado? (random-uuid)
      {:id (random-uuid) :tipo "projeto_lei" :autor-tipo "vereador" :autor-id vid})
    (is (= vid (:autor-id @recebido)))))

(deftest editar-proposicao-autor-id-sem-autor-tipo-vereador-na-mesma-escrita-400
  ;; M-1 no PATCH parcial: decisao explicita (documentada em controllers/validar-autor!) de NAO ler o
  ;; autor-tipo da linha anterior — um PATCH que muda autor-id tem que reafirmar autor-tipo="vereador" na
  ;; MESMA chamada, senao e' rejeitado (fail-closed, sem round-trip extra nem janela de corrida).
  (let [repo (fake-repo :editar (fn [_m] (throw (ex-info "nao deveria chegar aqui" {}))))]
    (is (invalido? #(controllers/editar-proposicao repo sempre-vinculado? (random-uuid)
                      {:id (random-uuid) :autor-id (random-uuid)})))))

(deftest editar-proposicao-autor-id-sem-vinculo-nesta-casa-400
  (let [repo (fake-repo :editar (fn [_m] (throw (ex-info "nao deveria chegar aqui" {}))))
        nunca-vinculado? (constantly false)]
    (is (invalido? #(controllers/editar-proposicao repo nunca-vinculado? (random-uuid)
                      {:id (random-uuid) :autor-tipo "vereador" :autor-id (random-uuid)})))))

(deftest editar-proposicao-autor-id-vereador-vinculado-repassa-ao-repo
  (let [repo (fake-repo :editar (fn [m] {:id (:id m)}))
        id (random-uuid) vid (random-uuid)]
    (is (= {:id id}
           (controllers/editar-proposicao repo sempre-vinculado? (random-uuid)
             {:id id :autor-tipo "vereador" :autor-id vid})))))

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
