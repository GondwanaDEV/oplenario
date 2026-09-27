(ns oplenario.identidade.db.credencial-agente
  "Persistencia SUPRATENANT da CREDENCIAL DELEGADA do agente (ADR-0010, mig `identidade-credencial-agente`). Mesma
  custodia da sessao opaca de login (`db/sessao.clj`): so' `sha256(segredo)` em repouso, o segredo cru existe uma vez
  (na emissao), prazos comparados no SQL com `now()`. Acessivel so' ao oplenario_id_resolver."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.identidade.db.sessao :as sess]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Emite a credencial e devolve o segredo CRU (a unica vez que ele existe fora de quem o recebe). `expira-em` ja' vem
  computado pelo caller (a config decide o prazo). `classes` = strings."
  [conn {:keys [execucao-id ente-id identidade-id agente publico classes expira-em]}]
  {:pre [(some? execucao-id) (some? ente-id) (some? agente) (some? publico) (seq classes) (some? expira-em)]}
  (let [segredo (sess/gerar-segredo)]
    (jdbc/execute-one! conn
      (sql/format {:insert-into :identidade.credencial_agente
                   :values [{:credencial_hash (sess/sha256-bytes segredo)
                             :execucao_id execucao-id :ente_id ente-id :identidade_id identidade-id
                             :agente agente :publico publico
                             :classes [:array (vec classes) :text]
                             :expira_em expira-em}]}))
    segredo))

(defn resolver
  "segredo cru -> {:execucao-id :ente-id :identidade-id :agente :publico :classes} se a credencial existe, nao
  expirou e nao foi revogada; senao nil (sem dizer qual das tres)."
  [conn segredo]
  {:pre [(some? segredo)]}
  (some-> (jdbc/execute-one! conn
            (sql/format {:select [:execucao_id :ente_id :identidade_id :agente :publico :classes]
                         :from [:identidade.credencial_agente]
                         :where [:and [:= :credencial_hash (sess/sha256-bytes segredo)]
                                      [:<= [:now] :expira_em]
                                      [:= :revogada_em nil]]}))
          comum/linha->kebab
          (update :classes #(vec (.getArray ^java.sql.Array %)))))

(defn revogar!
  "Revoga a credencial da execucao (a pessoa fechou o painel, cancelou, ou a execucao acabou). Idempotente."
  [conn execucao-id]
  (jdbc/execute-one! conn
    (sql/format {:update :identidade.credencial_agente :set {:revogada_em [:now]}
                 :where [:and [:= :execucao_id execucao-id] [:= :revogada_em nil]]}))
  nil)
