(ns oplenario.sessoes.sessoes-eventos-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component de SESSOES (ADR-0001 §3-bis) — os caminhos de escrita
  emitem os EVENTOS DE DOMINIO DE TEMPO REAL no shared.outbox na MESMA tx do ato (§22.6 eixo G / carry F4;
  atomicidade outbox-com-o-ato §22.9 E2: a linha do evento so existe se a tx commitou). Estes eventos sao a
  FONTE do projetor SSE (G2). Prova o caminho de producao (via o Component, nao o db/ direto). Instantes nos
  payloads viajam como ISO-8601 string (jsonista nao serializa java.time.Instant)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo, ente_id, payload::text AS payload FROM shared.outbox
      WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

(defn- agendar! [ente]
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"})))

(def ^:private t0 (java.time.Instant/parse "2026-06-29T14:00:00Z"))
(defn- mais [^java.time.Instant t s] (.plusSeconds t s))

;; ---------- sessao.agendada (F7 E3 carry: nascimento da sessao, p/ o SLI enxergar o no-show) ----------

(deftest agendamento-de-sessao-emite-evento
  (let [ente (random-uuid)
        agendada-para (java.time.Instant/parse "2026-07-08T13:00:00Z")
        sid  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                     :tipo-sessao "ordinaria" :modalidade "presencial"
                                                     :agendada-para agendada-para}))]
    (let [evs (eventos-por-tipo ente "sessao.agendada")]
      (is (= 1 (count evs)) "agendar emite exatamente 1 sessao.agendada")
      (let [pl (:payload (first evs))]
        (is (re-find (re-pattern (str sid)) pl) "payload carrega a sessao-id")
        (is (re-find #"2026-07-08T13:00:00Z" pl) "carrega agendada-para (ISO)")
        (is (re-find #"\"ocorrido-em\":\s*\"20\d\d-\d\d-\d\dT" pl)
            "carrega ocorrido-em (instante do ato, RETURNING de efetivado_em) — semeia o gate do SLI")))))

;; ---------- sessao.transicionou ----------

(deftest transicao-de-sessao-emite-evento
  (let [ente (random-uuid)
        sid  (agendar! ente)]
    (is (empty? (eventos-por-tipo ente "sessao.transicionou")) "nada antes de transicionar")
    (repo/transicionar-sessao! *repo* ente {:id sid :para "aberta" :updated-by (random-uuid) :lock-version 0})
    (let [evs (eventos-por-tipo ente "sessao.transicionou")]
      (is (= 1 (count evs)) "exatamente 1 evento de transicao")
      (let [pl (:payload (first evs))]
        (is (re-find #"agendada" pl) "payload carrega o estado de origem")
        (is (re-find #"aberta" pl) "payload carrega o estado de destino")
        (is (re-find (re-pattern (str sid)) pl) "payload carrega a sessao-id")
        ;; F7 E3 (SLI de sessao): o payload carrega o INSTANTE REAL da transicao no dominio (:ocorrido-em,
        ;; RETURNING de atualizado_em) — nao o momento em que paineis.sli_sessao eventualmente PROJETA o
        ;; evento (mirror do carry fechado em legislativo, a5a5532). Sem isto, o SLI de janela de sessao
        ;; carimbaria aberta_em/encerrada_em com o tempo de PROCESSAMENTO, mentindo sob atraso do relay.
        (is (re-find #"\"ocorrido-em\":\s*\"20\d\d-\d\d-\d\dT" pl) "carrega ocorrido-em como ISO-8601 string")))))

;; ---------- presenca.registrada ----------

(deftest presenca-emite-evento
  (let [ente (random-uuid)
        sid  (agendar! ente)
        ver  (random-uuid)]
    ;; `agora` = t0: o gate de janela (Etapa 2 da chamada) exige o teto do relogio do servidor; a sessao esta
    ;; `agendada`, entao nao ha limite inferior a satisfazer aqui.
    (repo/registrar-presenca! *repo* ente {:id (random-uuid) :sessao-id sid :vereador-id ver
                                           :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                           :ocorrido-em t0 :agora t0 :created-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "presenca.registrada")]
      (is (= 1 (count evs)) "1 evento de presenca")
      (let [pl (:payload (first evs))]
        (is (re-find #"entrada" pl) "payload carrega o tipo")
        (is (re-find (re-pattern (str ver)) pl) "payload carrega o vereador")
        (is (re-find #"2026-06-29T14:00:00Z" pl) "ocorrido-em como ISO-8601 string")))))

;; ---------- fala.iniciada / fala.cronometro / fala.encerrada ----------

(deftest tribuna-emite-eventos
  (let [ente (random-uuid)
        sid  (agendar! ente)
        orad (random-uuid)
        fid  (random-uuid)]
    (repo/iniciar-fala! *repo* ente {:id fid :sessao-id sid :orador-id orad :tipo-fala "principal"
                                     :fase "ordem_do_dia" :iniciou-em t0 :created-by (random-uuid)})
    (is (= 1 (count (eventos-por-tipo ente "fala.iniciada"))) "fala.iniciada emitido")
    (let [pl (:payload (first (eventos-por-tipo ente "fala.iniciada")))]
      (is (re-find (re-pattern (str orad)) pl) "carrega o orador")
      (is (re-find #"principal" pl) "carrega o tipo de fala"))
    (repo/registrar-evento-cronometro! *repo* ente {:fala-id fid :tipo "pausada"
                                                    :ocorrido-em (mais t0 100) :created-by (random-uuid)})
    (repo/registrar-evento-cronometro! *repo* ente {:fala-id fid :tipo "retomada"
                                                    :ocorrido-em (mais t0 160) :created-by (random-uuid)})
    (is (= 2 (count (eventos-por-tipo ente "fala.cronometro"))) "fala.cronometro emitido por marco")
    (is (re-find #"pausada" (:payload (first (eventos-por-tipo ente "fala.cronometro")))) "carrega o tipo de marco")
    (repo/encerrar-fala! *repo* ente {:id fid :encerrou-em (mais t0 300) :lock-version 0 :updated-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "fala.encerrada")]
      (is (= 1 (count evs)) "fala.encerrada emitido")
      (is (re-find #"\"tempo-segundos\":\s*240" (:payload (first evs)))
          "carrega o tempo efetivamente usado (300 - 60 de pausa = 240)"))))

;; ---------- G1b: inscricao.registrada / inscricao.desistida ----------

(deftest inscricao-emite-eventos
  (let [ente (random-uuid)
        sid  (agendar! ente)
        ver  (random-uuid)
        iid  (random-uuid)]
    (repo/inscrever! *repo* ente {:id iid :sessao-id sid :vereador-id ver
                                  :origem-inscricao "pre_sessao_app" :fase "expediente" :created-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "inscricao.registrada")]
      (is (= 1 (count evs)) "inscricao.registrada emitido")
      (is (re-find (re-pattern (str ver)) (:payload (first evs))) "carrega o vereador")
      (is (re-find #"pre_sessao_app" (:payload (first evs))) "carrega a origem"))
    (repo/desistir! *repo* ente {:id iid :lock-version 0 :updated-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "inscricao.desistida")]
      (is (= 1 (count evs)) "inscricao.desistida emitido")
      (is (re-find (re-pattern (str sid)) (:payload (first evs))) "carrega a sessao-id (rota do canal)"))))

;; ---------- §16.13: incidente.registrado (mesa de conducao ao vivo) ----------

(deftest incidente-emite-evento
  (let [ente (random-uuid)
        sid  (agendar! ente)
        prop (random-uuid)]
    (is (empty? (eventos-por-tipo ente "incidente.registrado")) "nada antes do incidente")
    (repo/registrar-incidente! *repo* ente {:id (random-uuid) :sessao-id sid :tipo "pedido_vista"
                                            :resultado "deferido" :descricao "Vista da Prop. 12/2026."
                                            :objeto-tipo "proposicao" :objeto-id prop
                                            :ocorrido-em t0 :created-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "incidente.registrado")]
      (is (= 1 (count evs)) "incidente.registrado emitido (mesa de conducao ao vivo)")
      (let [pl (:payload (first evs))]
        (is (re-find #"pedido_vista" pl) "carrega o tipo do incidente")
        (is (re-find #"deferido" pl) "carrega o resultado")
        (is (re-find (re-pattern (str sid)) pl) "carrega a sessao-id (rota do canal plenario)")
        (is (re-find #"2026-06-29T14:00:00Z" pl) "ocorrido-em como ISO-8601 string")))))

;; ---------- G1b: gravacao.segmento-captado (fronteira core->IA) ----------

(deftest gravacao-emite-evento
  (let [ente (random-uuid)
        sid  (agendar! ente)
        gid  (random-uuid)]
    (repo/registrar-segmento! *repo* ente {:id gid :sessao-id sid :iniciou-em t0 :encerrou-em (mais t0 300)
                                           :motivo-inicio "inicio_sessao" :motivo-fim "fim_sessao"
                                           :container-bruto-uri "s3://gravacoes/seg.mkv"
                                           :fonte-ingestao "gravacao_local_pos_sessao"
                                           :acesso-restrito false :created-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "gravacao.segmento-captado")]
      (is (= 1 (count evs)) "gravacao.segmento-captado emitido (fronteira core->IA)")
      (is (re-find #"s3://gravacoes/seg.mkv" (:payload (first evs))) "carrega o container bruto p/ a IA")
      (is (re-find #"gravacao_local_pos_sessao" (:payload (first evs))) "carrega a fonte de ingestao"))))

;; ---------- gravacao.segmento-vinculado (re-notifica o sigilo a IA no vinculo Opcao A) ----------

(deftest vinculo-de-segmento-emite-evento-com-sigilo-definitivo
  ;; Opcao A: o segmento e' captado SEM sessao com acesso-restrito=false (captado sai assim a IA). Ao vincular a
  ;; uma sessao SECRETA, o servidor forca acesso_restrito=true E re-notifica a IA via gravacao.segmento-vinculado
  ;; carregando o sigilo DEFINITIVO — senao a IA transcreveria audio sigiloso sem saber (review clojure MAJOR).
  (let [ente (random-uuid)
        sid  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                     :tipo-sessao "secreta" :modalidade "presencial"}))
        gid  (random-uuid)]
    (repo/registrar-segmento! *repo* ente {:id gid :iniciou-em t0 :encerrou-em (mais t0 300)
                                           :motivo-inicio "inicio_sessao" :motivo-fim "fim_sessao"
                                           :container-bruto-uri "s3://gravacoes/seg.mkv"
                                           :fonte-ingestao "gravacao_local_pos_sessao"
                                           :acesso-restrito false :created-by (random-uuid)})
    (is (empty? (eventos-por-tipo ente "gravacao.segmento-vinculado")) "nada antes do vinculo")
    (repo/vincular-segmento! *repo* ente {:id gid :sessao-id sid :lock-version 0
                                          :updated-by (random-uuid) :forcar-acesso-restrito true})
    (let [evs (eventos-por-tipo ente "gravacao.segmento-vinculado")]
      (is (= 1 (count evs)) "gravacao.segmento-vinculado emitido (core->IA) na MESMA tx do vinculo")
      (let [pl (:payload (first evs))]
        (is (re-find (re-pattern (str sid)) pl) "carrega a sessao-id agora vinculada")
        (is (re-find (re-pattern (str gid)) pl) "carrega o segmento-id")
        (is (re-find #"\"acesso-restrito\":\s*true" pl)
            "carrega o acesso-restrito DEFINITIVO (re-derivado true p/ sessao secreta)")))))
