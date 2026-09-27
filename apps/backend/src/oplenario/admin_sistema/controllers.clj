(ns oplenario.admin-sistema.controllers
  "Orquestracao do `admin_sistema` (ADR-0016). O ciclo de vida do OPERADOR e' separado do das Casas (§22.5.1):
  entra por convite (linha de comando — o primeiro operador nao tem console para se convidar) e sai desligado
  (sessoes do console e do realm derrubadas). Tudo fica na atuacao."
  (:require [clojure.string :as str]
            [oplenario.admin-sistema.components.idp-admin :as idp]
            [oplenario.admin-sistema.components.repositorio :as repo]))

(defn- validar-operador! [{:keys [email nome]}]
  (when-not (and (string? email) (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" (str/trim email)))
    (throw (ex-info "e-mail do operador invalido" {:tipo :validacao/invalido :campo :email})))
  (when (str/blank? nome)
    (throw (ex-info "nome do operador obrigatorio" {:tipo :validacao/invalido :campo :nome}))))

(defn convidar-operador!
  "Cadastra o operador (idempotente pelo e-mail), garante o realm, cria o usuario e manda o e-mail do Keycloak para
  definir a senha e registrar a chave fisica. `por` = quem convidou (operador-id, ou nil pela linha de comando)."
  [repo-op idp-op {:keys [email nome por]}]
  (validar-operador! {:email email :nome nome})
  (idp/provisionar-realm-operacao! idp-op)
  (let [o (repo/criar-operador! repo-op {:id (random-uuid) :email email :nome nome})]
    (when-not (= "ativo" (:estado o))
      (throw (ex-info "operador desligado nao volta por convite" {:tipo :conflito/operador-desligado})))
    (idp/criar-operador-no-idp! idp-op {:operador-id (:id o) :email (:email o) :nome (:nome o)})
    (idp/convidar-operador! idp-op (:id o))
    (repo/registrar-atuacao! repo-op {:operador-id por :acao "operador-convidado"
                                      :detalhe {:operador (str (:id o)) :email (:email o)}})
    o))

(defn desligar-operador!
  [repo-op idp-op {:keys [email por]}]
  (if-let [o (repo/operador-por-email repo-op email)]
    (let [d (repo/desligar-operador! repo-op (:id o))]
      (idp/desligar-operador-no-idp! idp-op (:id o))
      (repo/registrar-atuacao! repo-op {:operador-id por :acao "operador-desligado"
                                        :detalhe {:operador (str (:id o)) :email (:email o)}})
      d)
    (throw (ex-info "operador nao encontrado" {:tipo :nao-encontrado :email email}))))
