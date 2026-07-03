(ns oplenario.participacao.titular-test
  "INTEGRACAO (PG real) — F6 Slice 4: PORTAL DO TITULAR LGPD (feature 5.10, Lei 13.709 arts. 18/23/41) via o
  Repo-Component. Prova, contra o banco real sob FORCE RLS (mig 0041): (1) solicitar-titular! materializa a
  solicitacao + o RELOGIO (prazo_ativo objeto_tipo='solicitacao_titular') na MESMA tx do recibo + emite — o
  prazo e' um CONTADOR SEPARADO do e-SIC (linha de prazo propria, vence_em derivado de dias-titular, nao da LAI
  20); (2) responder cumpre o prazo do titular + grava resposta APPEND-ONLY (UPDATE lanca); (3) encarregado
  upsert (o 2o definir ATUALIZA a mesma linha — prova a UNIQUE(ente_id)); (4) leitura publica do encarregado;
  (5) isolamento de tenant (RLS). Constroi o Repo direto (datasource + outbox/bus) p/ inspecionar o outbox."
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
;;   titular: vence = recibo + 15 (default [GAP]) = 2026-07-18. e-SIC seria +20 = 2026-07-23 (contador SEPARADO).
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private vence-titular (LocalDate/of 2026 7 18))

(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo, payload::text AS payload FROM shared.outbox WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

(defn- respostas-da-solicitacao [ente solicitacao-id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT id, corpo, respondido_por FROM participacao.resposta_titular
                                 WHERE ente_id = ? AND solicitacao_id = ?" ente solicitacao-id]))))

;; ---------- solicitar: solicitacao + prazo SEPARADO + evento na mesma tx ----------

(deftest solicitar-materializa-solicitacao-prazo-separado-e-emite
  (let [ente (random-uuid) titular (random-uuid)
        {:keys [id protocolo recibo-em]}
        (controllers/solicitar-titular! *repo* relogio (ator ente titular)
                                        {:tipo "corrigir" :detalhe "Corrigir meu endereco no cadastro."})]
    (is (re-matches #"LGPD-2026-\d{6}" protocolo) "protocolo com namespace proprio (LGPD-)")
    (is (= t0 recibo-em) "recibo-em = instante do relogio (marco do relogio LGPD)")
    (let [solic (repo-part/buscar-solicitacao-titular *repo* ente id)
          prazo (repo-part/prazo-do-objeto *repo* ente "solicitacao_titular" id)]
      (is (= "protocolada" (:estado solic)) "solicitacao nasce protocolada")
      (is (= "corrigir" (:tipo solic)) "tipo do direito registrado")
      (is (= "Corrigir meu endereco no cadastro." (:detalhe solic)) "detalhe opcional gravado")
      (is (= t0 (:recibo-em solic)) "recibo_em carimbado")
      ;; CONTADOR SEPARADO: a linha de prazo tem objeto_tipo='solicitacao_titular' e vence pela regra do titular.
      (is (= "pendente" (:estado prazo)) "prazo materializado pendente")
      (is (= "solicitacao_titular" (:objeto-tipo prazo)) "objeto_tipo do prazo = solicitacao_titular (contador separado)")
      (is (= id (:objeto-id prazo)) "prazo aponta pra solicitacao")
      (is (= vence-titular (:vence-em prazo)) "vence_em = recibo + 15 dias (default LGPD [GAP], != 20 da LAI)")
      (is (= 15 (:base-dias prazo)) "base_dias registra o default do titular (15), nao o do e-SIC (20)"))
    (let [evs (eventos-por-tipo ente "participacao.solicitacao_titular.protocolada")]
      (is (= 1 (count evs)) "exatamente 1 evento protocolada (mesma tx do ato)")
      (let [pl (:payload (first evs))]
        (is (re-find #"LGPD-2026-000001" pl) "payload carrega o protocolo")
        (is (re-find #"corrigir" pl) "payload carrega o tipo do direito")
        (is (re-find #"2026-07-18" pl) "payload carrega o vence-em do CONTADOR SEPARADO (ISO date)")
        (is (not (re-find #"(?i)endereco|cadastro" pl)) "payload NAO carrega o detalhe (PII fica no banco)")))))

(deftest solicitar-sem-detalhe-e-valido
  (let [ente (random-uuid) titular (random-uuid)
        {:keys [id]} (controllers/solicitar-titular! *repo* relogio (ator ente titular) {:tipo "acessar"})]
    (is (nil? (:detalhe (repo-part/buscar-solicitacao-titular *repo* ente id)))
        "detalhe e' OPCIONAL — solicitacao sem detalhe grava nil")))

(deftest protocolo-titular-e-gapless-por-ente-e-separado-do-esic
  (let [ente (random-uuid) titular (random-uuid)
        s1 (:protocolo (controllers/solicitar-titular! *repo* relogio (ator ente titular) {:tipo "acessar"}))
        s2 (:protocolo (controllers/solicitar-titular! *repo* relogio (ator ente titular) {:tipo "eliminar"}))
        ;; o mesmo ente protocola um e-SIC: sequencial LGPD e ESIC sao ESCOPOS distintos (nao compartilham contador).
        esic (:protocolo (controllers/protocolar-pedido *repo* relogio (ator ente titular)
                                                        {:assunto "a" :descricao "b"}))]
    (is (= "LGPD-2026-000001" s1))
    (is (= "LGPD-2026-000002" s2) "sequencial do titular anda 1 em 1 por (ente, ano)")
    (is (= "ESIC-2026-000001" esic) "o contador do e-SIC e' SEPARADO (comeca em 1, nao continua o do LGPD)")))

;; ---------- responder: cumpre o prazo do TITULAR + resposta append-only + evento ----------

(deftest responder-cumpre-prazo-do-titular-e-grava-resposta
  (let [ente (random-uuid) titular (random-uuid) servidor (random-uuid)
        {sid :id} (controllers/solicitar-titular! *repo* relogio (ator ente titular) {:tipo "acessar"})
        r (controllers/responder-solicitacao! *repo* relogio (ator ente servidor) sid
                                               {:corpo "Segue a copia dos seus dados pessoais tratados."})]
    (is (= t0 (:respondida-em r)) "responder devolve o instante da resposta")
    (let [solic (repo-part/buscar-solicitacao-titular *repo* ente sid)
          prazo (repo-part/prazo-do-objeto *repo* ente "solicitacao_titular" sid)
          resps (respostas-da-solicitacao ente sid)]
      (is (= "respondida" (:estado solic)) "solicitacao -> respondida (CAS)")
      (is (= "cumprida" (:estado prazo)) "prazo do titular -> cumprida")
      (is (some? (:cumprida-em prazo)) "cumprida_em carimbado")
      (is (= 1 (count resps)) "exatamente 1 resposta gravada")
      (is (= servidor (:resposta_titular/respondido_por (first resps))) "respondido_por = o SERVIDOR (do ator)"))
    (is (= 1 (count (eventos-por-tipo ente "participacao.solicitacao_titular.respondida")))
        "emite solicitacao_titular.respondida (mesma tx)")))

(deftest responder-solicitacao-ja-terminal-e-conflito
  (let [ente (random-uuid) titular (random-uuid) servidor (random-uuid)
        {sid :id} (controllers/solicitar-titular! *repo* relogio (ator ente titular) {:tipo "acessar"})]
    (controllers/responder-solicitacao! *repo* relogio (ator ente servidor) sid {:corpo "primeira"})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ja respondida"
          (controllers/responder-solicitacao! *repo* relogio (ator ente servidor) sid {:corpo "segunda"}))
        "responder uma solicitacao ja terminal -> conflito de ciclo")))

(deftest responder-solicitacao-inexistente-e-nil
  (let [ente (random-uuid) servidor (random-uuid)]
    (is (nil? (controllers/responder-solicitacao! *repo* relogio (ator ente servidor) (random-uuid) {:corpo "x"}))
        "responder uma solicitacao que nao existe no tenant -> nil (a borda mapeia 404)")))

(deftest resposta-titular-e-append-only
  ;; DUAS camadas (Inv.10): (1) o role oplenario_app NAO tem grant de UPDATE (mig 0041: GRANT SELECT,INSERT) ->
  ;; a permissao e' negada ANTES do trigger; (2) o trg_resposta_titular_append_only barra UPDATE/DELETE.
  (let [ente (random-uuid) titular (random-uuid) servidor (random-uuid)
        {sid :id} (controllers/solicitar-titular! *repo* relogio (ator ente titular) {:tipo "acessar"})
        _ (controllers/responder-solicitacao! *repo* relogio (ator ente servidor) sid {:corpo "original"})
        resp-id (:resposta_titular/id (first (respostas-da-solicitacao ente sid)))]
    (is (some? resp-id) "ha uma resposta gravada")
    (is (thrown-with-msg? Exception #"(?i)permission denied|append-only"
          (tenancy/com-tenant* *ds* ente
            (fn [tx] (jdbc/execute-one! tx ["UPDATE participacao.resposta_titular SET corpo = ? WHERE ente_id = ? AND id = ?"
                                            "adulterado" ente resp-id]))))
        "UPDATE numa resposta do titular e' rejeitado (sem grant de UPDATE / trigger append-only)")))

;; ---------- minha-solicitacao: policy fina (so o titular le) ----------

(deftest minha-solicitacao-so-o-titular-le
  (let [ente (random-uuid) titular (random-uuid) intruso (random-uuid)
        {sid :id} (controllers/solicitar-titular! *repo* relogio (ator ente titular)
                                                  {:tipo "corrigir" :detalhe "meu dado"})]
    (let [d (controllers/minha-solicitacao *repo* (ator ente titular) relogio sid)]
      (is (= "protocolada" (:estado d)) "o titular le a propria solicitacao")
      (is (= vence-titular (:vence-em d)) "traz o vence-em do prazo do titular")
      (is (= 15 (:dias-restantes d)) "no dia do recibo faltam 15 (default LGPD)"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"autorizacao negada"
          (controllers/minha-solicitacao *repo* (ator ente intruso) relogio sid))
        "ator != titular -> authz/negar! (403 na borda)")))

;; ---------- encarregado: UPSERT 1-por-ente + leitura publica ----------

(deftest encarregado-upsert-atualiza-a-mesma-linha
  (let [ente (random-uuid) s1 (random-uuid) s2 (random-uuid)
        e1 (controllers/definir-encarregado! *repo* (ator ente s1)
                                             {:nome "Ana Souza" :rotulo "Encarregada de Dados (DPO)"
                                              :email "dpo@camara.gov.br"})
        e2 (controllers/definir-encarregado! *repo* (ator ente s2)
                                             {:nome "Bruno Lima" :rotulo "Encarregado de Dados (DPO)"
                                              :email "encarregado@camara.gov.br"})]
    (is (= (:id e1) (:id e2)) "o 2o definir ATUALIZA a MESMA linha (id estavel) — nao cria 2a")
    (is (= "Bruno Lima" (:nome e2)) "o contato refletiu a atualizacao")
    (is (= s2 (:atualizado-por e2)) "atualizado_por = o ultimo servidor (injetado do ator)")
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (= 1 (count (jdbc/execute! tx ["SELECT id FROM participacao.encarregado WHERE ente_id = ?" ente])))
            "exatamente 1 linha de encarregado por ente (UNIQUE(ente_id))")))))

(deftest encarregado-publico-le-o-contato-ou-nil
  (let [ente (random-uuid) sem-dpo (random-uuid) servidor (random-uuid)]
    (is (nil? (controllers/encarregado-publico *repo* sem-dpo)) "ente sem DPO definido -> nil (a borda mapeia 404)")
    (controllers/definir-encarregado! *repo* (ator ente servidor)
                                      {:nome "Ana Souza" :rotulo "Encarregada (DPO)" :email "dpo@camara.gov.br"})
    (let [dpo (controllers/encarregado-publico *repo* ente)]
      (is (= "Ana Souza" (:nome dpo)))
      (is (= "dpo@camara.gov.br" (:email dpo))))))

;; ---------- ISOLAMENTO de tenant (RLS) ----------

(deftest tenant-nao-le-solicitacao-nem-encarregado-de-outro
  (let [ente-a (random-uuid) ente-b (random-uuid) titular (random-uuid) servidor (random-uuid)
        {sid :id} (controllers/solicitar-titular! *repo* relogio (ator ente-a titular) {:tipo "acessar"})
        _ (controllers/definir-encarregado! *repo* (ator ente-a servidor)
                                            {:nome "DPO A" :rotulo "DPO" :email "a@camara.gov.br"})]
    (is (nil? (repo-part/buscar-solicitacao-titular *repo* ente-b sid))
        "a solicitacao de ente-a nao e' visivel sob o tenant de ente-b (RLS isola)")
    (is (nil? (controllers/encarregado-publico *repo* ente-b))
        "o encarregado de ente-a nao vaza p/ ente-b (RLS isola a rota publica por tenant)")
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx]
        (is (empty? (jdbc/execute! tx ["SELECT id FROM participacao.solicitacao_titular WHERE id = ?" sid]))
            "RLS: a solicitacao de ente-a nao aparece na visao de ente-b nem por SQL direto")))))
