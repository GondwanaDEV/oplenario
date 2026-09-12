(ns oplenario.sessoes.chamada-conduzida-db-test
  "INTEGRACAO (PG real) — §22.6 eixo C, Etapa 2d: persistencia do ATO DE CHAMADA CONDUZIDA
  (`sessoes.db.chamada`), contra a migration 0072. `objeto`/`vocabulario`: esta tabela NAO introduz um
  enum/CHECK de texto (contraste com `incidente_processual`) — o unico invariante e' NUMERICO
  (`membros_da_casa >= 0`), e o que se prova aqui e' que `logic/validar-membros-da-casa` (Clojure) e o CHECK
  do banco NAO DIVERGEM: um valor que a validacao em Clojure recusaria tambem e' recusado pelo banco quando o
  INSERT o contorna (a mesma disciplina anti-fixture-redigitado da fatia 2d, aplicada a um invariante
  numerico em vez de um enum de texto)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.chamada :as chamada]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.chamada :as mod]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(def ^:private f0 (java.time.Instant/parse "2026-06-29T14:00:00Z"))
(defn- mais [^java.time.Instant t s] (.plusSeconds t s))

(defn- agendar! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                            :tipo-sessao "ordinaria" :modalidade "presencial"})))

(defn- registrar! [tx ente sid extra]
  (chamada/registrar!
   tx (merge {:id (random-uuid) :ente-id ente :sessao-id sid :conduzida-por (random-uuid)
              :membros-da-casa 15 :ocorrido-em f0 :created-by (random-uuid)} extra)))

;; ---------- A1: registrar + aparece na leitura ----------

(deftest registrar-e-listar-chamada-conduzida
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              condutor (random-uuid)
              r (registrar! tx ente sid {:conduzida-por condutor :membros-da-casa 21})
              lst (chamada/listar-da-sessao tx ente sid)]
          (is (some? (:id r)) "recibo carrega o id do ato")
          (is (some? (:ocorrido-em r)) "recibo carrega o instante de DOMINIO")
          (is (some? (:registrado-em r)) "recibo carrega o instante de AUDIT (RETURNING do banco)")
          (is (= 1 (count lst)) "o ato aparece na listagem da sessao")
          (let [linha (first lst)]
            (is (= condutor (:conduzida-por linha)) "quem conduziu")
            (is (= 21 (:membros-da-casa linha)) "o denominador CONGELADO")
            (is (= f0 (:ocorrido-em linha)) "quando foi conduzida")
            (is (m/validate mod/ChamadaConduzida (assoc linha :ente-id ente :sessao-id sid))
                "bate o model")))))))

;; ---------- A3 (nivel de persistencia): sessao SEM ato != sessao com ato registrado ----------

(deftest sessao-sem-ato-tem-listagem-vazia-sessao-com-ato-nao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sem-chamada (agendar! tx ente)
              com-chamada (agendar! tx ente)]
          (registrar! tx ente com-chamada {})
          (is (empty? (chamada/listar-da-sessao tx ente sem-chamada))
              "sessao onde ninguem conduziu a chamada: lista vazia")
          (is (= 1 (count (chamada/listar-da-sessao tx ente com-chamada)))
              "sessao onde a chamada foi conduzida: lista NAO vazia — a chamada ACONTECEU, mesmo que
               presenca_evento continue em zero linhas (a Casa toda pode ter faltado)"))))))

;; ---------- A5: idempotencia — a chamada PODE ser reconduzida na mesma sessao ----------

(deftest chamada-pode-ser-conduzida-mais-de-uma-vez-na-mesma-sessao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (registrar! tx ente sid {:ocorrido-em f0 :membros-da-casa 15})
          (registrar! tx ente sid {:ocorrido-em (mais f0 3600) :membros-da-casa 16})
          (let [lst (chamada/listar-da-sessao tx ente sid)]
            (is (= 2 (count lst)) "as DUAS conducoes persistem — cada uma e' um fato historico apartado")
            (is (= [15 16] (mapv :membros-da-casa lst)) "ordem cronologica; cada ato guarda o SEU denominador")))))))

;; ---------- guards (auditoria + CHECK numerico + FK) ----------

(deftest chamada-conduzida-guards
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (is (thrown? Exception (registrar! tx ente sid {:created-by nil}))
              "created-by obrigatorio (trilha de auditoria, Inv.10)")
          (is (thrown? Exception (registrar! tx ente sid {:membros-da-casa -1}))
              "membros-da-casa negativo lanca em logic (fail-closed, ANTES do banco)")
          (is (thrown? Exception (registrar! tx ente (random-uuid) {}))
              "ato em sessao inexistente viola a FK same-schema"))))))

;; ---------- paridade Clojure<->banco do invariante numerico (analogo a A2, sem enum de texto) ----------

(deftest membros-da-casa-negativo-tambem-e-recusado-pelo-CHECK-do-banco-quando-a-validacao-e-contornada
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          ;; INSERT cru, contornando `chamada/registrar!` (e portanto `logic/validar-membros-da-casa`) —
          ;; prova que o banco SOZINHO tambem barra, e' o mesmo invariante dos dois lados (paridade).
          (is (thrown? Exception
                       (jdbc/execute-one! tx
                         (sql/format {:insert-into :sessoes.chamada_conduzida
                                      :values [{:id (random-uuid) :ente_id ente :sessao_id sid
                                                :conduzida_por (random-uuid) :membros_da_casa -5
                                                :ocorrido_em f0 :created_by (random-uuid)
                                                :efetivado_em [:now]}]})))
              "o CHECK membros_da_casa >= 0 recusa o mesmo valor que logic/validar-membros-da-casa recusaria"))))))

;; ---------- append-only ----------

(deftest chamada-conduzida-e-append-only
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {iid :id} (registrar! tx ente sid {})]
          (is (thrown? Exception
                       (jdbc/execute-one! tx
                         (sql/format {:update :sessoes.chamada_conduzida
                                      :set {:membros_da_casa 99}
                                      :where [:and [:= :ente_id ente] [:= :id iid]]})))
              "UPDATE e' barrado (append-only: ato regimental imutavel — corrigir = novo ato, nunca reescrita)"))))))

;; ---------- guard fail-closed em logic.clj ----------

(deftest vocabularios-chamada-conduzida
  (is (thrown? Exception (logic/validar-membros-da-casa -1)) "denominador negativo invalido")
  (is (nil? (logic/validar-membros-da-casa 0)) "denominador zero e' valido (Casa sem membros vigentes hoje)"))

;; ---------- fatia "truncamento-familia" sitio (c): fail-closed, nao -total ----------
;; O teto de ESCRITA (`registrar-chamada-conduzida!`, via `contar-da-sessao`) ja' impede a cardinalidade de
;; passar de `teto-de-atos-de-chamada` pelo caminho normal da aplicacao — entao `listar-da-sessao` so' pode
;; ver mais que o teto se ALGUEM contornar esse caminho (import de acervo legado direto no banco, o mesmo
;; cenario que a docstring do teto ja' citava). Este teste simula exatamente isso: chama `chamada/registrar!`
;; DIRETO (contornando o gate de `repositorio.clj`, que so' vive uma camada acima) mais vezes que o teto.

(deftest listar-da-sessao-acima-do-teto-lanca-em-vez-de-truncar-em-silencio
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          ;; teto-de-atos-de-chamada + 1 registros, cada um com `conduzida-por`/`ocorrido-em` proprios (a
          ;; dedup de `ato-recente-do-ator` vive no Repo, nao em `chamada/registrar!` — nao entra aqui).
          (dotimes [i (inc logic/teto-de-atos-de-chamada)]
            (registrar! tx ente sid {:conduzida-por (random-uuid) :ocorrido-em (mais f0 i)}))
          (is (thrown? Exception (chamada/listar-da-sessao tx ente sid))
              "acima do teto: lanca, nunca devolve uma pagina cortada calada"))))))

(deftest listar-da-sessao-no-teto-exato-nao-lanca
  ;; guarda de simetria: o teste acima nao pode passar so' porque QUALQUER excesso lanca — no teto EXATO
  ;; (o maximo que o caminho normal da aplicacao produz) a leitura continua devolvendo a lista inteira.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (dotimes [i logic/teto-de-atos-de-chamada]
            (registrar! tx ente sid {:conduzida-por (random-uuid) :ocorrido-em (mais f0 i)}))
          (is (= logic/teto-de-atos-de-chamada (count (chamada/listar-da-sessao tx ente sid)))
              "no teto exato, a leitura NAO lanca — devolve todas as linhas"))))))

(deftest listar-da-sessao-acima-do-teto-nomeia-medido-e-teto
  ;; a `ex-data` carrega o que o interceptor global precisa para responder 422 com `:medido`/`:teto`
  ;; (`limite-excedido` em interceptors.clj reconhece qualquer `:tipo` no namespace `limite`).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (dotimes [i (inc logic/teto-de-atos-de-chamada)]
            (registrar! tx ente sid {:conduzida-por (random-uuid) :ocorrido-em (mais f0 i)}))
          (try
            (chamada/listar-da-sessao tx ente sid)
            (is false "deveria ter lancado")
            (catch Exception e
              (let [d (ex-data e)]
                (is (= :limite/atos-de-chamada-excedido (:tipo d)))
                (is (= logic/teto-de-atos-de-chamada (:teto d)))
                (is (= (inc logic/teto-de-atos-de-chamada) (:medido-ao-menos d))
                    "medido-ao-menos = teto+1 (o driver para de materializar no primeiro excedente)")))))))))
