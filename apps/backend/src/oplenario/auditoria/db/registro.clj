(ns oplenario.auditoria.db.registro
  "A corrente da trilha de auditoria de cada Casa (ADR-0017). Grava selando o anterior sob um advisory lock POR CASA
  (uma Casa nao espera a outra); le com filtros; verifica percorrendo a corrente em ordem; o selo do dia. Tudo numa tx
  de tenant (RLS)."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.auditoria.logic :as logic]
            [oplenario.kernel.db-util :as comum])
  (:import (java.sql Array Timestamp)
           (java.time Instant)))

(set! *warn-on-reflection* true)

(defn- ->instant ^Instant [v] (if (instance? Timestamp v) (.toInstant ^Timestamp v) v))

(defn- ->vec [v] (if (instance? Array v) (vec (.getArray ^Array v)) (vec v)))

(defn- ->registro [linha]
  (-> (comum/linha->kebab linha)
      (update :ocorrido-em ->instant)
      (update :papeis ->vec)
      (update :campos ->vec)
      (update :detalhe comum/jsonb->kw)
      (update :ip #(some-> % str (str/replace #"/\d+$" "")))))

(defn- lock! [tx ente-id]
  (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(hashtextextended(?, 7162600017))" (str "auditoria:" ente-id)]))

(defn cabeca
  "O ultimo registro da corrente da Casa (seq, selo, ocorrido-em) ou nil."
  [tx ente-id]
  (some-> (jdbc/execute-one! tx ["SELECT seq, selo, ocorrido_em FROM auditoria.registro WHERE ente_id = ?
                                  ORDER BY seq DESC LIMIT 1" ente-id])
          comum/linha->kebab
          (update :ocorrido-em ->instant)))

(defn- texto-array [tx xs]
  (.createArrayOf ^java.sql.Connection tx "text" (to-array (map str xs))))

(defn gravar!
  "Acrescenta `r` a corrente da Casa (na tx `tx`, de tenant). Devolve {:registro :dia-fechado}: `:dia-fechado` =
  {:dia :seq :selo} quando este e' o primeiro registro de um dia novo — a cabeca anterior e' o selo daquele dia."
  [tx r]
  (lock! tx (:ente-id r))
  (let [anterior (cabeca tx (:ente-id r))
        agora    (->instant (:now (jdbc/execute-one! tx ["SELECT now() AS now"])))
        reg      (assoc r :id (random-uuid) :seq (inc (long (or (:seq anterior) 0))) :ocorrido-em agora)
        selo     (logic/selo-de (or (:selo anterior) "") reg)]
    (jdbc/execute-one! tx [(str "INSERT INTO auditoria.registro (ente_id, seq, id, ocorrido_em, ator_tipo, identidade_id,"
                                " papeis, via_agente, acao, classe, recurso_tipo, recurso_id, rotulo, campos, decisao,"
                                " status_http, canal, ip, detalhe, selo_anterior, selo)"
                                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::inet,?,?,?)")
                           (:ente-id reg) (:seq reg) (:id reg) (Timestamp/from agora) (:ator-tipo reg) (:identidade-id reg)
                           (texto-array tx (:papeis reg)) (:via-agente reg) (:acao reg) (:classe reg)
                           (:recurso-tipo reg) (:recurso-id reg) (:rotulo reg) (texto-array tx (:campos reg))
                           (:decisao reg) (:status-http reg) (:canal reg) (:ip reg)
                           (comum/->jsonb (or (:detalhe reg) {})) (or (:selo anterior) "") selo])
    {:registro (assoc reg :selo selo :selo-anterior (or (:selo anterior) ""))
     :dia-fechado (when (and anterior (.isBefore (logic/dia-de (:ocorrido-em anterior)) (logic/dia-de agora)))
                    {:dia (logic/dia-de (:ocorrido-em anterior)) :seq (:seq anterior) :selo (:selo anterior)})}))

(defn registrar-selo-do-dia!
  "Grava a cabeca da corrente ao fim de `dia` (idempotente: o dia ja' selado nao muda)."
  [tx ente-id {:keys [dia seq selo]}]
  (jdbc/execute-one! tx ["INSERT INTO auditoria.selo_diario (ente_id, dia, seq, selo) VALUES (?,?,?,?)
                          ON CONFLICT (ente_id, dia) DO NOTHING" ente-id dia seq selo]))

(defn anular-ips-antigos!
  "O IP de mais de 6 meses vira NULL (ADR-0017 4b). O resto do registro — e o selo — nao muda."
  [tx ente-id]
  (:next.jdbc/update-count
   (jdbc/execute-one! tx ["UPDATE auditoria.registro SET ip = NULL
                           WHERE ente_id = ? AND ip IS NOT NULL AND ocorrido_em < now() - interval '6 months'" ente-id])))

(defn garantir-particoes! [tx]
  (jdbc/execute-one! tx ["SELECT auditoria.garantir_particoes(now(), 2)"]))

;; ---- leitura ----

(defn- filtro-where [ente-id {:keys [escopo desde ate ator-tipo classe objeto antes-de]}]
  (cond-> [:and [:= :ente_id ente-id]]
    (= :propria (:tipo escopo)) (conj [:= :identidade_id (:identidade-id escopo)])
    (= :acessos (:tipo escopo)) (conj [:or [:like :acao (str logic/prefixo-dos-acessos "%")]
                                       [:= :identidade_id (:identidade-id escopo)]])
    desde     (conj [:>= :ocorrido_em (Timestamp/from ^Instant desde)])
    ate       (conj [:< :ocorrido_em (Timestamp/from ^Instant ate)])
    ator-tipo (conj [:= :ator_tipo ator-tipo])
    classe    (conj [:= :classe classe])
    objeto    (conj [:like :acao (str objeto "/%")])
    antes-de  (conj [:< :seq antes-de])))

(defn listar
  "Uma pagina da trilha, mais recente primeiro, e o total do filtro."
  [tx ente-id filtro limite]
  (let [onde (filtro-where ente-id filtro)]
    {:registros (mapv ->registro (jdbc/execute! tx (sql/format {:select [:*] :from [:auditoria.registro] :where onde
                                                                :order-by [[:seq :desc]] :limit limite})))
     :total (:n (jdbc/execute-one! tx (sql/format {:select [[[:count :*] :n]] :from [:auditoria.registro]
                                                   :where (filtro-where ente-id (dissoc filtro :antes-de))})
                                   {:builder-fn rs/as-unqualified-lower-maps}))}))

(defn total-da-casa [tx ente-id]
  (:n (jdbc/execute-one! tx ["SELECT count(*) AS n FROM auditoria.registro WHERE ente_id = ?" ente-id]
                         {:builder-fn rs/as-unqualified-lower-maps})))

(defn verificar
  "Percorre a corrente inteira da Casa em ordem, em paginas de 5000 (keyset por seq), e confere cada selo."
  [tx ente-id]
  (loop [acc {:integra true :total 0 :cabeca nil :quebra-em nil} depois-de 0]
    (let [pagina (mapv ->registro (jdbc/execute! tx ["SELECT * FROM auditoria.registro WHERE ente_id = ? AND seq > ?
                                                     ORDER BY seq LIMIT 5000" ente-id depois-de]))
          acc'   (reduce logic/verificar-passo acc pagina)]
      (if (or (:quebra-em acc') (< (count pagina) 5000))
        acc'
        (recur acc' (long (:seq (peek pagina))))))))

(defn selos-diarios
  "Os ultimos `n` selos do dia da Casa, o mais recente primeiro."
  [tx ente-id n]
  (mapv #(update (comum/linha->kebab %) :dia (fn [d] (if (instance? java.sql.Date d) (.toLocalDate ^java.sql.Date d) d)))
        (jdbc/execute! tx ["SELECT dia, seq, selo FROM auditoria.selo_diario WHERE ente_id = ? ORDER BY dia DESC LIMIT ?"
                           ente-id n])))
