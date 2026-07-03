(ns oplenario.participacao.db.prazo-ativo
  "Persistencia de 'participacao.prazo_ativo' (forma disc.6, decisao Arch B de F6) — funcoes sobre a `tx` do
  tenant (FORCE RLS isola, mig 0039). HoneySQL schema-qualified; ente_id em TODA query. O prazo evolui por
  UPDATE/CAS (pendente -> cumprida/vencida...), SEM DELETE (Inv.10). O 'anel' e' a leitura single-row por
  (ente, objeto_tipo, objeto_id), servida pela UNIQUE da mig 0039 (1 probe barato). IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :objeto_tipo :objeto_id :vence_em :estado :base_dias :prorrogado_ate
   :prazo_fonte_ref :cumprida_em :criado_em :atualizado_em])

(defn inserir!
  "Materializa um prazo (forma disc.6). `estado` default 'pendente'. Devolve o mapa kebab (RETURNING *).
  A UNIQUE(ente, objeto_tipo, objeto_id) e' a idempotencia: 1 prazo por objeto."
  [tx {:keys [id ente-id objeto-tipo objeto-id vence-em estado base-dias prorrogado-ate prazo-fonte-ref created-by]}]
  {:pre [(some? ente-id) (some? id) (some? objeto-id) (some? vence-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.prazo_ativo
                  :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id
                            :vence_em vence-em :estado (or estado "pendente") :base_dias base-dias
                            :prorrogado_ate prorrogado-ate :prazo_fonte_ref prazo-fonte-ref
                            :created_by created-by :efetivado_em [:now]}]
                  :returning [:*]}))))

(defn buscar-do-objeto
  "O 'anel do prazo': leitura single-row do prazo de um objeto (ente, objeto_tipo, objeto_id). Devolve o mapa
  kebab ou nil. Serve o read barato por page-load do cidadao (single-row via a UNIQUE da mig 0039)."
  [tx ente-id objeto-tipo objeto-id]
  {:pre [(some? ente-id) (some? objeto-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.prazo_ativo]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]]}))))

(defn cumprir!
  "CAS de cumprimento (Slice 2): transiciona o prazo de um objeto p/ 'cumprida' + carimba cumprida_em, SOMENTE
  se ainda esta ABERTO (pendente|vencida) — uma resposta APOS o vencimento (vencida) ainda CUMPRE a obrigacao
  (carimba o desfecho; a quebra do prazo fica registrada no historico/evento). `[:inline ...]` casa o predicado
  do idx parcial de sweep. Devolve o mapa kebab se cumpriu, ou nil se nao havia prazo aberto (idempotente —
  ja-cumprida/cancelada nao re-transiciona). `cumprida-em` INJETADO (relogio do ato) p/ determinismo."
  [tx {:keys [ente-id objeto-tipo objeto-id cumprida-em]}]
  {:pre [(some? ente-id) (some? objeto-id) (some? cumprida-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.prazo_ativo
                  :set {:estado "cumprida" :cumprida_em cumprida-em :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]
                          [:in :estado [[:inline "pendente"] [:inline "vencida"]]]]
                  :returning [:*]}))))

;; ---- sweep de vencimento (F6.3, espelha compliance/db/obrigacao) ----

(def ^:private teto-sweep
  "Teto server-side por passada do sweep (anti unbounded-read, review sec): um ente com muitos prazos overdue
  dreno em multiplas passadas (cada CAS tira a linha do filtro `pendente` -> a proxima varre o lote seguinte).
  Idempotente e limitado."
  1000)

(def ^:private cols-sweep
  "Projecao minima do sweep: so o que o CAS + o evento precisam (id p/ transicionar; objeto_tipo/objeto_id/
  vence_em p/ o payload de `participacao.prazo.vencido`). Nao traz PII."
  [:id :objeto_tipo :objeto_id :vence_em])

(defn pendentes-vencidas-ate
  "Sweep de vencimento (F6.3): os prazos PENDENTE estritamente vencidos em `hoje` (vence_em < hoje — ESTRITO:
  o proprio dia do vencimento NAO vence, coerente com `logic/dias-restantes` '0 = ultimo dia' ainda valido e
  com o sweep do compliance), por ente, em ordem de vencimento, com teto. So `pendente` (a unica fase candidata
  a vencer). `[:inline ...]` p/ o estado e' o que deixa o planner usar o idx parcial idx_prazo_ativo_sweep
  (WHERE estado IN ('pendente','vencida')) — bind param opaco cairia em Seq Scan (mesmo racional do compliance)."
  [tx ente-id hoje]
  {:pre [(some? ente-id) (some? hoje)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-sweep :from [:participacao.prazo_ativo]
                  :where [:and [:= :ente_id ente-id]
                          [:= :estado [:inline "pendente"]]
                          [:< :vence_em hoje]]
                  :order-by [[:vence_em :asc] [:id :asc]]
                  :limit teto-sweep}))))

(defn vencer-se-pendente!
  "CAS de vencimento: transiciona o prazo p/ 'vencida' + atualizado_em SOMENTE se AINDA esta 'pendente'
  (WHERE estado='pendente'). Devolve {:id} (RETURNING id — projecao minima, so a truthiness importa ao caller)
  se transicionou, ou nil se a corrida foi perdida (um cumprimento concorrente — `cumprir!` — ja o moveu p/
  cumprida entre o read do sweep e este UPDATE; ou uma passada anterior ja o venceu). Mata, na borda do SQL,
  o re-vencimento e o evento duplicado (idempotencia do sweep). Espelha compliance/db/obrigacao
  vencer-se-pendente! (mesmo `[:inline \"vencida\"]` no SET, mesmo RETURNING id)."
  [tx {:keys [ente-id id]}]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.prazo_ativo
                  :set {:estado [:inline "vencida"] :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado [:inline "pendente"]]]
                  :returning [:id]}))))
