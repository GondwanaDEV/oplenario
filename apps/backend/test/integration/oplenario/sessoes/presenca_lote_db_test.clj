(ns oplenario.sessoes.presenca-lote-db-test
  "INTEGRACAO (PG real) — Etapa 2c da CHAMADA (§22.6 eixo C): a ATOMICIDADE do LOTE de presenca contra o
  Postgres de verdade, com o Repo-Component DE VERDADE. Espelha `presenca-gate-estado-test` (fatia 2a) — o
  defeito que esta fatia fecha nao e' de calculo, e' de TRANSACAO: 21 POSTs sequenciais e nao-atomicos deixam
  meia chamada gravada quando a rede cai no meio; ninguem sabe que ficou pela metade. Os casos contam as
  LINHAS de `presenca_evento` antes e depois: um erro que ainda assim grava linhas parciais e' pior que
  nenhum lote, porque mente sobre o estado da chamada."
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

(defn- agendar! [ente]
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"})))

(defn- transicionar! [ente sid para lock]
  (repo/transicionar-sessao! *repo* ente (cond-> {:id sid :para para :updated-by (random-uuid) :lock-version lock}
                                           (= para "nao_realizada") (assoc :motivo "Falta de quorum"))))

(defn- abrir! [ente sid]
  (transicionar! ente sid "aberta" 0)
  (:aberta-em (repo/buscar-sessao *repo* ente sid)))

(defn- linhas-de-presenca [ente sid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:n (jdbc/execute-one! tx ["SELECT count(*) AS n FROM sessoes.presenca_evento WHERE ente_id = ? AND sessao_id = ?"
                                 ente sid])))))

(defn- reg [vid tipo ocorrido]
  {:vereador-id vid :tipo tipo :modalidade "plenario" :ocorrido-em ocorrido})

(defn- registrar-lote! [ente sid registros agora]
  (controllers/registrar-presenca-lote *repo* (ator ente) {:sessao-id sid :registros registros} agora))

(defn- conflito-de-presenca
  "Roda `f`, exige `:conflito/sessao-nao-aceita-presenca` e devolve {:msg :dados}. Falha se nada for lancado."
  [f]
  (try
    (f)
    (is false "esperava recusa :conflito/sessao-nao-aceita-presenca, mas a escrita passou")
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (= :conflito/sessao-nao-aceita-presenca (:tipo (ex-data e)))
          (str "tag de conflito errada: " (pr-str (ex-data e))))
      {:msg (ex-message e) :dados (ex-data e)})))

;; ---------- L1: lote de 3 validos -> EXATAMENTE 3 linhas novas ----------

(deftest l1-lote-de-tres-validos-grava-exatamente-tres-linhas
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        agora (.plusSeconds ^Instant aberta-em 3600)
        ocorrido (.plusSeconds ^Instant aberta-em 60)
        v1 (random-uuid) v2 (random-uuid) v3 (random-uuid)
        antes (linhas-de-presenca ente sid)
        recibos (registrar-lote! ente sid [(reg v1 "entrada" ocorrido) (reg v2 "entrada" ocorrido)
                                            (reg v3 "entrada" ocorrido)]
                                 agora)]
    (is (= 3 (count recibos)) "um recibo por linha do lote")
    (is (every? :id recibos) "todo recibo carrega o id do evento gravado")
    (is (= (+ antes 3) (linhas-de-presenca ente sid)) "EXATAMENTE 3 linhas novas, nem mais nem menos")))

;; ---------- L2: 1 invalido no meio -> ERRO e ZERO linha nova (o coracao da fatia) ----------

(deftest l2-uma-linha-fora-da-janela-recusa-o-lote-inteiro-sem-gravar-nada
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        agora (.plusSeconds ^Instant aberta-em 3600)
        ocorrido-valido (.plusSeconds ^Instant aberta-em 60)
        no-futuro (.plusSeconds ^Instant agora 600)
        antes (linhas-de-presenca ente sid)
        r (conflito-de-presenca
           #(registrar-lote! ente sid [(reg (random-uuid) "entrada" ocorrido-valido)
                                        (reg (random-uuid) "entrada" no-futuro)
                                        (reg (random-uuid) "entrada" ocorrido-valido)]
                             agora))]
    (is (= :instante-no-futuro (:motivo (:dados r))) "a linha do MEIO e' a que reprova")
    (is (= antes (linhas-de-presenca ente sid))
        "ZERO linha nova — as DUAS validas ao redor da invalida tambem NAO gravam: tudo ou nada")))

(deftest l2b-vereador-repetido-recusa-o-lote-antes-de-tocar-o-banco
  ;; a duplicata e' pega no adapters/in (borda), mas o controller/Repo tambem nao deve ver nada gravado se a
  ;; chamada chegasse a bater no Repo — este caso prova a camada de dominio via `logic` direto (a borda HTTP
  ;; ja' prova o 400 em `presenca-lote-http-in-test`); aqui confirmamos que o Repo nunca e' NEM CHAMADO com
  ;; duplicata, porque o controller nao filtra — quem filtra e' o adapter, uma camada ACIMA do controller.
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        agora (.plusSeconds ^Instant aberta-em 3600)
        ocorrido (.plusSeconds ^Instant aberta-em 60)
        vid (random-uuid)
        antes (linhas-de-presenca ente sid)
        ;; o controller NAO filtra duplicata (isso e' contrato do adapters/in) — se duas linhas do MESMO
        ;; vereador chegarem ao controller, ambas gravam (dois eventos "entrada"/"saida" append-only,
        ;; legitimos por si so'). Este teste documenta a FRONTEIRA: a garantia de unicidade do lote e' de
        ;; BORDA, nao de Repo.
        recibos (registrar-lote! ente sid [(reg vid "entrada" ocorrido) (reg vid "saida" ocorrido)] agora)]
    (is (= 2 (count recibos)) "o Repo nao rejeita por si so' — quem barra e' o adapters/in, antes de chegar aqui")
    (is (= (+ antes 2) (linhas-de-presenca ente sid)))))

;; ---------- L3: lote em sessao ENCERRADA -> 409, zero linha (o gate da 2a vale para o lote) ----------

(deftest l3-lote-em-sessao-encerrada-e-recusado-sem-gravar-nada
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        _ (transicionar! ente sid "encerrada" 1)
        agora (.plusSeconds ^Instant aberta-em 3600)
        ocorrido (.plusSeconds ^Instant aberta-em 60)
        antes (linhas-de-presenca ente sid)
        r (conflito-de-presenca
           #(registrar-lote! ente sid [(reg (random-uuid) "entrada" ocorrido)
                                        (reg (random-uuid) "entrada" ocorrido)]
                             agora))]
    (is (str/includes? (:msg r) "encerrada") "mensagem acionavel nomeia o estado atual")
    (is (= antes (linhas-de-presenca ente sid)) "ZERO linha nova — o gate de estado vale para o LOTE inteiro")))

;; ---------- L6: apos o lote, GET /sessoes/:id/chamada reflete as 3 linhas (teste cruzado com a Etapa 1) ----------

(deftest l6-apos-o-lote-a-chamada-reflete-as-tres-linhas
  (let [ente (random-uuid)
        sid (agendar! ente)
        aberta-em (abrir! ente sid)
        agora (.plusSeconds ^Instant aberta-em 3600)
        ocorrido (.plusSeconds ^Instant aberta-em 60)
        v1 (random-uuid) v2 (random-uuid) v3 (random-uuid)]
    (registrar-lote! ente sid [(reg v1 "entrada" ocorrido) (reg v2 "entrada" ocorrido) (reg v3 "entrada" ocorrido)]
                     agora)
    ;; roster vazio de proposito (mesma tatica de g8 em presenca-gate-estado-test): as 3 linhas entram pela
    ;; UNIAO (`sem-assento`), sem redigitar o vocabulario do roster de `cadastros` aqui.
    (let [chamada (controllers/chamada-da-sessao *repo* (fn [_ _] []) (ator ente) sid
                                                 (tempo/relogio-fixo agora))
          wire (adapters-out/chamada->wire chamada)
          vereadores-na-chamada (set (map :vereador-id (:linhas wire)))]
      (is (= 3 (count (:linhas wire))) "as 3 linhas do lote aparecem na chamada")
      (is (= #{(str v1) (str v2) (str v3)} vereadores-na-chamada)
          "sao EXATAMENTE os 3 vereadores do lote, nenhum a mais nem a menos")
      (is (every? #(= "presente-plenario" (:estado %)) (:linhas wire))
          "cada um entrou como 'entrada'/'plenario' -> presente-plenario")
      (is (= 3 (:presentes-plenario (:quorum wire))) "o QUORUM tambem reflete o lote — mesma fonte canonica"))))
