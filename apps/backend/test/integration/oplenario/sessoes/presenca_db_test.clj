(ns oplenario.sessoes.presenca-db-test
  "INTEGRACAO (PG real): F4.3a — §22.6 eixo C, PRESENCA e quorum (camada de fatos). Prova: presenca_evento
  APPEND-ONLY (entrada|saida|retorno|mudanca_modalidade x plenario|remoto x fonte); a presenca corrente e'
  DERIVADA do ultimo evento por vereador ate um instante (sem snapshot); precedencia em conflito de mesmo
  instante = manual_secretaria > painel_eletronico > inferida_*; agregadores presentes_plenario/presentes_remoto
  (insumo do quorum, expostos a DSL do motor em F4.3b); justificativa_ausencia = ato administrativo apartado com
  state machine pequena (pendente -> aprovada|indeferida, terminal). vereador_id e' forward-ref (uuid, sem FK
  cross-schema p/ cadastros, §22.10)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as registro-fatos]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.presenca :as mod]
            [oplenario.sessoes.relacoes.presenca :as rel-sessoes])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- nova-sessao! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                            :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"})))

(defn- ev! [tx ente sessao-id vereador-id extra]
  (presenca/registrar-evento! tx (merge {:id (random-uuid) :ente-id ente :sessao-id sessao-id
                                         :vereador-id vereador-id :tipo "entrada" :modalidade "plenario"
                                         :fonte "manual_secretaria" :ocorrido-em (Instant/parse "2026-06-20T10:00:00Z")}
                                        extra)))

(def ^:private t10 (Instant/parse "2026-06-20T10:00:00Z"))
(def ^:private t1030 (Instant/parse "2026-06-20T10:30:00Z"))
(def ^:private t1040 (Instant/parse "2026-06-20T10:40:00Z"))
(def ^:private t1045 (Instant/parse "2026-06-20T10:45:00Z"))
(def ^:private t11 (Instant/parse "2026-06-20T11:00:00Z"))
(def ^:private t1115 (Instant/parse "2026-06-20T11:15:00Z"))

;; ---------- logica pura: vocabularios + state machine ----------

(deftest vocabularios-presenca
  (is (contains? logic/tipos-evento-presenca "mudanca_modalidade"))
  (is (contains? logic/modalidades-presenca "remoto"))
  (is (contains? logic/fontes-presenca "inferida_por_voto"))
  (is (false? (contains? logic/fontes-presenca "videoconferencia")) "V1 nao integra videoconferencia (Nivel 1)")
  (is (true? (logic/presente-por-tipo? "entrada")))
  (is (true? (logic/presente-por-tipo? "retorno")))
  (is (false? (logic/presente-por-tipo? "saida")))
  (is (> (logic/precedencia-fonte "manual_secretaria") (logic/precedencia-fonte "painel_eletronico")))
  (is (> (logic/precedencia-fonte "painel_eletronico") (logic/precedencia-fonte "inferida_por_voto")))
  (is (thrown? Exception (logic/validar-tipo-evento "almoco")))
  (is (thrown? Exception (logic/validar-fonte "videoconferencia"))))

(deftest justificativa-state-machine-pura
  (is (true? (logic/transicao-justificativa-valida? "pendente" "aprovada")))
  (is (true? (logic/transicao-justificativa-valida? "pendente" "indeferida")))
  (is (false? (logic/transicao-justificativa-valida? "aprovada" "indeferida")) "terminal nao sai")
  (is (contains? logic/estados-justificativa-terminais "indeferida")))

;; ---------- presenca_evento: registro + derivacao ----------

(deftest presenca-deriva-do-ultimo-evento
  (let [ente (random-uuid) ver (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)]
          (ev! tx ente sid ver {:tipo "entrada" :ocorrido-em t10})
          (is (true? (rel-sessoes/esta-presente-em? tx sid ver t1030)) "entrada -> presente")
          (ev! tx ente sid ver {:tipo "saida" :ocorrido-em t11})
          (is (false? (rel-sessoes/esta-presente-em? tx sid ver t1115)) "saida posterior -> ausente")
          (is (true? (rel-sessoes/esta-presente-em? tx sid ver t1030))
              "consulta historica: presente em t1030 (antes da saida)")
          (is (= 2 (count (presenca/listar-eventos tx ente sid))) "ambos eventos persistidos (append-only)"))))))

(deftest precedencia-em-conflito-mesmo-instante
  (let [ente (random-uuid) ver (random-uuid) ver2 (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)]
          ;; mesmo instante: painel diz saida, secretaria diz entrada -> manual vence (presente)
          (ev! tx ente sid ver {:tipo "saida" :fonte "painel_eletronico" :ocorrido-em t10})
          (ev! tx ente sid ver {:tipo "entrada" :fonte "manual_secretaria" :ocorrido-em t10})
          (is (true? (rel-sessoes/esta-presente-em? tx sid ver t1030))
              "manual_secretaria > painel_eletronico no desempate de mesmo instante (via esta-presente?)")
          ;; segundo par: painel > inferida no mesmo instante; e o desempate tem de valer TAMBEM pelo caminho
          ;; do AGREGADOR (ultimos-eventos-q), nao so por esta-presente? — senao quorum incoerente.
          (ev! tx ente sid ver2 {:tipo "entrada" :fonte "inferida_por_voto"  :modalidade "plenario" :ocorrido-em t10})
          (ev! tx ente sid ver2 {:tipo "saida"   :fonte "painel_eletronico"  :modalidade "plenario" :ocorrido-em t10})
          (is (false? (rel-sessoes/esta-presente-em? tx sid ver2 t1030)) "painel (saida) > inferida (entrada)")
          (is (= 1 (rel-sessoes/presentes-plenario tx sid t1030))
              "agregador concorda com a precedencia: so ver presente; ver2 saiu (painel>inferida)"))))))

(deftest agregadores-quorum-por-modalidade
  (let [ente (random-uuid) a (random-uuid) b (random-uuid) c (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)]
          (ev! tx ente sid a {:modalidade "plenario" :ocorrido-em t10})
          (ev! tx ente sid b {:modalidade "remoto"   :ocorrido-em t10})
          (ev! tx ente sid c {:modalidade "plenario" :ocorrido-em t10})
          (is (= 2 (rel-sessoes/presentes-plenario tx sid t1030)) "A e C presentes no plenario as 10:30")
          (is (= 1 (rel-sessoes/presentes-remoto   tx sid t1030)) "B remoto")
          (ev! tx ente sid c {:tipo "saida" :modalidade "plenario" :ocorrido-em t1040})
          (is (= 1 (rel-sessoes/presentes-plenario tx sid t1045)) "C saiu as 10:40 -> so A no plenario as 10:45")
          ;; A muda de modalidade: sai do plenario, entra no remoto, sem perder presenca
          (ev! tx ente sid a {:tipo "mudanca_modalidade" :modalidade "remoto" :ocorrido-em t11})
          (is (= 0 (rel-sessoes/presentes-plenario tx sid t1115)) "A migrou; C saiu -> plenario vazio")
          (is (= 2 (rel-sessoes/presentes-remoto   tx sid t1115)) "A e B no remoto"))))))

(deftest presenca-e-append-only
  (let [ente (random-uuid) ver (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)
              {evid :id} (ev! tx ente sid ver {:tipo "entrada" :ocorrido-em t10})]
          (is (thrown? Exception
                (jdbc/execute-one! tx ["UPDATE sessoes.presenca_evento SET tipo='saida' WHERE id=?" evid]))
              "UPDATE barrado (append-only)")
          (is (thrown? Exception
                (jdbc/execute-one! tx ["DELETE FROM sessoes.presenca_evento WHERE id=?" evid]))
              "DELETE barrado (append-only)"))))))

;; ---------- justificativa_ausencia: ato apartado com state machine ----------

(deftest justificativa-fluxo-e-terminal
  (let [ente (random-uuid) ver (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)
              {jid :id} (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id sid
                                                           :vereador-id ver :motivo "Atestado medico"})]
          (is (= "pendente" (:estado (presenca/buscar-justificativa tx ente jid))))
          (presenca/decidir-justificativa! tx {:ente-id ente :id jid :estado "aprovada"
                                               :decidido-por (random-uuid) :lock-version 0})
          (is (= "aprovada" (:estado (presenca/buscar-justificativa tx ente jid))))
          (is (thrown? Exception
                (presenca/decidir-justificativa! tx {:ente-id ente :id jid :estado "indeferida"
                                                     :decidido-por (random-uuid) :lock-version 1}))
              "estado terminal nao transiciona"))))))

;; ---------- models ----------

(deftest models-validam
  (is (m/validate mod/PresencaEvento
                  {:ente-id (random-uuid) :id (random-uuid) :sessao-id (random-uuid) :vereador-id (random-uuid)
                   :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em t10}))
  (is (false? (m/validate mod/PresencaEvento
                          {:ente-id (random-uuid) :id (random-uuid) :sessao-id (random-uuid) :vereador-id (random-uuid)
                           :tipo "voando" :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em t10}))
      "tipo fora do enum reprova")
  (is (m/validate mod/JustificativaAusencia
                  {:ente-id (random-uuid) :id (random-uuid) :sessao-id (random-uuid) :vereador-id (random-uuid)
                   :estado "pendente" :motivo "Atestado" :lock-version 0})))

;; ---------- F4.3b: acoplamento a DSL do motor (quorum) ----------

(deftest costura-relacoes-sessoes
  ;; as fns de relacao do sessoes casam as assinaturas :relacao do catalogo (aridade 3 = SessaoId+Instante+tx).
  ;; E' o assert que o RegistroFatos roda no boot (fail-closed) — aqui sem subir o sistema.
  (let [r (registro-fatos/verificar-costura rel-sessoes/relacoes)]
    (is (:ok r) (str "costura quebrada: " (:erros r)))))

(deftest quorum-alcancado-pelo-motor-por-nome
  ;; prova viva da F2: o motor alcanca o agregador de quorum POR NOME (resolver-para), nunca por import (§22.10).
  (let [ente (random-uuid) a (random-uuid) b (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)
              ;; start intencionalmente omitido — a costura fail-closed do boot e' provada por costura-relacoes-sessoes;
              ;; aqui prova-se o caminho de RESOLUCAO por nome (resolver-para), nao o assert de boot.
              registro (registro-fatos/registro-fatos rel-sessoes/relacoes)
              resolver (registro-fatos/resolver-para registro tx)]
          (ev! tx ente sid a {:modalidade "plenario" :ocorrido-em t10})
          (ev! tx ente sid b {:modalidade "remoto"   :ocorrido-em t10})
          (is (= 1 (resolver "presentes_plenario" [sid t11])) "exatamente A no plenario via resolver")
          (is (= 1 (resolver "presentes_remoto"   [sid t11])) "exatamente B no remoto via resolver"))))))

;; ---------- presenca agregada (read-model barato, F7/FE Onda A1) ----------

(deftest resumo-presenca-agrega-as-ultimas-10-sessoes-encerradas
  ;; membros-da-casa chega JA RESOLVIDO do caller (cadastros, injecao cross-modulo — este ns nao importa
  ;; cadastros, §22.10); aqui e' uma constante fixa (3) que espelha o contrato do adapter/Repo. `antes` fica
  ;; seguramente no passado relativo ao instante de encerramento (Postgres now(), congelado no INICIO da tx —
  ;; ver o carry documentado em sessao/transicionar!), sem depender do relogio-de-parede do host de teste.
  (let [ente (random-uuid)
        v1 (random-uuid) v2 (random-uuid)
        antes (.minusSeconds (Instant/now) 3600)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [s1 (nova-sessao! tx ente)
              s2 (nova-sessao! tx ente)]
          ;; leva as duas sessoes a 'encerrada' pela maquina real (agendada -> aberta -> encerrada;
          ;; lock_version 0 -> 1 -> 2), nao ha `inserir!` de baixo nivel neste modulo.
          (sessao/transicionar! tx {:id s1 :ente-id ente :para "aberta" :lock-version 0})
          (sessao/transicionar! tx {:id s1 :ente-id ente :para "encerrada" :lock-version 1})
          (sessao/transicionar! tx {:id s2 :ente-id ente :para "aberta" :lock-version 0})
          (sessao/transicionar! tx {:id s2 :ente-id ente :para "encerrada" :lock-version 1})
          ;; s1: v1+v2 presentes (2/3); s2: so v1 presente (1/3) — v3 (o 3o membro) nunca aparece
          (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id s1 :vereador-id v1
                                          :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                          :ocorrido-em antes})
          (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id s1 :vereador-id v2
                                          :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                          :ocorrido-em antes})
          (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id s2 :vereador-id v1
                                          :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                          :ocorrido-em antes})
          (let [r (presenca/resumo-presenca tx ente 3 10)]
            (is (= 2 (:sessoes-consideradas r)))
            (is (= 3 (:membros-da-casa r)))
            ;; numerador (2+1)=3, denominador 2*3=6 -> 50%
            (is (= 50 (:media-percentual r)))))))
    (let [ente2 (random-uuid)]
      (tenancy/com-tenant* *ds* ente2
        (fn [tx]
          (is (nil? (:media-percentual (presenca/resumo-presenca tx ente2 3 10)))
              "sem sessao encerrada -> media indefinida (nil, nao 0%)"))))))
