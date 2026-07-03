(ns oplenario.participacao.db.denuncia-comentario
  "Persistencia de 'participacao.denuncia_comentario' (FAST-FOLLOW Slice 6) — APPEND-ONLY (Inv.10): SO
  `inserir!` + selects (sem UPDATE/DELETE; a mig 0043 nega o grant + trg_denuncia_comentario_append_only).
  IDEMPOTENCIA por (comentario, denunciante): `inserir!` usa INSERT ... ON CONFLICT (ente_id, comentario_id,
  denunciante_identidade_id) DO NOTHING — a UNIQUE da mig 0043 e' o alvo; a 2a tentativa do MESMO cidadao
  sobre o MESMO comentario devolve nil (sem lancar 23505), permitindo ao Repo tratar como sucesso idempotente
  em vez de conflito de negocio. `denunciante-identidade-id` INJETADO do ATOR. HoneySQL schema-qualified;
  ente_id em TODA query. IMPL atras do RepoParticipacao."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :comentario_id :denunciante_identidade_id :motivo :denunciado_em :criado_em])

(defn inserir!
  "Registra uma denuncia (append-only, IDEMPOTENTE via ON CONFLICT DO NOTHING). Devolve o mapa kebab
  (RETURNING *) se e' a 1a denuncia deste cidadao sobre este comentario, ou nil se JA existia (idempotente —
  NAO lanca 23505; o caller decide o que fazer com a repeticao, tipicamente nada-a-mais)."
  [tx {:keys [id ente-id comentario-id denunciante-identidade-id motivo denunciado-em]}]
  {:pre [(some? ente-id) (some? id) (some? comentario-id) (some? denunciante-identidade-id) (some? denunciado-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :participacao.denuncia_comentario
                  :values [{:id id :ente_id ente-id :comentario_id comentario-id
                            :denunciante_identidade_id denunciante-identidade-id
                            :motivo motivo :denunciado_em denunciado-em :efetivado_em [:now]}]
                  :on-conflict [:ente_id :comentario_id :denunciante_identidade_id]
                  :do-nothing true
                  :returning [:*]}))))

(defn listar-do-comentario
  "Historico de denuncias de UM comentario (ente, comentario_id), mais recente primeiro."
  [tx ente-id comentario-id]
  {:pre [(some? ente-id) (some? comentario-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:participacao.denuncia_comentario]
                  :where [:and [:= :ente_id ente-id] [:= :comentario_id comentario-id]]
                  :order-by [[:denunciado_em :desc] [:id :desc]]}))))
