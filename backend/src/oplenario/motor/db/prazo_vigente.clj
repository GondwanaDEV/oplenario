(ns oplenario.motor.db.prazo-vigente
  "Persistencia de 'motor.prazo_dominio_vigente' (B4 §22.7.6 — REFERENCIA regulatoria de prazo, NAO
  obrigacao de runtime). Lido pelo builtin prazo_vigente(dominio, tipo, competencia). Override por
  Oficio Circular (S3 'prazo deslizante'): append-only + flag 'vigente' (so UMA vigente por chave).
  DOMINIO (sem ente_id). HoneySQL schema-qualified, IMPL atras do RepoMotor."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Append do prazo (norma-base ou deslize por circular). vigente=true exige que o caller tenha
  desmarcado o anterior (o index UNIQUE-vigente garante a invariante 'so uma vigente')."
  [tx {:keys [id dominio chave-dominio tipo-prazo chave-periodo data-limite fonte vigente]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :motor.prazo_dominio_vigente
                 :values [{:id id :dominio dominio :chave_dominio chave-dominio :tipo_prazo tipo-prazo
                           :chave_periodo chave-periodo :data_limite data-limite :fonte fonte
                           :vigente (boolean vigente)}]})))

(defn vigente
  "A linha 'vigente' p/ (dominio, chave_dominio, tipo_prazo, chave_periodo) -> {:data-limite :fonte}.
  nil se nao ha prazo p/ o periodo (o caller/motor trata como [GAP de conteudo], nunca 'pula a regra').
  chave_dominio NULL (federal) via COALESCE (a armadilha NULL != NULL)."
  [tx dominio chave-dominio tipo-prazo chave-periodo]
  (when-let [row (jdbc/execute-one! tx
                   (sql/format {:select [:data_limite :fonte]
                                :from [:motor.prazo_dominio_vigente]
                                :where [:and [:= :vigente true] [:= :dominio dominio]
                                        [:= [:coalesce :chave_dominio ""] [:coalesce chave-dominio ""]]
                                        [:= :tipo_prazo tipo-prazo] [:= :chave_periodo chave-periodo]]}))]
    (comum/linha->kebab row)))
