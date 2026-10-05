(ns oplenario.identidade.db.identidade
  "Persistencia SUPRATENANT (sem RLS): identidade (ancora CPF) + identidade_externa (broker gov.br).
  Recebem um `conn` connectable (pool/ds OU tx) — sao supratenant. PRIVILEGIO: as tabelas so sao
  acessiveis ao role oplenario_id_resolver (do qual o pool herda), NUNCA ao oplenario_app do dominio
  (split de privilegio, review F1.3 — codigo de tenant nao enumera CPF). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
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

(defn id-por-cpf
  "ADR-0025 (entrada pelo CPF): CPF -> id da identidade | nil. Leitura ESTREITA: so' o id volta do banco — nem o nome
  nem o CPF de ninguem passam pela memoria do caminho publico de entrada."
  [conn cpf]
  (:identidade/id
   (jdbc/execute-one! conn
     (sql/format {:select [:id] :from [:identidade.identidade] :where [:= :cpf cpf]}))))

(defn casas-com-acesso-institucional
  "ADR-0025: os `ente_id` das Casas onde a identidade tem vinculo INSTITUCIONAL ativo (servidor, vereador, admin_ente).
  O vinculo e' tenant (FORCE RLS); a pergunta atravessa as Casas so' pela funcao estreita do banco
  (`identidade.casas_com_acesso_institucional`, SECURITY DEFINER, EXECUTE so' do role id_resolver)."
  [conn identidade-id]
  (mapv :ente_id
        (jdbc/execute! conn ["SELECT ente_id FROM identidade.casas_com_acesso_institucional(?)" identidade-id]
                       {:builder-fn rs/as-unqualified-maps})))

(defn nomes-por-ids
  "ADR-0020: ids -> {id nome}, numa consulta so' (leitura ESTREITA como `nome-por-id`: nunca materializa CPF).
  Supratenant (pool, role id_resolver) — o CHAMADOR ja' restringiu os ids as pessoas da Casa (vinculo, sob RLS).
  Vazio nao vai ao banco."
  [conn ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (into {} (map (juxt :id :nome))
            (comum/linhas->kebab
              (jdbc/execute! conn
                (sql/format {:select [:id :nome] :from [:identidade.identidade] :where [:in :id ids]})))))))

(defn com-cpf-mascarado-por-ids
  "Balcao de atendimento (e-SIC/LGPD): ids -> {id {:nome :cpf-mascarado}}, numa consulta so'. O CPF sai MASCARADO DO
  BANCO ('***.456.789-**': os 3 primeiros e os 2 ultimos digitos nunca chegam a memoria) — leitura estreita como
  `nomes-por-ids`, por construcao. Supratenant: o CHAMADOR ja' restringiu os ids aos requerentes da Casa (sob RLS).
  Vazio nao vai ao banco."
  [conn ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (into {} (map (fn [l] [(:id l) {:nome (:nome l) :cpf-mascarado (:cpf-mascarado l)}]))
            (comum/linhas->kebab
              (jdbc/execute! conn
                (sql/format {:select [:id :nome
                                      [[:raw "'***.' || substr(cpf, 4, 3) || '.' || substr(cpf, 7, 3) || '-**'"]
                                       :cpf_mascarado]]
                             :from [:identidade.identidade] :where [:in :id ids]})))))))

(defn existe?
  "Leitura ESTREITA (nem :nome, nem :cpf — so' um booleano) — usada pelo guard `identidade-existe?`
  default de `rotas.clj`, que backa `PATCH /cadastros/vereadores/:id/identidade` e so' precisa saber SE
  a identidade existe, nada mais (review Task 12 IMPORTANT: o guard reusava `identidade-por-id`, que
  materializa CPF+nome e descarta os dois — o mesmo anti-padrao que `nome-por-id` foi criado pra
  eliminar POR CONSTRUCAO no caminho vizinho do Keycloak; `existe?` faz o mesmo aqui, `SELECT 1 ... LIMIT
  1`, nunca traz a linha inteira pra memoria)."
  [conn id]
  (boolean
   (jdbc/execute-one! conn
     (sql/format {:select [1] :from [:identidade.identidade] :where [:= :id id] :limit 1}))))

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
