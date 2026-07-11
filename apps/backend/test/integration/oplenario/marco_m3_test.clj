(ns oplenario.marco-m3-test
  "MARCO M3 — Onda C3 Slice 3 Task 4: 'o vereador vota do proprio celular' de ponta-a-ponta contra
  Postgres real. Chama `controllers/meu-voto` DIRETO (sem HTTP — mesmo padrao de marco_m2_test) com os
  Repo-Components REAIS do sistema (`repo-legislativo`/`repo-sessoes`/`repo-cadastros`) + o RegistroFatos
  REAL (`registro-fatos` de *sys*), provando a costura completa: `resolver-vereador` (identidade->vereador
  desta Casa) + `consultar-sessao` (authz herdada) + a policy fina — `tem_mandato_vigente` (cadastros,
  fato de DATA) E `esta_presente_em` (sessoes, fato de INSTANTE) avaliados em DUAS chamadas SEPARADAS de
  `motor/politica-dsl` (§3.2 do design, `docs/superpowers/specs/2026-07-11-onda-c-slice3-cockpit-votacao-
  design.md`) — nunca uma so' expressao compartilhando o slot `:agora` do motor/runtime.clj.

  Prova:
   - HAPPY PATH: mandato vigente + presenca registrada nesta sessao -> registra o voto NOMINAL com o
     vereador-id RESOLVIDO (nunca de `m`).
   - NEGA por ausencia (sem evento de presenca).
   - NEGA por mandato cassado (mudanca de estado real, mesmo racional de marco_m2).
   - NEGA (na verdade LANCA :validacao/invalido) por modalidade secreta — o pre-check trivial, ANTES da
     policy/tx.
   - SANIDADE hoje()/agora(): um par de valores ONDE MISTURAR os dois tipos daria um resultado DIFERENTE
     do correto — ver o comentario no teste dedicado."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.config :as config]
            [oplenario.kernel.autorizacao :as autz]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sess]
            [oplenario.sistema :as sistema])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *sys* nil)

(defn- seed-referencias!
  "Mesmo fixture de marco_m2_test — referencias (sem ente_id) exigidas por `criar-ente!` (FK a
  cadastros.municipios). Idempotente."
  [ds]
  (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
  (referencia/inserir-tribunal! ds {:codigo "TCE-CE" :nome "Tribunal de Contas do Estado do Ceara" :uf "CE" :tipo "estadual"}))

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (seed-referencias! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

;; ---------- helpers de seed (adaptados de marco_m2_test — sao `defn-`, nao importaveis cross-ns) ----------

(defn- seed-casa!
  "Semeia o TENANT de uma Casa com um vereador de mandato vigente. `vigencia-inicio` e' parametrizavel (o
  teste de sanidade hoje()/agora() precisa de um valor CRAVADO na fronteira, nao o default de marco_m2).
  Devolve {:ente :identidade :vereador-id}."
  ([sys] (seed-casa! sys (LocalDate/of 2025 1 1)))
  ([{:keys [repo-cadastros]} vigencia-inicio]
   (let [ente (random-uuid) ident (random-uuid)
         vereador (random-uuid) legislatura (random-uuid) mandato (random-uuid)]
     (repo-cad/criar-ente! repo-cadastros ente {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza"})
     (repo-cad/criar-legislatura! repo-cadastros ente {:id legislatura :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
     (repo-cad/criar-vereador! repo-cadastros ente {:id vereador :ente-id ente :identidade-id ident :nome "Maria Souza" :nome-parlamentar "Maria do Povo"})
     (repo-cad/criar-mandato! repo-cadastros ente {:id mandato :ente-id ente :vereador-id vereador :legislatura-id legislatura
                                                   :partido "PT" :estado "vigente" :natureza "titular"
                                                   :vigencia-inicio vigencia-inicio})
     {:ente ente :identidade ident :vereador-id vereador})))

(defn- cassar-mandato! [repo-cadastros ente identidade fim-efetivo]
  (let [v  (repo-cad/vereador-por-identidade repo-cadastros ente identidade)
        ms (repo-cad/mandatos-do-vereador repo-cadastros ente (:id v))]
    (repo-cad/mudar-estado-mandato! repo-cadastros ente {:id (:id (first ms)) :estado "cassado" :fim-efetivo fim-efetivo})))

(defn- nega? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (autz/negado? e))))

(defn- invalido? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

;; ---------- fiacao de meu-voto contra os Repo-Components reais (mesmo padrao de `rotas/montar`) ----------

(defn- consultar-sessao-fn [repo-sessoes]
  (fn [ente-id sessao-id] (repo-sess/buscar-sessao repo-sessoes ente-id sessao-id)))

(defn- resolver-vereador-fn [repo-cadastros]
  (fn [ente-id identidade-id] (:id (repo-cad/vereador-por-identidade repo-cadastros ente-id identidade-id))))

(defn- protocolar-materia! [repo-legislativo ente]
  (:id (repo-leg/protocolar! repo-legislativo ente
         {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
          :ementa "Materia de teste M3"})))

(defn- agendar-sessao! [repo-sessoes ente]
  (:id (repo-sess/agendar-sessao! repo-sessoes ente
         {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"})))

(defn- abrir-votacao! [repo-legislativo ente sessao-id materia-id modalidade]
  (:id (repo-leg/abrir-votacao! repo-legislativo ente
         {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id materia-id
          :modalidade modalidade :quorum-tipo "maioria_simples" :sessao-id sessao-id})))

(defn- registrar-presenca! [repo-sessoes ente sessao-id vereador-id ocorrido-em]
  (repo-sess/registrar-presenca! repo-sessoes ente
    {:id (random-uuid) :sessao-id sessao-id :vereador-id vereador-id :tipo "entrada" :modalidade "plenario"
     :fonte "autoatendimento" :ocorrido-em ocorrido-em}))

(def ^:private HOJE (LocalDate/of 2026 7 11))
(def ^:private INSTANTE (Instant/parse "2026-07-11T12:00:00Z"))
(def ^:private ANTES-DO-INSTANTE (Instant/parse "2026-07-11T11:00:00Z"))

;; ---------- 1: happy path — mandato vigente + presenca registrada -> registra o voto ----------

(deftest m3-happy-path-registra-voto-com-vereador-id-resolvido
  (let [{:keys [datasource repo-legislativo repo-sessoes repo-cadastros registro-fatos]} *sys*
        {:keys [ente identidade vereador-id]} (seed-casa! {:repo-cadastros repo-cadastros})
        sid (agendar-sessao! repo-sessoes ente)
        pid (protocolar-materia! repo-legislativo ente)
        vid (abrir-votacao! repo-legislativo ente sid pid "nominal")]
    (registrar-presenca! repo-sessoes ente sid vereador-id ANTES-DO-INSTANTE)
    (let [ator {:ente-id ente :identidade-id identidade}
          m {:id (random-uuid) :votacao-id vid :voto "sim" :created-by nil}
          recibo (controllers/meu-voto repo-legislativo (consultar-sessao-fn repo-sessoes)
                                        (resolver-vereador-fn repo-cadastros) registro-fatos
                                        ator sid vid HOJE INSTANTE m)]
      (is (= (:id m) (:id recibo)) "recibo carrega o id do voto")
      (let [votos (repo-leg/votos-da-votacao repo-legislativo ente vid)]
        (is (= 1 (count votos)) "exatamente um voto registrado")
        (is (= vereador-id (:vereador-id (first votos)))
            "o voto persistido carrega o vereador-id RESOLVIDO (identidade->vereador desta Casa), nunca de `m`")))
    (is (some? datasource) "sanity: fixture bindou o sistema")))

;; ---------- 2: NEGA por ausencia (sem evento de presenca nesta sessao) ----------

(deftest m3-nega-por-ausencia
  (let [{:keys [repo-legislativo repo-sessoes repo-cadastros registro-fatos]} *sys*
        {:keys [ente identidade vereador-id]} (seed-casa! {:repo-cadastros repo-cadastros})
        sid (agendar-sessao! repo-sessoes ente)
        pid (protocolar-materia! repo-legislativo ente)
        vid (abrir-votacao! repo-legislativo ente sid pid "nominal")]
    ;; SEM registrar-presenca! — o vereador tem mandato vigente mas nunca "chegou" nesta sessao.
    (let [ator {:ente-id ente :identidade-id identidade}
          m {:id (random-uuid) :votacao-id vid :voto "sim" :created-by nil}]
      (is (nega? #(controllers/meu-voto repo-legislativo (consultar-sessao-fn repo-sessoes)
                                         (resolver-vereador-fn repo-cadastros) registro-fatos
                                         ator sid vid HOJE INSTANTE m))
          "sem presenca registrada nesta sessao -> esta_presente_em=false -> NEGA")
      (is (empty? (repo-leg/votos-da-votacao repo-legislativo ente vid)) "nenhum voto persistido")
      (is (some? vereador-id) "sanity: o seed devolveu o vereador-id"))))

;; ---------- 3: NEGA por mandato cassado (mudanca de estado real) ----------

(deftest m3-nega-por-mandato-cassado
  (let [{:keys [repo-legislativo repo-sessoes repo-cadastros registro-fatos]} *sys*
        {:keys [ente identidade vereador-id]} (seed-casa! {:repo-cadastros repo-cadastros})
        sid (agendar-sessao! repo-sessoes ente)
        pid (protocolar-materia! repo-legislativo ente)
        vid (abrir-votacao! repo-legislativo ente sid pid "nominal")]
    (registrar-presenca! repo-sessoes ente sid vereador-id ANTES-DO-INSTANTE)
    (cassar-mandato! repo-cadastros ente identidade HOJE)
    (let [ator {:ente-id ente :identidade-id identidade}
          m {:id (random-uuid) :votacao-id vid :voto "sim" :created-by nil}]
      (is (nega? #(controllers/meu-voto repo-legislativo (consultar-sessao-fn repo-sessoes)
                                         (resolver-vereador-fn repo-cadastros) registro-fatos
                                         ator sid vid HOJE INSTANTE m))
          "mandato cassado (mesmo com presenca OK) -> tem_mandato_vigente=false -> NEGA")
      (is (empty? (repo-leg/votos-da-votacao repo-legislativo ente vid)) "nenhum voto persistido"))))

;; ---------- 4: modalidade secreta -> :validacao/invalido (pre-check trivial, NAO authz) ----------

(deftest m3-secreta-lanca-invalido-nao-authz
  (let [{:keys [repo-legislativo repo-sessoes repo-cadastros registro-fatos]} *sys*
        {:keys [ente identidade vereador-id]} (seed-casa! {:repo-cadastros repo-cadastros})
        sid (agendar-sessao! repo-sessoes ente)
        pid (protocolar-materia! repo-legislativo ente)
        vid (abrir-votacao! repo-legislativo ente sid pid "secreta")]
    ;; mandato + presenca AMBOS OK — teria passado a policy, mas a votacao e' secreta.
    (registrar-presenca! repo-sessoes ente sid vereador-id ANTES-DO-INSTANTE)
    (let [ator {:ente-id ente :identidade-id identidade}
          m {:id (random-uuid) :votacao-id vid :voto "sim" :created-by nil}]
      (is (invalido? #(controllers/meu-voto repo-legislativo (consultar-sessao-fn repo-sessoes)
                                             (resolver-vereador-fn repo-cadastros) registro-fatos
                                             ator sid vid HOJE INSTANTE m))
          "voto secreto pelo proprio celular -> :validacao/invalido (pre-check de borda)")
      (is (empty? (repo-leg/votos-da-votacao repo-legislativo ente vid))
          "nominal continua vazio (voto secreto nunca vai por votos-da-votacao, mas tambem nao foi tentado)"))))

;; ---------- 5/6: SANIDADE hoje()/agora() — os dois fatos avaliam com o TIPO certo, em chamadas SEPARADAS ----------
;;
;; O design (§3.2) exige DUAS chamadas de `motor/politica-dsl` porque `motor/runtime.clj` tem hoje() e
;; agora() lendo do MESMO slot `:agora` do contexto — se o controller um dia regredisse e passasse o
;; valor ERRADO pra' UMA das duas chamadas (ex.: `instante` (Instant) onde `hoje` (LocalDate) era
;; esperado, ou vice-versa), o Postgres NAO lanca (kernel/db-tipos faz o bind silencioso: Instant vira
;; timestamptz via OffsetDateTime, LocalDate vira date nativo — Postgres tem cast IMPLICITO date<->
;; timestamptz, entao o valor do tipo errado ainda compara, so' que contra a DATA/HORA ERRADA).
;;
;; Este teste crava um par de valores onde ISSO IMPORTA: a vigencia_inicio do mandato = EXATAMENTE
;; `HOJE-SANIDADE` (fronteira <=, so' passa comparado contra 11/07 ou depois) e `INSTANTE-SANIDADE` e'
;; UMA SEMANA ANTES (05/07) — bem separado em DIA, nao so' em hora. Se o controller trocasse e
;; alimentasse `tem_mandato_vigente(...)` com o valor de `instante` (05/07, mais cedo) no lugar de `hoje`
;; (11/07), a comparacao viraria `vigencia_inicio(11/07) <= data(05/07)` = FALSE — o teste PEGARIA a
;; regressao (o `is` abaixo esperaria true e receberia false). Com o controller correto (que usa DUAS
;; chamadas de politica-dsl com `:agora` distinto cada), o resultado e' o esperado: TRUE.
(deftest m3-hoje-e-instante-nao-se-confundem
  (let [{:keys [repo-legislativo repo-sessoes repo-cadastros registro-fatos]} *sys*
        hoje-sanidade (LocalDate/of 2026 7 11)
        instante-sanidade (Instant/parse "2026-07-05T10:00:00Z")           ; uma SEMANA antes de hoje-sanidade
        presenca-sanidade (Instant/parse "2026-07-05T09:00:00Z")           ; antes de instante-sanidade (mesmo dia)
        {:keys [ente identidade vereador-id]} (seed-casa! {:repo-cadastros repo-cadastros} hoje-sanidade)
        sid (agendar-sessao! repo-sessoes ente)
        pid (protocolar-materia! repo-legislativo ente)
        vid (abrir-votacao! repo-legislativo ente sid pid "nominal")]
    (registrar-presenca! repo-sessoes ente sid vereador-id presenca-sanidade)
    (let [ator {:ente-id ente :identidade-id identidade}
          m {:id (random-uuid) :votacao-id vid :voto "sim" :created-by nil}
          recibo (controllers/meu-voto repo-legislativo (consultar-sessao-fn repo-sessoes)
                                        (resolver-vereador-fn repo-cadastros) registro-fatos
                                        ator sid vid hoje-sanidade instante-sanidade m)]
      (is (= (:id m) (:id recibo))
          "tem_mandato_vigente usou `hoje-sanidade` (LocalDate, 11/07) E esta_presente_em usou
           `instante-sanidade` (Instant, 05/07) em avaliacoes SEPARADAS — se o controller regredisse e
           trocasse os dois valores, vigencia_inicio(11/07)<=data(05/07) daria false e este `is` falharia")
      (is (= vereador-id (:vereador-id (first (repo-leg/votos-da-votacao repo-legislativo ente vid))))))))
