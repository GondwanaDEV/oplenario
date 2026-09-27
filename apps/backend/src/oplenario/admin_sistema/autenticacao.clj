(ns oplenario.admin-sistema.autenticacao
  "Quem e' o operador desta request (ADR-0016). O ator do console NAO tem Casa (`:ente-id` ausente) e so' vale nas
  rotas do `admin_sistema`; a checagem de esfera (`kernel/autorizacao`) e' feita aqui, na borda. Operador
  desligado nao entra, mesmo com sessao ou token ainda no prazo (a autorizacao e' viva, como a da Casa)."
  (:require [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.kernel.autorizacao :as authz]))

(defn ator-do-operador
  "operador-id -> ator supratenant, ou nil (inexistente / desligado)."
  [repo operador-id]
  (when-let [o (and operador-id (repo/operador-por-id repo operador-id))]
    (when (= "ativo" (:estado o))
      (authz/checar-esfera! {:operador-id (:id o) :nome (:nome o) :email (:email o) :papeis (:papeis o)}
                            :supratenant))))
