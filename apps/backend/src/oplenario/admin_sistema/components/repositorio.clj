(ns oplenario.admin-sistema.components.repositorio
  "Component de PERSISTENCIA do `admin_sistema` (ADR-0016). SUPRATENANT: tudo roda sobre o `:ds` direto (o pool
  herda o role oplenario_operacao), nunca via com-tenant* — nao ha' Casa aqui, este modulo e' quem as emite.
  `transacao` agrupa varias acoes numa tx so' (ex.: provisionar + registrar a atuacao)."
  (:require [next.jdbc :as jdbc]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.admin-sistema.db.operador :as op]))

(defprotocol RepoAdminSistema
  (transacao [this f] "Roda (f tx) numa tx supratenant.")
  (operador-por-id [this id])
  (operador-por-email [this email])
  (criar-operador! [this operador] "{:id :email :nome} -> o operador; e-mail ja' cadastrado devolve o existente.")
  (desligar-operador! [this id] "Estado 'desligado' + derruba as sessoes do console, numa tx.")
  (criar-sessao-operador! [this sessao] "{:operador-id :expira-em :ocioso-ate} -> segredo CRU.")
  (resolver-sessao-operador [this segredo] "segredo -> {:operador-id} | nil; desliza a ociosidade.")
  (apagar-sessao-operador! [this segredo])
  (registrar-atuacao! [this registro] "{:operador-id :ente-id :acao :detalhe} -> registro selado.")
  (atuacao-do-ente [this ente-id limite]))

(defrecord RepoAdminSistemaPg [datasource sessao-janela-ociosa-seg]
  RepoAdminSistema
  (transacao [_ f] (jdbc/with-transaction [tx (:ds datasource)] (f tx)))
  (operador-por-id [_ id] (op/por-id (:ds datasource) id))
  (operador-por-email [_ email] (op/por-email (:ds datasource) email))
  (criar-operador! [this o] (transacao this #(op/inserir! % o)))
  (desligar-operador! [this id] (transacao this #(op/desligar! % id)))
  (criar-sessao-operador! [_ s] (op/inserir-sessao! (:ds datasource) s))
  (resolver-sessao-operador [_ segredo] (op/resolver-sessao! (:ds datasource) segredo sessao-janela-ociosa-seg))
  (apagar-sessao-operador! [_ segredo] (op/apagar-sessao! (:ds datasource) segredo))
  (registrar-atuacao! [this r] (transacao this #(atuacao/registrar! % r)))
  (atuacao-do-ente [_ ente-id limite] (atuacao/do-ente (:ds datasource) ente-id limite)))

(defn repositorio
  "Aridade-1 recebe a janela de ociosidade da sessao do console (segundos) — a mesma fonte que o mint usa
  (`:operacao :sessao :ociosa-min`). Aridade-0 (testes) = 15 min."
  ([] (repositorio 900))
  ([janela-ociosa-seg] (->RepoAdminSistemaPg nil janela-ociosa-seg)))
