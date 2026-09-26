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
    "compliance" "paineis" "tempo_real" "admin_sistema" "integracao_ia"})

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

(defn- adapters-ns? [ns-sym] (re-find #"\.adapters\." (str ns-sym)))
(defn- diplomat-ns? [ns-sym] (re-find #"\.diplomat\." (str ns-sym)))

(defn- violacoes-adapters
  "ADR-0001 §3: o gate `adapters/` so e' chamado pelo `diplomat/` — o nucleo (controllers/logic) trabalha
  em MODELS; a traducao wire<->model acontece SO na borda de IO. Toda ns-usage cujo ALVO e' um `adapters`
  deve vir de um `diplomat`. (O caso cross-modulo ja e' barrado por violacoes-de.)"
  [usos]
  (vec (for [{:keys [from to]} usos
             :when (and (adapters-ns? to) (not (diplomat-ns? from)))]
         {:from from :to to})))

(defn- modulo-db
  "\"<mod>\" se o ns e' oplenario.<mod>.db.* (db de um modulo); nil caso contrario. (kernel/db-util nao
  e' db de modulo.)"
  [ns-sym]
  (let [p (str/split (str ns-sym) #"\.")]
    (when (and (= "oplenario" (first p)) (>= (count p) 4) (modulos (second p)) (= "db" (nth p 2)))
      (second p))))

(def ^:private camadas-que-podem-tocar-db
  "As camadas que podem importar o `db/` do PROPRIO modulo (ADR-0001 §3-bis).

  `components` (o Repo-Component) e `db` (db->db) sao a regra original: o controller depende do Repo,
  nunca do `db/` direto.

  `relacoes` foi ACRESCENTADA em 3-B, e a razao e' que a regra nunca falou dela. O motivo do §3-bis e'
  que quem ABRE transacao tem de passar pelo Repo (que trata `com-tenant*`); `relacoes/` nao abre tx
  nenhuma — ela RECEBE a tx do tenant como 1o argumento, injetada pelo motor via RegistroFatos, exatamente
  como uma fn de `db/`. E' uma folha tenant-aware do mesmo modulo, irma do `db/`, e nao ha' caminho pelo
  Repo que ela PUDESSE tomar: o Repo abriria uma segunda tx, fora da tx em que o guard esta' sendo
  avaliado.

  O que isso NAO abre: cross-modulo segue barrado por `violacoes-de` (uma relacao do legislativo nao
  alcanca `cadastros.db.*`), e controller/logic/diplomat/autenticacao seguem barrados aqui."
  #{"components" "db" "relacoes"})

(defn- violacoes-db
  "ADR-0001 §3-bis: o `db/` de um modulo so e' importado por uma camada tenant-aware do MESMO modulo
  (`components/` — o Repo-Component —, outro `db/`, ou `relacoes/`). controllers/logic/diplomat/
  autenticacao nunca tocam db/ direto — vao pelo Repo."
  [usos]
  (vec (for [{:keys [from to]} usos
             :let [mt (modulo-db to)
                   fp (str/split (str from) #"\.")
                   from-mod (when (and (= "oplenario" (first fp)) (>= (count fp) 3)) (second fp))
                   from-camada (when (>= (count fp) 3) (nth fp 2))]
             :when (and mt (not (and (= from-mod mt) (camadas-que-podem-tocar-db from-camada))))]
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

(deftest adapters-lint-tem-dentes
  ;; prova que a regra DETECTA o caller ilegitimo e PERMITE o diplomat.
  (is (seq (violacoes-adapters [{:from 'oplenario.legislativo.controllers
                                 :to 'oplenario.legislativo.adapters.in.proposicao}]))
      "controller->adapters = violacao (adapters so do diplomat)")
  (is (empty? (violacoes-adapters [{:from 'oplenario.legislativo.diplomat.http.in
                                    :to 'oplenario.legislativo.adapters.in.proposicao}]))
      "diplomat->adapters = permitido")
  (is (empty? (violacoes-adapters [{:from 'oplenario.legislativo.controllers
                                    :to 'oplenario.legislativo.logic}]))
      "controller->logic (nao-adapter) = ok"))

(deftest adapters-so-chamados-do-diplomat
  (let [usos (:namespace-usages @analise)]
    (is (empty? (violacoes-adapters usos))
        (str "ADR-0001 §3: adapters/ so podem ser chamados pelo diplomat/ (o nucleo trabalha em models): "
             (pr-str (violacoes-adapters usos))))))

(deftest db-lint-tem-dentes
  ;; prova que a regra DETECTA o caller ilegitimo e PERMITE o Repo-Component / db do mesmo modulo.
  (is (seq (violacoes-db [{:from 'oplenario.identidade.autenticacao :to 'oplenario.identidade.db.vinculo}]))
      "autenticacao->db = violacao (db so do Repo-Component)")
  (is (empty? (violacoes-db [{:from 'oplenario.identidade.components.repositorio :to 'oplenario.identidade.db.vinculo}]))
      "repositorio->db = permitido")
  (is (empty? (violacoes-db [{:from 'oplenario.cadastros.db.vereador :to 'oplenario.cadastros.db.estrutura}]))
      "db->db do MESMO modulo = permitido")
  (is (empty? (violacoes-db [{:from 'oplenario.legislativo.controllers :to 'oplenario.kernel.db-util}]))
      "kernel/db-util nao e' db de modulo = ok")
  ;; 3-B: `relacoes/` e' folha tenant-aware do modulo (recebe a tx do motor, nao abre tx) -> pode o db/ do
  ;; PROPRIO modulo. Os dois casos abaixo sao o que a permissao NAO pode arrastar junto.
  (is (empty? (violacoes-db [{:from 'oplenario.legislativo.relacoes :to 'oplenario.legislativo.db.votacao}]))
      "relacoes->db do MESMO modulo = permitido (3-B)")
  (is (seq (violacoes-db [{:from 'oplenario.legislativo.relacoes :to 'oplenario.cadastros.db.vereador}]))
      "relacoes->db de OUTRO modulo = violacao (a permissao de 3-B e' intra-modulo)")
  (is (seq (violacoes-db [{:from 'oplenario.legislativo.controllers :to 'oplenario.legislativo.db.votacao}]))
      "controller->db do proprio modulo CONTINUA violacao (3-B nao afrouxou o §3-bis)"))

(deftest db-so-do-repo-component
  (let [usos (:namespace-usages @analise)]
    (is (empty? (violacoes-db usos))
        (str "ADR-0001 §3-bis: db/ de um modulo so e' importado pelo Repo-Component (controller depende do "
             "Repo, nunca do db/ direto): " (pr-str (violacoes-db usos))))))
