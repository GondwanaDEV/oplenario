(ns oplenario.participacao.ouvidoria-test
  "INTEGRACAO (PG real) — FAST-FOLLOW Slice 5: OUVIDORIA (Lei 13.460/2017 art. 10) via o Repo-Component. Prova,
  contra o banco real sob FORCE RLS (mig 0042): (1) protocolar-manifestacao! materializa a manifestacao + o
  RELOGIO (prazo_ativo objeto_tipo='manifestacao_ouvidoria', 30 dias) na MESMA tx do recibo + emite; (2)
  ANONIMA nao persiste manifestante_identidade_id (decisao: anonima != sem-auth); (3) responder cumpre o prazo
  + grava resposta APPEND-ONLY; (4) arquivar CANCELA o prazo (nao cumpre — sem merito); (5) prorrogar (CAS 1x)
  desloca o vencimento EFETIVO (+30, Lei 13.460 'por igual periodo'), 2a tentativa e' no-op (nil); (6) o sweep
  usa o vencimento EFETIVO (COALESCE) — um prazo prorrogado NAO vence antes da nova data; (7) isolamento de
  tenant (RLS). Constroi o Repo direto (datasource + outbox/bus) p/ inspecionar o outbox."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; relogio fixo: 12:00Z de 2026-07-03 -> zona civil America/Fortaleza = 2026-07-03.
;; ouvidoria: vence = recibo + 30 dias corridos = 2026-08-02.
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private vence-esperado (LocalDate/of 2026 8 2))
(def ^:private vence-prorrogado-esperado (LocalDate/of 2026 9 1))  ; +30 a partir do vence ORIGINAL

(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo, payload::text AS payload FROM shared.outbox WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

(defn- protocolar!
  ([ente cidadao] (protocolar! ente cidadao false))
  ([ente cidadao anonima?]
   (controllers/protocolar-manifestacao! *repo* relogio (ator ente cidadao)
     {:tipo "reclamacao" :assunto "Buracos na rua" :descricao "A rua X esta cheia de buracos ha meses."
      :anonima anonima?})))

;; ---------- protocolar: manifestacao + prazo + evento na mesma tx ----------

(deftest protocolar-insere-manifestacao-prazo-e-emite-evento
  (let [ente (random-uuid) cidadao (random-uuid)
        {:keys [id protocolo recibo-em]} (protocolar! ente cidadao)]
    (is (= "OUV-2026-000001" protocolo) "protocolo gapless comeca em 1 no ano (ente fresco)")
    (is (= t0 recibo-em) "recibo-em = instante do relogio (marco de inicio do relogio Lei 13.460)")
    (let [manif (repo-part/buscar-manifestacao *repo* ente id)
          prazo (repo-part/prazo-do-objeto *repo* ente "manifestacao_ouvidoria" id)]
      (is (= "protocolada" (:estado manif)) "manifestacao nasce protocolada")
      (is (= cidadao (:manifestante-identidade-id manif)) "manifestante NAO-anonima persiste o ator")
      (is (= "pendente" (:estado prazo)) "prazo materializado pendente")
      (is (= "manifestacao_ouvidoria" (:objeto-tipo prazo)) "objeto_tipo do prazo")
      (is (= vence-esperado (:vence-em prazo)) "vence_em = recibo + 30 dias corridos (Lei 13.460 art. 10)")
      (is (= 30 (:base-dias prazo)) "base_dias registra a proveniencia do calculo (30)"))
    (let [evs (eventos-por-tipo ente "participacao.manifestacao_ouvidoria.protocolada")]
      (is (= 1 (count evs)) "exatamente 1 evento protocolada (mesma tx do ato)")
      (is (re-find #"OUV-2026-000001" (:payload (first evs))) "payload carrega o protocolo"))))

;; ---------- anonima: NAO persiste manifestante (decisao: anonima != sem-auth) ----------

(deftest manifestacao-anonima-nao-persiste-manifestante
  (let [ente (random-uuid) cidadao (random-uuid)
        {:keys [id]} (protocolar! ente cidadao true)
        manif (repo-part/buscar-manifestacao *repo* ente id)]
    (is (true? (:anonima manif)) "flag anonima persistida")
    (is (nil? (:manifestante-identidade-id manif))
        "manifestante_identidade_id NAO persiste quando anonima (o ator so' serviu p/ authz/anti-abuso)")))

;; ---------- acompanhar publico: estado + dias-restantes sob relogio fixo ----------

(deftest acompanhar-devolve-estado-e-dias-restantes
  (let [ente (random-uuid) cidadao (random-uuid)
        {:keys [protocolo]} (protocolar! ente cidadao)
        acomp (controllers/acompanhar-manifestacao-por-protocolo *repo* ente relogio protocolo)]
    (is (= protocolo (:protocolo acomp)))
    (is (= "protocolada" (:estado acomp)))
    (is (= 30 (:dias-restantes acomp)) "no dia do recibo faltam 30 (Lei 13.460 art. 10)")))

;; ---------- responder: cumpre o prazo (com merito) ----------

(deftest responder-cumpre-manifestacao-e-prazo
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {:keys [id]} (protocolar! ente cidadao)
        r (controllers/responder-manifestacao! *repo* relogio (ator ente servidor) id {:corpo "Encaminhado a manutencao."})]
    (is (= t0 (:respondida-em r)))
    (is (= "respondida" (:estado (repo-part/buscar-manifestacao *repo* ente id))))
    (is (= "cumprida" (:estado (repo-part/prazo-do-objeto *repo* ente "manifestacao_ouvidoria" id)))
        "responder CUMPRE o prazo (com merito)")
    (is (= 1 (count (eventos-por-tipo ente "participacao.manifestacao_ouvidoria.respondida"))))))

;; ---------- arquivar: CANCELA o prazo (sem merito, NAO cumprida) ----------

(deftest arquivar-cancela-manifestacao-e-prazo-sem-cumprir
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {:keys [id]} (protocolar! ente cidadao)
        r (controllers/arquivar-manifestacao! *repo* relogio (ator ente servidor) id {:motivo "Duplicada do protocolo OUV-2026-000001."})]
    (is (some? r))
    (is (= "arquivada" (:estado (repo-part/buscar-manifestacao *repo* ente id))))
    (is (= "cancelada" (:estado (repo-part/prazo-do-objeto *repo* ente "manifestacao_ouvidoria" id)))
        "arquivar CANCELA o prazo (sem merito) — NAO 'cumprida'")
    (is (= 1 (count (eventos-por-tipo ente "participacao.manifestacao_ouvidoria.arquivada"))))))

;; ---------- prorrogar: CAS 1x, desloca o vencimento EFETIVO ----------

(deftest prorrogar-desloca-vencimento-efetivo-uma-unica-vez
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {:keys [id]} (protocolar! ente cidadao)
        r1 (controllers/prorrogar-manifestacao! *repo* relogio (ator ente servidor) id
             {:justificativa "Apuracao demanda mais tempo (Lei 13.460 art. 10)."})]
    (is (some? r1) "1a prorrogacao sucede")
    (let [prazo (repo-part/prazo-do-objeto *repo* ente "manifestacao_ouvidoria" id)]
      (is (= vence-esperado (:vence-em prazo)) "vence_em ORIGINAL intocado (so' prorrogado_ate muda)")
      (is (= vence-prorrogado-esperado (:prorrogado-ate prazo)) "prorrogado_ate = +30 a partir do original"))
    (let [acomp (controllers/acompanhar-manifestacao-por-protocolo *repo* ente relogio
                  (:protocolo (repo-part/buscar-manifestacao *repo* ente id)))]
      (is (= 60 (:dias-restantes acomp)) "o anel publico usa o vencimento EFETIVO (prorrogado)"))
    ;; 2a tentativa: CAS nao casa (prorrogado_ate ja nao e' nil) -> nil -> controller sinaliza conflito.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ja prorrogad"
          (controllers/prorrogar-manifestacao! *repo* relogio (ator ente servidor) id
            {:justificativa "2a tentativa deveria falhar."}))
        "prorrogar 2x -> conflito (a Lei 13.460 admite so' 1 prorrogacao 'por igual periodo')")
    (let [prorrogacoes (jdbc/execute! *ds*
                          ["SELECT id FROM participacao.prorrogacao WHERE ente_id = ? AND objeto_id = ?" ente id])]
      (is (= 1 (count prorrogacoes)) "so' 1 linha de prorrogacao registrada (a 2a tentativa nao escreveu)"))))

;; ---------- sweep usa o vencimento EFETIVO: prazo prorrogado NAO vence antes da nova data ----------

(deftest sweep-nao-vence-prazo-prorrogado-antes-da-nova-data
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {:keys [id]} (protocolar! ente cidadao)   ; vence ORIGINAL 2026-08-02
        _ (controllers/prorrogar-manifestacao! *repo* relogio (ator ente servidor) id
            {:justificativa "Apuracao em curso."})]   ; prorrogado_ate = 2026-09-01
    ;; hoje = 2026-08-15: > vence_em ORIGINAL (08-02) mas < prorrogado_ate (09-01) -> NAO vence (COALESCE).
    (let [transicionadas (repo-part/varrer-vencimentos! *repo* ente (LocalDate/of 2026 8 15))]
      (is (empty? transicionadas)
          "o sweep usa COALESCE(prorrogado_ate, vence_em): apos o vencimento ORIGINAL mas antes do EFETIVO, nao vence"))
    (is (= "pendente" (:estado (repo-part/prazo-do-objeto *repo* ente "manifestacao_ouvidoria" id))) "segue pendente")
    ;; hoje = 2026-09-02: > prorrogado_ate (09-01) -> vence.
    (let [transicionadas (repo-part/varrer-vencimentos! *repo* ente (LocalDate/of 2026 9 2))]
      (is (= [id] (mapv :objeto-id transicionadas)) "apos o vencimento EFETIVO (prorrogado), o sweep vence"))
    (is (= "vencida" (:estado (repo-part/prazo-do-objeto *repo* ente "manifestacao_ouvidoria" id))))))

;; ---------- ISOLAMENTO de tenant (RLS) ----------

(deftest tenant-nao-le-manifestacao-de-outro
  (let [ente-a (random-uuid) ente-b (random-uuid) cidadao (random-uuid)
        {:keys [protocolo]} (protocolar! ente-a cidadao)]
    (is (nil? (controllers/acompanhar-manifestacao-por-protocolo *repo* ente-b relogio protocolo))
        "acompanhar sob o tenant errado NAO encontra a manifestacao de outro ente (RLS isola)")
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx]
        (is (empty? (jdbc/execute! tx ["SELECT id FROM participacao.manifestacao_ouvidoria WHERE protocolo = ?" protocolo]))
            "RLS: a manifestacao de ente-a nao aparece na visao de ente-b nem por SQL direto")))))
