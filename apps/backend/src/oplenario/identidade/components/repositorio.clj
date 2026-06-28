(ns oplenario.identidade.components.repositorio
  "Component de PERSISTENCIA do identidade — o banco DISPONIBILIZADO como Stuart Sierra Component (ADR-0001
  §3, revisao). RepoIdentidade expoe as ACOES; RepoIdentidadePg segura o `:datasource` (via `using`); o
  `db/` e' a IMPL. SUPRATENANT (identidade/CPF, broker) roda sobre o `:ds` direto (pool herda o role
  id_resolver); TENANT (vinculo/papel/consentimento) roda via `com-tenant*`. `transacao` compoe acoes
  tenant numa unica tx (ex.: resolver sessao = vinculos + papeis no mesmo snapshot)."
  (:require [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.tenancy :as tenancy]))

(defprotocol RepoIdentidade
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant.")
  ;; SUPRATENANT (sobre o :ds; role id_resolver)
  (criar-identidade! [this identidade] "CPF -> id canonico (idempotente).")
  (identidade-por-cpf [this cpf])
  (identidade-por-id [this id])
  (vincular-externa! [this vinculo-externo] "Liga sub gov.br -> identidade (anti-takeover).")
  (identidade-por-sub [this provedor sub])
  ;; TENANT (com-tenant*)
  (criar-vinculo! [this ente-id vinculo])
  (vinculos-de [this ente-id identidade-id])
  (mudar-estado-vinculo! [this ente-id id estado])
  (adicionar-papel! [this ente-id papel])
  (papeis-de [this ente-id identidade-id])
  (registrar-consentimento! [this ente-id consentimento])
  (revogar-consentimento! [this ente-id id])
  (consentimentos-ativos [this ente-id identidade-id]))

(defrecord RepoIdentidadePg [datasource]
  RepoIdentidade
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  ;; supratenant
  (criar-identidade! [_ identidade] (id/inserir! (:ds datasource) identidade))
  (identidade-por-cpf [_ cpf] (id/por-cpf (:ds datasource) cpf))
  (identidade-por-id [_ id] (id/por-id (:ds datasource) id))
  (vincular-externa! [_ ve] (id/vincular-externa! (:ds datasource) ve))
  (identidade-por-sub [_ provedor sub] (id/identidade-por-sub (:ds datasource) provedor sub))
  ;; tenant
  (criar-vinculo! [this ente-id v] (transacao this ente-id #(vinc/criar! % v)))
  (vinculos-de [this ente-id ident] (transacao this ente-id #(vinc/vinculos-de % ident)))
  (mudar-estado-vinculo! [this ente-id id estado] (transacao this ente-id #(vinc/mudar-estado! % id estado)))
  (adicionar-papel! [this ente-id p] (transacao this ente-id #(vinc/adicionar-papel! % p)))
  (papeis-de [this ente-id ident] (transacao this ente-id #(vinc/papeis-de % ident)))
  (registrar-consentimento! [this ente-id c] (transacao this ente-id #(vinc/registrar-consentimento! % c)))
  (revogar-consentimento! [this ente-id id] (transacao this ente-id #(vinc/revogar-consentimento! % id)))
  (consentimentos-ativos [this ente-id ident] (transacao this ente-id #(vinc/consentimentos-ativos % ident))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoIdentidadePg nil))
