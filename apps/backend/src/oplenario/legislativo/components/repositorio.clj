(ns oplenario.legislativo.components.repositorio
  "Component de PERSISTENCIA do legislativo — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoLegislativo expoe as ACOES (tenant-aware: trata `com-tenant*` por
  dentro); o record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE
  Component, nunca do db/ direto. `transacao` compoe varias acoes numa UNICA tx do tenant."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.tramitacao :as tram]))

(defprotocol RepoLegislativo
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  (protocolar! [this ente-id proposicao] "Gate eixo H: numera (gapless) + URN + insere, atomico.")
  (buscar-proposicao [this ente-id id])
  (listar-por-estado [this ente-id estado])
  (mudar-estado-proposicao! [this ente-id m])
  ;; eixo B — versionamento de texto
  (nova-versao! [this ente-id versao] "Cria versao 'rascunho' (conteudo append-only).")
  (promover-versao! [this ente-id m] "Promove rascunho->vigente (ato auditado; reaponta o pointer).")
  (buscar-versao [this ente-id id])
  (versoes-da-proposicao [this ente-id proposicao-id])
  (texto-vigente [this ente-id proposicao-id])
  ;; eixo C — tramitacao por motor declarativo
  (criar-template! [this ente-id template])
  (criar-estado! [this ente-id estado])
  (criar-transicao! [this ente-id transicao])
  (transicionar! [this ente-id registro args] "Engine: guard via motor + historico + muda estado, 1 tx.")
  (historico-da-proposicao [this ente-id proposicao-id]))

(defrecord RepoLegislativoPg [datasource]
  RepoLegislativo
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (protocolar! [this ente-id p] (transacao this ente-id #(proposicao/protocolar! % p)))
  (buscar-proposicao [this ente-id id] (transacao this ente-id #(proposicao/buscar % ente-id id)))
  (listar-por-estado [this ente-id estado] (transacao this ente-id #(proposicao/listar-por-estado % ente-id estado)))
  (mudar-estado-proposicao! [this ente-id m] (transacao this ente-id #(proposicao/mudar-estado! % (assoc m :ente-id ente-id))))
  (nova-versao! [this ente-id v] (transacao this ente-id #(texto/nova-versao! % (assoc v :ente-id ente-id))))
  (promover-versao! [this ente-id m] (transacao this ente-id #(texto/promover! % (assoc m :ente-id ente-id))))
  (buscar-versao [this ente-id id] (transacao this ente-id #(texto/buscar % ente-id id)))
  (versoes-da-proposicao [this ente-id pid] (transacao this ente-id #(texto/versoes-da-proposicao % ente-id pid)))
  (texto-vigente [this ente-id pid] (transacao this ente-id #(texto/vigente % ente-id pid)))
  (criar-template! [this ente-id t] (transacao this ente-id #(tram/criar-template! % (assoc t :ente-id ente-id))))
  (criar-estado! [this ente-id e] (transacao this ente-id #(tram/criar-estado! % (assoc e :ente-id ente-id))))
  (criar-transicao! [this ente-id tr] (transacao this ente-id #(tram/criar-transicao! % (assoc tr :ente-id ente-id))))
  (transicionar! [this ente-id registro args] (transacao this ente-id #(tram/transicionar! % (assoc args :registro registro :ente-id ente-id))))
  (historico-da-proposicao [this ente-id pid] (transacao this ente-id #(tram/historico-da-proposicao % ente-id pid))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoLegislativoPg nil))
