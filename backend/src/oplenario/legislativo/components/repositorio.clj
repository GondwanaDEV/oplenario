(ns oplenario.legislativo.components.repositorio
  "Component de PERSISTENCIA do legislativo — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoLegislativo expoe as ACOES (tenant-aware: trata `com-tenant*` por
  dentro); o record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE
  Component, nunca do db/ direto. `transacao` compoe varias acoes numa UNICA tx do tenant."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as proposicao]))

(defprotocol RepoLegislativo
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  (protocolar! [this ente-id proposicao] "Gate eixo H: numera (gapless) + URN + insere, atomico.")
  (buscar-proposicao [this ente-id id])
  (listar-por-estado [this ente-id estado])
  (mudar-estado-proposicao! [this ente-id m]))

(defrecord RepoLegislativoPg [datasource]
  RepoLegislativo
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (protocolar! [this ente-id p] (transacao this ente-id #(proposicao/protocolar! % p)))
  (buscar-proposicao [this ente-id id] (transacao this ente-id #(proposicao/buscar % id)))
  (listar-por-estado [this ente-id estado] (transacao this ente-id #(proposicao/listar-por-estado % estado)))
  (mudar-estado-proposicao! [this ente-id m] (transacao this ente-id #(proposicao/mudar-estado! % m))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoLegislativoPg nil))
