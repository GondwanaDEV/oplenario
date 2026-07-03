(ns oplenario.participacao.db.comentario
  "Persistencia de 'participacao.comentario' (FAST-FOLLOW Slice 6, feature 6.3) — funcoes sobre a `tx` do
  tenant (FORCE RLS isola, mig 0043). HoneySQL schema-qualified; ente_id em TODA query. SEM protocolo/
  sequencial (nao e' obrigacao com relogio — nao usa kernel/sequencial). Estado evolui por CAS
  (pendente -> aprovado|rejeitado), SEM DELETE (Inv.10). IMPL atras do RepoParticipacao (ADR-0001 §3-bis: o
  db/ so e' importado pelo Repo-Component).

  `marcar-denunciado!` e' a CAS que so seta a flag `denunciado` quando o comentario AINDA esta 'pendente'
  (WHERE estado='pendente') — uma linha que a WHERE nao casa nunca dispara o trigger BEFORE UPDATE de estado
  terminal (mig 0043), entao denunciar um comentario JA terminal nunca colide com a trava (ver docstring da
  tabela)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :proposicao_id :autor_identidade_id :corpo :estado :motivo_rejeicao :denunciado
   :created_by :criado_em :atualizado_em])

(def ^:private teto-listagem
  "Teto server-side (anti unbounded-read, mesmo racional de teto-sweep/compliance): a lista publica de uma
  materia e a fila de moderacao sao ambas limitadas — sem paginacao nesta fatia (decisao registrada no plano)."
  200)

(defn inserir!
  "Protocola um comentario PENDENTE. `autor-identidade-id` DEVE vir do ator (injetado upstream; anti-forge —
  nunca do corpo do cliente). Devolve o mapa kebab (RETURNING *)."
  [tx {:keys [id ente-id proposicao-id autor-identidade-id corpo created-by]}]
  {:pre [(some? ente-id) (some? id) (some? proposicao-id) (some? autor-identidade-id) (some? corpo)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.comentario
                  :values [{:id id :ente_id ente-id :proposicao_id proposicao-id
                            :autor_identidade_id autor-identidade-id :corpo corpo
                            :estado "pendente" :created_by created-by :efetivado_em [:now]}]
                  :returning [:*]}))))

(defn buscar
  "Busca um comentario por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:participacao.comentario]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn moderar!
  "CAS de MODERACAO: transiciona 'pendente' -> `estado` (aprovado|rejeitado), carimbando motivo_rejeicao (nil
  quando aprovado — o caller ja' filtra). SOMENTE se AINDA esta 'pendente' (WHERE estado='pendente'). Devolve
  o mapa kebab (RETURNING *) se transicionou, ou nil se a corrida foi perdida (ja moderado — a borda
  desambigua nil-existente p/ 409)."
  [tx {:keys [id ente-id estado motivo-rejeicao]}]
  {:pre [(some? ente-id) (some? id) (contains? #{"aprovado" "rejeitado"} estado)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.comentario
                  :set {:estado estado :motivo_rejeicao motivo-rejeicao :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado [:inline "pendente"]]]
                  :returning [:*]}))))

(defn marcar-denunciado!
  "CAS: seta `denunciado=true` SOMENTE se o comentario AINDA esta 'pendente' (WHERE estado='pendente') — uma
  linha JA terminal (aprovado|rejeitado) NAO casa a WHERE, entao o UPDATE nao a toca e o trigger de estado
  terminal (BEFORE UPDATE, mig 0043) nunca dispara. Devolve {:id} se marcou, ou nil (ja denunciado -> no-op
  idempotente; OU ja terminal -> a flag fica como estava, o registro em denuncia_comentario e' o rastro)."
  [tx {:keys [id ente-id]}]
  {:pre [(some? ente-id) (some? id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :participacao.comentario
                  :set {:denunciado true :atualizado_em [:now]}
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado [:inline "pendente"]]]
                  :returning [:id]}))))

(defn listar-aprovados-da-materia
  "'comentarios-da-materia' (PUBLICA, sem auth): SO `estado='aprovado'`, ordem cronologica (o thread de uma
  materia), com teto. Nunca vaza pendente/rejeitado."
  [tx ente-id proposicao-id]
  {:pre [(some? ente-id) (some? proposicao-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.comentario]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]
                          [:= :estado [:inline "aprovado"]]]
                  :order-by [[:criado_em :asc] [:id :asc]]
                  :limit teto-listagem}))))

(defn listar-fila-moderacao
  "'fila-moderacao' (SERVIDOR): SO `estado='pendente'`, denunciados PRIMEIRO (`denunciado DESC`), depois
  cronologico (`criado_em ASC`), com teto. `[:inline ...]` no estado p/ o planner usar o indice parcial
  idx_comentario_fila_moderacao (mig 0043 — bind param opaco cairia em Seq Scan, mesmo racional do sweep)."
  [tx ente-id]
  {:pre [(some? ente-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.comentario]
                  :where [:and [:= :ente_id ente-id] [:= :estado [:inline "pendente"]]]
                  :order-by [[:denunciado :desc] [:criado_em :asc] [:id :asc]]
                  :limit teto-listagem}))))
