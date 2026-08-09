(ns oplenario.sessoes.chamada-conduzida-gate-estado-test
  "INTEGRACAO (PG real) — §22.6 eixo C, Etapa 2d: o GATE DE ESTADO em `controllers/registrar-chamada-conduzida`,
  com o Repo-Component DE VERDADE. MESMO racional de `presenca-gate-estado-test` (fatia 2a): o defeito que
  este gate fecha e' de TRANSACAO (a checagem tem de rodar DENTRO da tx da escrita, sobre a sessao lida `FOR
  SHARE`, nunca sobre uma leitura anterior de authz) — so' um teste contra o banco prova isso.

  Cobre tambem A1 (registra + aparece na leitura de `chamada-da-sessao`), A3 (o campo `:chamadas-conduzidas`
  distingue 'ninguem chamou' de 'chamou e a Casa toda faltou' — o teste que justifica a fatia inteira) e A5
  (idempotencia: a chamada pode ser reconduzida na mesma sessao aberta)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.controllers :as controllers])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- ator [ente] {:ente-id ente :identidade-id (random-uuid)})

(defn- roster-de [& vereador-ids]
  (fn [_ente _data] (mapv (fn [v] {:vereador-id v}) vereador-ids)))

(defn- agendar! [ente]
  ;; `agendada-para` e' o que resolve a DATA DE COMPOSICAO do roster (controllers/data-de-referencia, que
  ;; `registrar-chamada-conduzida` chama para congelar o denominador ANTES de tentar escrever) — sem ela uma
  ;; sessao que nunca abriu (ainda 'agendada', ou 'nao_realizada' sem jamais ter aberto) cai em
  ;; :conflito/sessao-sem-data, que e' OUTRO caso (nao o que este ns testa).
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"
                                          :agendada-para (Instant/parse "2026-06-20T13:00:00Z")})))

(defn- transicionar! [ente sid para lock]
  (repo/transicionar-sessao! *repo* ente (cond-> {:id sid :para para :updated-by (random-uuid) :lock-version lock}
                                           (= para "nao_realizada") (assoc :motivo "Falta de quorum"))))

(defn- abrir! [ente sid]
  (transicionar! ente sid "aberta" 0)
  (:aberta-em (repo/buscar-sessao *repo* ente sid)))

(defn- linhas-chamada-conduzida [ente sid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:n (jdbc/execute-one! tx
            ["SELECT count(*) AS n FROM sessoes.chamada_conduzida WHERE ente_id = ? AND sessao_id = ?"
             ente sid])))))

(defn- conduzir! [ente sid roster agora]
  (controllers/registrar-chamada-conduzida *repo* roster (ator ente) sid agora))

(defn- conflito-de-chamada
  "Roda `f`, exige que ela lance `:conflito/chamada` (falha em vez de nil se nada for lancado — senao um gate
  que sumiu passaria como verde)."
  [f]
  (try
    (f)
    (is false "esperava recusa :conflito/chamada, mas a escrita passou")
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (= :conflito/chamada (:tipo (ex-data e))) (str "tag de conflito errada: " (pr-str (ex-data e))))
      {:msg (ex-message e) :dados (ex-data e)})))

;; ---------- A4: o gate de estado (fatia 2a) vale para a chamada conduzida ----------

(deftest gate-recusa-conduzir-a-chamada-em-sessao-encerrada
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        _ (transicionar! ente sid "encerrada" 1)
        antes (linhas-chamada-conduzida ente sid)
        r (conflito-de-chamada #(conduzir! ente sid (roster-de) (.plusSeconds ^Instant aberta-em 3600)))]
    (is (str/includes? (:msg r) "chamada") "a mensagem fala do RECURSO certo (conducao da chamada, nao 'presenca'")
    (is (str/includes? (:msg r) "encerrada") "a mensagem nomeia o estado atual")
    (is (= antes (linhas-chamada-conduzida ente sid))
        "ZERO linha nova em chamada_conduzida — a recusa aconteceu ANTES do INSERT, na mesma tx")))

(deftest gate-recusa-conduzir-a-chamada-em-sessao-arquivada-e-nao-realizada
  (let [ente (random-uuid)
        arq (agendar! ente)
        aberta-em (abrir! ente arq)
        _ (transicionar! ente arq "encerrada" 1)
        _ (transicionar! ente arq "arquivada" 2)
        nr (agendar! ente)
        _ (transicionar! ente nr "nao_realizada" 0)
        agora (.plusSeconds ^Instant aberta-em 3600)]
    (conflito-de-chamada #(conduzir! ente arq (roster-de) agora))
    (is (zero? (linhas-chamada-conduzida ente arq)) "sessao arquivada nao aceita conducao de chamada")
    (conflito-de-chamada #(conduzir! ente nr (roster-de) agora))
    (is (zero? (linhas-chamada-conduzida ente nr)) "sessao nao realizada nao aceita conducao de chamada")))

(deftest gate-sessao-inexistente-lanca-conflito-nao-o-nil-de-404
  ;; `registrar-chamada-conduzida` ja' devolveria nil (-> 404) antes de chegar ao Repo, mas o Repo tambem
  ;; fecha fail-closed se for chamado direto com uma sessao que sumiu entre a authz e a escrita (TOCTOU
  ;; estrutural, na pratica inalcancavel por esta porta — mesmo cinto de seguranca de `transicionar!`).
  (let [ente (random-uuid)]
    (conflito-de-chamada #(repo/registrar-chamada-conduzida! *repo* ente
                            {:id (random-uuid) :sessao-id (random-uuid) :conduzida-por (random-uuid)
                             :membros-da-casa 10 :ocorrido-em (Instant/now) :agora (Instant/now)
                             :created-by (random-uuid)}))))

;; ---------- nao-regressao: agendada/aberta/suspensa continuam aceitando ----------

(deftest sessao-agendada-aberta-e-suspensa-aceitam-conducao-de-chamada
  (let [ente (random-uuid)
        ag (agendar! ente)
        agora (Instant/now)]
    (is (some? (:id (conduzir! ente ag (roster-de) agora))) "'agendada' aceita — precede a abertura")
    (let [sus (agendar! ente)
          aberta-em (abrir! ente sus)]
      (transicionar! ente sus "suspensa" 1)
      (is (some? (:id (conduzir! ente sus (roster-de) (.plusSeconds ^Instant aberta-em 3600))))
          "'suspensa' aceita"))))

;; ---------- A1 + A3: registra, aparece na leitura, e DISTINGUE de "ninguem chamou" ----------

(deftest a1-a3-conduzir-a-chamada-aparece-na-leitura-e-distingue-de-nunca-ter-sido-conduzida
  (let [ente (random-uuid)
        v1 (random-uuid) v2 (random-uuid) v3 (random-uuid)
        nunca-chamada (agendar! ente)
        com-chamada   (agendar! ente)
        aberta-nunca  (abrir! ente nunca-chamada)
        aberta-com    (abrir! ente com-chamada)
        agora (max-key #(.getEpochSecond ^Instant %)
                       (.plusSeconds ^Instant aberta-nunca 60) (.plusSeconds ^Instant aberta-com 60))
        relogio (tempo/relogio-fixo agora)
        roster (roster-de v1 v2 v3)]
    ;; sessao NUNCA chamada: sem-registro-de-presenca=true E chamadas-conduzidas VAZIA.
    (let [leitura-nunca (controllers/chamada-da-sessao *repo* roster (ator ente) nunca-chamada relogio)]
      (is (:sem-registro-de-presenca leitura-nunca))
      (is (empty? (:chamadas-conduzidas leitura-nunca))
          "ninguem conduziu a chamada ainda — a lista tem de vir VAZIA"))
    ;; sessao COM chamada conduzida e ZERO presencas: sem-registro-de-presenca=true (continua verdadeiro — o
    ;; read-model de presenca_evento nao mudou) MAS chamadas-conduzidas NAO-VAZIA (o dado NOVO que desambigua).
    (let [recibo (conduzir! ente com-chamada roster (.plusSeconds ^Instant aberta-com 30))
          leitura-com (controllers/chamada-da-sessao *repo* roster (ator ente) com-chamada relogio)
          ato (first (:chamadas-conduzidas leitura-com))]
      (is (:sem-registro-de-presenca leitura-com)
          "presenca_evento continua em zero linhas: a Casa toda faltou (nao e' 'ninguem chamou')")
      (is (= 1 (count (:chamadas-conduzidas leitura-com)))
          "ESTE e' o teste que justifica a fatia: a mesma condicao de zero-presenca agora vem acompanhada de
           um ato de chamada — as duas sessoes acima NAO SAO MAIS INDISTINGUIVEIS")
      (is (= (:id recibo) (:id ato)) "o ato na leitura e' o mesmo que o recibo do registro")
      (is (= 3 (:membros-da-casa ato))
          "o denominador CONGELADO veio da MESMA fonte do roster (3 membros), nao de uma conta a parte")
      (is (some? (:conduzida-por ato)) "quem conduziu esta na leitura"))))

;; ---------- A5: idempotencia — reconduzir a chamada na MESMA sessao aberta e' aceito ----------

(deftest a5-chamada-pode-ser-reconduzida-na-mesma-sessao-via-controller
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        roster (roster-de (random-uuid) (random-uuid))]
    (conduzir! ente sid roster (.plusSeconds ^Instant aberta-em 60))
    (conduzir! ente sid roster (.plusSeconds ^Instant aberta-em 120))
    (is (= 2 (linhas-chamada-conduzida ente sid))
        "duas conducoes na mesma sessao aberta — cada uma e' um fato historico apartado (decisao A5)")))
