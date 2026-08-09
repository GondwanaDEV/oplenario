(ns oplenario.sessoes.presenca-gate-estado-test
  "INTEGRACAO (PG real) — Etapa 2 da CHAMADA, fatia 2a: o GATE DE ESTADO e o CLAMP DA HORA nas DUAS bordas de
  escrita de presenca (`controllers/registrar-presenca`, a Mesa; `controllers/confirmar-minha-presenca`, o
  autoatendimento do vereador), com o Repo-Component DE VERDADE.

  Por que integracao e nao unit: o defeito que esta fatia fecha nao e' de calculo, e' de TRANSACAO. Ate' aqui
  o controller lia a sessao numa tx (p/ a authz) e escrevia noutra — entre as duas a Mesa podia encerrar a
  sessao, e o INSERT em `presenca_evento` (append-only, sem CAS) entrava mesmo assim, mudando o quorum de uma
  votacao ja realizada. So' um teste contra o banco prova que a checagem passou para DENTRO da tx da escrita
  (e o `FOR SHARE` sobre a linha da sessao e' o que serializa isso com `transicionar!`).

  Os casos contam as LINHAS de `presenca_evento` antes e depois: um 409 que ainda assim grava a linha e' pior
  que nenhum gate, porque mente para o operador."
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
            [oplenario.sessoes.adapters.out.presenca :as adapters-out]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.logic :as logic])
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

(defn- agendar!
  "`agendada-para` = AGORA (nao um literal): desde a revisao da Etapa 2 ela resolve duas coisas — a DATA DE
  REFERENCIA do roster (gate de assento) e o DIA CIVIL que e' o piso da janela. Ancorar no relogio do
  container mantem os instantes deste ns (todos derivados de `aberta_em`, carimbada pelo banco) dentro do
  mesmo dia civil; um literal de junho colocaria o piso a meses de distancia e o teste viraria outra coisa."
  [ente]
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"
                                          :agendada-para (Instant/now)})))

(defn- transicionar! [ente sid para lock]
  (repo/transicionar-sessao! *repo* ente (cond-> {:id sid :para para :updated-by (random-uuid) :lock-version lock}
                                           (= para "nao_realizada") (assoc :motivo "Falta de quorum"))))

(defn- abrir! [ente sid]
  (transicionar! ente sid "aberta" 0)
  ;; a `aberta_em` e' carimbada pelo BANCO (now() da tx de transicao) — os instantes do teste sao derivados
  ;; DELA, nunca de um literal, senao o teste vira uma aposta sobre o relogio do container.
  (:aberta-em (repo/buscar-sessao *repo* ente sid)))

(defn- linhas-de-presenca [ente sid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:n (jdbc/execute-one! tx ["SELECT count(*) AS n FROM sessoes.presenca_evento WHERE ente_id = ? AND sessao_id = ?"
                                 ente sid])))))

(defn- roster-com
  "O seam `roster-da-casa` que a revisao da Etapa 2 passou a exigir tambem nas escritas de presenca. Este ns
  testa o gate de ESTADO/JANELA, entao o vereador SEMPRE tem cadeira aqui — o gate de assento e' testado
  a parte (`presenca-lote-http-in-test/lote-com-uuid-fora-do-roster-409-e-nenhuma-linha-grava`)."
  [& vereador-ids]
  (fn [_ente _data] (mapv (fn [v] {:vereador-id v}) vereador-ids)))

(defn- registrar! [ente sid vereador-id ocorrido-em agora]
  (controllers/registrar-presenca *repo* (roster-com vereador-id) (ator ente)
    {:sessao-id sid :vereador-id vereador-id :tipo "entrada" :modalidade "plenario" :ocorrido-em ocorrido-em}
    agora))

(defn- conflito-de-presenca
  "Roda `f`, exige que ela lance `:conflito/sessao-nao-aceita-presenca` e devolve {:msg :dados}. Falha (em vez
  de devolver nil) se nada for lancado — senao um gate que sumiu passaria como verde."
  [f]
  (try
    (f)
    (is false "esperava recusa :conflito/sessao-nao-aceita-presenca, mas a escrita passou")
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (= :conflito/sessao-nao-aceita-presenca (:tipo (ex-data e)))
          (str "tag de conflito errada: " (pr-str (ex-data e))))
      {:msg (ex-message e) :dados (ex-data e)})))

;; ---------- G4: a Mesa nao registra presenca em sessao ENCERRADA ----------

(deftest g4-registrar-presenca-em-sessao-encerrada-e-recusado-sem-gravar
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        _ (transicionar! ente sid "encerrada" 1)
        antes (linhas-de-presenca ente sid)
        r (conflito-de-presenca #(registrar! ente sid (random-uuid)
                                             (.plusSeconds ^Instant aberta-em 60)
                                             (.plusSeconds ^Instant aberta-em 3600)))]
    (is (str/includes? (:msg r) "encerrada") "a mensagem nomeia o estado atual da sessao (acionavel)")
    (is (= antes (linhas-de-presenca ente sid))
        "ZERO linha nova em presenca_evento — a recusa aconteceu ANTES do INSERT, na mesma tx")))

(deftest g4b-registrar-presenca-em-sessao-arquivada-e-nao-realizada-e-recusado
  (let [ente (random-uuid)
        arq (agendar! ente)
        aberta-em (abrir! ente arq)
        _ (transicionar! ente arq "encerrada" 1)
        _ (transicionar! ente arq "arquivada" 2)
        nr (agendar! ente)
        _ (transicionar! ente nr "nao_realizada" 0)
        agora (.plusSeconds ^Instant aberta-em 3600)]
    (conflito-de-presenca #(registrar! ente arq (random-uuid) (.plusSeconds ^Instant aberta-em 60) agora))
    (is (zero? (linhas-de-presenca ente arq)) "sessao arquivada nao recebe presenca")
    ;; `nao_realizada` nunca abriu: nao ha aberta_em, e o instante so' precisa nao ser futuro.
    (conflito-de-presenca #(registrar! ente nr (random-uuid) agora agora))
    (is (zero? (linhas-de-presenca ente nr)) "sessao nao realizada nao recebe presenca")))

;; ---------- G5: o autoatendimento do vereador tem o MESMO gate ----------

(deftest g5-confirmar-presenca-em-sessao-encerrada-e-recusado-sem-gravar
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        _ (transicionar! ente sid "encerrada" 1)
        vid (random-uuid)
        instante (.plusSeconds ^Instant aberta-em 3600)
        r (conflito-de-presenca
           #(controllers/confirmar-minha-presenca *repo* (roster-com vid) (fn [_ _] vid) (ator ente) sid instante))]
    (is (str/includes? (:msg r) "encerrada"))
    (is (zero? (linhas-de-presenca ente sid))
        "a porta self-service do vereador nao e' um bypass do gate da Mesa")))

;; ---------- G6: nao-regressao — sessao viva continua gravando ----------

(deftest g6-sessao-aberta-registra-presenca-como-antes
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        vid (random-uuid)
        recibo (registrar! ente sid vid (.plusSeconds ^Instant aberta-em 60) (.plusSeconds ^Instant aberta-em 3600))]
    (is (some? (:id recibo)) "o recibo carrega o id do evento")
    (is (= 1 (linhas-de-presenca ente sid)) "a linha entrou")))

(deftest g6b-sessao-agendada-e-suspensa-tambem-registram
  (let [ente (random-uuid)
        ag (agendar! ente)
        agora (Instant/now)]
    ;; `agendada`: a chamada de quorum PRECEDE a abertura — se este caso recusasse, a Mesa nao teria como
    ;; apurar quorum para abrir a sessao.
    (is (some? (:id (registrar! ente ag (random-uuid) agora agora))))
    (is (= 1 (linhas-de-presenca ente ag)))
    (let [sus (agendar! ente)
          aberta-em (abrir! ente sus)]
      (transicionar! ente sus "suspensa" 1)
      (is (some? (:id (registrar! ente sus (random-uuid)
                                  (.plusSeconds ^Instant aberta-em 60)
                                  (.plusSeconds ^Instant aberta-em 3600)))))
      (is (= 1 (linhas-de-presenca ente sus))))))

;; ---------- G7: o CLAMP da hora declarada ----------

(deftest g7-instante-fora-da-janela-da-sessao-e-recusado
  ;; REVISAO da Etapa 2: o piso deixou de ser `aberta_em` e passou a ser o DIA CIVIL da sessao. Chegar ANTES
  ;; do martelo e' o caso NORMAL da chamada (a folha de presenca registra horas de chegada), e recusa-lo
  ;; tornava a hora real irregistravel — ver `g7b`. O que o piso barra e' o fato de OUTRO DIA. O teto ganhou
  ;; `logic/tolerancia-de-relogio` (skew browser/JVM/Postgres), entao o "futuro" tem de passar dela.
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        agora (.plusSeconds ^Instant aberta-em 3600)
        outro-dia (.minusSeconds ^Instant aberta-em (* 3 86400))
        no-futuro (.plusSeconds ^Instant agora (+ 600 (.toSeconds logic/tolerancia-de-relogio)))]
    (let [r (conflito-de-presenca #(registrar! ente sid (random-uuid) outro-dia agora))]
      (is (= :instante-fora-do-dia-da-sessao (:motivo (:dados r)))))
    (is (zero? (linhas-de-presenca ente sid)) "nada gravado no caso 'de outro dia'")
    (let [r (conflito-de-presenca #(registrar! ente sid (random-uuid) no-futuro agora))]
      (is (= :instante-no-futuro (:motivo (:dados r)))))
    (is (zero? (linhas-de-presenca ente sid)) "nada gravado no caso 'no futuro'")))

(deftest g7b-chegada-anterior-a-abertura-no-MESMO-dia-e-gravada
  ;; REGRESSAO do achado MAJOR: com o piso em `aberta_em`, a hora de quem chegou 12 min antes do martelo
  ;; voltava 409 — e no `POST /presenca/lote` a PRIMEIRA linha assim recusava a chamada inteira. Sem evento
  ;; de retificacao, a unica saida do secretario era redigitar a hora da abertura em todos: falsificar o
  ;; fato observado para caber num gate feito contra falsificacao.
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        chegada (.minusSeconds ^Instant aberta-em 720)
        agora (.plusSeconds ^Instant aberta-em 180)
        recibo (registrar! ente sid (random-uuid) chegada agora)]
    (is (some? (:id recibo)))
    (is (= chegada (:ocorrido-em recibo)) "a hora REAL de chegada foi gravada, nao a do martelo")
    (is (= 1 (linhas-de-presenca ente sid)))))

(deftest g7c-skew-pequeno-do-relogio-do-cliente-nao-recusa
  ;; REGRESSAO do achado MEDIO: `ocorrido-em` vem do browser, `agora` da JVM, `aberta_em` do Postgres — tres
  ;; relogios comparados com desigualdade estrita. Uma estacao adiantada em segundos derrubava a chamada.
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        agora (.plusSeconds ^Instant aberta-em 60)
        adiantado (.plusSeconds ^Instant agora 40)]
    (is (some? (:id (registrar! ente sid (random-uuid) adiantado agora))))
    (is (= 1 (linhas-de-presenca ente sid)))))

;; ---------- G8: o par (hora do fato, hora do registro) chega ao wire ----------

(deftest g8-recibo-e-linha-da-chamada-expoem-os-dois-carimbos
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        vid (random-uuid)
        ocorrido (.plusSeconds ^Instant aberta-em 60)
        agora (.plusSeconds ^Instant aberta-em 3600)
        recibo (registrar! ente sid vid ocorrido agora)
        recibo-wire (adapters-out/recibo-presenca->wire recibo)]
    (is (= (str ocorrido) (:ocorrido-em recibo-wire))
        "o recibo devolve a hora do FATO exatamente como declarada")
    (is (some? (:registrado-em recibo-wire))
        "o recibo devolve a hora do REGISTRO (carimbo do banco) — sem ela nao ha como distinguir o fato da digitacao")
    (is (not= (:ocorrido-em recibo-wire) (:registrado-em recibo-wire))
        "sao dois tempos distintos: o fato e' das 10h, o registro e' de agora")
    ;; a CHAMADA: roster vazio de proposito — a linha entra pela UNIAO (`sem-assento`), o que evita redigitar
    ;; aqui o vocabulario do roster de `cadastros` (fixture que redigita vocabulario alheio ja custou meses).
    (let [chamada (controllers/chamada-da-sessao *repo* (fn [_ _] []) (ator ente) sid
                                                 (tempo/relogio-fixo agora))
          linha (first (:linhas (adapters-out/chamada->wire chamada)))]
      (is (some? linha) "a presenca sem assento produz linha na chamada")
      (is (= (str ocorrido) (:desde linha)) "a linha expoe a hora do FATO (ocorrido-em)")
      (is (some? (:registrado-em linha)) "a linha expoe a hora do REGISTRO"))))
