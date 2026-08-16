(ns oplenario.sessoes.db.folha
  "Persistencia de `sessoes.folha_sessao` (§22.6 eixo C, Etapa 5 fatia 4, D2/D3/D7/D9) — funcoes sobre a `tx`
  do tenant (FORCE RLS isola, mig 0073). A folha e' APPEND-ONLY IMUTAVEL: re-congelamento = NOVA versao
  (nunca muta hash/objeto_store_ref das anteriores); sem UPDATE/DELETE. Os DOIS binarios (HTML/PDF) moram no
  objeto_store — aqui so' metadados + os dois trios de ponteiro+hash (D3). HoneySQL schema-qualified;
  ente_id em toda query. IMPL atras de `RepoSessoes` (o db/ so' e' importado pelo Repo-Component do proprio
  modulo, import-lint ADR-0001).

  D7 — `inserir!` recebe a VERSAO EXPLICITA (nao computa MAX+1 no proprio INSERT, ao contrario de
  `legislativo.db.artefato-publicacao/inserir-versionada!`/`compliance.db.remessa/inserir-versionada!`): a
  folha imprime a propria versao no papel, entao quem decide o numero e' o CHAMADOR (`sessoes.controllers/
  gerar-folha!`), que le' `max-versao` ANTES de renderizar. `max-versao` e `inserir!` sao dois statements
  distintos de proposito — e' essa distancia (ler, renderizar, so' entao inserir) que o molde de
  INSERT...SELECT nao permite."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :sessao_id :versao :spec_versao
   :html_hash :html_content_type :html_objeto_store_ref
   :pdf_hash :pdf_content_type :pdf_objeto_store_ref
   :gerada_por :gerada_em :criado_em])

(defn max-versao
  "A MAIOR versao ja congelada de (ente, sessao), ou 0 se nunca houve congelamento — o insumo de D7: o
  CHAMADOR le' isto, soma 1, renderiza com o numero previsto, e so' entao chama `inserir!`. `COALESCE` faz o
  caso 'nunca congelada' virar 0 (nao nil), para `(inc (max-versao ...))` nunca precisar de um `or` a parte."
  [tx ente-id sessao-id]
  (:max (jdbc/execute-one! tx
          (sql/format {:select [[[:coalesce [:max :versao] [:inline 0]] :max]]
                       :from [:sessoes.folha_sessao]
                       :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]}))))

(defn inserir!
  "Insere a folha na versao EXPLICITA que o chamador propos (D7 — nao MAX+1 no proprio INSERT). `UNIQUE
  (ente_id, sessao_id, versao)` (mig 0073) e' quem detecta a corrida: duas propostas concorrentes para o
  MESMO numero fazem a 2a lancar 23505 (o chamador re-tenta com a versao relida). Devolve o mapa kebab da
  linha criada (RETURNING *, com `criado_em` do DEFAULT do banco)."
  [tx {:keys [id ente-id sessao-id versao spec-versao gerada-por gerada-em
              html-hash html-content-type html-objeto-store-ref
              pdf-hash pdf-content-type pdf-objeto-store-ref]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :sessoes.folha_sessao
                  :values [{:id id :ente_id ente-id :sessao_id sessao-id :versao versao
                            :spec_versao spec-versao
                            :html_hash html-hash :html_content_type html-content-type
                            :html_objeto_store_ref html-objeto-store-ref
                            :pdf_hash pdf-hash :pdf_content_type pdf-content-type
                            :pdf_objeto_store_ref pdf-objeto-store-ref
                            :gerada_por gerada-por :gerada_em gerada-em}]
                  :returning [:*]}))))

(defn buscar
  "Uma versao especifica da folha, ou nil. RLS via ente-id."
  [tx ente-id sessao-id versao]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:sessoes.folha_sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :versao versao]]}))))

(defn folhas-da-sessao
  "Todas as versoes congeladas da sessao, mais recente primeiro (metadados — nenhum binario mora nesta
  tabela, os dois blobs ficam no objeto_store sob os *_objeto_store_ref)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:sessoes.folha_sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:versao :desc]]}))))

(defn recente-do-ator
  "A folha MAIS RECENTE de (ente, sessao) gerada por `gerada-por` a partir de `desde`, ou nil — o insumo de
  D9: um reenvio do MESMO ator dentro da janela de deduplicacao devolve esta linha em vez de congelar de
  novo. Mesmo desenho de `sessoes.db.chamada/ato-recente-do-ator` (Etapa 2d)."
  [tx ente-id sessao-id gerada-por desde]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:sessoes.folha_sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                          [:= :gerada_por gerada-por] [:>= :gerada_em desde]]
                  :order-by [[:gerada_em :desc] [:id :desc]]
                  :limit 1}))))
