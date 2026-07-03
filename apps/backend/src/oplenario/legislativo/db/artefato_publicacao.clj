(ns oplenario.legislativo.db.artefato-publicacao
  "Persistencia de 'legislativo.artefato_publicacao' (F6c Slice 4a, doc-mestre L287) — funcoes sobre a `tx` do
  tenant (FORCE RLS isola, mig 0046). O artefato e' APPEND-ONLY IMUTAVEL: re-geracao = NOVA versao (nunca muta
  hash/objeto_store_ref/assinatura); sem UPDATE/DELETE. O binario mora no objeto_store — aqui so' metadados +
  ponteiro + a assinatura destacada. HoneySQL schema-qualified; ente_id em toda query. IMPL atras do
  RepoLegislativo (o db/ so' e' importado pelo Repo-Component do proprio modulo, import-lint ADR-0001)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :norma_id :versao :spec_versao :content_type :hash :objeto_store_ref
   :assinatura_algoritmo :assinatura_b64 :assinado_por :assinado_em :criado_em])

(defn buscar
  "Um artefato por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:legislativo.artefato_publicacao]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn existe?
  "O artefato `id` existe no tenant (RLS via ente-id)? Point-lookup pela PK, projeta SO' `1` — nao traz ao heap
  os ponteiros/proveniencia (hash/objeto_store_ref/assinatura) que `buscar` traria (defesa-em-profundidade,
  espelha compliance.db.remessa/existe?). Usado pela borda p/ desambiguar 404 vs 409 (Slice 4b)."
  [tx ente-id id]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [[[:inline 1] :existe]] :from [:legislativo.artefato_publicacao]
                        :where [:and [:= :ente_id ente-id] [:= :id id]] :limit 1}))))

(def ^:private sql-inserir-versionada
  "INSERT...SELECT que computa a versao (MAX+1) na MESMA instrucao — ATOMICO, fecha o TOCTOU ler-versao->inserir
  (mesma disciplina de compliance.db.remessa/inserir-versionada!, carry F5.3a-1). Concorrencia: duas geracoes
  simultaneas podem ler o mesmo MAX (READ COMMITTED) e a 2a viola o UNIQUE(ente_id,norma_id,versao) com 23505;
  o Repo re-tenta UMA vez em tx nova (a re-leitura ja' enxerga a versao commitada). RETURNING * devolve a linha
  (DEFAULTs do banco: assinado_em, criado_em). Raw SQL (nao HoneySQL) por legibilidade do scalar-subquery."
  (str "INSERT INTO legislativo.artefato_publicacao"
       " (id, ente_id, norma_id, versao, spec_versao, content_type, hash, objeto_store_ref,"
       "  assinatura_algoritmo, assinatura_b64, assinado_por)"
       " SELECT ?, ?, ?, COALESCE(MAX(versao), 0) + 1, ?, ?, ?, ?, ?, ?, ?"
       " FROM legislativo.artefato_publicacao"
       " WHERE ente_id = ? AND norma_id = ?"
       " RETURNING *"))

(defn inserir-versionada!
  "Materializa o artefato na PROXIMA versao ATOMICAMENTE (versao = MAX+1 no proprio INSERT...SELECT). 23505 em
  corrida = o caller re-tenta (Repo/gerar-artefato-publicacao!). Devolve o mapa kebab do artefato criado."
  [tx {:keys [id ente-id norma-id spec-versao content-type objeto-store-ref
              assinatura-algoritmo assinatura-b64 assinado-por] hash-conteudo :hash}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     [sql-inserir-versionada
      id ente-id norma-id spec-versao content-type hash-conteudo objeto-store-ref
      assinatura-algoritmo assinatura-b64 assinado-por
      ente-id norma-id])))

(defn listar-por-norma
  "Artefatos de uma norma, por versao ASC (historico de (re)geracoes). RLS via ente-id."
  [tx ente-id norma-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:legislativo.artefato_publicacao]
                  :where [:and [:= :ente_id ente-id] [:= :norma_id norma-id]]
                  :order-by [[:versao :asc]]}))))
