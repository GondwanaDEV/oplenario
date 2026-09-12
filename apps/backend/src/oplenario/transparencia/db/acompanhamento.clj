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

(defn- where-meus
  "O predicado de 'minhas materias acompanhadas' — FONTE UNICA para `meus-da-materia` e `contar-meus`
  (regra 3 da frente 'truncamento-familia'). Alias `:a` SEMPRE, mesmo na contagem (que nao faz JOIN): so'
  assim as duas queries usam o MESMO texto de predicado — a prova de identidade e' textual, nao so' de
  resultado."
  [ente-id seguidor-identidade-id]
  [:and [:= :a.ente_id ente-id] [:= :a.seguidor_identidade_id seguidor-identidade-id]
   [:= :a.estado [:inline "ativo"]]])

(defn meus-da-materia
  "'minhas materias acompanhadas' (por seguidor autenticado): SO' as subscricoes 'ativas' do `seguidor`, com o
  cabecalho da materia (LEFT JOIN same-schema), mais recentes primeiro, com teto (`teto-listagem`; o par
  `contar-meus` diz o total real — frente 'truncamento-familia', sitio (c)). `[:inline \"ativo\"]` p/ o
  planner usar o indice parcial idx_acompanhamento_seguidor.

  LEFT JOIN, NAO INNER (achado 'outra familia' da mesma frente, corrige a decisao original desta docstring):
  `transparencia.materia` e' uma PROJECAO ASSINCRONA sem FK (mig 0045 e' explicita: 'nao ha FK a
  transparencia.materia... a existencia e' checada no controller, nao constraint'). Com INNER JOIN, um
  follow cujo relay ainda nao drenou (ou cuja projecao sumiu) desaparecia de 'minhas' SEM aviso: o cidadao
  lia 'sigo 3 materias' com 5 linhas ATIVAS no banco — a mesma mentira da familia, so' que sem LIMIT nenhum
  produzindo o corte. `:indisponivel` (computado aqui, pos-query: `:tipo` nulo so' acontece quando o LEFT
  JOIN nao achou par) e' o sinal que a borda usa para NUNCA fingir um cabecalho que nao existe — a subscricao
  (VERDADE de dominio) sempre aparece; o cabecalho pode faltar.

  ARIDADE de 4: `limite` INJETAVEL (achado IMPORTANTE da revisao adversarial — mesmo racional de
  listar-em-tramitacao/pendencia) — SO' para o teste provar 'o total nao capa' sem pagar 201 linhas; o
  caminho de PRODUCAO (repositorio.clj) usa a aridade de 3 e cai no default `teto-listagem`."
  ([tx ente-id seguidor-identidade-id]
   (meus-da-materia tx ente-id seguidor-identidade-id teto-listagem))
  ([tx ente-id seguidor-identidade-id limite]
   {:pre [(some? ente-id) (some? seguidor-identidade-id) (pos-int? limite)]}
   (mapv #(assoc % :indisponivel (nil? (:tipo %)))
     (comum/linhas->kebab
      (jdbc/execute! tx
        (sql/format {:select [[:a.proposicao_id :proposicao-id] [:a.criado_em :seguido-em]
                              [:m.tipo :tipo] [:m.ano :ano] [:m.sequencial :sequencial]
                              [:m.urn_lex :urn-lex] [:m.ementa :ementa] [:m.estado :estado]]
                     :from [[:transparencia.acompanhamento :a]]
                     :left-join [[:transparencia.materia :m]
                                 [:and [:= :a.ente_id :m.ente_id] [:= :a.proposicao_id :m.proposicao_id]]]
                     :where (where-meus ente-id seguidor-identidade-id)
                     :order-by [[:a.criado_em :desc]]
                     :limit (min limite teto-listagem)}))))))

(defn contar-meus
  "Quantos acompanhamentos ATIVOS o seguidor tem — SEM teto e SEM JOIN (frente 'truncamento-familia',
  sitios (c) e (d)): conta a tabela DONA (`transparencia.acompanhamento`, VERDADE de dominio) direto, nao o
  resultado do LEFT JOIN de `meus-da-materia` — a contagem nunca deve depender de uma projecao re-projetavel
  (mesmo racional do porque a tabela em si nao leva FK a `materia`). MESMO predicado (`where-meus`) da
  lista: e' o mesmo conjunto de linhas, so' sem o LIMIT e sem o cabecalho."
  [tx ente-id seguidor-identidade-id]
  {:pre [(some? ente-id) (some? seguidor-identidade-id)]}
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]] :from [[:transparencia.acompanhamento :a]]
                   :where (where-meus ente-id seguidor-identidade-id)})))))
