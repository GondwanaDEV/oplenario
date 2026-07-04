(ns oplenario.transparencia.db.acompanhamento
  "Persistencia de 'transparencia.acompanhamento' (F6c Slice 2, feature 16.5) — funcoes sobre a `tx` do
  tenant (FORCE RLS isola, mig 0045). HoneySQL schema-qualified; ente_id em TODA query. VERDADE de dominio
  (nao projecao): a subscricao do cidadao. Importado SO' pelo Repo-Component (regra do import-lint).

  `meus-da-materia` faz JOIN transparencia.acompanhamento x transparencia.materia — MESMO schema (§22.10 so'
  proibe JOIN cross-SCHEMA), entao e' permitido: a listagem 'minhas materias acompanhadas' mostra o cidadao
  seus follows JA com o cabecalho da materia (identificador/ementa/estado), sem N+1."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private teto-listagem
  "Teto server-side (anti unbounded-read) — sem paginacao nesta fatia (mesmo racional das demais listagens)."
  200)

(defn seguir!
  "Segue a materia: UPSERT por (ente_id, proposicao_id, seguidor_identidade_id). A 1a vez cria 'ativo'; um
  RE-seguir (apos cancelar) REATIVA a MESMA linha (DO UPDATE estado='ativo') — sem duplicar. `seguidor` e
  `created-by` INJETADOS do ator upstream (nunca do corpo). Devolve o mapa kebab (RETURNING *)."
  [tx {:keys [id ente-id proposicao-id seguidor-identidade-id created-by]}]
  {:pre [(some? ente-id) (some? id) (some? proposicao-id) (some? seguidor-identidade-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :transparencia.acompanhamento
                  :values [{:id id :ente_id ente-id :proposicao_id proposicao-id
                            :seguidor_identidade_id seguidor-identidade-id :estado "ativo"
                            :created_by created-by :efetivado_em [:now]}]
                  :on-conflict [:ente_id :proposicao_id :seguidor_identidade_id]
                  :do-update-set {:estado "ativo" :atualizado_em [:now]}
                  :returning [:*]}))))

(defn deixar-de-seguir!
  "Soft-cancel: estado 'ativo' -> 'cancelado' para (cidadao, materia). WHERE estado='ativo' torna a operacao
  IDEMPOTENTE — deixar de seguir algo que nao se segue (nunca seguiu, ou ja cancelou) e' no-op (0 linhas ->
  nil). Sem DELETE (Inv.10). Devolve {:id} se cancelou, ou nil (no-op)."
  [tx {:keys [ente-id proposicao-id seguidor-identidade-id]}]
  {:pre [(some? ente-id) (some? proposicao-id) (some? seguidor-identidade-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :transparencia.acompanhamento
                  :set {:estado "cancelado" :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                          [:= :seguidor_identidade_id seguidor-identidade-id] [:= :estado [:inline "ativo"]]]
                  :returning [:id]}))))

(defn seguidores-ativos
  "F7 E2 — a query do FAN-OUT: os `seguidor_identidade_id` que seguem ATIVAMENTE a materia (para notificar numa
  transicao). Usa o prefixo (ente_id, proposicao_id) do UNIQUE (ente_id, proposicao_id, seguidor) — sem indice
  adicional (nota da mig 0045). `[:inline \"ativo\"]` = consent-gating por construcao (so' quem consente hoje).
  Devolve so' os UUIDs (paineis nunca ve 'quem-segue-o-que' — o evento e' 1 por destinatario ja' resolvido).
  `teto` limita o fan-out por transicao (anti unbounded — uma materia MUITO seguida nao explode a tx do relay)."
  [tx ente-id proposicao-id teto]
  {:pre [(some? ente-id) (some? proposicao-id)]}
  (mapv :seguidor-identidade-id
        (comum/linhas->kebab
         (jdbc/execute! tx
           (sql/format {:select [[:seguidor_identidade_id :seguidor-identidade-id]]
                        :from :transparencia.acompanhamento
                        :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                                [:= :estado [:inline "ativo"]]]
                        :order-by [:seguidor_identidade_id]
                        :limit teto})))))

(defn meus-da-materia
  "'minhas materias acompanhadas' (por seguidor autenticado): SO' as subscricoes 'ativas' do `seguidor`, com o
  cabecalho da materia (JOIN same-schema), mais recentes primeiro, com teto. `[:inline \"ativo\"]` p/ o
  planner usar o indice parcial idx_acompanhamento_seguidor. INNER JOIN: a listagem so' mostra follows cuja
  materia existe no read-model (o guard do follow ja garante isso; se uma projecao sumir, esconder o item e'
  melhor que uma linha de campos nulos inuteis ao cidadao — o registro do acompanhamento continua no banco)."
  [tx ente-id seguidor-identidade-id]
  {:pre [(some? ente-id) (some? seguidor-identidade-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [[:a.proposicao_id :proposicao-id] [:a.criado_em :seguido-em]
                           [:m.tipo :tipo] [:m.ano :ano] [:m.sequencial :sequencial]
                           [:m.urn_lex :urn-lex] [:m.ementa :ementa] [:m.estado :estado]]
                  :from [[:transparencia.acompanhamento :a]]
                  :join [[:transparencia.materia :m]
                         [:and [:= :a.ente_id :m.ente_id] [:= :a.proposicao_id :m.proposicao_id]]]
                  :where [:and [:= :a.ente_id ente-id] [:= :a.seguidor_identidade_id seguidor-identidade-id]
                          [:= :a.estado [:inline "ativo"]]]
                  :order-by [[:a.criado_em :desc]]
                  :limit teto-listagem}))))
