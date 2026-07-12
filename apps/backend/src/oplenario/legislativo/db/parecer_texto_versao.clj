(ns oplenario.legislativo.db.parecer-texto-versao
  "Persistencia do versionamento de texto do PARECER (eixo F / F3.6b) — funcoes sobre a `tx` do tenant.
  MESMA estrategia do eixo B (db/texto-versao): conteudo append-only (trigger congela); `promover!` e' o
  ato auditado rascunho->vigente (supersede a anterior + reaponta pareceres.texto_vigente_versao_id, na
  MESMA tx). HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :parecer_id :numero_versao :origem_versao :origem_ref :origem_tipo :estado_versao
   :formato :texto_inline :conteudo_uri :hash_conteudo :lock_version
   :assinatura_algoritmo :assinatura_b64 :assinado_por :assinado_em])

(defn nova-versao!
  "Insere uma versao NOVA do texto do parecer em 'rascunho'. numero_versao = proximo ordinal local do
  parecer (a UNIQUE (ente_id,parecer_id,numero_versao) barra corrida). Conteudo XOR (inline OU uri).
  Devolve {:id :numero-versao}."
  [tx {:keys [id ente-id parecer-id origem-versao origem-ref origem-tipo formato
              texto-inline conteudo-uri hash-conteudo created-by]}]
  (let [prox (-> (jdbc/execute-one! tx
                   (sql/format {:select [[[:+ [:coalesce [:max :numero_versao] 0] 1] :n]]
                                :from [:legislativo.parecer_texto_versao]
                                :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]]}))
                 :n)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.parecer_texto_versao
                   :values [{:id id :ente_id ente-id :parecer_id parecer-id :numero_versao prox
                             :origem_versao origem-versao :origem_ref origem-ref :origem_tipo origem-tipo
                             :estado_versao "rascunho" :formato (or formato "markdown")
                             :texto_inline texto-inline :conteudo_uri conteudo-uri :hash_conteudo hash-conteudo
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :numero-versao prox}))

(defn promover!
  "Promove `versao-id` a 'vigente' (ato auditado): supersede a vigente anterior do parecer, marca a alvo
  como vigente (CAS por lock-version, com parecer_id no WHERE p/ a versao TER de pertencer a este parecer)
  e reaponta pareceres.texto_vigente_versao_id — na MESMA tx. Lanca em conflito de versao OU versao
  inexistente neste parecer OU parecer filtrado. Onda C4: quando `assinatura-algoritmo` vem preenchido
  (o caller ja assinou os bytes do texto-inline — Repo/emitir-parecer!), grava a assinatura NA MESMA
  UPDATE que marca vigente (uma escrita, nao duas); `assinado-em` e' sempre `now()` do banco, nunca vem
  do app (mesmo padrao de artefato_publicacao)."
  [tx {:keys [ente-id parecer-id versao-id updated-by lock-version
              assinatura-algoritmo assinatura-b64 assinado-por]}]
  ;; guard de dominio (review F3.6b clojure-MAJOR): parecer em estado terminal tem o texto CONGELADO — o
  ;; reaponte do pointer (passo 3, UPDATE em pareceres) seria barrado pelo trg_pareceres_imut_estado com
  ;; uma PSQLException opaca. Antecipa com erro inspecionavel (e' o mesmo congelamento, so legivel).
  (let [{:keys [estado]} (comum/linha->kebab
                          (jdbc/execute-one! tx
                            (sql/format {:select [:estado] :from [:legislativo.pareceres]
                                         :where [:and [:= :ente_id ente-id] [:= :id parecer-id]]})))]
    (when (contains? logic/estados-parecer-terminais estado)
      (throw (ex-info "promover!: parecer em estado terminal — texto imutavel"
                      {:parecer-id parecer-id :estado estado}))))
  (jdbc/execute-one! tx
    (sql/format {:update :legislativo.parecer_texto_versao
                 :set {:estado_versao "superada" :updated_by updated-by :atualizado_em [:now]
                       :lock_version [:+ :lock_version 1]}
                 :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]
                         [:= :estado_versao "vigente"] [:<> :id versao-id]]}))
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.parecer_texto_versao
                         :set (cond-> {:estado_versao "vigente" :updated_by updated-by :atualizado_em [:now]
                                       :lock_version [:+ :lock_version 1]}
                                assinatura-algoritmo
                                (assoc :assinatura_algoritmo assinatura-algoritmo
                                       :assinatura_b64 assinatura-b64
                                       :assinado_por assinado-por
                                       :assinado_em [:now]))
                         :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]
                                 [:= :id versao-id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "promover!: conflito de lock_version ou versao inexistente neste parecer"
                      {:versao-id versao-id :parecer-id parecer-id :lock-version lock-version})))
    (let [rp (jdbc/execute-one! tx
               (sql/format {:update :legislativo.pareceres
                            :set {:texto_vigente_versao_id versao-id :atualizado_em [:now]
                                  :lock_version [:+ :lock_version 1]}
                            :where [:and [:= :ente_id ente-id] [:= :id parecer-id]]}))]
      (when (zero? (:next.jdbc/update-count rp 0))
        (throw (ex-info "promover!: parecer inexistente ou filtrado (pointer nao reapontado)"
                        {:parecer-id parecer-id :ente-id ente-id}))))
    {:versao-id versao-id :estado "vigente"}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.parecer_texto_versao]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn versoes-do-parecer [tx ente-id parecer-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.parecer_texto_versao]
                  :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]]
                  :order-by [[:numero_versao :asc]]}))))

(defn vigente [tx ente-id parecer-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.parecer_texto_versao]
                  :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]
                          [:= :estado_versao "vigente"]]}))))

(defn rascunho-mais-recente
  "A versao 'rascunho' de MAIOR numero_versao do parecer (Onda B Slice 5 — editor busca o rascunho em
  edicao, se houver). nil se nao houver nenhum rascunho (so' vigente, ou nenhum texto ainda)."
  [tx ente-id parecer-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.parecer_texto_versao]
                  :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]
                          [:= :estado_versao "rascunho"]]
                  :order-by [[:numero_versao :desc]]
                  :limit 1}))))
