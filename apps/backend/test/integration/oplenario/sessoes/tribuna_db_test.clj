(ns oplenario.sessoes.tribuna-db-test
  "INTEGRACAO (PG real): §22.6 eixo F (F4.5) — tribuna. F4.5a prova a INSCRICAO de oradores como camada de
  INTENCAO: `inscricao_oradores` com `origem_inscricao` discriminando os 4 caminhos (pre_sessao_app|
  pre_sessao_secretaria|intra_sessao_pedido|automatica_por_autoria); subordinada a FASE da pauta (reusa o enum
  de fase); vinculo OPCIONAL a `proposicao_ref_id`; pode terminar em `desistencia` (terminal) SEM gerar fala —
  intencao != execucao. `vereador_id`/`proposicao_ref_id` sao forward-ref (uuid, sem FK cross-schema, §22.10).
  F4.5b prova a FALA EXECUTADA (separada da inscricao) + o cronometro como PROJECAO sobre eventos append-only
  (tempo computado ao encerrar, sem snapshot) + apartes via fala_pai_id."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [honey.sql]
            [malli.core :as m]
            [next.jdbc]
            [next.jdbc.result-set]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.db.tribuna :as tribuna]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.tribuna :as mod]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- agendar! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                            :tipo-sessao "ordinaria" :modalidade "presencial"})))

(defn- inscrever! [tx ente sid extra]
  (tribuna/inscrever!
   tx (merge {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id (random-uuid)
              :origem-inscricao "pre_sessao_secretaria" :fase "expediente" :created-by (random-uuid)} extra)))

;; ---------- vocabularios (puros) ----------

(deftest vocabularios-inscricao
  (is (thrown? Exception (logic/validar-origem-inscricao "telepatia")) "origem invalida lanca")
  (is (nil? (logic/validar-origem-inscricao "automatica_por_autoria")) "origem valida nao lanca")
  (is (true? (logic/transicao-inscricao-valida? "inscrita" "desistencia")) "inscrita -> desistencia")
  (is (false? (logic/transicao-inscricao-valida? "desistencia" "inscrita")) "desistencia e' terminal"))

;; ---------- inscrever + buscar + model ----------

(deftest inscrever-e-buscar
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              pid (random-uuid)
              {iid :id} (inscrever! tx ente sid {:origem-inscricao "automatica_por_autoria"
                                                 :fase "ordem_do_dia" :proposicao-ref-id pid})
              r (tribuna/buscar-inscricao tx ente iid)]
          (is (= "automatica_por_autoria" (:origem-inscricao r)) "origem discrimina o caminho")
          (is (= "ordem_do_dia" (:fase r)) "subordinada a fase da pauta")
          (is (= pid (:proposicao-ref-id r)) "vinculo opcional a materia")
          (is (= "inscrita" (:estado r)) "nasce inscrita")
          (is (= 1 (:ordem r)) "ordem = 1 (primeira da fase)")
          (is (m/validate mod/InscricaoOrador r) "bate o model"))))))

;; ---------- ordem = fila independente por (sessao, fase) ----------

(deftest ordem-fila-por-fase
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {a :ordem} (inscrever! tx ente sid {:fase "expediente"})
              {b :ordem} (inscrever! tx ente sid {:fase "expediente"})
              {c :ordem} (inscrever! tx ente sid {:fase "ordem_do_dia"})]
          (is (= [1 2 1] [a b c]) "ordem = max+1 por (sessao, fase) — fila independente por fase"))))))

;; ---------- desistencia: intencao termina SEM fala (terminal) ----------

(deftest desistir-termina-intencao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {iid :id} (inscrever! tx ente sid {})]
          (is (thrown? Exception (tribuna/desistir! tx {:ente-id ente :id iid :lock-version 0 :updated-by nil}))
              "desistencia sem updated-by e' barrada (trilha de auditoria, Inv.10)")
          (tribuna/desistir! tx {:ente-id ente :id iid :lock-version 0 :updated-by (random-uuid)})
          (is (= "desistencia" (:estado (tribuna/buscar-inscricao tx ente iid)))
              "inscricao pode terminar em desistencia SEM gerar fala (intencao != execucao)")
          (is (thrown? Exception
                       (tribuna/desistir! tx {:ente-id ente :id iid :lock-version 1 :updated-by (random-uuid)}))
              "desistencia e' terminal (trava re-transicao)"))))))

;; ---------- FK same-schema + RLS + listar ----------

(deftest inscricao-sessao-inexistente-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? Exception (inscrever! tx ente (random-uuid) {}))
            "inscricao em sessao inexistente viola a FK same-schema")))))

(deftest listar-inscricoes-da-sessao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (inscrever! tx ente sid {:fase "expediente"})
          (inscrever! tx ente sid {:fase "ordem_do_dia"})
          (let [lst (tribuna/listar-inscricoes tx ente sid)]
            (is (= 2 (count lst)) "lista as inscricoes da sessao")
            (is (every? #(= sid (:sessao-id %)) lst) "todas da sessao")))))))

;; ============================================================================
;; F4.5b — FALA EXECUTADA + CRONOMETRO (execucao). A fala e' SEPARADA da inscricao
;; (intencao != execucao); cronometro = PROJECAO sobre eventos append-only, tempo
;; computado ao encerrar (sem coluna ticando — disc.5). Apartes via fala_pai_id.
;; ============================================================================

(def ^:private f0 (java.time.Instant/parse "2026-06-29T14:00:00Z"))
(defn- mais [^java.time.Instant t s] (.plusSeconds t s))

(defn- iniciar! [tx ente sid extra]
  (tribuna/iniciar-fala!
   tx (merge {:id (random-uuid) :ente-id ente :sessao-id sid :orador-id (random-uuid)
              :tipo-fala "principal" :fase "ordem_do_dia" :iniciou-em f0 :created-by (random-uuid)} extra)))

;; ---------- vocabularios (puros) ----------

(deftest vocabularios-fala
  (is (thrown? Exception (logic/validar-tipo-fala "cochicho")) "tipo_fala invalido lanca")
  (is (nil? (logic/validar-tipo-fala "aparte")) "tipo_fala valido")
  (is (true? (logic/aparte? "aparte")) "aparte e' aparte")
  (is (false? (logic/aparte? "principal")) "principal nao e' aparte")
  (is (thrown? Exception (logic/validar-evento-cronometro "tempo_adicional_concedido" nil))
      "tempo adicional exige segundos")
  (is (thrown? Exception (logic/validar-evento-cronometro "pausada" 30))
      "pausada nao carrega segundos_adicionais"))

;; ---------- cronometro PURO: tempo efetivo = elapsed - pausas ----------

(deftest tempo-efetivo-puro
  (is (= 300 (logic/tempo-efetivo-segundos f0 (mais f0 300) []))
      "sem pausa: tempo usado = elapsed")
  (is (= 240 (logic/tempo-efetivo-segundos f0 (mais f0 300)
                                           [{:tipo "pausada"  :ocorrido-em (mais f0 100)}
                                            {:tipo "retomada" :ocorrido-em (mais f0 160)}]))
      "uma pausa de 60s desconta do tempo usado")
  (is (= 300 (logic/tempo-efetivo-segundos f0 (mais f0 300)
                                           [{:tipo "aparte_concedido" :ocorrido-em (mais f0 50)}]))
      "aparte_concedido nao desconta (marcador; cronometro principal segue)")
  (is (= 200 (logic/tempo-efetivo-segundos f0 (mais f0 300)
                                           [{:tipo "pausada" :ocorrido-em (mais f0 200)}]))
      "pausa ABERTA no encerramento (pausada sem retomada) desconta [pausa, encerrou] — nao infla"))

;; ---------- iniciar + buscar + model ----------

(deftest iniciar-e-buscar-fala
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {fid :id} (iniciar! tx ente sid {})
              r (tribuna/buscar-fala tx ente fid)]
          (is (= "principal" (:tipo-fala r)) "tipo da fala")
          (is (= f0 (:iniciou-em r)) "marco de inicio (intervalo p/ diarizacao)")
          (is (nil? (:encerrou-em r)) "ainda em curso")
          (is (nil? (:tempo-efetivamente-usado-segundos r)) "tempo so ao encerrar")
          (is (m/validate mod/FalaExecutada r) "bate o model")
          (let [evs (tribuna/listar-eventos-cronometro tx ente fid)]
            (is (= ["iniciada"] (mapv :tipo evs)) "iniciar loga o evento 'iniciada'")))))))

;; ---------- encerrar computa o tempo a partir dos eventos ----------

(deftest encerrar-computa-tempo
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {fid :id} (iniciar! tx ente sid {})]
          (tribuna/registrar-evento-cronometro! tx {:ente-id ente :fala-id fid :tipo "pausada"
                                                    :ocorrido-em (mais f0 100) :created-by (random-uuid)})
          (tribuna/registrar-evento-cronometro! tx {:ente-id ente :fala-id fid :tipo "retomada"
                                                    :ocorrido-em (mais f0 160) :created-by (random-uuid)})
          (tribuna/encerrar-fala! tx {:ente-id ente :id fid :encerrou-em (mais f0 300)
                                      :lock-version 0 :updated-by (random-uuid)})
          (let [r (tribuna/buscar-fala tx ente fid)]
            (is (= (mais f0 300) (:encerrou-em r)) "marco de fim cravado")
            (is (= 240 (:tempo-efetivamente-usado-segundos r)) "tempo = 300 - 60 de pausa, computado ao encerrar")
            (is (= #{"iniciada" "pausada" "retomada" "encerrada"}
                   (set (map :tipo (tribuna/listar-eventos-cronometro tx ente fid))))
                "encerrar loga 'encerrada'"))
          (is (thrown? Exception (tribuna/encerrar-fala! tx {:ente-id ente :id fid :encerrou-em (mais f0 400)
                                                             :lock-version 1 :updated-by (random-uuid)}))
              "encerrar uma fala ja encerrada e' barrado (uma vez)"))))))

;; ---------- fala-em-curso: `encerrou_em IS NULL`, nao "a mais recente" (achado C1 da revisao) ----------

(deftest fala-em-curso-e-a-aberta-nao-a-ultima-por-ordem
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {f1 :id} (iniciar! tx ente sid {})]
          (is (= f1 (:id (tribuna/fala-em-curso tx ente sid)))
              "a unica fala aberta e' a em curso")
          (tribuna/encerrar-fala! tx {:ente-id ente :id f1 :encerrou-em (mais f0 60)
                                      :lock-version 0 :updated-by (random-uuid)})
          (let [{f2 :id} (iniciar! tx ente sid {:iniciou-em (mais f0 90)})]
            (is (= f2 (:id (tribuna/fala-em-curso tx ente sid)))
                "com f1 encerrada, a fala em curso e' a nova (f2), ainda aberta")
            (tribuna/encerrar-fala! tx {:ente-id ente :id f2 :encerrou-em (mais f0 150)
                                        :lock-version 0 :updated-by (random-uuid)})
            (is (nil? (tribuna/fala-em-curso tx ente sid))
                (str "com AS DUAS encerradas -- e f2 e' a MAIS RECENTE por iniciou_em -- fala-em-curso "
                     "tem de ser nil. Se este `is` passar com `[:= :encerrou_em nil]` removido de "
                     "db/tribuna.clj, o filtro voltou a ser 'a ultima fala', nao 'a fala em curso'."))))))))

;; ---------- fala-em-curso com PAI + APARTE ambos abertos (achado I2 da revisao) ----------

(deftest fala-em-curso-durante-aparte-e-o-aparteante-mais-recente-vence
  ;; Duas falas abertas ao MESMO TEMPO e' o caso NORMAL do aparte (ver docstring de `fala-em-curso`):
  ;; `iniciar-fala!` nao tem guarda contra abrir o aparte com a fala-mae ainda sem encerrar, e a Mesa ao
  ;; vivo tipicamente so' encerra a fala principal. `fala-em-curso` resolve isso de forma DETERMINISTICA
  ;; -- a MAIS RECENTE por `iniciou_em` -- espelhando o reducer do SSE (`fala.iniciada` do aparte
  ;; SUBSTITUI o orador atual no canal).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {pai :id} (iniciar! tx ente sid {})
              {ap :id} (iniciar! tx ente sid {:tipo-fala "aparte" :fala-pai-id pai :iniciou-em (mais f0 30)})]
          (is (= ap (:id (tribuna/fala-em-curso tx ente sid)))
              "pai e aparte ambos abertos -- o aparteante (iniciou_em mais recente) e' quem esta com a palavra"))))))

;; ---------- apartes via fala_pai_id ----------

(deftest apartes-via-fala-pai
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {pai :id} (iniciar! tx ente sid {})
              {ap :id} (iniciar! tx ente sid {:tipo-fala "aparte" :fala-pai-id pai :iniciou-em (mais f0 30)})]
          (is (= [ap] (mapv :id (tribuna/listar-apartes tx ente pai)))
              "aparte vinculado ao pai via fala_pai_id")
          (is (thrown? Exception (iniciar! tx ente sid {:tipo-fala "aparte"}))
              "aparte SEM fala_pai_id viola o CHECK (aparte exige pai)")
          (is (thrown? Exception (iniciar! tx ente sid {:tipo-fala "principal" :fala-pai-id pai}))
              "fala nao-aparte COM fala_pai_id viola o CHECK (so aparte tem pai)"))))))

;; ---------- sessao solene: fala SEM inscricao (inscricao_id nullable) ----------

(deftest fala-sem-inscricao-solene
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "solene" :modalidade "presencial"}))
              {fid :id} (iniciar! tx ente sid {:tipo-fala "comunicado"})]
          (is (nil? (:inscricao-id (tribuna/buscar-fala tx ente fid)))
              "sessao solene reusa fala_executada com inscricao_id nulo (campos relaxados)"))))))

;; ---------- evento de cronometro e' append-only ----------

(deftest cronometro-evento-append-only
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {fid :id} (iniciar! tx ente sid {})
              {eid :id} (tribuna/registrar-evento-cronometro! tx {:ente-id ente :fala-id fid :tipo "aparte_concedido"
                                                                  :ocorrido-em (mais f0 50) :created-by (random-uuid)})]
          (is (some? eid) "evento gravado")
          (is (thrown? Exception
                       (next.jdbc/execute-one!
                        tx (honey.sql/format {:update :sessoes.fala_cronometro_evento
                                              :set {:tipo "pausada"}
                                              :where [:and [:= :ente_id ente] [:= :id eid]]})))
              "UPDATE no evento de cronometro e' barrado (append-only)"))))))

;; ============================================================================
;; F4.5c — DECISAO DA MESA (questao de ordem). Ato regimental do presidente sobre
;; questao de ordem, com efeito juridico -> vai para a ata. APPEND-ONLY puro
;; (decisao tomada uma vez; disciplina §22.4.3 "atos auditados tem registro proprio").
;; ============================================================================

(defn- decidir! [tx ente sid extra]
  (tribuna/registrar-decisao-mesa!
   tx (merge {:id (random-uuid) :ente-id ente :sessao-id sid :presidente-id (random-uuid)
              :questao "Procede a questao de ordem sobre o quorum?"
              :decisao "Indeferida; o quorum esta regular conforme o painel."
              :decidido-em f0 :created-by (random-uuid)} extra)))

(deftest registrar-e-buscar-decisao-mesa
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {fid :id} (iniciar! tx ente sid {:tipo-fala "questao_de_ordem"})
              {did :id} (decidir! tx ente sid {:fala-id fid :fundamentacao "Art. 80 do Regimento Interno."})
              r (tribuna/buscar-decisao-mesa tx ente did)]
          (is (= fid (:fala-id r)) "vincula a fala de questao de ordem que a motivou")
          (is (= "Art. 80 do Regimento Interno." (:fundamentacao r)) "fundamentacao opcional registrada")
          (is (some? (:decisao r)) "a decisao do presidente")
          (is (m/validate mod/DecisaoMesa r) "bate o model"))))))

(deftest decisao-mesa-sem-fala
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {did :id} (decidir! tx ente sid {})]
          (is (nil? (:fala-id (tribuna/buscar-decisao-mesa tx ente did)))
              "decisao da mesa pode existir sem fala registrada (fala_id nullable)"))))))

(deftest decisao-mesa-guards
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))]
          (is (thrown? Exception (decidir! tx ente sid {:created-by nil}))
              "created-by obrigatorio (trilha de auditoria)")
          (is (thrown? Exception (decidir! tx ente sid {:presidente-id nil}))
              "presidente-id obrigatorio (quem decidiu)")
          (is (thrown? Exception (decidir! tx ente sid {:decisao "   "}))
              "decisao vazia viola o CHECK")
          (is (thrown? Exception (decidir! tx ente sid {:questao "   "}))
              "questao vazia viola o CHECK")
          (is (thrown? Exception (decidir! tx ente sid {:fala-id (random-uuid)}))
              "fala_id inexistente viola a FK same-schema"))))))

(deftest decisao-mesa-append-only
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                            :tipo-sessao "ordinaria" :modalidade "presencial"}))
              {did :id} (decidir! tx ente sid {:decidido-em f0 :questao "Primeira?"})
              _ (decidir! tx ente sid {:decidido-em (mais f0 600) :questao "Segunda?"})
              lst (tribuna/listar-decisoes-mesa tx ente sid)]
          (is (= 2 (count lst)) "lista as decisoes da sessao")
          (is (= ["Primeira?" "Segunda?"] (mapv :questao lst)) "ordem cronologica por decidido_em")
          (is (thrown? Exception
                       (next.jdbc/execute-one!
                        tx (honey.sql/format {:update :sessoes.decisao_mesa
                                              :set {:decisao "alterada"}
                                              :where [:and [:= :ente_id ente] [:= :id did]]})))
              "UPDATE numa decisao da mesa e' barrado (append-only: ato regimental imutavel)"))))))

;; ============================================================================
;; mig 0081 — o TEMPO-LIMITE da fala (pedido do stakeholder: tempo de tribuna com campainha). O limite e'
;; FOTOGRAFADO na fala ao iniciar: o que a Mesa informou vence; sem isso, o regimental da Casa para (fase, tipo),
;; com a linha de fase explicita vencendo a generica; sem nenhum dos dois, nil (sem limite, como antes).
;; ============================================================================

(defn- sessao! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                            :tipo-sessao "ordinaria" :modalidade "presencial"})))

(defn- definir! [tx ente fase tipo seg]
  (tribuna/definir-tempo-regimental! tx {:ente-id ente :fase fase :tipo-fala tipo :segundos seg
                                         :created-by (random-uuid)}))

(deftest tempo-regimental-especifico-vence-generico
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (nil? (tribuna/tempo-regimental tx ente "ordem_do_dia" "principal"))
            "Casa sem configuracao -> nil (sem limite)")
        (definir! tx ente nil "principal" 300)
        (is (= 300 (tribuna/tempo-regimental tx ente "ordem_do_dia" "principal"))
            "so' a generica -> vale em qualquer fase")
        (definir! tx ente "ordem_do_dia" "principal" 600)
        (is (= 600 (tribuna/tempo-regimental tx ente "ordem_do_dia" "principal"))
            "a linha da fase explicita vence a generica")
        (is (= 300 (tribuna/tempo-regimental tx ente "expediente" "principal"))
            "em outra fase continua valendo a generica")
        (is (nil? (tribuna/tempo-regimental tx ente "ordem_do_dia" "aparte"))
            "outro tipo de fala nao herda o tempo da principal")))))

(deftest definir-tempo-regimental-substitui-o-mesmo-par
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (definir! tx ente nil "aparte" 60)
        (definir! tx ente nil "aparte" 90)
        (definir! tx ente "expediente" "aparte" 45)
        (definir! tx ente "expediente" "aparte" 30)
        (is (= 90 (tribuna/tempo-regimental tx ente "ordem_do_dia" "aparte")) "a generica foi substituida")
        (is (= 30 (tribuna/tempo-regimental tx ente "expediente" "aparte")) "a especifica foi substituida")
        (is (= 2 (:c (next.jdbc/execute-one!
                      tx ["SELECT count(*) c FROM sessoes.tempo_regimental WHERE ente_id = ?" ente]
                      {:builder-fn next.jdbc.result-set/as-unqualified-maps})))
            "uma linha por (fase, tipo) — redefinir nao acumula")
        (is (thrown? Exception (definir! tx ente nil "aparte" 0)) "segundos > 0 (fail-closed antes do banco)")
        (is (thrown? Exception (definir! tx ente nil "cochicho" 60)) "tipo de fala fora do vocabulario")
        (is (thrown? Exception (definir! tx ente "recreio" "aparte" 60)) "fase fora do vocabulario")))))

(deftest iniciar-fala-fotografa-o-tempo-concedido
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (sessao! tx ente)
              sem-config (iniciar! tx ente sid {})]
          (is (nil? (:tempo-concedido-segundos sem-config)) "sem configuracao e sem tempo informado -> sem limite")
          (is (nil? (:tempo-concedido-segundos (tribuna/buscar-fala tx ente (:id sem-config)))))
          (definir! tx ente "ordem_do_dia" "principal" 600)
          (let [regimental (iniciar! tx ente sid {})
                informado  (iniciar! tx ente sid {:tempo-concedido-segundos 120})]
            (is (= 600 (:tempo-concedido-segundos regimental)) "sem tempo informado -> o regimental da Casa")
            (is (= 120 (:tempo-concedido-segundos informado)) "o tempo que a Mesa informou vence o regimental")
            (definir! tx ente "ordem_do_dia" "principal" 900)
            (let [r (tribuna/buscar-fala tx ente (:id regimental))]
              (is (= 600 (:tempo-concedido-segundos r))
                  "reconfigurar a Casa NAO muda a fala ja' iniciada (o limite foi fotografado)")
              (is (m/validate mod/FalaExecutada r) "bate o model"))))))))

;; ---------- a tabela inteira (tela "Tempos da tribuna" da secretaria) ----------

(deftest substituir-tempos-regimentais-troca-a-tabela-inteira
  (let [ente (random-uuid) outro (random-uuid) autor (random-uuid)]
    (tenancy/com-tenant* *ds* outro
      (fn [tx] (definir! tx outro nil "principal" 900)))
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (= [] (tribuna/listar-tempos-regimentais tx ente)) "Casa sem configuracao -> lista vazia")
        (definir! tx ente nil "comunicado" 120)
        (tribuna/substituir-tempos-regimentais!
         tx ente [{:fase nil :tipo-fala "principal" :segundos 180 :referencia-normativa "RI art. 98"}
                  {:fase "ordem_do_dia" :tipo-fala "principal" :segundos 600}
                  {:fase nil :tipo-fala "aparte" :segundos 60}]
         autor)
        (let [lista (tribuna/listar-tempos-regimentais tx ente)]
          (is (= #{[nil "principal" 180 "RI art. 98"] ["ordem_do_dia" "principal" 600 nil] [nil "aparte" 60 nil]}
                 (set (map (juxt :fase :tipo-fala :segundos :referencia-normativa) lista)))
              "a tabela nova substitui a antiga inteira — o 'comunicado' que nao veio saiu")
          (is (= 180 (tribuna/tempo-regimental tx ente "expediente" "principal"))
              "o que a fala le ao iniciar e' a tabela nova"))
        (tribuna/substituir-tempos-regimentais! tx ente [] autor)
        (is (= [] (tribuna/listar-tempos-regimentais tx ente)) "tabela vazia = a Casa volta a nao ter limite")))
    (tenancy/com-tenant* *ds* outro
      (fn [tx]
        (is (= 900 (tribuna/tempo-regimental tx outro "expediente" "principal"))
            "substituir a tabela de uma Casa nao toca a de outra")))))
