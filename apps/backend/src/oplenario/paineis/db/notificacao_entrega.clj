(ns oplenario.paineis.db.notificacao-entrega
  "Persistencia de 'paineis.notificacao_entrega' (F7 E2, §16.11) — o ledger DURAVEL de entrega (mig 0004 +
  conteudo/rastreabilidade da mig 0050). Funcoes sobre a `tx` corrente (FORCE RLS isola, mig 0009); ente_id em
  TODA query. Importado SO' pelo Repo-Component (regra do import-lint).

  DISCIPLINA OUTBOX-DE-ENTREGA (anti dual-write): `registrar-intent!` grava o INTENT 'pendente' na tx do relay
  (atomico com o dedup §22.9 E2) — NADA e' enviado aqui. Um passo SEPARADO (worker) le' `listar-pendentes!`,
  chama o notificador (efeito EXTERNO nao-transacional) e aplica `marcar-enviada!`/`marcar-falha!`. Separar
  intent-duravel de envio evita o classico dual-write: um e-mail enviado dentro de uma tx que depois faz
  rollback seria reenviado no redrive (duplicata). Aqui o envio so' ocorre sobre um intent JA' commitado."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.ids :as ids]))

(set! *warn-on-reflection* true)

(def ^:private teto-pendentes
  "Teto server-side por varredura do worker (anti unbounded-read — o worker pagina por rodadas, cada uma <=teto)."
  500)

(defn registrar-intent!
  "Materializa o INTENT de entrega (estado 'pendente') a partir de `notificacao.requisitada`. `ON CONFLICT
  (ente_id, idempotency_key) DO NOTHING` (§22.9 E2): a idempotency-key DETERMINISTICA (f(transicao,destinatario),
  do payload) torna um redrive/backfill do MESMO fan-out um no-op — a entrega logica nunca duplica. O `id` (PK,
  sem default na mig 0004) e' gerado aqui; e' irrelevante ao dedup (o conflito e' na UNIQUE de idempotency_key).
  Devolve a linha inserida, ou nil se ja' existia (no-op) — NUNCA lanca 23505 (envenenaria o relay compartilhado)."
  [tx {:keys [ente-id destinatario canal idempotency-key consent-base assunto corpo objeto-tipo objeto-id]}]
  {:pre [(some? ente-id) (some? destinatario) (some? canal) (some? idempotency-key)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :paineis.notificacao_entrega
                  :values [{:id (ids/novo-id) :ente_id ente-id :destinatario destinatario :canal canal
                            :idempotency_key idempotency-key :estado "pendente" :consent_base consent-base
                            :assunto assunto :corpo corpo :objeto_tipo objeto-tipo :objeto_id objeto-id}]
                  :on-conflict [:ente_id :idempotency_key]
                  :do-nothing true
                  :returning [:*]}))))

(defn listar-pendentes
  "A query do WORKER de entrega: intents 'pendente' do tenant, mais antigos primeiro (FIFO justo). Usa o
  indice parcial idx_notificacao_pendente (mig 0050); `[:inline \"pendente\"]` p/ o planner prova-lo."
  [tx ente-id]
  {:pre [(some? ente-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :destinatario :canal :assunto :corpo :objeto_tipo :objeto_id]
                  :from :paineis.notificacao_entrega
                  :where [:and [:= :ente_id ente-id] [:= :estado [:inline "pendente"]]]
                  :order-by [:criado_em]
                  :limit teto-pendentes}))))

(defn marcar-enviada!
  "Fecha o intent como ENVIADO (o notificador confirmou). So' a partir de 'pendente' (WHERE estado='pendente'):
  uma 2a confirmacao (worker reentrante) e' no-op. Devolve {:id} se transicionou, ou nil."
  [tx {:keys [ente-id id]}]
  {:pre [(some? ente-id) (some? id)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :paineis.notificacao_entrega
                         :set {:estado "enviada" :enviada_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado [:inline "pendente"]]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0)) {:id id :estado "enviada"})))

(defn marcar-falha!
  "Fecha o intent como FALHA (o notificador nao entregou), guardando o motivo. So' a partir de 'pendente'.
  CARRY infra: uma politica de RETRY (voltar 'falha'->'pendente' com backoff/tentativas) e' do worker real —
  aqui o estado terminal 'falha' apenas registra a tentativa fracassada. Devolve {:id} se transicionou, ou nil."
  [tx {:keys [ente-id id motivo]}]
  {:pre [(some? ente-id) (some? id)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :paineis.notificacao_entrega
                         :set {:estado "falha" :falha_motivo motivo}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado [:inline "pendente"]]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0)) {:id id :estado "falha"})))
