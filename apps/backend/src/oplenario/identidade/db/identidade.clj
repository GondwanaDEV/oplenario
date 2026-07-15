(ns oplenario.identidade.db.identidade
  "Persistencia SUPRATENANT (sem RLS): identidade (ancora CPF) + identidade_externa (broker gov.br).
  Recebem um `conn` connectable (pool/ds OU tx) — sao supratenant. PRIVILEGIO: as tabelas so sao
  acessiveis ao role oplenario_id_resolver (do qual o pool herda), NUNCA ao oplenario_app do dominio
  (split de privilegio, review F1.3 — codigo de tenant nao enumera CPF). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
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
     (sql/format {:insert-into :identidade.identidade
                  :values [{:id id :cpf cpf :nome nome}]
                  :on-conflict [:cpf] :do-update-set {:nome :excluded.nome}
                  :returning [:id]}))))

(defn por-cpf [conn cpf]
  (comum/linha->kebab
    (jdbc/execute-one! conn
      (sql/format {:select [:id :cpf :nome] :from [:identidade.identidade] :where [:= :cpf cpf]}))))

(defn por-id [conn id]
  (comum/linha->kebab
    (jdbc/execute-one! conn
      (sql/format {:select [:id :cpf :nome] :from [:identidade.identidade] :where [:= :id id]}))))

(defn nome-por-id
  "Leitura ESTREITA (so' :nome) — usada por caminhos que precisam so' do nome (ex.: provisionar usuario
  Keycloak) e NAO devem materializar CPF em memoria (review Task 8 IMPORTANT-2b: seguranca estrutural,
  nao incidental — um `(merge {...} identidade)` futuro sobre `por-id` vazaria CPF pro payload do IdP;
  este caminho torna isso impossivel por construcao)."
  [conn id]
  (comum/linha->kebab
    (jdbc/execute-one! conn
      (sql/format {:select [:id :nome] :from [:identidade.identidade] :where [:= :id id]}))))

(defn identidade-por-sub
  "Resolve (provedor, sub) -> identidade_id. Base do login cidadao via gov.br (F1.4)."
  [conn provedor sub]
  (:identidade_externa/identidade_id
   (jdbc/execute-one! conn
     (sql/format {:select [:identidade_id] :from [:identidade.identidade_externa]
                  :where [:and [:= :provedor provedor] [:= :sub sub]]}))))

(defn vincular-externa!
  "Liga um sub OIDC externo (gov.br) a uma identidade. Idempotente por (provedor, sub). Se o sub JA
  aponta p/ identidade DIFERENTE (reciclagem de sub), LANCA — sinal de incidente de seguranca, nunca
  takeover silencioso. Retorna o identidade_id efetivamente vinculado."
  [conn {:keys [id identidade-id provedor sub]}]
  (jdbc/execute-one! conn
    (sql/format {:insert-into :identidade.identidade_externa
                 :values [{:id id :identidade_id identidade-id :provedor provedor :sub sub}]
                 :on-conflict [:provedor :sub] :do-nothing true}))
  (let [atual (identidade-por-sub conn provedor sub)]
    (when-not (= (str identidade-id) (str atual))
      (throw (ex-info "sub ja vinculado a identidade diferente — possivel reciclagem de sub"
                      {:tipo :identidade/sub-conflito :provedor provedor})))
    atual))
