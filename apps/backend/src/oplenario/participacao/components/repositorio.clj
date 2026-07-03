(ns oplenario.participacao.components.repositorio
  "Component de PERSISTENCIA do participacao — banco DISPONIBILIZADO como Stuart Sierra Component (ADR-0001
  §3). O protocolo RepoParticipacao expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o record
  segura o :datasource + o :bus (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca
  do db/ direto. `transacao` compoe varias acoes numa UNICA tx do tenant.

  Decisao Arch B (F6): o timer do e-SIC (prazo_ativo) vive NESTE schema — `protocolar-pedido!` materializa o
  pedido + o prazo na MESMA tx do recibo (o relogio LAI comeca atomico com o protocolo), e emite o evento no
  outbox na mesma tx (§22.9 E2). Sem cross-schema, sem HTTP cross-modulo (§22.10)."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.participacao.db.pedido-esic :as db-pedido]
            [oplenario.participacao.db.prazo-ativo :as db-prazo]
            [oplenario.participacao.diplomat.producers :as producers]))

(set! *warn-on-reflection* true)

(defprotocol RepoParticipacao
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  (protocolar-pedido! [this ente-id m]
    "UMA tx: sequencial gapless + INSERT pedido_esic + INSERT prazo_ativo pendente (vence-em) + emit
     `pedido_esic.protocolado` (outbox, mesma tx). `m` = {:id :ano :assunto :descricao
     :solicitante-identidade-id :recibo-em :vence-em :prazo-id :base-dias :prazo-fonte-ref :created-by}.
     Devolve {:id :protocolo :recibo-em}.")
  (buscar-pedido [this ente-id id])
  (pedido-com-prazo [this ente-id id]
    "Pedido por id + o prazo do objeto (in-schema), numa tx. Devolve {:pedido :prazo} ou nil (inexistente).")
  (acompanhar-por-protocolo [this ente-id protocolo]
    "Pedido por protocolo (chave publica) + o prazo do objeto, numa tx. Devolve {:pedido :prazo} ou nil.")
  (pedidos-do-solicitante [this ente-id solicitante-id]
    "'Meus pedidos' — lista os pedidos de um solicitante autenticado (mais recentes primeiro).")
  (prazo-do-objeto [this ente-id objeto-tipo objeto-id]
    "O 'anel do prazo': leitura single-row do prazo de um objeto."))

(defrecord RepoParticipacaoPg [datasource bus]
  RepoParticipacao
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (protocolar-pedido! [this ente-id {:keys [id ano assunto descricao solicitante-identidade-id
                                            recibo-em vence-em prazo-id base-dias prazo-fonte-ref created-by]}]
    (transacao this ente-id
      (fn [tx]
        (let [pedido (db-pedido/protocolar! tx {:id id :ente-id ente-id :ano ano :assunto assunto
                                                :descricao descricao
                                                :solicitante-identidade-id solicitante-identidade-id
                                                :recibo-em recibo-em :created-by created-by})]
          ;; o RELOGIO comeca atomico com o protocolo (Arch B): prazo pendente sobre o pedido, mesma tx.
          (db-prazo/inserir! tx {:id prazo-id :ente-id ente-id :objeto-tipo "pedido_esic" :objeto-id id
                                 :vence-em vence-em :estado "pendente" :base-dias base-dias
                                 :prazo-fonte-ref prazo-fonte-ref :created-by created-by})
          ;; evento no outbox na MESMA tx (§22.9 E2): so existe se a tx commitou. Instantes/datas -> ISO string.
          (producers/emitir-pedido-protocolado! bus tx ente-id
            {:pedido-id id :protocolo (:protocolo pedido)
             :recibo-em (str recibo-em) :vence-em (str vence-em)})
          {:id id :protocolo (:protocolo pedido) :recibo-em recibo-em}))))
  (buscar-pedido [this ente-id id] (transacao this ente-id #(db-pedido/buscar % ente-id id)))
  (pedido-com-prazo [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [pedido (db-pedido/buscar tx ente-id id)]
          {:pedido pedido :prazo (db-prazo/buscar-do-objeto tx ente-id "pedido_esic" id)}))))
  (acompanhar-por-protocolo [this ente-id protocolo]
    (transacao this ente-id
      (fn [tx]
        (when-let [pedido (db-pedido/por-protocolo tx ente-id protocolo)]
          {:pedido pedido :prazo (db-prazo/buscar-do-objeto tx ente-id "pedido_esic" (:id pedido))}))))
  (pedidos-do-solicitante [this ente-id solicitante-id]
    (transacao this ente-id #(db-pedido/listar-por-solicitante % ente-id solicitante-id)))
  (prazo-do-objeto [this ente-id objeto-tipo objeto-id]
    (transacao this ente-id #(db-prazo/buscar-do-objeto % ente-id objeto-tipo objeto-id))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource + :bus via `using`)."
  []
  (->RepoParticipacaoPg nil nil))
