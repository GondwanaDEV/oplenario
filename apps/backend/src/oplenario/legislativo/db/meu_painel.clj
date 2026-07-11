(ns oplenario.legislativo.db.meu-painel
  "Leitura escopada do painel do vereador (Onda C1, §11.2/§11.3) — 'minhas proposicoes' (autor_tipo=
  'vereador' AND autor_id=V) e 'meus pareceres' (relator_id=V), SEMPRE com `ente_id` no WHERE (Inv.1;
  `proposicoes` e' hash-particionada por ente_id — `idx_proposicoes_autor` ja' existe p/ este hot-path
  exato). Subconjunto ESTREITO de colunas (mesma disciplina de `proposicao/colunas-resumo`): so' o que o
  painel de fato mostra, nunca `atributos_especificos`/jsonb. `:ciencias` e' preenchido pela Task 3
  (db/meu-painel/ciencias-pendentes, ainda inexistente aqui)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas-proposicao
  [:id :tipo :ano :sequencial :urn_lex :ementa :estado :atualizado_em])

(defn proposicoes-do-autor
  "Proposicoes de autoria do vereador `vereador-id` (autor_tipo='vereador') neste ente — mais recente
  primeiro (atualizado_em desc; :id asc como desempate estavel, mesma disciplina de db/proposicao/listar)."
  [tx ente-id vereador-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas-proposicao :from [:legislativo.proposicoes]
                  :where [:and [:= :ente_id ente-id] [:= :autor_tipo [:inline "vereador"]]
                          [:= :autor_id vereador-id]]
                  :order-by [[:atualizado_em :desc] [:id :asc]]}))))

(def ^:private colunas-parecer
  [:id :objeto_tipo :objeto_id :comissao_id :estado :voto_relator :criado_em])

(defn pareceres-do-relator
  "Pareceres em que o vereador `vereador-id` e' o relator, neste ente — mais recente primeiro (criado_em
  desc; :id asc como desempate estavel)."
  [tx ente-id vereador-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas-parecer :from [:legislativo.pareceres]
                  :where [:and [:= :ente_id ente-id] [:= :relator_id vereador-id]]
                  :order-by [[:criado_em :desc] [:id :asc]]}))))

;; ============================ Task 3: ciencia append-only (Inv.10) ============================

(defn ciencias-pendentes
  "Pareceres PUBLICADOS (texto_vigente_versao_id IS NOT NULL — emitidos, Onda B Slice 5) sobre proposicao
  de autoria do vereador `vereador-id`, MENOS os ja' acusados (LEFT JOIN anti-join contra
  ciencia_vereador — decisao assumida #2 do plano C1: ciencia DERIVADA, sem pipeline de notificacao). Join
  SAME-SCHEMA (legislativo.pareceres <-> legislativo.proposicoes <-> legislativo.ciencia_vereador), nunca
  cross-modulo (§22.10). `parecer-id` e' o `evento-ref` p/ `acusar-ciencia!` — cada parecer publicado gera
  no maximo UM item pendente (a UNIQUE e' por parecer, nao por proposicao)."
  [tx ente-id vereador-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [[:pc.id :parecer_id] [:p.id :proposicao_id] :p.tipo :p.ano :p.sequencial
                            :p.urn_lex :p.ementa]
                  :from [[:legislativo.pareceres :pc]]
                  :join [[:legislativo.proposicoes :p]
                         [:and [:= :p.id :pc.objeto_id] [:= :p.ente_id :pc.ente_id]]]
                  :left-join [[:legislativo.ciencia_vereador :cv]
                              [:and [:= :cv.ente_id :pc.ente_id] [:= :cv.vereador_id vereador-id]
                               [:= :cv.evento_ref :pc.id]]]
                  :where [:and [:= :pc.ente_id ente-id] [:= :pc.objeto_tipo [:inline "proposicao"]]
                          [:is-not :pc.texto_vigente_versao_id nil]
                          [:= :p.autor_tipo [:inline "vereador"]] [:= :p.autor_id vereador-id]
                          [:is :cv.id nil]]
                  :order-by [[:pc.atualizado_em :desc] [:pc.id :asc]]}))))

(defn acusar-ciencia!
  "Insere a ciencia do vereador `vereador-id` sobre `evento-ref` (append-only puro, Inv.10) — idempotente
  por UNIQUE (ente_id, vereador_id, evento_ref). Race-safe (mesmo padrao de compliance/db/obrigacao.clj):
  INSERT ... ON CONFLICT DO NOTHING RETURNING *; em conflito (corrida OU 2a chamada do mesmo vereador
  sobre o mesmo evento), re-le a linha JA existente — preserva o `ciente-em` do 1o registro (nao duplica,
  nao reescreve). Devolve {:id :ciente-em}."
  [tx {:keys [id ente-id vereador-id evento-ref tipo]}]
  (let [inserida (jdbc/execute-one! tx
                   (sql/format {:insert-into :legislativo.ciencia_vereador
                                :values [{:id id :ente_id ente-id :vereador_id vereador-id
                                          :evento_ref evento-ref :tipo tipo}]
                                :on-conflict [:ente_id :vereador_id :evento_ref] :do-nothing true
                                :returning [:id :ciente_em]}))]
    (comum/linha->kebab
     (or inserida
         (jdbc/execute-one! tx
           (sql/format {:select [:id :ciente_em] :from [:legislativo.ciencia_vereador]
                        :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                                [:= :evento_ref evento-ref]]}))))))
