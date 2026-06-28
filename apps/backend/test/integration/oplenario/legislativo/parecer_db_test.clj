(ns oplenario.legislativo.parecer-db-test
  "INTEGRACAO (PG real): eixo F — parecer_comissao (§22.4), NUCLEO (F3.6a). Prova: o parecer e' uma
  entidade propria com state machine PROPRIA governada pelo MESMO motor do eixo C — reusa as MESMAS
  tabelas de template (tram/transicoes-de, subject-agnosticas) + o MESMO avaliador (motor/guarda-dsl,
  disciplina 5), com estado/historico PROPRIOS. Estado inicial DERIVADO de template.estado_inicial
  (template-driven, nao hardcoded). Ref polimorfica (objeto_tipo,objeto_id) p/ proposicao OU emenda, com
  prova de existencia same-tenant (disc.2). Discriminador de sujeito barra template de proposicao. Trava
  terminal (nivel b) + historico append-only (nivel a). Decisao (b) do workflow de reuso do motor."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.emenda :as em]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.db.parecer-tramitacao :as ptram]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.models.parecer :as mod]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))

(defn- montar-template-parecer! [tx ente]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "parecer_ccj" :versao 1 :sujeito "parecer"
                              :nome "Parecer CCJ [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (doseq [[ch nm term] [["aguardando_designacao" "Aguardando designacao" false]
                          ["com_relator" "Com relator" false] ["apresentado" "Apresentado" false]
                          ["aprovado" "Aprovado" true] ["rejeitado" "Rejeitado" true]]]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nm :terminal term}))
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "aguardando_designacao"
                               :para-estado "com_relator" :gatilho "designar" :guarda nil :ordem 1})
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "com_relator"
                               :para-estado "apresentado" :gatilho "apresentar" :guarda nil :ordem 1})
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "apresentado"
                               :para-estado "aprovado" :gatilho "aprovar" :guarda nil :ordem 1})
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "apresentado"
                               :para-estado "rejeitado" :gatilho "bloquear" :guarda "falso" :ordem 1})
    tid))

(defn- protocolar! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- criar-parecer! [tx ente tid objeto-tipo objeto-id]
  (parecer/criar! tx {:id (random-uuid) :ente-id ente :objeto-tipo objeto-tipo :objeto-id objeto-id
                      :comissao-id (random-uuid) :template-id tid}))

(defn- transicionar [tx ente tid parecer-id gatilho]
  (ptram/transicionar-parecer! tx {:registro *registro* :ente-id ente :parecer-id parecer-id
                                   :template-id tid :gatilho gatilho :agora data}))

(deftest criar-deriva-estado-inicial-do-template-e-conforma
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template-parecer! tx ente)
              pid (protocolar! tx ente)
              {pcid :id est :estado} (criar-parecer! tx ente tid "proposicao" pid)]
          (is (= "aguardando_designacao" est) "estado inicial DERIVADO de template.estado_inicial (nao hardcoded)")
          (let [r (parecer/buscar tx ente pcid)]
            (is (= "aguardando_designacao" (:estado r)) "estado persistido")
            (is (= "proposicao" (:objeto-tipo r)))
            (is (= pid (:objeto-id r)))
            (is (nil? (:relator-id r)) "relator designado depois")
            (is (m/validate mod/Parecer r) "parecer bate o model interno")))))))

(deftest engine-guard-e-mudanca-de-estado
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template-parecer! tx ente)
              pid (protocolar! tx ente)
              {pcid :id} (criar-parecer! tx ente tid "proposicao" pid)]
          (let [r1 (transicionar tx ente tid pcid "designar")]
            (is (true? (:transicionou? r1)) "guard nil -> transiciona")
            (is (= "com_relator" (:para r1)))
            (is (= "com_relator" (:estado (parecer/buscar tx ente pcid))) "estado do parecer mudou"))
          (transicionar tx ente tid pcid "apresentar")          ; com_relator -> apresentado
          (let [r2 (transicionar tx ente tid pcid "bloquear")]
            (is (false? (:transicionou? r2)) "guard 'falso' bloqueia")
            (is (= "apresentado" (:estado (parecer/buscar tx ente pcid))) "estado inalterado apos bloqueio"))
          (is (= 2 (count (ptram/historico-do-parecer tx ente pcid)))
              "so as transicoes que OCORRERAM (designar+apresentar) foram ao historico"))))))

(deftest historico-append-only
  (let [ente (random-uuid) hid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template-parecer! tx ente) pid (protocolar! tx ente)
              {pcid :id} (criar-parecer! tx ente tid "proposicao" pid)]
          (transicionar tx ente tid pcid "designar")
          (reset! hid (:id (first (ptram/historico-do-parecer tx ente pcid)))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.parecer_transicao_historico SET gatilho = 'hack' WHERE id = ?" @hid]))))
        "historico de transicao do parecer e' append-only (sem UPDATE/DELETE)")))

(deftest ref-polimorfica-ambos-tipos-e-objeto-deve-existir
  (let [ente (random-uuid) ctx (atom nil)]
    ;; parecer sobre PROPOSICAO e sobre EMENDA = ambos OK; tipo invalido e objeto orfao = barrados
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template-parecer! tx ente)
              pid (protocolar! tx ente)
              eid (:id (em/criar! tx {:id (random-uuid) :ente-id ente :proposicao-mae-id pid
                                      :tipo-emenda "modificativa" :momento-apresentacao "no_prazo"
                                      :texto-inline "X->Y"}))]
          (reset! ctx {:tid tid :pid pid :eid eid})
          (is (some? (:id (criar-parecer! tx ente tid "proposicao" pid))) "parecer sobre proposicao OK")
          (is (some? (:id (criar-parecer! tx ente tid "emenda" eid))) "parecer sobre emenda OK"))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (criar-parecer! tx ente (:tid @ctx) "votacao" (:pid @ctx)))))
        "objeto_tipo fora do vocabulario e barrado (whitelist Clojure no criar!, antes do INSERT)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (criar-parecer! tx ente (:tid @ctx) "proposicao" (random-uuid)))))
        "objeto_id inexistente no tenant e barrado (prova de existencia, disc.2)")))

(deftest template-de-sujeito-errado-barra
  ;; discriminador de sujeito: um parecer NAO pode ser governado por um template de PROPOSICAO.
  (let [ente (random-uuid) ctx (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid-prop (random-uuid) pid (protocolar! tx ente)]
          ;; template SEM :sujeito -> default 'proposicao'
          (tram/criar-template! tx {:id tid-prop :ente-id ente :chave "rito" :versao 1
                                    :nome "Rito [FIXTURE]" :estado-inicial "protocolada"})
          (reset! ctx {:tid-prop tid-prop :pid pid}))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (criar-parecer! tx ente (:tid-prop @ctx) "proposicao" (:pid @ctx)))))
        "parecer com template de sujeito 'proposicao' e' barrado (anti-misconfig)")))

(deftest estado-terminal-trava
  (let [ente (random-uuid) ctx (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-template-parecer! tx ente) pid (protocolar! tx ente)
              {pcid :id} (criar-parecer! tx ente tid "proposicao" pid)]
          (transicionar tx ente tid pcid "designar")            ; -> com_relator
          (transicionar tx ente tid pcid "apresentar")          ; -> apresentado
          (transicionar tx ente tid pcid "aprovar")             ; -> aprovado (TERMINAL)
          (reset! ctx {:pcid pcid})
          (is (= "aprovado" (:estado (parecer/buscar tx ente pcid))) "chegou ao terminal"))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (parecer/mudar-estado! tx {:id (:pcid @ctx) :ente-id ente :estado "rejeitado"
                                                       :updated-by nil :lock-version 3}))))
        "parecer em estado terminal nao muda sem correcao auditada (imutabilidade nivel b)")))

(deftest rls-isola-cross-tenant
  (let [a (random-uuid) b (random-uuid) ctx (atom nil)]
    (tenancy/com-tenant* *ds* a
      (fn [tx]
        (let [tid (montar-template-parecer! tx a) pid (protocolar! tx a)
              {pcid :id} (criar-parecer! tx a tid "proposicao" pid)]
          (reset! ctx {:pid pid :pcid pcid}))))
    (is (seq (tenancy/com-tenant* *ds* a (fn [tx] (parecer/listar-por-objeto tx a "proposicao" (:pid @ctx)))))
        "A ve o proprio parecer")
    (is (empty? (tenancy/com-tenant* *ds* b (fn [tx] (parecer/listar-por-objeto tx b "proposicao" (:pid @ctx)))))
        "B NAO ve o parecer de A (RLS)")))
