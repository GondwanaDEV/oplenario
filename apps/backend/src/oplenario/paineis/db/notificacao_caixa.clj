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

(def ^:private teto-inbox
  "Teto RIGIDO da inbox (spec §4.5). SEM PAGINACAO nesta fatia, de proposito: a resposta declara a
  contagem TOTAL de nao lidas, entao a UI nunca mente sobre o que existe. Paginacao entra quando um
  usuario real passar do teto. O teto e' aplicado no SQL (`:limit`), NUNCA em Clojure depois do fetch —
  cortar em memoria descarta os itens MAIS RECENTES (armadilha que ja' mordeu o projeto na F3)."
  50)

(def ^:private colunas
  [:id :destinatario_identidade_id :categoria :assunto :corpo :objeto_tipo :objeto_id :criado_em :lida_em])

(defn listar-do-destinatario
  "As notificacoes DO PROPRIO ator, mais recentes primeiro (`:id` asc como desempate estavel, mesma
  disciplina de db/proposicao/listar). `destinatario-identidade-id` vem SEMPRE do `(:ator req)` — nunca
  de path/query/corpo. Usa idx_notificacao_caixa_destinatario (mig 0062)."
  [tx ente-id destinatario-identidade-id]
  {:pre [(some? ente-id) (some? destinatario-identidade-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from :paineis.notificacao_caixa
                  :where [:and [:= :ente_id ente-id]
                          [:= :destinatario_identidade_id destinatario-identidade-id]]
                  :order-by [[:criado_em :desc] [:id :asc]]
                  :limit teto-inbox}))))

(defn contar-nao-lidas
  "Contagem TOTAL de nao lidas do ator — NAO limitada pelo teto da listagem (e' justamente o numero que
  impede a UI de mentir quando ha' mais de 50). Usa o indice PARCIAL idx_notificacao_caixa_nao_lidas."
  [tx ente-id destinatario-identidade-id]
  {:pre [(some? ente-id) (some? destinatario-identidade-id)]}
  (:c (comum/linha->kebab
       (jdbc/execute-one! tx
         (sql/format {:select [[[:count :*] :c]] :from :paineis.notificacao_caixa
                      :where [:and [:= :ente_id ente-id]
                              [:= :destinatario_identidade_id destinatario-identidade-id]
                              [:is :lida_em nil]]})))))

(defn marcar-lida!
  "Marca a notificacao como lida. IDEMPOTENTE por `COALESCE(lida_em, now())`: a 2a chamada re-grava o
  MESMO carimbo (nao move a data) e devolve o mesmo recibo — criterio de aceitacao 6.

  POR QUE COALESCE, e nao `WHERE lida_em IS NULL` (a forma literal da spec §4.5): com o WHERE, a 2a
  chamada atualizaria 0 linhas e a borda nao teria como distinguir 'ja' lida' de 'nao existe / nao e'
  sua' — devolveria 404 para uma operacao legitima. Com COALESCE, update-count 0 significa EXATAMENTE
  uma coisa: a linha nao existe OU nao e' do ator. O efeito visivel e' o mesmo (o carimbo nunca se move).

  ANTI-CONFUSED-DEPUTY: `destinatario_identidade_id` esta' no MESMO WHERE do `ente_id` — marcar a
  notificacao de outra pessoa nao e' possivel nem com o id adivinhado. Devolve {:id :lida-em} ou nil."
  [tx {:keys [ente-id id destinatario-identidade-id]}]
  {:pre [(some? ente-id) (some? id) (some? destinatario-identidade-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :paineis.notificacao_caixa
                  :set {:lida_em [:coalesce :lida_em [:now]]}
                  :where [:and [:= :ente_id ente-id] [:= :id id]
                          [:= :destinatario_identidade_id destinatario-identidade-id]]
                  :returning [:id :lida_em]}))))
