(ns oplenario.legislativo.meu-voto-controller-test
  "UNITARIO (DB-free) — Onda C3 Slice 3 Task 4: `controllers/meu-voto`, o vereador vota do proprio celular.
  Chama o controller DIRETO (sem HTTP, sem Postgres): RepoLegislativo FAKE (reify, so os metodos que a
  vertical usa — buscar-votacao/registrar-voto!/transacao) + um RegistroFatos REAL (component/start, p/
  provar a costura fail-closed contra o catalogo real) com fns STUB de `tem_mandato_vigente`/`esta_presente_em`
  cujo booleano cada caso escolhe. `hoje`/`instante` aqui sao valores QUALQUER (as fns stub ignoram os
  argumentos de dominio e so devolvem o booleano do caso) — o comportamento do SLOT `:agora` compartilhado
  entre hoje()/agora() (motor/runtime.clj) so importa quando os fatos SAO avaliados de verdade contra
  Postgres, o que e' o escopo do teste de integracao irmao (marco_m3_test)."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.kernel.autorizacao :as autz]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.motor.components.registro-fatos :as registro-fatos]
            [oplenario.sessoes.logic :as sessoes-logic])
  (:import (java.time Instant LocalDate)))

;; valores arbitrarios — as fns stub de fato ignoram os argumentos de dominio (ver docstring do ns).
(def ^:private HOJE (LocalDate/of 2026 6 19))
(def ^:private INSTANTE (Instant/parse "2026-06-19T10:00:00Z"))

;; T2 grupo A achado #4/#5 (ledger Fase 8): `sessao-autorizada` agora recebe `sessao-fechada?` (injetada pelo
;; host em producao, via `oplenario.rotas`). Aqui a REUSAMOS de verdade (`sessoes.logic/estados-sessao-
;; fechada`, a MESMA particao que `sessoes.controllers/exigir-sessao-aberta!` usa) — nao um stub `(constantly
;; false)`, para o teste dedicado abaixo poder EXERCITAR o gate contra a sessao "encerrada".
(defn- sessao-fechada? [s] (contains? sessoes-logic/estados-sessao-fechada (:estado s)))

(defn- nega?
  "Mesmo padrao de marco_m2_test/marco_m3_test: true se `f` lanca uma negacao de autorizacao."
  [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (autz/negado? e))))

(defn- invalido?
  "true se `f` lanca ex-info com :tipo :validacao/invalido (o pre-check trivial de borda, NUNCA authz)."
  [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

(defn- registro-stub
  "RegistroFatos REAL (component/start roda verificar-costura contra o catalogo real — tem_mandato_vigente
  aridade-fn 3, esta_presente_em aridade-fn 4, ambas ja registradas em producao) com fns STUB cujo booleano
  os testes escolhem por caso."
  [mandato-ok? presente-ok?]
  (component/start
   (registro-fatos/registro-fatos
    {"tem_mandato_vigente" (fn [_tx _identidade-id _data] mandato-ok?)
     "esta_presente_em" (fn [_tx _sessao-id _vereador-id _instante] presente-ok?)})))

(defn- sessao-canonica
  "Sessao como `consultar-sessao` devolve — so o que a authz le (ente-id) + estado (nao usado aqui)."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta"})

(defn- votacao-canonica
  [ente-id id sessao-id modalidade estado]
  {:id id :ente-id ente-id :sessao-id sessao-id :modalidade modalidade :estado estado
   :objeto-tipo "proposicao" :objeto-id (random-uuid) :quorum-tipo "maioria_simples" :lock-version 0})

(defn- fake-repo-legislativo
  "RepoLegislativo fake (parcial proposital — so os metodos que meu-voto exercita). `registrar-meu-voto!`
  espelha a forma REAL (review CRÍTICO — autorizar+escrever numa SO tx): re-busca a votacao (equivalente ao
  `FOR UPDATE` real, aqui so' uma segunda chamada a `busca-votacao-fn`), roda `autorizar!` (`tx` fake = nil,
  os stubs de fato nao a usam) ANTES de gravar — se `autorizar!` lanca, o `swap!` abaixo nunca roda.
  `chamadas` (atom []) acumula os `m` de cada escrita exercida — prova que o Repo NUNCA escreveu quando o
  controller/authz deve barrar antes."
  [busca-votacao-fn chamadas]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (buscar-votacao [_ _ente-id id] (busca-votacao-fn id))
    (registrar-meu-voto! [_ _ente-id m autorizar!]
      (let [v (busca-votacao-fn (:votacao-id m))]
        (autorizar! nil v)
        (swap! chamadas conj m)
        {:id (:id m) :recibo :ok}))))

(defn- m-voto [votacao-id]
  {:id (random-uuid) :votacao-id votacao-id :voto "sim" :created-by (random-uuid)})

;; ---------- 1: resolver-vereador nil -> nil, sem tocar o Repo ----------

(deftest meu-voto-sem-cadastro-de-vereador-devolve-nil
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] nil)
        registro (registro-stub true true)
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "nominal" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (nil? (controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                     HOJE INSTANTE (m-voto vid)))
        "ator sem cadastro de vereador (resolver-vereador nil) -> nil, mesmo contrato de meu-painel")
    (is (empty? @chamadas) "nunca chamou o Repo (nem chegou perto da sessao/votacao)")))

;; ---------- 2: sessao de outra Casa -> NEGA (authz fina) ----------

(deftest meu-voto-sessao-de-outra-casa-nega
  (let [ente (random-uuid) outro-ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica outro-ente id))  ; escapou da RLS (bug hipotetico)
        resolver-vereador (fn [_ _] (random-uuid))
        registro (registro-stub true true)
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "nominal" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (nega? #(controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                       HOJE INSTANTE (m-voto vid)))
        "sessao de ente alheio -> pode-dirigir-votacao? nega -> 403 na borda")
    (is (empty? @chamadas))))

;; ---------- 3: votacao de outra sessao (amarra falha) -> nil ----------

(deftest meu-voto-votacao-de-outra-sessao-devolve-nil
  (let [ente (random-uuid) sid (random-uuid) outra-sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] (random-uuid))
        registro (registro-stub true true)
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid outra-sid "nominal" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (nil? (controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                     HOJE INSTANTE (m-voto vid)))
        "votacao pertence a OUTRA sessao -> amarra barra -> nil (404 na borda)")
    (is (empty? @chamadas))))

;; ---------- 4: modalidade secreta -> :validacao/invalido ANTES de qualquer policy/tx ----------

(deftest meu-voto-secreta-lanca-antes-da-policy-e-da-tx
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] (random-uuid))
        ;; ambos os fatos PASSARIAM se chegassem a ser avaliados — prova que o guard de secreta roda ANTES.
        registro (registro-stub true true)
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "secreta" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (invalido? #(controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                           HOJE INSTANTE (m-voto vid)))
        "voto secreto pelo proprio celular -> :validacao/invalido")
    (is (empty? @chamadas) "nunca abriu tx/chamou o Repo — a checagem de secreta e' de borda, antes da policy")))

;; ---------- 5: modalidade simbolica com facts passando -> ainda :validacao/invalido ----------

(deftest meu-voto-simbolica-com-policy-ok-ainda-invalido
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] (random-uuid))
        registro (registro-stub true true)  ; mandato+presenca OK, estado aberta -> a POLICY passaria
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "simbolica" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (invalido? #(controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                           HOJE INSTANTE (m-voto vid)))
        "simbolica nao registra voto individual — o DISPATCH de modalidade (pos-policy) e' quem barra")
    (is (empty? @chamadas) "a policy passou (nao negou), mas o case fail-closed nunca chamou registrar-voto!")))

;; ---------- 6: happy path — nominal, aberta, facts OK -> registra com vereador-id RESOLVIDO ----------

(deftest meu-voto-happy-path-registra-com-vereador-id-resolvido
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        vereador-id (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] vereador-id)
        registro (registro-stub true true)
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "nominal" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}
        m (m-voto vid)  ; SEM :vereador-id — o wire/in de meu-voto nao carrega esse campo
        recibo (controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                      HOJE INSTANTE m)]
    (is (= {:id (:id m) :recibo :ok} recibo) "devolve o recibo ecoado pelo Repo")
    (is (= 1 (count @chamadas)) "registrar-voto! chamado exatamente uma vez")
    (is (= vereador-id (:vereador-id (first @chamadas)))
        "o vereador-id gravado e' o RESOLVIDO do ator (anti-forja), nunca vindo de `m`")))

;; ---------- 7/8: NEGA quando so' um dos dois fatos falha (prova que AMBOS sao exigidos) ----------

(deftest meu-voto-nega-quando-mandato-nao-vigente
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] (random-uuid))
        registro (registro-stub false true)  ; mandato NAO vigente, presenca OK
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "nominal" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (nega? #(controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                       HOJE INSTANTE (m-voto vid)))
        "sem mandato vigente -> NEGA, mesmo com presenca OK")
    (is (empty? @chamadas))))

(deftest meu-voto-nega-quando-nao-esta-presente
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] (random-uuid))
        registro (registro-stub true false)  ; mandato OK, presenca NAO
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "nominal" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (nega? #(controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                       HOJE INSTANTE (m-voto vid)))
        "sem presenca registrada nesta sessao -> NEGA, mesmo com mandato OK")
    (is (empty? @chamadas))))

;; ---------- 9: NEGA quando a votacao nao esta' aberta (mesmo com os 2 fatos OK) ----------

(deftest meu-voto-nega-quando-votacao-nao-esta-aberta
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (sessao-canonica ente id))
        resolver-vereador (fn [_ _] (random-uuid))
        registro (registro-stub true true)
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "nominal" "encerrada")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (nega? #(controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                       HOJE INSTANTE (m-voto vid)))
        "votacao 'encerrada' -> NEGA (o check em Clojure puro de estado tambem mora na policy, `and`)")
    (is (empty? @chamadas))))

;; ---------- 10: T2 grupo A achado #4/#5 (ledger Fase 8) — sessao ENCERRADA bloqueia meu-voto ----------
;; A votacao em si continua 'aberta' (o achado ao vivo foi provado em abrir-votacao/pauta, nao aqui — mas
;; `sessao-autorizada` e' o MESMO ponto de checagem para as 4 escritas da familia votacao, entao o proprio
;; celular do vereador tem de recusar tambem: a Mesa ja fechou a sessao, o registro esta congelado).

(defn- conflito-sessao-fechada? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :conflito/sessao-fechada (:tipo (ex-data e))))))

(deftest meu-voto-nega-quando-sessao-esta-encerrada
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        chamadas (atom [])
        consultar-sessao (fn [_ id] (assoc (sessao-canonica ente id) :estado "encerrada"))
        resolver-vereador (fn [_ _] (random-uuid))
        registro (registro-stub true true)
        repo (fake-repo-legislativo (fn [_] (votacao-canonica ente vid sid "nominal" "aberta")) chamadas)
        ator {:ente-id ente :identidade-id (random-uuid)}]
    (is (conflito-sessao-fechada?
         #(controllers/meu-voto repo consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid
                                 HOJE INSTANTE (m-voto vid)))
        "sessao 'encerrada' -> :conflito/sessao-fechada (o diplomat mapeia 409), MESMO com a votacao 'aberta'")
    (is (empty? @chamadas) "nunca chegou perto do Repo — o gate roda em sessao-autorizada, antes da amarra")))
