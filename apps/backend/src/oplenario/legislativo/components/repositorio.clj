(ns oplenario.legislativo.components.repositorio
  "Component de PERSISTENCIA do legislativo — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoLegislativo expoe as ACOES (tenant-aware: trata `com-tenant*` por
  dentro); o record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE
  Component, nunca do db/ direto. `transacao` compoe varias acoes numa UNICA tx do tenant."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.emenda :as emenda]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.diplomat.producers :as producers]))

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
  (historico-da-proposicao [this ente-id proposicao-id])
  ;; eixo D — emendas
  (criar-emenda! [this ente-id emenda] "Numera local por mae + insere ('apresentada').")
  (buscar-emenda [this ente-id id])
  (emendas-da-proposicao [this ente-id proposicao-mae-id])
  (mudar-estado-emenda! [this ente-id m] "Ciclo enum simples; CAS + trava terminal.")
  (aprovar-emenda! [this ente-id m] "Aplica ao texto-mae: cria rascunho + fecha ciclo, 1 tx."))

(defrecord RepoLegislativoPg [datasource bus]
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
  ;; eixo C / F3.3b: ENGINE + emissao do evento de dominio na MESMA tx do tenant (atomicidade
  ;; outbox-com-o-ato §22.9 E2 — o `proposicao.transicionou` so existe se a transicao commitou; guard
  ;; que bloqueia = sem transicao = sem evento). E' o Repo (composer de tx) quem casa ato+emissao.
  (transicionar! [this ente-id registro args]
    (transacao this ente-id
      (fn [tx]
        (let [r (tram/transicionar! tx (assoc args :registro registro :ente-id ente-id))]
          (when (:transicionou? r)
            (producers/emitir-transicionou! bus tx ente-id
              ;; :ator-id so entra quando ha ator (acao anonima omite a chave — contrato {:optional true})
              (cond-> {:proposicao-id (:proposicao-id args) :template-id (:template-id args)
                       :de (:de r) :para (:para r) :gatilho (:gatilho args)
                       :transicao-id (:transicao-id r)}
                (:ator-id args) (assoc :ator-id (:ator-id args)))))
          r))))
  (historico-da-proposicao [this ente-id pid] (transacao this ente-id #(tram/historico-da-proposicao % ente-id pid)))
  ;; eixo D / F3.4 — emendas. aprovar! compoe (nova-versao rascunho + muda estado) numa UNICA tx do tenant.
  (criar-emenda! [this ente-id e] (transacao this ente-id #(emenda/criar! % (assoc e :ente-id ente-id))))
  (buscar-emenda [this ente-id id] (transacao this ente-id #(emenda/buscar % ente-id id)))
  (emendas-da-proposicao [this ente-id pid] (transacao this ente-id #(emenda/listar-por-mae % ente-id pid)))
  (mudar-estado-emenda! [this ente-id m] (transacao this ente-id #(emenda/mudar-estado! % (assoc m :ente-id ente-id))))
  (aprovar-emenda! [this ente-id m] (transacao this ente-id #(emenda/aprovar! % (assoc m :ente-id ente-id)))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoLegislativoPg nil nil))
