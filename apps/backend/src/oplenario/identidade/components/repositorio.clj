(ns oplenario.identidade.components.repositorio
  "Component de PERSISTENCIA do identidade — o banco DISPONIBILIZADO como Stuart Sierra Component (ADR-0001
  §3, revisao). RepoIdentidade expoe as ACOES; RepoIdentidadePg segura o `:datasource` (via `using`); o
  `db/` e' a IMPL. SUPRATENANT (identidade/CPF, broker, sessao opaca) roda sobre o `:ds` direto (pool herda
  o role id_resolver); TENANT (vinculo/papel/consentimento) roda via `com-tenant*`. `transacao` compoe acoes
  tenant numa unica tx (ex.: snapshot de ator = vinculos + papeis no mesmo snapshot).

  `sessao-janela-ociosa-seg` (2o campo do record, default 1800s/30min via a aridade-0 de `repositorio`) e'
  a janela de deslize de `resolver-sessao-por-segredo` — existe como campo do record (nao arg extra do
  protocolo) para manter o metodo 2-arg, do qual o interceptor de auth (Task 4) depende. CARRY: reconciliar
  este default com a config `:sessao :ociosa-min` quando o mint (Task 4) existir — hoje sao DUAS fontes
  do mesmo numero (o mint crava o `ocioso-ate` inicial; este campo crava o deslize) que precisam concordar."
  (:require [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.sessao :as sess]
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
  (criar-sessao! [this sessao] "Sessao opaca de login (custodia BFF): gera+INSERT o hash, devolve o SEGREDO CRU.")
  (resolver-sessao-por-segredo [this segredo] "segredo -> {:identidade-id :ente-id} | nil; desliza ocioso_ate em acerto.")
  (apagar-sessao! [this segredo] "DELETE por hash (logout), idempotente.")
  ;; TENANT (com-tenant*)
  (criar-vinculo! [this ente-id vinculo])
  (vinculos-de [this ente-id identidade-id])
  (mudar-estado-vinculo! [this ente-id id estado])
  (adicionar-papel! [this ente-id papel])
  (papeis-de [this ente-id identidade-id])
  (registrar-consentimento! [this ente-id consentimento])
  (revogar-consentimento! [this ente-id id])
  (consentimentos-ativos [this ente-id identidade-id])
  (snapshot-ator [this ente-id identidade-id]
    "Snapshot de SESSAO numa UNICA tx (vinculo ATIVO + papeis). Devolve {:vinculo-ativo :papeis} ou nil
    se nao ha vinculo ativo. Composto AQUI (§3-bis) p/ resolver-sessao nao importar db/ direto."))

(defrecord RepoIdentidadePg [datasource sessao-janela-ociosa-seg]
  RepoIdentidade
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  ;; supratenant
  (criar-identidade! [_ identidade] (id/inserir! (:ds datasource) identidade))
  (identidade-por-cpf [_ cpf] (id/por-cpf (:ds datasource) cpf))
  (identidade-por-id [_ id] (id/por-id (:ds datasource) id))
  (vincular-externa! [_ ve] (id/vincular-externa! (:ds datasource) ve))
  (identidade-por-sub [_ provedor sub] (id/identidade-por-sub (:ds datasource) provedor sub))
  (criar-sessao! [_ sessao] (sess/inserir! (:ds datasource) sessao))
  (resolver-sessao-por-segredo [_ segredo] (sess/resolver! (:ds datasource) segredo sessao-janela-ociosa-seg))
  (apagar-sessao! [_ segredo] (sess/apagar! (:ds datasource) segredo))
  ;; tenant
  (criar-vinculo! [this ente-id v] (transacao this ente-id #(vinc/criar! % v)))
  (vinculos-de [this ente-id ident] (transacao this ente-id #(vinc/vinculos-de % ente-id ident)))
  (mudar-estado-vinculo! [this ente-id id estado] (transacao this ente-id #(vinc/mudar-estado! % id estado)))
  (adicionar-papel! [this ente-id p] (transacao this ente-id #(vinc/adicionar-papel! % p)))
  (papeis-de [this ente-id ident] (transacao this ente-id #(vinc/papeis-de % ente-id ident)))
  (registrar-consentimento! [this ente-id c] (transacao this ente-id #(vinc/registrar-consentimento! % c)))
  (revogar-consentimento! [this ente-id id] (transacao this ente-id #(vinc/revogar-consentimento! % id)))
  (consentimentos-ativos [this ente-id ident] (transacao this ente-id #(vinc/consentimentos-ativos % ente-id ident)))
  (snapshot-ator [this ente-id identidade-id]
    (transacao this ente-id
      (fn [tx]
        (when-let [ativo (->> (vinc/vinculos-de tx ente-id identidade-id)
                              (filter #(= "ativo" (:estado %)))
                              first)]
          {:vinculo-ativo ativo :papeis (vinc/papeis-de tx ente-id identidade-id)})))))

(defn repositorio
  "Cria o Component (recebe :datasource via `using`). Aridade-1 seta a janela de deslize de ociosidade da
  sessao (segundos) — `sistema/montar` injeta `(* 60 (:sessao :ociosa-min config))`, mesma fonte que o mint
  usa p/ o ocioso-ate inicial (fonte unica). Aridade-0 default 1800s/30min = fallback só p/ testes que
  constroem o repo direto sem config."
  ([] (repositorio 1800))
  ([sessao-janela-ociosa-seg] (->RepoIdentidadePg nil sessao-janela-ociosa-seg)))
