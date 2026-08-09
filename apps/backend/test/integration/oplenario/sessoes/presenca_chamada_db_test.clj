(ns oplenario.sessoes.presenca-chamada-db-test
  "INTEGRACAO (PG real): as LEITURAS que alimentam a CHAMADA (§22.6 eixo C, fatia 1b-DB). A derivacao pura
  (`logic/estado-de-presenca` / `contar-quorum`) ja' esta' provada em `presenca-chamada-test` (unit); aqui
  prova-se a OUTRA metade: que o dado que ela recebe sai do banco correto.

  Duas leituras novas em `sessoes/db/presenca`:
    - `presenca-corrente`  = o ULTIMO evento de CADA vereador da sessao ate' um instante (DISTINCT ON), pela
      subquery CANONICA de `logic/ultimos-eventos-por-vereador-q` — a MESMA que o quorum do motor e o
      dashboard da Mesa usam. Nao ha' terceira transcricao da ordem de desempate (fatia 1a).
    - `listar-justificativas-da-sessao` = os atos apartados da sessao (o 3o insumo da derivacao).

  E o metodo COMPOSTO do Repo (`chamada-da-sessao`): sessao + presencas + justificativas numa UNICA tx do
  tenant. Tres leituras em tres tx separadas dariam uma chamada montada de instantes diferentes — um vereador
  poderia entrar entre a leitura dos eventos e a das justificativas e aparecer presente E justificado."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                ;; bus nil: este ns so' exercita LEITURAS (nenhum producer e' alcancado).
                *repo* (repo/->RepoSessoesPg c nil)]
        (try (t) (finally (component/stop c)))))))

(defn- nova-sessao! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                            :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"})))

;; O seed passa por `presenca/registrar-evento!` — o PRODUTOR real do sistema, que valida tipo/modalidade/
;; fonte contra `sessoes/logic` (fail-closed) antes de tocar o banco. Um vocabulario inventado aqui nao
;; chega ao INSERT: explode no teste, em vez de deixar a leitura verde sobre dado que producao nunca emite.
(defn- ev! [tx ente sessao-id vereador-id extra]
  (presenca/registrar-evento! tx (merge {:id (random-uuid) :ente-id ente :sessao-id sessao-id
                                         :vereador-id vereador-id :tipo "entrada" :modalidade "plenario"
                                         :fonte "manual_secretaria"
                                         :ocorrido-em (Instant/parse "2026-06-20T10:00:00Z")}
                                        extra)))

(def ^:private t10   (Instant/parse "2026-06-20T10:00:00Z"))
(def ^:private t1020 (Instant/parse "2026-06-20T10:20:00Z"))
(def ^:private t1030 (Instant/parse "2026-06-20T10:30:00Z"))
(def ^:private t1040 (Instant/parse "2026-06-20T10:40:00Z"))
(def ^:private t11   (Instant/parse "2026-06-20T11:00:00Z"))

;; ---------- T9: o desempate de MESMO instante vale tambem nesta leitura ----------

(deftest t9-presenca-corrente-desempata-mesmo-instante-pela-fonte
  ;; A chamada e' a tela que a Mesa projeta. Se ela desempatasse o mesmo instante por outro criterio que o
  ;; quorum do motor, o telao anunciaria um vereador presente enquanto a policy de votacao o contaria ausente.
  ;; Este assert e' o que ancora `presenca-corrente` na ordem canonica da fatia 1a (nao numa 3a transcricao).
  (let [ente (random-uuid) ver (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)]
          ;; MESMO instante: o painel eletronico registra saida, a secretaria registra entrada.
          (ev! tx ente sid ver {:tipo "saida"   :fonte "painel_eletronico" :ocorrido-em t10})
          (ev! tx ente sid ver {:tipo "entrada" :fonte "manual_secretaria" :ocorrido-em t10})
          (let [linhas (presenca/presenca-corrente tx ente sid t1030)]
            (is (= 1 (count linhas)) "um vereador, uma linha")
            (is (= "entrada" (:tipo (first linhas))))
            (is (= "manual_secretaria" (:fonte (first linhas)))
                "manual_secretaria > painel_eletronico no desempate de mesmo instante")
            (is (true? (logic/presente-por-tipo? (:tipo (first linhas))))
                "a derivacao pura le' este mesmo `tipo` e conclui PRESENTE")))))))

;; ---------- T12: uma linha por vereador, nunca fan-out ----------

(deftest t12-presenca-corrente-uma-linha-por-vereador-com-n-eventos
  ;; `presenca_evento` e' append-only: um vereador que entra, sai e volta tem TRES linhas na tabela. A chamada
  ;; tem de mostrar UMA — a ultima. Sem o DISTINCT ON o vereador apareceria tres vezes no telao e o quorum
  ;; contaria tres presentes onde ha' uma pessoa.
  (let [ente (random-uuid) a (random-uuid) b (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)]
          (ev! tx ente sid a {:tipo "entrada" :ocorrido-em t10})
          (ev! tx ente sid a {:tipo "saida"   :ocorrido-em t1020})
          (ev! tx ente sid a {:tipo "retorno" :ocorrido-em t1040})
          (ev! tx ente sid b {:tipo "entrada" :ocorrido-em t10})
          (let [linhas (presenca/presenca-corrente tx ente sid t11)
                por-ver (into {} (map (juxt :vereador-id identity)) linhas)]
            (is (= 2 (count linhas)) "2 vereadores, 2 linhas (4 eventos na tabela)")
            (is (= 2 (count por-ver)) "sem vereador repetido")
            (is (= "retorno" (:tipo (por-ver a))) "o ULTIMO evento de A, nao o primeiro")
            (is (= t1040 (:ocorrido-em (por-ver a))))
            ;; consulta historica: as 10:30 o ultimo evento de A ainda era a saida.
            (is (= "saida" (:tipo (-> (presenca/presenca-corrente tx ente sid t1030)
                                      (->> (filter #(= a (:vereador-id %)))) first)))
                "o instante de corte e' respeitado (o retorno das 10:40 ainda nao ocorreu)")))))))

;; ---------- projecao: a chamada precisa da fonte e dos dois tempos ----------

(deftest presenca-corrente-projeta-o-contrato-da-chamada
  ;; `fonte` alimenta a coluna "como foi registrado" da chamada (a Mesa distingue o que ela mesma marcou do
  ;; que o vereador confirmou pelo celular); `ocorrido-em` (dominio) e `registrado-em` (audit) sao tempos
  ;; DIFERENTES e a ata precisa dos dois — publicar so' um deles perde a diferenca entre "entrou as 10h" e
  ;; "a secretaria digitou as 11h".
  (let [ente (random-uuid) ver (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)]
          (ev! tx ente sid ver {:tipo "entrada" :modalidade "remoto" :fonte "autoatendimento" :ocorrido-em t10})
          (let [l (first (presenca/presenca-corrente tx ente sid t1030))]
            (is (= #{:vereador-id :tipo :modalidade :fonte :ocorrido-em :registrado-em} (set (keys l))))
            (is (= ver (:vereador-id l)))
            (is (= "remoto" (:modalidade l)))
            (is (= "autoatendimento" (:fonte l)))
            (is (some? (:registrado-em l)) "audit carimbado pelo DEFAULT now()")))))))

;; ---------- justificativas da sessao ----------

(deftest listar-justificativas-da-sessao-projeta-estado-e-lock
  ;; O `lock-version` vai no contrato porque a borda que DECIDE a justificativa (Etapa 2) faz CAS com ele:
  ;; sem devolve-lo aqui, a tela teria de fazer uma segunda leitura por linha so' para poder deferir.
  (let [ente (random-uuid) a (random-uuid) b (random-uuid) decisor (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)
              {ja :id} (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id sid
                                                          :vereador-id a :motivo "Atestado medico"})
              _        (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id sid
                                                          :vereador-id b :motivo "Missao oficial"})]
          (presenca/decidir-justificativa! tx {:ente-id ente :id ja :estado "aprovada"
                                               :decidido-por decisor :lock-version 0})
          (let [linhas (presenca/listar-justificativas-da-sessao tx ente sid)
                por-ver (into {} (map (juxt :vereador-id identity)) linhas)]
            (is (= 2 (count linhas)))
            (is (= #{:id :vereador-id :estado :motivo :decidido-por :decidido-em :lock-version}
                   (set (keys (first linhas)))))
            (is (= "aprovada" (:estado (por-ver a))))
            (is (= decisor (:decidido-por (por-ver a))))
            (is (some? (:decidido-em (por-ver a))))
            (is (= 1 (:lock-version (por-ver a))) "o CAS incrementou")
            (is (= "pendente" (:estado (por-ver b))))
            (is (nil? (:decidido-por (por-ver b))))
            (is (= 0 (:lock-version (por-ver b))))))))))

(deftest justificativa-de-outra-sessao-nao-vaza
  (let [ente (random-uuid) ver (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [s1 (nova-sessao! tx ente)
              s2 (nova-sessao! tx ente)]
          (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id s1
                                             :vereador-id ver :motivo "Atestado"})
          (is (= 1 (count (presenca/listar-justificativas-da-sessao tx ente s1))))
          (is (= [] (presenca/listar-justificativas-da-sessao tx ente s2))))))))

;; ---------- T13: a sessao sem nenhum registro ----------

(deftest t13-sessao-sem-evento-nem-justificativa-devolve-vazio
  ;; E' o caso que distingue "todo mundo faltou" de "ninguem fez a chamada". As DUAS leituras vazias sao o
  ;; dado que permite a borda marcar `sem-registro-de-presenca` em vez de publicar 21 ausencias — cada uma
  ;; delas uma acusacao falsa que vai para a ata.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (nova-sessao! tx ente)]
          (is (= [] (presenca/presenca-corrente tx ente sid t11)))
          (is (= [] (presenca/listar-justificativas-da-sessao tx ente sid))))))))

;; ---------- o Repo: as tres leituras numa UNICA tx ----------

(deftest chamada-da-sessao-le-tudo-numa-so-tx
  (let [ente (random-uuid) a (random-uuid) b (random-uuid)
        sid (repo/transacao *repo* ente
              (fn [tx]
                (let [sid (nova-sessao! tx ente)]
                  (ev! tx ente sid a {:tipo "entrada" :ocorrido-em t10})
                  (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id sid
                                                     :vereador-id b :motivo "Atestado"})
                  sid)))
        r (repo/chamada-da-sessao *repo* ente sid t11)]
    (is (= sid (:id (:sessao r))) "a sessao vem junto — e' o guard de 404 da borda")
    (is (= "agendada" (:estado (:sessao r))))
    (is (= [a] (mapv :vereador-id (:presencas r))))
    (is (= [b] (mapv :vereador-id (:justificativas r))))
    ;; os metodos granulares tambem existem no protocolo (o motor/outras bordas leem um lado so').
    (is (= [a] (mapv :vereador-id (repo/presenca-corrente *repo* ente sid t11))))
    (is (= [b] (mapv :vereador-id (repo/listar-justificativas *repo* ente sid))))))

(deftest chamada-da-sessao-devolve-nil-em-sessao-inexistente
  ;; nil (nao um mapa com listas vazias) — a borda traduz nil em 404. Um {:sessao nil :presencas []} viraria
  ;; uma chamada 200 OK de uma sessao que nao existe.
  (is (nil? (repo/chamada-da-sessao *repo* (random-uuid) (random-uuid) t11))))
