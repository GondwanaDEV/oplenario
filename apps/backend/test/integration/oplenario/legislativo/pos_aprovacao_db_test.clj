(ns oplenario.legislativo.pos-aprovacao-db-test
  "INTEGRACAO (PG real): F3.8a — pos-aprovacao (§22.4; doc-mestre L247). Prova o fluxo
  autografo -> tramitacao no Executivo (sancao/veto -> apreciacao do veto). O autografo e' artefato
  legal APPEND-ONLY (numerado gapless, imutavel); a tramitacao_executiva e' o processo que evolui
  (state machine, CAS, trava terminal). A apreciacao do veto REUSA a votacao do eixo G (maioria
  absoluta). Rito/prazos exatos do veto = [GAP] regimental — provamos a FORMA."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.autografo :as autografo]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao-executiva :as exec]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.models.autografo :as mod-aut]
            [oplenario.legislativo.models.tramitacao-executiva :as mod-exec]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- gerar-autografo! [tx ente pid]
  ;; :texto-versao-id = a versao 'redacao_final' aprovada (forward-ref sem FK; aqui um id simbolico — o
  ;; CHECK autografo_efetivado_tem_texto exige que exista). Em producao vem do eixo B.
  (autografo/gerar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :ano 2026
                        :texto-versao-id (random-uuid)
                        :destinatario-texto "Prefeito Municipal de Fortaleza"}))

;; ---------- autografo: numeracao gapless + append-only + UNIQUE por proposicao ----------

(deftest autografo-numera-gapless-e-imutavel
  (let [ente (random-uuid) aut-id (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p1 (protocolar! tx ente) p2 (protocolar! tx ente)
              a1 (gerar-autografo! tx ente p1)
              a2 (gerar-autografo! tx ente p2)]
          (reset! aut-id (:id a1))
          (is (= 1 (:numero a1)) "primeiro autografo do ano = 1")
          (is (= 2 (:numero a2)) "segundo = 2 (gapless por ente/ano)")
          (let [r (autografo/buscar tx ente (:id a1))]
            (is (= p1 (:proposicao-id r)))
            (is (= "Prefeito Municipal de Fortaleza" (:destinatario-texto r)))
            (is (m/validate mod-aut/Autografo r) "autografo bate o model")))))
    ;; append-only puro: nem UPDATE nem DELETE
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.autografo SET numero = 99 WHERE id = ?" @aut-id]))))
        "autografo e' append-only (sem UPDATE — artefato legal imutavel)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["DELETE FROM legislativo.autografo WHERE id = ?" @aut-id]))))
        "autografo e' append-only (sem DELETE)")))

(deftest autografo-unico-por-proposicao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)]
          (gerar-autografo! tx ente pid)
          (is (thrown? Exception (gerar-autografo! tx ente pid))
              "uma proposicao gera UM autografo (UNIQUE por proposicao)"))))))

;; ---------- caminho da SANCAO (expressa) ----------

(deftest fluxo-sancao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              {aid :id} (gerar-autografo! tx ente pid)
              {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
          (is (= "aguardando" (:estado (exec/buscar tx ente tid))) "nasce aguardando")
          (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancionado"
                                        :updated-by nil :lock-version 0})
          (let [r (exec/buscar tx ente tid)]
            (is (= "sancionado" (:estado r)))
            (is (some? (:respondido-em r)) "carimba respondido_em")
            (is (logic/promulgavel? (:estado r)) "sancionado -> promulgavel (vira norma em F3.8b)")
            (is (m/validate mod-exec/TramitacaoExecutiva r) "bate o model")))))))

;; ---------- caminho da SANCAO TACITA (silencio do Executivo) ----------

(deftest fluxo-sancao-tacita-e-promulgavel
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {aid :id} (gerar-autografo! tx ente pid)
              {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
          (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancao_tacita"
                                        :updated-by nil :lock-version 0})
          (is (logic/promulgavel? (:estado (exec/buscar tx ente tid))) "sancao tacita tambem promulga"))))))

;; ---------- caminho do VETO + apreciacao pela camara (reusa votacao eixo G, maioria absoluta) ----------

(deftest fluxo-veto-derrubado-pela-camara
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {aid :id} (gerar-autografo! tx ente pid)
              {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
          ;; Executivo veta (total). veto exige veto-tipo.
          (is (thrown? Exception
                       (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "vetado"
                                                     :updated-by nil :lock-version 0}))
              "veto sem veto-tipo barra (guard)")
          (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "vetado" :veto-tipo "total"
                                        :veto-razoes "Inconstitucional" :updated-by nil :lock-version 0})
          (is (= "vetado" (:estado (exec/buscar tx ente tid))))
          (is (not (logic/promulgavel? "vetado")) "vetado (pendente) ainda nao promulga")
          ;; a camara aprecia o veto = VOTACAO do eixo G (maioria absoluta). Derruba (>= maioria absoluta).
          (let [{vid :id} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao"
                                              :objeto-id pid :modalidade "nominal" :quorum-tipo "maioria_absoluta"})]
            (dotimes [_ 7] (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid
                                                        :vereador-id (random-uuid) :voto "sim"}))
            (let [enc (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 10 :updated-by nil :lock-version 0})]
              (is (= "aprovada" (:resultado enc)) "7 >= 6 (maioria absoluta de 10) -> derruba o veto"))
            ;; carimba a votacao e move p/ veto_derrubado
            (exec/apreciar-veto! tx {:id tid :ente-id ente :resultado "veto_derrubado"
                                     :veto-votacao-id vid :updated-by nil :lock-version 1})
            (let [r (exec/buscar tx ente tid)]
              (is (= "veto_derrubado" (:estado r)))
              (is (= vid (:veto-votacao-id r)) "carimba a votacao de apreciacao")
              (is (some? (:apreciado-em r)))
              (is (logic/promulgavel? (:estado r)) "veto derrubado -> promulgavel"))))))))

(deftest fluxo-veto-mantido-nao-promulga
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {aid :id} (gerar-autografo! tx ente pid)
              {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
          (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "vetado" :veto-tipo "parcial"
                                        :updated-by nil :lock-version 0})
          (exec/apreciar-veto! tx {:id tid :ente-id ente :resultado "veto_mantido"
                                   :veto-votacao-id nil :updated-by nil :lock-version 1})
          (is (= "veto_mantido" (:estado (exec/buscar tx ente tid))))
          (is (not (logic/promulgavel? "veto_mantido")) "veto mantido arquiva (nao vira norma)"))))))

;; ---------- guards de state machine + trava terminal ----------

(deftest state-machine-guards
  (let [ente (random-uuid) ctx (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {aid :id} (gerar-autografo! tx ente pid)
              {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
          (reset! ctx {:tid tid})
          ;; nao se aprecia veto de quem nao esta 'vetado'
          (is (thrown? Exception
                       (exec/apreciar-veto! tx {:id tid :ente-id ente :resultado "veto_derrubado"
                                                :veto-votacao-id nil :updated-by nil :lock-version 0}))
              "apreciar-veto! exige estado 'vetado'")
          ;; sanciona (terminal)
          (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancionado"
                                        :updated-by nil :lock-version 0}))))
    ;; sancionado e' terminal: nao se responde de novo
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (exec/registrar-resposta! tx {:id (:tid @ctx) :ente-id ente :resultado "vetado"
                                                          :veto-tipo "total" :updated-by nil :lock-version 1}))))
        "tramitacao terminal (sancionado) nao reabre (guard 'aguardando')")
    ;; e o trigger tambem trava UPDATE direto num estado terminal
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.tramitacao_executiva SET estado = 'vetado' WHERE id = ?"
                                                   (:tid @ctx)]))))
        "trava terminal (b) barra UPDATE direto em estado terminal")))

(deftest vocabularios-invalidos-barram
  (let [ente (random-uuid) ctx (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {aid :id} (gerar-autografo! tx ente pid)
              {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
          (reset! ctx {:tid tid})
          (is (thrown? Exception
                       (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "engavetado"
                                                     :updated-by nil :lock-version 0}))
              "resultado fora do vocabulario barra (guard)"))))
    ;; veto_tipo invalido barra no CHECK do banco
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.tramitacao_executiva SET veto_tipo = 'meio' WHERE id = ?"
                                                   (:tid @ctx)]))))
        "veto_tipo invalido barra (CHECK)")))
