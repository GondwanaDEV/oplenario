(ns oplenario.compliance.db.remessa
  "Persistencia de 'compliance.remessa_gerada' (§22.7.8) — funcoes sobre a `tx` do tenant (FORCE RLS isola,
  mig 0009). O ARTEFATO e' imutavel por VERSAO (re-emissao = nova versao; nunca muta hash/objeto_store_ref);
  o `estado` de submissao evolui via UPDATE no ciclo (rascunho -> validada -> submetida -> {aceita|rejeitada}).
  O binario mora no objeto_store — aqui so metadados + ponteiro. HoneySQL schema-qualified; ente_id em toda
  query. A COSTURA `remessa_enviada` (so 'aceita' cumpre) e' `aceita-existe?`. IMPL atras do RepoCompliance."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.compliance.logic :as logic]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :template_chave :sistema :competencia :versao :spec_layout_versao :registry_versao_ref
   :hash :objeto_store_ref :estado :submetida_em :resposta_em :criado_em])

(defn buscar
  "Busca uma remessa por id no tenant (RLS via ente-id). Devolve o mapa kebab-case ou nil."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:compliance.remessa_gerada]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn existe?
  "A remessa `id` existe no tenant (RLS via ente-id)? Point-lookup pela PK, projeta SO `1` — NAO traz ao
  heap os ponteiros/proveniencia internos (hash/objeto_store_ref/...) que `buscar` traria (review sec
  BAIXO; defesa-em-profundidade, como `cols-painel`). Usado pela borda p/ desambiguar 404 vs 409."
  [tx ente-id id]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [[[:inline 1] :existe]] :from [:compliance.remessa_gerada]
                        :where [:and [:= :ente_id ente-id] [:= :id id]] :limit 1}))))

(defn proxima-versao
  "SUPERSEDIDA p/ a geracao: use `inserir-versionada!` (computa a versao no proprio INSERT, sem janela
  TOCTOU). Mantida p/ leitura/diagnostico. A proxima versao p/ (ente, template, competencia): max(versao)+1,
  ou 1 se nao ha nenhuma. Re-emissao =
  NOVA versao (§22.7.8) — o UNIQUE(ente, template, competencia, versao) impede colisao se duas geracoes
  concorrerem; o caller trata o conflito (re-tenta a proxima)."
  [tx ente-id template-chave competencia]
  (let [r (comum/linha->kebab
           (jdbc/execute-one! tx
             (sql/format {:select [[[:max :versao] :v]] :from [:compliance.remessa_gerada]
                          :where [:and [:= :ente_id ente-id] [:= :template_chave template-chave]
                                  [:= :competencia competencia]]})))]
    (inc (or (:v r) 0))))

(defn inserir!
  "Materializa uma remessa nova (estado 'rascunho' por DEFAULT do banco). `criado_em`/`estado` ficam a
  cargo dos DEFAULT. Devolve o mapa kebab da remessa criada (RETURNING *). O UNIQUE
  (ente, template, competencia, versao) barra re-emissao com versao repetida — o caller usa proxima-versao."
  [tx {:keys [id ente-id template-chave sistema competencia versao spec-layout-versao
              registry-versao-ref objeto-store-ref] hash-conteudo :hash}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :compliance.remessa_gerada
                  :values [{:id id :ente_id ente-id :template_chave template-chave :sistema sistema
                            :competencia competencia :versao versao :spec_layout_versao spec-layout-versao
                            :registry_versao_ref registry-versao-ref :hash hash-conteudo
                            :objeto_store_ref objeto-store-ref}]
                  :returning [:*]}))))

(def ^:private sql-inserir-versionada
  "INSERT...SELECT que computa a versao (MAX+1) na MESMA instrucao — ATOMICO, fecha o TOCTOU
  proxima-versao->inserir! (carry F5.3a-1). Concorrencia: duas geracoes simultaneas podem ler o mesmo MAX
  (READ COMMITTED) e a 2a viola o UNIQUE(ente,template,competencia,versao) com 23505; o Repo re-tenta UMA
  vez em tx nova (a re-leitura ja' enxerga a versao commitada). RETURNING * devolve a linha (DEFAULTs do
  banco: estado='rascunho', criado_em). Raw SQL (nao HoneySQL) por legibilidade do scalar-subquery."
  (str "INSERT INTO compliance.remessa_gerada"
       " (id, ente_id, template_chave, sistema, competencia, versao,"
       "  spec_layout_versao, registry_versao_ref, hash, objeto_store_ref)"
       " SELECT ?, ?, ?, ?, ?, COALESCE(MAX(versao), 0) + 1, ?, ?, ?, ?"
       " FROM compliance.remessa_gerada"
       " WHERE ente_id = ? AND template_chave = ? AND competencia = ?"
       " RETURNING *"))

(defn inserir-versionada!
  "Materializa uma remessa na PROXIMA versao ATOMICAMENTE (versao = MAX+1 no proprio INSERT...SELECT) —
  fecha o carry TOCTOU F5.3a-1 (sem janela entre ler a versao e inserir). 23505 em corrida = o caller
  re-tenta (Repo/gerar-remessa!). Devolve o mapa kebab da remessa criada."
  [tx {:keys [id ente-id template-chave sistema competencia spec-layout-versao registry-versao-ref
              objeto-store-ref] hash-conteudo :hash}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     [sql-inserir-versionada
      id ente-id template-chave sistema competencia
      spec-layout-versao registry-versao-ref hash-conteudo objeto-store-ref
      ente-id template-chave competencia])))

(defn transicionar-estado!
  "CAS de ciclo: transiciona a remessa de `de` -> `para` SOMENTE se ainda esta em `de` (WHERE estado=de) —
  race-safe contra uma transicao concorrente. `extra` carrega os carimbos opcionais ({:submetida-em [:now]}
  / {:resposta-em [:now]}). Devolve o mapa kebab da remessa ja' transicionada (RETURNING *), ou nil se a
  corrida foi perdida (o estado nao era `de`). GUARDA o grafo do ciclo (logic/transicao-remessa-valida?):
  um salto ilegal (ex.: rascunho->aceita, que o CAS aceitaria se o estado fosse 'rascunho') LANCA — 2a linha
  de defesa contra uso direto incorreto (handler/script/teste), independente do CHECK de valores no banco
  (review database M2 / security #2). O CAS `WHERE estado=de` segue cobrindo a corrida concorrente."
  [tx ente-id id de para {:keys [submetida-em resposta-em]}]
  (when-not (logic/transicao-remessa-valida? de para)
    (throw (ex-info "transicao de estado de remessa invalida" {:de de :para para})))
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :compliance.remessa_gerada
                  :set (cond-> {:estado para}
                         submetida-em (assoc :submetida_em submetida-em)
                         resposta-em  (assoc :resposta_em resposta-em))
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :estado de]]
                  :returning [:*]}))))

(defn listar
  "Remessas de (ente, template, competencia) em ordem de versao — historico de (re)emissoes."
  [tx ente-id template-chave competencia]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:compliance.remessa_gerada]
                  :where [:and [:= :ente_id ente-id] [:= :template_chave template-chave]
                          [:= :competencia competencia]]
                  :order-by [[:versao :asc]]}))))

(def ^:private cols-painel
  "Colunas do read-model do painel — SUBCONJUNTO publico de `cols` (review sec MÉDIO-1): NAO traz p/ o heap
  da JVM os ponteiros/proveniencia internos (hash, objeto_store_ref, registry_versao_ref, spec_layout_versao)
  que o adapters/out descartaria de qualquer forma — defesa-em-profundidade contra log cru / refactor futuro."
  [:id :ente_id :template_chave :sistema :competencia :versao :estado :submetida_em :resposta_em :criado_em])

(defn listar-recentes
  "Read-model do painel (§16.11): as remessas mais recentes do tenant (todas as competencias/sistemas),
  ordem `criado_em` DESC (id DESC como tiebreaker estavel num empate de timestamp), com TETO `limite`
  (anti unbounded-read — review sec). E' o pipeline de remessas que a Mesa/juridico le no painel. Projeta
  so `cols-painel` (sem os campos internos — review sec MÉDIO-1)."
  [tx ente-id limite]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols-painel :from [:compliance.remessa_gerada]
                  :where [:= :ente_id ente-id]
                  :order-by [[:criado_em :desc] [:id :desc]]
                  :limit limite}))))

;; NOTA: a COSTURA `remessa_enviada(sistema, competencia)` (so 'aceita' cumpre) NAO mora aqui — e' uma
;; funcao de RELACAO (compliance/relacoes), que inlina a query do proprio schema como as do cadastros
;; (ADR-0001 §3-bis: o db/ so e' importado pelo Repo-Component; a relacao e' injetada no motor por nome).
