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
            [oplenario.participacao.db.recurso-esic :as db-recurso]
            [oplenario.participacao.db.resposta-esic :as db-resposta]
            [oplenario.participacao.diplomat.producers :as producers])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(defprotocol RepoParticipacao
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  (protocolar-pedido! [this ente-id m]
    "UMA tx: sequencial gapless + INSERT pedido_esic + INSERT prazo_ativo pendente (vence-em) + emit
     `pedido_esic.protocolado` (outbox, mesma tx). `m` = {:id :ano :assunto :descricao
     :solicitante-identidade-id :recibo-em :vence-em :prazo-id :base-dias :prazo-fonte-ref :created-by}.
     Devolve {:id :protocolo :recibo-em}.")
  (buscar-pedido [this ente-id id])
  (buscar-recurso [this ente-id id]
    "Recurso por id no tenant. Usado pela borda p/ desambiguar o nil de decidir-recurso! (existe -> 409 de
     ciclo; ausente -> 404).")
  (responder-pedido! [this ente-id m]
    "SERVIDOR — UMA tx: CAS pedido protocolado|em_analise -> respondido (nil = ja terminal, aborta sem escrever)
     + INSERT resposta_esic(pedido_id) (append-only) + cumpre o prazo do PEDIDO (prazo_ativo -> cumprida) + emit
     `pedido_esic.respondido` (outbox, mesma tx). `m` = {:pedido-id :resposta-id :corpo :respondido-por
     :respondida-em}. Devolve {:respondida-em :protocolo} ou nil (pedido ja terminal/inexistente).")
  (interpor-recurso! [this ente-id m]
    "CIDADAO — UMA tx: sequencial gapless 'recurso_esic:<ano>' + INSERT recurso_esic (instancia) + INSERT
     prazo_ativo(objeto_tipo=recurso_esic) pendente com vence_em PROPRIO (relogio independente do pedido) + emit
     `recurso_esic.protocolado`. A policy (dono + pedido recorrivel) e' guardada UPSTREAM no controller. `m` =
     {:recurso-id :pedido-id :ano :instancia :motivo :recibo-em :vence-em :prazo-id :base-dias :prazo-fonte-ref
     :created-by}. Devolve {:id :protocolo :recibo-em}.")
  (decidir-recurso! [this ente-id m]
    "SERVIDOR — UMA tx: CAS recurso protocolado -> decidido (+ carimba decidido_em; nil = ja decidido, aborta) +
     INSERT resposta_esic(recurso_id) (append-only) + cumpre o prazo do RECURSO (prazo_ativo -> cumprida) + emit
     `recurso_esic.decidido`. `m` = {:recurso-id :resposta-id :corpo :respondido-por :respondida-em :decidido-em}.
     Devolve {:decidido-em} ou nil (recurso ja decidido/inexistente).")
  (pedido-com-prazo [this ente-id id]
    "Pedido por id + o prazo do objeto (in-schema), numa tx. Devolve {:pedido :prazo} ou nil (inexistente).")
  (acompanhar-por-protocolo [this ente-id protocolo]
    "Pedido por protocolo (chave publica) + o prazo do objeto, numa tx. Devolve {:pedido :prazo} ou nil.")
  (pedidos-do-solicitante [this ente-id solicitante-id]
    "'Meus pedidos' — lista os pedidos de um solicitante autenticado (mais recentes primeiro).")
  (prazo-do-objeto [this ente-id objeto-tipo objeto-id]
    "O 'anel do prazo': leitura single-row do prazo de um objeto.")
  (varrer-vencimentos! [this ente-id hoje]
    "Sweep de vencimento (F6.3/§22.7.7 S1): numa UNICA tx do tenant, transiciona pendente->vencida os prazos
     abertos cujo `vence_em` passou estritamente em `hoje` (LocalDate) e emite `participacao.prazo.vencido`
     por transicao (o `acao_no_vencimento` V1) na MESMA tx. PURO por DATA — nao re-roda motor algum (o
     vencimento e' a unica transicao que evento nao dispara). CAS race-safe (`vencer-se-pendente!`): so
     emite se DE FATO transicionou (nil = cumprimento concorrente venceu a corrida -> sem evento espurio).
     Idempotente (a ja-vencida nao re-transiciona). RETORNO PARCIAL por passada: no maximo `teto-sweep` (1000)
     transicoes por chamada (guarda anti unbounded-read) — o scheduler deve RE-INVOCAR por ente ate a passada
     voltar VAZIA p/ drenar um backlog > 1000 (ex.: migracao de e-SIC legado; um cron ingenuo de 1 chamada/tick
     deixa o excedente 'no prazo' no painel por ate 1 tick). Job disparado por scheduler (INFRA, mesma pendencia
     do sweep do compliance — nao ha worker/cron aqui). Devolve [{:id :objeto-tipo :objeto-id :de :para}...]."))

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
  (buscar-recurso [this ente-id id] (transacao this ente-id #(db-recurso/buscar % ente-id id)))
  (responder-pedido! [this ente-id {:keys [pedido-id resposta-id corpo respondido-por respondida-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS PRIMEIRO (short-circuit): so escreve a resposta/cumpre o prazo se o pedido AINDA e' respondivel.
        ;; nil = ja terminal (respondido/indeferido) -> aborta sem inserir nada (a borda desambigua p/ 409).
        (when-let [pedido (db-pedido/responder! tx {:id pedido-id :ente-id ente-id})]
          (db-resposta/inserir! tx {:id resposta-id :ente-id ente-id :pedido-id pedido-id :recurso-id nil
                                    :corpo corpo :respondido-por respondido-por :respondida-em respondida-em})
          ;; fecha o relogio do PEDIDO (prazo_ativo pedido_esic -> cumprida), cumprida_em = instante do ato.
          ;; INVARIANTE (Inv.10): o pedido so vira terminal ATOMICO com o fechamento do seu prazo. Se o CAS de
          ;; cumprimento nao achou prazo ABERTO (pendente|vencida), ha inconsistencia (prazo orfao) — aborta a
          ;; tx (500 auditavel, tipo != :conflito -> nao 409) em vez de commitar 'respondido' com prazo preso.
          (when-not (db-prazo/cumprir! tx {:ente-id ente-id :objeto-tipo "pedido_esic" :objeto-id pedido-id
                                           :cumprida-em respondida-em})
            (throw (ex-info "prazo do pedido nao estava aberto ao cumprir (invariante de compliance)"
                            {:tipo :invariante/prazo-orfao :objeto-tipo "pedido_esic" :objeto-id pedido-id})))
          (producers/emitir-pedido-respondido! bus tx ente-id
            {:pedido-id pedido-id :protocolo (:protocolo pedido) :respondida-em (str respondida-em)})
          {:respondida-em respondida-em :protocolo (:protocolo pedido)}))))
  (interpor-recurso! [this ente-id {:keys [recurso-id pedido-id ano instancia motivo recibo-em vence-em
                                           prazo-id base-dias prazo-fonte-ref created-by]}]
    ;; IDEMPOTENCIA: a UNIQUE(ente, pedido, instancia) da mig 0040 barra o double-click/retry do cidadao (o
    ;; pedido terminal nao muta, entao a policy do controller nao detecta a 2a tentativa). A corrida perdida
    ;; vira 23505 -> :conflito/participacao (409, nao 500) — mesmo predicado 23505 do compliance/inserir-com-retry!.
    (try
      (transacao this ente-id
        (fn [tx]
          (let [recurso (db-recurso/inserir! tx {:id recurso-id :ente-id ente-id :pedido-id pedido-id :ano ano
                                                 :instancia instancia :motivo motivo :recibo-em recibo-em
                                                 :created-by created-by})]
            ;; o relogio PROPRIO do recurso comeca atomico com a interposicao: 2a linha de prazo_ativo, vence_em proprio.
            (db-prazo/inserir! tx {:id prazo-id :ente-id ente-id :objeto-tipo "recurso_esic" :objeto-id recurso-id
                                   :vence-em vence-em :estado "pendente" :base-dias base-dias
                                   :prazo-fonte-ref prazo-fonte-ref :created-by created-by})
            (producers/emitir-recurso-protocolado! bus tx ente-id
              {:recurso-id recurso-id :pedido-id pedido-id :protocolo (:protocolo recurso)
               :recibo-em (str recibo-em) :vence-em (str vence-em)})
            {:id recurso-id :protocolo (:protocolo recurso) :recibo-em recibo-em})))
      (catch PSQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "recurso ja interposto para este pedido/instancia"
                          {:tipo :conflito/participacao :pedido-id pedido-id :instancia instancia}))
          (throw e)))))
  (decidir-recurso! [this ente-id {:keys [recurso-id resposta-id corpo respondido-por respondida-em decidido-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS protocolado -> decidido, carimbando decidido_em (a CHECK recurso_decidido_coerente exige). nil =
        ;; ja decidido -> aborta sem escrever (a borda desambigua p/ 409). decidido_em = instante INJETADO (determinismo).
        (when-let [recurso (db-recurso/transicionar-estado! tx {:id recurso-id :ente-id ente-id
                                                                :de "protocolado" :para "decidido"
                                                                :extra {:decidido_em decidido-em}})]
          (db-resposta/inserir! tx {:id resposta-id :ente-id ente-id :pedido-id nil :recurso-id recurso-id
                                    :corpo corpo :respondido-por respondido-por :respondida-em respondida-em})
          ;; INVARIANTE (Inv.10, espelha responder-pedido!): decidir so vira terminal ATOMICO com o fechamento
          ;; do prazo do RECURSO. Sem prazo aberto = inconsistencia (prazo orfao) -> aborta a tx (500 auditavel).
          (when-not (db-prazo/cumprir! tx {:ente-id ente-id :objeto-tipo "recurso_esic" :objeto-id recurso-id
                                           :cumprida-em respondida-em})
            (throw (ex-info "prazo do recurso nao estava aberto ao cumprir (invariante de compliance)"
                            {:tipo :invariante/prazo-orfao :objeto-tipo "recurso_esic" :objeto-id recurso-id})))
          (producers/emitir-recurso-decidido! bus tx ente-id
            {:recurso-id recurso-id :decidido-em (str (:decidido-em recurso))})
          {:decidido-em (:decidido-em recurso)}))))
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
    (transacao this ente-id #(db-prazo/buscar-do-objeto % ente-id objeto-tipo objeto-id)))
  (varrer-vencimentos! [this ente-id hoje]
    (transacao this ente-id
      (fn [tx]
        ;; o SQL ja' devolve so as candidatas (pendente + estritamente overdue). Por candidata: CAS
        ;; `vencer-se-pendente!` — so emite/reporta se DE FATO transicionou (nil = corrida perdida p/ um
        ;; cumprimento concorrente -> sem evento espurio; idempotente na 2a passada). Emissao na MESMA tx
        ;; (outbox-com-o-ato, §22.9 E2). Espelha compliance/varrer-vencimentos! (sem a auditoria, que o
        ;; participacao nao tem — o `acao_no_vencimento` V1 e' so o evento). Datas -> ISO string no payload.
        (->> (db-prazo/pendentes-vencidas-ate tx ente-id hoje)
             (keep (fn [p]
                     (when (db-prazo/vencer-se-pendente! tx {:ente-id ente-id :id (:id p)})
                       (producers/emitir-prazo-vencido! bus tx ente-id
                         {:objeto-tipo (:objeto-tipo p) :objeto-id (:objeto-id p) :vence-em (str (:vence-em p))})
                       {:id (:id p) :objeto-tipo (:objeto-tipo p) :objeto-id (:objeto-id p)
                        :de "pendente" :para "vencida"})))   ; pendente->vencida: a unica transicao deste sweep
             vec)))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource + :bus via `using`)."
  []
  (->RepoParticipacaoPg nil nil))
