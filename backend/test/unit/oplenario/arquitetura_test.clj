(ns oplenario.arquitetura-test
  "Enforcement da matriz de import-lint do §22.10 (CI FALHA em violacao) via analise clj-kondo:
   (1) modulo A NUNCA requer ns de modulo B (a!=b) -> comunicacao so HTTP/eventos;
   (2) kernel.* e motor.* NUNCA requerem um modulo (impede a 'bola de pelo' de §22.2);
   o HOST (main/sistema/http) e' a raiz de composicao e PODE requerer modulos (wiring de Component).

   Leak test 3-dim (gate de CI, §22.2/§22.10) ja coberto e em suite:
     dim 1 cross-tenant         -> oplenario.kernel.tenancy-test/rls-isola-cross-tenant
     dim 3 lote-nao-efetivado   -> oplenario.kernel.tenancy-test/rls-esconde-lote-nao-efetivado-do-proprio-ente
     dim 2 cross-esfera         -> oplenario.kernel.autorizacao-test/esfera-* (operador<->tenant)
   A lint de disciplina de authz (§22.5: toda fn com `ator` chama policy.check) fica p/ F2, quando
   houver operacoes de dominio com `ator` p/ validar a regra (hoje seria vacua)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clj-kondo.core :as kondo]))

(def ^:private modulos
  "Os bounded contexts (dominio + projecao + supratenant) — cada um e' um modulo isolado (§22.10)."
  #{"identidade" "cadastros" "legislativo" "sessoes" "transparencia" "participacao"
    "compliance" "paineis" "tempo_real" "admin_sistema"})

(defn- modulo-de
  "oplenario.<modulo>.* -> \"<modulo>\"; nil p/ kernel/motor/host."
  [ns-sym]
  (let [p (str/split (str ns-sym) #"\.")]
    (when (and (= "oplenario" (first p)) (>= (count p) 3) (modulos (second p)))
      (second p))))

(defn- kernel-ou-motor? [ns-sym]
  (let [s (str ns-sym)]
    (or (str/starts-with? s "oplenario.kernel")
        (str/starts-with? s "oplenario.motor"))))

(defn- violacoes-de
  "Aplica a matriz §22.10 a uma colecao de {:from :to} (ns-usages). Devolve as violacoes."
  [usos]
  (vec (for [{:keys [from to]} usos
             :let [mf (modulo-de from) mt (modulo-de to)]
             :when (or (and mf mt (not= mf mt))            ; (1) modulo A -> modulo B
                       (and (kernel-ou-motor? from) mt))]  ; (2) kernel/motor -> modulo
         {:from from :to to})))

(def ^:private analise
  (delay (:analysis (kondo/run! {:lint ["src"] :config {:output {:analysis true}}}))))

(deftest import-lint-tem-dentes
  ;; prova que a regra DETECTA violacoes e PERMITE o legitimo (senao um lint vazio passaria vacuo).
  (is (seq (violacoes-de [{:from 'oplenario.legislativo.x :to 'oplenario.cadastros.y}]))
      "cross-modulo (legislativo->cadastros) = violacao")
  (is (seq (violacoes-de [{:from 'oplenario.kernel.x :to 'oplenario.compliance.y}]))
      "kernel->modulo = violacao")
  (is (empty? (violacoes-de [{:from 'oplenario.compliance.x :to 'oplenario.kernel.tempo}]))
      "modulo->kernel = permitido")
  (is (empty? (violacoes-de [{:from 'oplenario.compliance.x :to 'oplenario.motor.api}]))
      "modulo->motor = permitido (motor e' lib compartilhada)")
  (is (empty? (violacoes-de [{:from 'oplenario.sistema :to 'oplenario.legislativo.components}]))
      "host->modulo = permitido (raiz de composicao)")
  (is (empty? (violacoes-de [{:from 'oplenario.legislativo.a :to 'oplenario.legislativo.b}]))
      "mesmo modulo = permitido"))

(deftest matriz-de-dependencia-de-modulos
  (let [usos (:namespace-usages @analise)]
    (is (seq usos) "a analise clj-kondo encontrou ns-usages (o lint rodou de fato, nao passou vacuo)")
    (is (empty? (violacoes-de usos))
        (str "import-lint §22.10 violado (use HTTP/eventos, nunca import direto): "
             (pr-str (violacoes-de usos))))))
