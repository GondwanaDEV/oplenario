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

(defn- cabeca-e-agora
  "A cabeca da corrente da Casa e o relogio do banco numa consulta so': com a tentativa, cada escrita grava duas vezes
  na corrente, e cada gravacao custa o lock, esta leitura e o INSERT."
  [tx ente-id]
  (let [l (jdbc/execute-one! tx ["SELECT now() AS agora, c.seq, c.selo, c.ocorrido_em
                                  FROM (SELECT 1) AS um
                                  LEFT JOIN LATERAL (SELECT seq, selo, ocorrido_em FROM auditoria.registro
                                                     WHERE ente_id = ? ORDER BY seq DESC LIMIT 1) c ON true" ente-id]
                             {:builder-fn rs/as-unqualified-lower-maps})]
    {:agora    (->instant (:agora l))
     :anterior (when (:seq l) {:seq (:seq l) :selo (:selo l) :ocorrido-em (->instant (:ocorrido_em l))})}))

(defn gravar!
  "Acrescenta `r` a corrente da Casa (na tx `tx`, de tenant). Devolve {:registro :dia-fechado}: `:dia-fechado` =
  {:dia :seq :selo} quando este e' o primeiro registro de um dia novo — a cabeca anterior e' o selo daquele dia."
  [tx r]
  (lock! tx (:ente-id r))
  (let [{:keys [agora anterior]} (cabeca-e-agora tx (:ente-id r))
        reg     (assoc r :id (random-uuid) :seq (inc (long (or (:seq anterior) 0))) :ocorrido-em agora)
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

;; A TENTATIVA (decisao = iniciado) na leitura (ADR-0017, adendo): a que TEM desfecho nao e' linha da tela — o desfecho
;; e' a linha do ato; a que NAO tem aparece, porque e' o ato que pode ter acontecido sem registro. Uma tentativa mais
;; nova que a tolerancia ainda pode estar em curso (o handler rodando) e so' e' acusada depois dela. O desfecho aponta a
;; tentativa em detalhe.tentativa (indice parcial idx_registro_tentativa, migration 0188).

(def tolerancia-padrao-s
  "Quanto tempo uma tentativa pode ficar sem desfecho antes de ser acusada: o pedido ainda pode estar em curso."
  120)

(defn- tentativa-oculta-sql
  "A tentativa que NAO se mostra: a que ainda esta' dentro da tolerancia ou a que tem desfecho. `a` = o alias da linha."
  [a tolerancia-s]
  (str a ".decisao = 'iniciado' AND (" a ".ocorrido_em > now() - interval '"
       (long (or tolerancia-s tolerancia-padrao-s)) " seconds'"
       " OR EXISTS (SELECT 1 FROM auditoria.registro d WHERE d.ente_id = " a ".ente_id"
       " AND (d.detalhe->>'tentativa') IS NOT NULL AND (d.detalhe->>'tentativa')::bigint = " a ".seq))"))

(defn- filtro-where
  "O WHERE da leitura: a Casa, o escopo do papel e os filtros da tela, todos em AND (o filtro estreita, nunca alarga o
  escopo). `recurso-tipo`/`recurso-id` vem juntos do gate de entrada."
  [ente-id {:keys [escopo desde ate ator-tipo classe objeto recurso-tipo recurso-id antes-de sem-desfecho tolerancia-s]}]
  (cond-> [:and [:= :r.ente_id ente-id] [:raw (str "NOT (" (tentativa-oculta-sql "r" tolerancia-s) ")")]]
    (= :propria (:tipo escopo)) (conj [:= :r.identidade_id (:identidade-id escopo)])
    (= :acessos (:tipo escopo)) (conj [:or [:like :r.acao (str logic/prefixo-dos-acessos "%")]
                                       [:= :r.identidade_id (:identidade-id escopo)]])
    desde     (conj [:>= :r.ocorrido_em (Timestamp/from ^Instant desde)])
    ate       (conj [:< :r.ocorrido_em (Timestamp/from ^Instant ate)])
    ator-tipo (conj [:= :r.ator_tipo ator-tipo])
    classe    (conj [:= :r.classe classe])
    sem-desfecho (conj [:= :r.decisao logic/iniciado])
    objeto    (conj [:like :r.acao (str objeto "/%")])
    recurso-tipo (conj [:= :r.recurso_tipo recurso-tipo])
    recurso-id   (conj [:= :r.recurso_id recurso-id])
    antes-de  (conj [:< :r.seq antes-de])))

(defn listar
  "Uma pagina da trilha, mais recente primeiro, e o total do filtro. A tentativa com desfecho nao e' linha da tela."
  [tx ente-id filtro limite]
  (let [onde (filtro-where ente-id filtro)]
    {:registros (mapv ->registro (jdbc/execute! tx (sql/format {:select [:r.*] :from [[:auditoria.registro :r]]
                                                                :where onde :order-by [[:r.seq :desc]] :limit limite})))
     :total (:n (jdbc/execute-one! tx (sql/format {:select [[[:count :*] :n]] :from [[:auditoria.registro :r]]
                                                   :where (filtro-where ente-id (dissoc filtro :antes-de))})
                                   {:builder-fn rs/as-unqualified-lower-maps}))}))

(defn sem-desfecho
  "As tentativas da Casa sem desfecho registrado, fora da tolerancia: {:total :primeiro} (`:primeiro` = o seq da mais
  antiga, ou nil). E' o que a conferencia acusa ao lado do selo: a corrente pode estar integra e faltar um desfecho."
  [tx ente-id tolerancia-s]
  (let [l (jdbc/execute-one! tx [(str "SELECT count(*) AS n, min(r.seq) AS primeiro FROM auditoria.registro r"
                                      " WHERE r.ente_id = ? AND r.decisao = 'iniciado'"
                                      " AND NOT (" (tentativa-oculta-sql "r" tolerancia-s) ")") ente-id]
                             {:builder-fn rs/as-unqualified-lower-maps})]
    {:total (long (:n l)) :primeiro (:primeiro l)}))

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
