(ns oplenario.compliance.db.obrigacao
  "Persistencia de 'compliance.prazo_dominio_ativo' (§22.7.7) — funcoes sobre a `tx` do tenant (FORCE RLS
  isola, mig 0009). A obrigacao materializada: estado evolui via UPDATE (pendente->cumprida/vencida...),
  SEM DELETE (Inv.10). Idempotencia da materializacao = a chave UNIQUE (ente, template, objeto_tipo,
  objeto_id). HoneySQL schema-qualified; ente_id em toda query. IMPL atras do RepoCompliance."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :template_chave :objeto_tipo :objeto_id :vence_em :prazo_fonte_ref
   :estado :cumprida_em :criado_em :atualizado_em])

(defn buscar
  "Busca uma obrigacao por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:compliance.prazo_dominio_ativo]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn buscar-para-reconciliar
  "Le a obrigacao pela CHAVE de idempotencia (ente, template, objeto_tipo, objeto_id) SOB FOR UPDATE —
  serializa a reconciliacao concorrente do MESMO objeto (dois eventos disparando avaliacao em paralelo).
  Devolve {:id :estado} ou nil (ainda nao materializada). O `vence_em` da reconciliacao vem do motor
  (sempre fresco), nao da linha — por isso nao e' lido aqui (review clj m4)."
  [tx ente-id template-chave objeto-tipo objeto-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :estado] :from [:compliance.prazo_dominio_ativo]
                  :where [:and [:= :ente_id ente-id] [:= :template_chave template-chave]
                          [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]]
                  :for :update}))))

(defn inserir!
  "Materializa uma obrigacao nova via INSERT ... ON CONFLICT DO NOTHING RETURNING * — RACE-SAFE na chave
  de idempotencia (review CRITICO C1: o FOR UPDATE nao trava linha inexistente; dois drivers — sweep +
  evento — poderiam INSERT concorrente e o perdedor estourava duplicate-key, abortando a tx e perdendo a
  prova de compliance). `cumprida-em` opcional. Devolve o mapa kebab da obrigacao criada, ou nil se a
  chave ja existia (corrida perdida — o caller re-le sob FOR UPDATE e segue pelo ramo de atualizar!)."
  [tx {:keys [id ente-id template-chave objeto-tipo objeto-id vence-em prazo-fonte-ref estado cumprida-em]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :compliance.prazo_dominio_ativo
                  :values [{:id id :ente_id ente-id :template_chave template-chave
                            :objeto_tipo objeto-tipo :objeto_id objeto-id :vence_em vence-em
                            :prazo_fonte_ref prazo-fonte-ref :estado estado :cumprida_em cumprida-em}]
                  :on-conflict [:ente_id :template_chave :objeto_tipo :objeto_id] :do-nothing true
                  :returning [:*]}))))

(defn atualizar!
  "Reconcilia a obrigacao existente: sempre escreve `estado`+atualizado_em; condicionalmente re-stampa
  vence_em/prazo_fonte_ref (`re-stamp?` — so enquanto pendente, S3) e carimba cumprida_em (`marcar-cumprida?`
  — so na 1a transicao p/ cumprida; nao reescreve um cumprida_em ja gravado). RETURNING * devolve o mapa
  kebab da obrigacao ja reconciliada (sem re-read por PK — review db MAJOR-2)."
  [tx {:keys [id ente-id estado re-stamp? vence-em prazo-fonte-ref marcar-cumprida?]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :compliance.prazo_dominio_ativo
                  :set (cond-> {:estado estado :atualizado_em [:now]}
                         re-stamp?        (assoc :vence_em vence-em :prazo_fonte_ref prazo-fonte-ref)
                         marcar-cumprida? (assoc :cumprida_em [:now]))
                  :where [:and [:= :ente_id ente-id] [:= :id id]]
                  :returning [:*]}))))

(defn listar-do-objeto
  "Obrigacoes de um objeto (ente, objeto_tipo, objeto_id) — pela chave de idempotencia ha no maximo uma
  por template; este reader cobre os varios templates que podem incidir sobre o mesmo objeto."
  [tx ente-id objeto-tipo objeto-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:compliance.prazo_dominio_ativo]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]]
                  :order-by [[:template_chave :asc]]}))))

(defn pendentes-vencidas-ate
  "Sweep de vencimento (§22.7.7): obrigacoes PENDENTE estritamente vencidas em `data` (vence_em < data —
  estrito: o proprio dia do vencimento NAO vence), por ente, em ordem de vencimento. So `pendente` (a
  unica fase candidata a vencer; ja-vencida nao re-transiciona). `[:inline ...]` p/ o generic plan do PG
  poder usar o indice parcial idx_prazo_dominio_ativo_sweep (bind param opaco cairia em Seq Scan)."
  [tx ente-id data]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:compliance.prazo_dominio_ativo]
                  :where [:and [:= :ente_id ente-id]
                          [:= :estado [:inline "pendente"]]
                          [:< :vence_em data]]
                  :order-by [[:vence_em :asc] [:id :asc]]}))))

(defn vencer-se-pendente!
  "CAS de vencimento: transiciona a obrigacao p/ 'vencida' SOMENTE se ainda esta 'pendente' (WHERE
  estado='pendente'). Devolve {:id} se transicionou, ou nil se a corrida foi perdida (um `avaliar-obrigacao!`
  concorrente ja a moveu p/ cumprida/vencida entre o read do sweep e este UPDATE). Mata, na borda do SQL,
  o clobber 'cumprida'->'vencida' e a auditoria duplicada (review db CRITICO C1 / clj MAJOR-2)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :compliance.prazo_dominio_ativo
                  :set {:estado [:inline "vencida"] :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado [:inline "pendente"]]]
                  :returning [:id]}))))
