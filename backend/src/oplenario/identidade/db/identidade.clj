(ns oplenario.identidade.db.identidade
  "Persistencia SUPRATENANT (sem RLS): identidade (ancora CPF) + identidade_externa (broker gov.br).
  Recebem um `conn` connectable (pool/ds OU tx) — sao supratenant. PRIVILEGIO: as tabelas so sao
  acessiveis ao role oplenario_id_resolver (do qual o pool herda), NUNCA ao oplenario_app do dominio
  (split de privilegio, review F1.3 — codigo de tenant nao enumera CPF). next.jdbc parametrizado."
  (:require [next.jdbc :as jdbc]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Cria a identidade (CPF -> id), validando o digito verificador do CPF. Idempotente por CPF; RETORNA o
  id CANONICO (o existente, em caso de conflito) — o caller DEVE usar este id (nao o que passou)."
  [conn {:keys [id cpf nome]}]
  {:pre [(mod/valido-cpf? cpf)]}
  (:identidade/id
   (jdbc/execute-one! conn
     ["INSERT INTO identidade.identidade (id, cpf, nome) VALUES (?, ?, ?)
       ON CONFLICT (cpf) DO UPDATE SET nome = EXCLUDED.nome
       RETURNING id" id cpf nome])))

(defn por-cpf [conn cpf]
  (comum/linha->kebab
    (jdbc/execute-one! conn ["SELECT id, cpf, nome FROM identidade.identidade WHERE cpf = ?" cpf])))

(defn por-id [conn id]
  (comum/linha->kebab
    (jdbc/execute-one! conn ["SELECT id, cpf, nome FROM identidade.identidade WHERE id = ?" id])))

(defn identidade-por-sub
  "Resolve (provedor, sub) -> identidade_id. Base do login cidadao via gov.br (F1.4)."
  [conn provedor sub]
  (:identidade_externa/identidade_id
   (jdbc/execute-one! conn
     ["SELECT identidade_id FROM identidade.identidade_externa WHERE provedor = ? AND sub = ?" provedor sub])))

(defn vincular-externa!
  "Liga um sub OIDC externo (gov.br) a uma identidade. Idempotente por (provedor, sub). Se o sub JA
  aponta p/ identidade DIFERENTE (reciclagem de sub), LANCA — sinal de incidente de seguranca, nunca
  takeover silencioso. Retorna o identidade_id efetivamente vinculado."
  [conn {:keys [id identidade-id provedor sub]}]
  (jdbc/execute-one! conn
    ["INSERT INTO identidade.identidade_externa (id, identidade_id, provedor, sub) VALUES (?, ?, ?, ?)
      ON CONFLICT (provedor, sub) DO NOTHING" id identidade-id provedor sub])
  (let [atual (identidade-por-sub conn provedor sub)]
    (when-not (= (str identidade-id) (str atual))
      (throw (ex-info "sub ja vinculado a identidade diferente — possivel reciclagem de sub"
                      {:tipo :identidade/sub-conflito :provedor provedor})))
    atual))
