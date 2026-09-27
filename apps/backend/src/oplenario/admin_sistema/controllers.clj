(ns oplenario.admin-sistema.controllers
  "Orquestracao do `admin_sistema` (ADR-0016). O ciclo de vida do OPERADOR e' separado do das Casas (§22.5.1):
  entra por convite (linha de comando — o primeiro operador nao tem console para se convidar) e sai desligado
  (sessoes do console e do realm derrubadas). Tudo fica na atuacao."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [oplenario.admin-sistema.components.idp-admin :as idp]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.kernel.components.idp :as idp-casa]))

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

;; ---------------------------------------------------------------------------------------------
;; Registro de Casas (12.1). O provisionamento e' HANDOFF, nao controle: a Operacao registra a Casa, entrega o
;; perfil ao cadastros, cria o 1o administrador e o convida; a Casa passa a ser dela quando ele entra
;; (consumidor `identidade.vinculo.primeiro_acesso`). Nenhum passo cruza modulo por import: o host injeta
;; `deps` (inversao de dependencia, §22.10) — `:garantir-perfil-da-casa!`, `:garantir-primeiro-admin!`,
;; `:nome-da-identidade` e o IdP das Casas (`:idp-casa`). Cada passo e' idempotente; o que falhar no Keycloak
;; fica visivel ('convite nao saiu') e se retoma por `reenviar-convite!`.
;; ---------------------------------------------------------------------------------------------

(defn- convidar-primeiro-admin! [repo-op {:keys [idp-casa]} ator ente-id identidade-id nome email reenvio?]
  (idp-casa/provisionar-realm! idp-casa ente-id)
  (idp-casa/criar-usuario! idp-casa ente-id {:identidade-id identidade-id :nome nome :email email})
  (idp-casa/convidar! idp-casa ente-id identidade-id)
  (repo/marcar-convite! repo-op ente-id (:operador-id ator) reenvio?))

(defn provisionar-casa!
  "Devolve {:casa <registro> :convite :enviado|:falhou}. A Casa fica registrada mesmo se o Keycloak falhar."
  [repo-op deps ator {:keys [admin] :as casa}]
  (let [ente-id (random-uuid)]
    (repo/registrar-casa! repo-op (-> casa (dissoc :admin) (assoc :ente-id ente-id :primeiro-admin-email (:email admin)))
                          ator)
    ((:garantir-perfil-da-casa! deps) ente-id casa)
    (let [iid ((:garantir-primeiro-admin! deps) ente-id admin)]
      (repo/marcar-primeiro-admin! repo-op ente-id iid)
      (let [convite (try (convidar-primeiro-admin! repo-op deps ator ente-id iid (:nome admin) (:email admin) false)
                         :enviado
                         (catch Exception e
                           (log/warn e "admin-sistema: convite do 1o administrador nao saiu" {:ente-id ente-id})
                           :falhou))]
        {:casa (repo/casa-por-id repo-op ente-id) :convite convite}))))

(defn- casa-ou-404! [repo-op ente-id]
  (or (and ente-id (repo/casa-por-id repo-op ente-id))
      (throw (ex-info "Casa nao encontrada" {:tipo :admin-sistema/nao-encontrada}))))

(defn reenviar-convite!
  "So' enquanto a Casa espera o 1o administrador. Retoma o que faltou (perfil, realm, usuario) e reenvia."
  [repo-op deps ator ente-id]
  (let [casa (casa-ou-404! repo-op ente-id)
        iid (:primeiro-admin-identidade-id casa)]
    (when-not (= "provisionar" (:estado casa))
      (throw (ex-info "a Casa ja' passou as maos dela — o acesso agora e' com o administrador da Casa"
                      {:tipo :admin-sistema/conflito})))
    (when-not iid
      (throw (ex-info "o 1o administrador nao chegou a ser criado — provisione de novo" {:tipo :admin-sistema/conflito})))
    ((:garantir-perfil-da-casa! deps) ente-id casa)
    (convidar-primeiro-admin! repo-op deps ator ente-id iid ((:nome-da-identidade deps) iid)
                              (:primeiro-admin-email casa) true)
    (repo/casa-por-id repo-op ente-id)))

(defn reprovisionar-realm!
  "Converge o realm da Casa com a config atual (ex.: gov.br ligado depois, ADR-0015). Idempotente."
  [repo-op {:keys [idp-casa]} ator ente-id]
  (casa-ou-404! repo-op ente-id)
  (idp-casa/provisionar-realm! idp-casa ente-id)
  (repo/registrar-atuacao! repo-op {:operador-id (:operador-id ator) :ente-id ente-id :acao "realm-reprovisionado"})
  (repo/casa-por-id repo-op ente-id))

(defn ficha-da-casa
  "A Casa + o 1o administrador (nome) + a atuacao da Operacao nela."
  [repo-op deps ente-id]
  (let [casa (casa-ou-404! repo-op ente-id)]
    {:casa casa
     :primeiro-admin (when-let [iid (:primeiro-admin-identidade-id casa)]
                       {:nome ((:nome-da-identidade deps) iid) :email (:primeiro-admin-email casa)})
     :atuacao (repo/atuacao-do-ente repo-op ente-id 50)}))
