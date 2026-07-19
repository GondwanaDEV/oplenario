(ns oplenario.paineis.db.notificacao-caixa
  "Persistencia de 'paineis.notificacao_caixa' (Onda E fatia 1, mig 0062) — a INBOX interna: a MENSAGEM +
  o estado de leitura do destinatario. Tabela SEPARADA de `notificacao_entrega` (ledger de tentativa de
  entrega por canal) de proposito — decisao D4 da spec; nenhum JOIN entre as duas. Funcoes sobre a `tx`
  corrente (FORCE RLS isola, mig 0062); `ente_id` em TODA query. Importado SO' pelo Repo-Component
  (regra do import-lint, arquitetura-test)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.ids :as ids]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Projeta uma notificacao in-app na inbox. `ON CONFLICT (ente_id, idempotency_key) DO NOTHING`: a chave
  DETERMINISTICA do payload torna redrive/backfill um no-op (criterio de aceitacao 1). Devolve a linha
  inserida, ou nil se ja' existia — NUNCA lanca 23505 (envenenaria o relay compartilhado).

  A `:pre` abaixo cobre TODAS as colunas NOT NULL de `paineis.notificacao_caixa` (mig 0062) que vem do
  payload — `id`/`criado_em` tem DEFAULT, `lida_em` e' nullable, o resto (ente_id, destinatario_identidade_id,
  categoria, assunto, corpo, objeto_tipo, objeto_id, idempotency_key) e' NOT NULL sem default. Isso NAO e'
  preciosismo: em Postgres um comando que erra deixa a TRANSACAO em estado abortado; engolir a excecao no
  catch do handler (`projetar-inbox!`) nao desfaz isso. Se um campo NOT NULL chegasse nil ate' aqui, o
  INSERT abaixo violaria 23502 DENTRO da tx do relay COMPARTILHADO — o UPDATE seguinte do proprio relay
  (marcar `processed_at`, ver `kernel/outbox.clj`) lancaria por cima do catch com \"current transaction is
  aborted\", a tx do relay faria rollback, e o evento voltaria PENDENTE para ser repescado (e falhar de novo)
  no proximo tick — redrive eterno + head-of-line block do bus inteiro. Checando aqui, a falha vira
  `AssertionError` ANTES de qualquer SQL rodar: nenhuma tx foi tocada, e `AssertionError` e' `Error` — irmao
  de `Exception` sob `Throwable`, ja' capturado pelo `catch Throwable` do handler, sem abortar nada."
  [tx {:keys [ente-id destinatario-identidade-id categoria assunto corpo objeto-tipo objeto-id
              idempotency-key]}]
  {:pre [(some? ente-id) (some? destinatario-identidade-id) (some? categoria) (some? assunto)
         (some? corpo) (some? objeto-tipo) (some? objeto-id) (some? idempotency-key)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :paineis.notificacao_caixa
                  :values [{:id (ids/novo-id) :ente_id ente-id
                            :destinatario_identidade_id destinatario-identidade-id
                            :categoria categoria :assunto assunto :corpo corpo
                            :objeto_tipo objeto-tipo :objeto_id objeto-id
                            :idempotency_key idempotency-key}]
                  :on-conflict [:ente_id :idempotency_key]
                  :do-nothing true
                  :returning [:id]}))))
