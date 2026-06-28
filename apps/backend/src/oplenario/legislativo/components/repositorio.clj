(ns oplenario.legislativo.components.repositorio
  "Component de PERSISTENCIA do legislativo — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoLegislativo expoe as ACOES (tenant-aware: trata `com-tenant*` por
  dentro); o record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE
  Component, nunca do db/ direto. `transacao` compoe varias acoes numa UNICA tx do tenant."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.apensacao :as apensacao]
            [oplenario.legislativo.db.emenda :as emenda]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.db.parecer-texto-versao :as parecer-texto]
            [oplenario.legislativo.db.parecer-tramitacao :as parecer-tram]
            [oplenario.legislativo.db.parecer-voto-divergente :as parecer-voto]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.db.votacao :as votacao]
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
  (aprovar-emenda! [this ente-id m] "Aplica ao texto-mae: cria rascunho + fecha ciclo, 1 tx.")
  ;; eixo E — apensacao (associacao com historico)
  (apensar! [this ente-id m] "Apensa apensada->principal (ativa). UNIQUE-ativa + CHECK reflexivo barram.")
  (desapensar! [this ente-id m] "UPDATE em desapensada_em (NAO DELETE); CAS + so a ativa desapensa.")
  (buscar-apensacao [this ente-id id])
  (apensadas-ativas [this ente-id principal-id] "Apensadas ativas diretas (nivel 1).")
  (cadeia-apensacao [this ente-id principal-id] "Cadeia genuina (traversal recursivo, cycle-safe).")
  ;; eixo F — parecer_comissao (state machine propria governada pelo motor do eixo C)
  (iniciar-parecer! [this ente-id m] "Cria parecer: estado inicial do template + valida sujeito/objeto.")
  (buscar-parecer [this ente-id id])
  (pareceres-do-objeto [this ente-id objeto-tipo objeto-id] "Pareceres sobre proposicao|emenda (disc.2).")
  (designar-relator! [this ente-id m] "Designa o relator (CAS).")
  (transicionar-parecer! [this ente-id registro args] "Engine do parecer + emite parecer.transicionou, 1 tx.")
  (historico-do-parecer [this ente-id parecer-id])
  ;; eixo F / F3.6b — texto do parecer (eixo B aplicado) + votos divergentes (aux append-only)
  (nova-versao-parecer! [this ente-id versao] "Cria versao 'rascunho' do texto do parecer (append-only).")
  (promover-versao-parecer! [this ente-id m] "Promove rascunho->vigente + reaponta o pointer, 1 tx.")
  (buscar-versao-parecer [this ente-id id])
  (versoes-do-parecer [this ente-id parecer-id])
  (texto-vigente-parecer [this ente-id parecer-id])
  (registrar-voto-divergente! [this ente-id m] "Registra voto vencido (append-only puro).")
  (votos-divergentes-do-parecer [this ente-id parecer-id])
  ;; eixo G — votacao (eventos Votacao*/real-time = carry F4)
  (abrir-votacao! [this ente-id m] "Abre votacao 'aberta' sobre objeto polimorfico.")
  (registrar-voto! [this ente-id m] "Voto nominal atribuido (append-only; UNIQUE por vereador).")
  (registrar-voto-secreto! [this ente-id m] "Voto secreto anonimo (sem vereador_id).")
  (encerrar-votacao! [this ente-id m] "Apura + computa resultado (quorum exato) + grava snapshot, CAS.")
  (anular-votacao! [this ente-id m] "Leva a 'anulada' (correcao = nova votacao).")
  (buscar-votacao [this ente-id id])
  (votos-da-votacao [this ente-id votacao-id]))

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
  (aprovar-emenda! [this ente-id m] (transacao this ente-id #(emenda/aprovar! % (assoc m :ente-id ente-id))))
  ;; eixo E / F3.5 — apensacao. Desapensacao = UPDATE (fato persiste); mudanca de principal = 2 atos.
  (apensar! [this ente-id m] (transacao this ente-id #(apensacao/apensar! % (assoc m :ente-id ente-id))))
  (desapensar! [this ente-id m] (transacao this ente-id #(apensacao/desapensar! % (assoc m :ente-id ente-id))))
  (buscar-apensacao [this ente-id id] (transacao this ente-id #(apensacao/buscar % ente-id id)))
  (apensadas-ativas [this ente-id pid] (transacao this ente-id #(apensacao/apensadas-ativas % ente-id pid)))
  (cadeia-apensacao [this ente-id pid] (transacao this ente-id #(apensacao/cadeia % ente-id pid)))
  ;; eixo F / F3.6a — parecer. transicionar-parecer! compoe ENGINE + emissao do evento na MESMA tx do
  ;; tenant (atomicidade outbox-com-o-ato §22.9 E2; espelha transicionar! da proposicao). O payload carrega
  ;; objeto_tipo/objeto_id (do retorno do engine) p/ o consumer da mae em F3.6c.
  (iniciar-parecer! [this ente-id m] (transacao this ente-id #(parecer/criar! % (assoc m :ente-id ente-id))))
  (buscar-parecer [this ente-id id] (transacao this ente-id #(parecer/buscar % ente-id id)))
  (pareceres-do-objeto [this ente-id ot oid] (transacao this ente-id #(parecer/listar-por-objeto % ente-id ot oid)))
  (designar-relator! [this ente-id m] (transacao this ente-id #(parecer/designar-relator! % (assoc m :ente-id ente-id))))
  (transicionar-parecer! [this ente-id registro args]
    (transacao this ente-id
      (fn [tx]
        (let [r (parecer-tram/transicionar-parecer! tx (assoc args :registro registro :ente-id ente-id))]
          (when (:transicionou? r)
            (producers/emitir-transicionou-parecer! bus tx ente-id
              (cond-> {:parecer-id (:parecer-id args) :template-id (:template-id args)
                       :objeto-tipo (:objeto-tipo r) :objeto-id (:objeto-id r)
                       :de (:de r) :para (:para r) :gatilho (:gatilho args)
                       :transicao-id (:transicao-id r)}
                (:ator-id args) (assoc :ator-id (:ator-id args)))))
          r))))
  (historico-do-parecer [this ente-id pid] (transacao this ente-id #(parecer-tram/historico-do-parecer % ente-id pid)))
  ;; eixo F / F3.6b — texto + votos divergentes. promover! compoe (supersede + vigente + reaponta pointer) 1 tx.
  (nova-versao-parecer! [this ente-id v] (transacao this ente-id #(parecer-texto/nova-versao! % (assoc v :ente-id ente-id))))
  (promover-versao-parecer! [this ente-id m] (transacao this ente-id #(parecer-texto/promover! % (assoc m :ente-id ente-id))))
  (buscar-versao-parecer [this ente-id id] (transacao this ente-id #(parecer-texto/buscar % ente-id id)))
  (versoes-do-parecer [this ente-id pid] (transacao this ente-id #(parecer-texto/versoes-do-parecer % ente-id pid)))
  (texto-vigente-parecer [this ente-id pid] (transacao this ente-id #(parecer-texto/vigente % ente-id pid)))
  (registrar-voto-divergente! [this ente-id m] (transacao this ente-id #(parecer-voto/registrar! % (assoc m :ente-id ente-id))))
  (votos-divergentes-do-parecer [this ente-id pid] (transacao this ente-id #(parecer-voto/listar-por-parecer % ente-id pid)))
  ;; eixo G / F3.7 — votacao. Sem emissao de evento aqui (Votacao*/real-time = carry F4).
  (abrir-votacao! [this ente-id m] (transacao this ente-id #(votacao/abrir! % (assoc m :ente-id ente-id))))
  (registrar-voto! [this ente-id m] (transacao this ente-id #(votacao/registrar-voto! % (assoc m :ente-id ente-id))))
  (registrar-voto-secreto! [this ente-id m] (transacao this ente-id #(votacao/registrar-voto-secreto! % (assoc m :ente-id ente-id))))
  (encerrar-votacao! [this ente-id m] (transacao this ente-id #(votacao/encerrar! % (assoc m :ente-id ente-id))))
  (anular-votacao! [this ente-id m] (transacao this ente-id #(votacao/anular! % (assoc m :ente-id ente-id))))
  (buscar-votacao [this ente-id id] (transacao this ente-id #(votacao/buscar % ente-id id)))
  (votos-da-votacao [this ente-id vid] (transacao this ente-id #(votacao/votos-da-votacao % ente-id vid))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoLegislativoPg nil nil))
