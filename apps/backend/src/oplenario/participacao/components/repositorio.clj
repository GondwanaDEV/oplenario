(ns oplenario.participacao.components.repositorio
  "Component de PERSISTENCIA do participacao — banco DISPONIBILIZADO como Stuart Sierra Component (ADR-0001
  §3). O protocolo RepoParticipacao expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o record
  segura o :datasource + o :bus (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca
  do db/ direto. `transacao` compoe varias acoes numa UNICA tx do tenant.

  Decisao Arch B (F6): o timer do e-SIC (prazo_ativo) vive NESTE schema — `protocolar-pedido!` materializa o
  pedido + o prazo na MESMA tx do recibo (o relogio LAI comeca atomico com o protocolo), e emite o evento no
  outbox na mesma tx (§22.9 E2). Sem cross-schema, sem HTTP cross-modulo (§22.10).

  FAST-FOLLOW Slice 5 (ouvidoria, Lei 13.460 art. 10): mesma forma — `protocolar-manifestacao!` materializa
  manifestacao+prazo atomico; `prorrogar-manifestacao!` compoe a CAS de `db/prazo-ativo/prorrogar!` (1x) + o
  registro append-only de `db/prorrogacao` + emit, na MESMA tx (aborta sem escrever/emitir se a CAS nao
  transicionou — a 2a tentativa nao deixa rastro espurio)."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.participacao.db.anexo :as db-anexo]
            [oplenario.participacao.db.atendimento :as db-atendimento]
            [oplenario.participacao.db.comentario :as db-comentario]
            [oplenario.participacao.db.complemento :as db-complemento]
            [oplenario.participacao.db.denuncia-comentario :as db-denuncia]
            [oplenario.participacao.db.encarregado :as db-encarregado]
            [oplenario.participacao.db.manifestacao-ouvidoria :as db-manifestacao]
            [oplenario.participacao.db.moderacao-comentario :as db-moderacao]
            [oplenario.participacao.db.pedido-esic :as db-pedido]
            [oplenario.participacao.db.prazo-ativo :as db-prazo]
            [oplenario.participacao.db.prorrogacao :as db-prorrogacao]
            [oplenario.participacao.db.recurso-esic :as db-recurso]
            [oplenario.participacao.db.resposta-esic :as db-resposta]
            [oplenario.participacao.db.resposta-ouvidoria :as db-resposta-ouvidoria]
            [oplenario.participacao.db.resposta-titular :as db-resposta-titular]
            [oplenario.participacao.logic.anexo :as logic-anexo]
            [oplenario.participacao.db.solicitacao-titular :as db-solicitacao]
            [oplenario.participacao.diplomat.producers :as producers]
            [oplenario.participacao.logic :as logic])
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
  (indeferir-pedido! [this ente-id m]
    "SERVIDOR — UMA tx: CAS pedido protocolado|em_analise -> indeferido (nil = ja terminal, aborta sem escrever) +
     INSERT resposta_esic(pedido_id) (append-only — a FUNDAMENTACAO e' a resposta, na mesma tabela do responder) +
     cumpre o prazo do PEDIDO (negar e' responder dentro do prazo) + emit `pedido_esic.indeferido` (outbox, mesma
     tx). `m` = {:pedido-id :resposta-id :fundamentacao :indeferido-por :indeferido-em}. Devolve {:indeferido-em
     :protocolo} ou nil (pedido ja terminal/inexistente).")
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
     deixa o excedente 'no prazo' no painel por ate 1 tick). Job disparado por scheduler — mas HOJE NAO HA
     scheduler nenhum chamando isto em producao (CONFIRMADO por grep, prova + severidade em
     `diplomat/consumers.clj`, frente 'truncamento-familia' sitio (a): nao e' o teto de 1000 que trunca,
     e' a INEXISTENCIA do job — prazos de LAI/LGPD vencidos hoje ficam 'pendente' para sempre ate' alguem
     chamar isto manualmente). Devolve [{:id :objeto-tipo :objeto-id :de :para}...].")
  ;; ---- Slice 4: LGPD — solicitacao do titular (contador SEPARADO) + Encarregado/DPO ----
  (solicitar-titular! [this ente-id m]
    "TITULAR — UMA tx: sequencial gapless 'solicitacao_titular:<ano>' + INSERT solicitacao_titular + INSERT
     prazo_ativo(objeto_tipo=solicitacao_titular) pendente com vence_em PROPRIO (CONTADOR SEPARADO do e-SIC) +
     emit `solicitacao_titular.protocolada` (outbox, mesma tx). `m` = {:id :ano :tipo :detalhe
     :titular-identidade-id :recibo-em :vence-em :prazo-id :base-dias :prazo-fonte-ref :created-by}. Devolve
     {:id :protocolo :recibo-em}.")
  (buscar-solicitacao-titular [this ente-id id]
    "Solicitacao do titular por id no tenant. Usada pela borda p/ desambiguar o nil de responder-solicitacao!
     (existe -> 409 de ciclo; ausente -> 404).")
  (solicitacao-titular-com-prazo [this ente-id id]
    "Solicitacao por id + o prazo do objeto (in-schema), numa tx. Devolve {:solicitacao :prazo} ou nil.")
  (responder-solicitacao! [this ente-id m]
    "SERVIDOR/Encarregado — UMA tx: CAS solicitacao protocolada|em_analise -> respondida (nil = ja terminal,
     aborta) + INSERT resposta_titular (append-only) + cumpre o prazo do TITULAR (prazo_ativo -> cumprida) +
     emit `solicitacao_titular.respondida`. `m` = {:solicitacao-id :resposta-id :corpo :respondido-por
     :respondida-em}. Devolve {:respondida-em} ou nil (solicitacao ja terminal/inexistente).")
  (indeferir-solicitacao! [this ente-id m]
    "SERVIDOR/Encarregado — UMA tx: CAS solicitacao protocolada|em_analise -> indeferida (nil = ja terminal, aborta) +
     INSERT resposta_titular (append-only — a FUNDAMENTACAO, LGPD art. 18 §4º) + cumpre o prazo do TITULAR + emit
     `solicitacao_titular.indeferida`. `m` = {:solicitacao-id :resposta-id :fundamentacao :indeferido-por
     :indeferida-em}. Devolve {:indeferida-em :protocolo} ou nil (solicitacao ja terminal/inexistente).")
  (definir-encarregado! [this ente-id m]
    "SERVIDOR — UPSERT do contato do Encarregado/DPO (1 por ente; ON CONFLICT ente_id). `m` = {:id :nome :rotulo
     :email :atualizado-por}. Devolve o mapa kebab da linha.")
  (buscar-encarregado [this ente-id]
    "O contato PUBLICO do Encarregado/DPO do ente (leitura da rota publica). Devolve o mapa kebab ou nil.")
  ;; ---- FAST-FOLLOW Slice 5: Ouvidoria (Lei 13.460 art. 10) ----
  (protocolar-manifestacao! [this ente-id m]
    "UMA tx: sequencial gapless + INSERT manifestacao_ouvidoria + INSERT prazo_ativo pendente (vence-em,
     objeto_tipo=manifestacao_ouvidoria) + emit `manifestacao_ouvidoria.protocolada`. ANONIMA nao e' sem-auth:
     `manifestante-identidade-id` chega nil quando o controller decidiu anonima?=true. `m` = {:id :ano :tipo
     :assunto :descricao :anonima :manifestante-identidade-id :recibo-em :vence-em :prazo-id :base-dias
     :prazo-fonte-ref :created-by}. Devolve {:id :protocolo :recibo-em}.")
  (buscar-manifestacao [this ente-id id]
    "Manifestacao por id no tenant. Usada pela borda p/ desambiguar o nil de responder!/arquivar! (existe ->
     409 de ciclo; ausente -> 404).")
  (manifestacao-com-prazo [this ente-id id]
    "Manifestacao por id + o prazo do objeto (in-schema), numa tx. Devolve {:manifestacao :prazo} ou nil.")
  (acompanhar-manifestacao-por-protocolo [this ente-id protocolo]
    "Manifestacao por protocolo (chave publica) + o prazo do objeto, numa tx. Devolve {:manifestacao :prazo}
     ou nil.")
  (responder-manifestacao! [this ente-id m]
    "SERVIDOR — UMA tx: CAS manifestacao protocolada|em_analise -> respondida (nil = ja terminal, aborta) +
     INSERT resposta_ouvidoria (append-only) + CUMPRE o prazo (prazo_ativo -> cumprida, com merito) + emit
     `manifestacao_ouvidoria.respondida`. `m` = {:manifestacao-id :resposta-id :corpo :respondido-por
     :respondida-em}. Devolve {:respondida-em :protocolo} ou nil (manifestacao ja terminal/inexistente).")
  (arquivar-manifestacao! [this ente-id m]
    "SERVIDOR — UMA tx: CAS manifestacao protocolada|em_analise -> arquivada (nil = ja terminal, aborta) +
     INSERT resposta_ouvidoria (append-only, a justificativa do arquivamento) + CANCELA o prazo (prazo_ativo
     -> cancelada, SEM merito — nao 'cumprida') + emit `manifestacao_ouvidoria.arquivada`. `m` =
     {:manifestacao-id :resposta-id :motivo :arquivado-por :arquivada-em}. Devolve {:arquivada-em :protocolo}
     ou nil (manifestacao ja terminal/inexistente).")
  (prorrogar-manifestacao! [this ente-id m]
    "SERVIDOR — UMA tx: CAS `db/prazo-ativo/prorrogar!` (so' se pendente + prorrogado_ate AINDA nil — forca
     1x) + INSERT `prorrogacao` (append-only, justificativa) + emit `participacao.prazo.prorrogado`. Se a CAS
     NAO transicionou (nil — ja prorrogado ou nao-pendente), ABORTA sem inserir/emitir (a 2a tentativa nao
     deixa rastro espurio na tabela de auditoria). `m` = {:prorrogacao-id :objeto-tipo :objeto-id :de-data
     :para-data :justificativa :prorrogado-por :prorrogado-em}. Devolve {:prorrogado-ate} ou nil.")
  ;; ---- FAST-FOLLOW Slice 6: Comentarios/moderacao (feature 6.3) ----
  (comentar! [this ente-id m]
    "UMA tx: INSERT comentario (sempre 'pendente') + emit `comentario.protocolado`. autor-identidade-id
     INJETADO upstream (nunca do corpo). `m` = {:id :proposicao-id :autor-identidade-id :corpo :created-by}.
     Devolve {:id :estado}.")
  (buscar-comentario [this ente-id id]
    "Comentario por id no tenant. Usada pela borda p/ desambiguar o nil de moderar!/denunciar! (existe ->
     409/nil-idempotente; ausente -> 404).")
  (moderar-comentario! [this ente-id m]
    "SERVIDOR — UMA tx: CAS comentario pendente -> `acao` (nil = ja terminal, aborta sem escrever) + INSERT
     moderacao_comentario (append-only, a trilha) + emit `comentario.moderado`. `m` = {:id :acao
     :motivo-rejeicao :moderacao-id :moderado-por :moderado-em}. Devolve {:id :estado} ou nil (comentario ja
     moderado/inexistente).")
  (denunciar-comentario! [this ente-id m]
    "CIDADAO — UMA tx: INSERT denuncia_comentario (append-only, IDEMPOTENTE via ON CONFLICT DO NOTHING) + SE
     foi a 1a denuncia deste cidadao: CAS `marcar-denunciado!` (so' quando AINDA pendente — nunca colide com
     o trigger de estado terminal) + emit `comentario.denunciado`. Repeticoes idempotentes NAO reemitem nem
     re-tentam a CAS. `m` = {:denuncia-id :comentario-id :denunciante-identidade-id :motivo :denunciado-em}.
     Devolve {:denunciado true} SEMPRE que o comentario existe (o caller upstream ja' confirmou a
     existencia — ver controllers/denunciar-comentario!).")
  (comentarios-da-materia [this ente-id proposicao-id]
    "'comentarios-da-materia' (PUBLICA): SO aprovados de UMA materia, cronologico, com teto. Lista completa,
     sem paginacao nesta fatia.")
  (fila-moderacao [this ente-id]
    "'fila-moderacao' (SERVIDOR): SO pendentes, denunciados PRIMEIRO, depois cronologico, com teto.")
  (esic-cumprimento [this ente-id] "Cumprimento de prazo do e-SIC (FE Onda A1, §16.11).")
  (meus-protocolos [this ente-id identidade-id]
    "O que a pessoa protocolou nesta Casa, cada item com o seu prazo, numa UNICA tx: {:pedidos-esic
    :solicitacoes-lgpd :manifestacoes}, cada um [{:item :prazo}]. Manifestacao anonima fica de fora. e-SIC e ouvidoria
    levam tambem `:prorrogacao` (a mais recente do objeto, ou nil) e as tres especies levam `:anexos` (os da resposta,
    [] se nao ha) e `:complementos` (o complemento da resposta, [] se nao ha, na ordem de chegada) — lidos em lote, uma
    consulta por especie, nao por item.")
  ;; ---- BALCAO interno de atendimento (6.1/6.2/5.10): o que a secretaria le para responder ----
  (fila-do-balcao [this ente-id especie situacao]
    "A fila da `especie` (:esic|:ouvidoria|:lgpd) na `situacao` (abertos|respondidos|todos), numa tx: [{:item :prazo
    :recurso?}] (ver db/atendimento — a ORDEM e o teto moram la').")
  (atendimento-esic [this ente-id id]
    "O pedido e-SIC inteiro para o balcao, numa tx: {:pedido :prazo :respostas :recurso (com :prazo e :respostas, ou
    nil) :prorrogacoes} ou nil (inexistente no tenant).")
  (atendimento-ouvidoria [this ente-id id]
    "A manifestacao inteira para o balcao, numa tx: {:manifestacao :prazo :respostas :prorrogacoes} ou nil. A linha
    traz o manifestante (a tabela o tem); quem o tira do caminho e' o controller, que nunca o le.")
  (atendimento-lgpd [this ente-id id]
    "A solicitacao do titular inteira para o balcao, numa tx: {:solicitacao :prazo :respostas} ou nil.")
  (anexar-ao-atendimento! [this ente-id m limite]
    "UMA tx: serializa os anexos do MESMO protocolo (trava consultiva) e, na ordem: (1) se ja' existe um anexo VIGENTE
    com o mesmo sha256 neste protocolo e origem, devolve ESSE (`:reenvio true`, sem linha nova — o reenvio e' idempotente;
    o chamador tira o blob que subiu a mais); (2) se a `origem` (casa | requerente) ja' tem `limite` anexos vigentes ->
    :conflito/anexos-demais; (3) se `m` traz `:cota` {:max-bytes :desde} e a soma dos bytes que a identidade
    (`:enviado-por`) anexou desde `:desde`, mais este, passa do teto -> :conflito/cota-de-anexos; (4) grava (append-only).
    As recusas lancam ANTES de qualquer escrita (a tx volta): o chamador pode tirar o blob que subiu antes. `m` = {:id
    :objeto-tipo :objeto-id :origem :nome :tipo-midia :bytes :sha256 :chave-objeto :enviado-por [:enviado-em] [:cota]}. O
    protocolo ja' foi conferido (existe nesta Casa) pelo chamador. Devolve o anexo (kebab).")
  (retirar-anexo! [this ente-id m]
    "SERVIDOR — UMA tx: registra a RETIRADA do anexo (append-only, uma por anexo) e devolve o anexo lido de volta, com a
    retirada que ficou (a primeira, se ja' estava retirado: idempotente, sem segunda linha). nil = o anexo nao existe neste
    protocolo desta Casa. `m` = {:objeto-tipo :objeto-id :anexo-id :retirado-em :retirado-por :motivo}. O efeito no object
    storage e' do controller.")
  (substituir-anexo! [this ente-id m]
    "SERVIDOR — UMA tx (ADR-0022, \"Substituir um anexo\"): serializa os anexos do protocolo (trava consultiva), le o anexo
    antigo e, na ordem: (1) nao existe neste protocolo desta Casa -> nil; (2) `logic.anexo/motivo-de-nao-substituir` (so' o da
    Casa, vigente, nunca trocado) -> :conflito/anexo-nao-substituivel (com `:motivo`); (3) ja' ha' um anexo VIGENTE da Casa
    com o mesmo sha256 (inclusive o proprio antigo) -> :conflito/anexo-igual; (4) grava a RETIRADA do antigo (com o motivo) e o
    anexo NOVO apontando para ele (`substitui-anexo-id`), ambos no mesmo instante `:em`. A vaga do antigo e' do novo: o retirado
    ja' nao conta no limite, entao substituir cabe mesmo com 5 anexos. Sem janela de 10 minutos. As recusas lancam ANTES de
    qualquer escrita (a tx volta): o chamador pode tirar o blob novo que subiu antes. `m` = {:objeto-tipo :objeto-id
    :anexo-id (o antigo) :motivo :retirado-por :em :novo {:id :nome :tipo-midia :bytes :sha256 :chave-objeto :enviado-por}}
    (o `:origem` do novo e' sempre `casa`). Devolve {:antigo (lido de volta, com a retirada e `:substituido-por`) :novo}.")
  (anexo-do-atendimento [this ente-id objeto-tipo objeto-id anexo-id]
    "O anexo do protocolo (objeto-tipo + objeto-id), ou nil.")
  (chaves-de-anexos [this ente-id]
    "Toda chave de blob que a Casa tem em `participacao.anexo`: [{:chave :retirado?}]. O retirado nao tem blob
    de proposito. Para a reconciliacao banco x object storage (`oplenario.reconciliar-anexos`); so' leitura.")
  (complementar! [this ente-id m]
    "SERVIDOR — UMA tx: grava o COMPLEMENTO DA RESPOSTA (append-only, ADR-0022) de um protocolo ja' respondido. Sem CAS e sem
    prazo: nao muda estado nem prazo, e ter resposta e' monotono (uma resposta nunca some), entao a conferencia previa do
    controller nao envelhece. `m` = {:id :objeto-tipo :objeto-id :corpo :complementado-em :complementado-por}. Devolve o
    complemento (kebab).")
  (prorrogar-pedido! [this ente-id m]
    "SERVIDOR — a prorrogacao do pedido e-SIC (LAI art. 11 §2º), pelo MESMO mecanismo generico de
    `prorrogar-manifestacao!` (CAS 1x + registro append-only + emit), com objeto_tipo 'pedido_esic'. Devolve
    {:prorrogado-ate} ou nil."))

(declare prorrogar-prazo-impl)

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
  (indeferir-pedido! [this ente-id {:keys [pedido-id resposta-id fundamentacao indeferido-por indeferido-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS PRIMEIRO (short-circuit), como o responder: so grava a fundamentacao/cumpre o prazo se o pedido AINDA
        ;; esta aberto. nil = ja terminal (respondido/indeferido) -> aborta sem inserir nada (a borda desambigua 409).
        (when-let [pedido (db-pedido/indeferir! tx {:id pedido-id :ente-id ente-id})]
          ;; a fundamentacao E' a resposta: mesma tabela append-only, mesmo `corpo` (a leitura do cidadao e o historico
          ;; do balcao a distinguem pelo ESTADO do pedido, que so' tem um desfecho).
          (db-resposta/inserir! tx {:id resposta-id :ente-id ente-id :pedido-id pedido-id :recurso-id nil
                                    :corpo fundamentacao :respondido-por indeferido-por :respondida-em indeferido-em})
          ;; INVARIANTE (Inv.10, a mesma do responder): o pedido so vira terminal ATOMICO com o fechamento do seu
          ;; prazo. Sem prazo aberto = prazo orfao -> aborta a tx (500 auditavel, tipo != :conflito -> nao 409).
          (when-not (db-prazo/cumprir! tx {:ente-id ente-id :objeto-tipo "pedido_esic" :objeto-id pedido-id
                                           :cumprida-em indeferido-em})
            (throw (ex-info "prazo do pedido nao estava aberto ao indeferir (invariante de compliance)"
                            {:tipo :invariante/prazo-orfao :objeto-tipo "pedido_esic" :objeto-id pedido-id})))
          (producers/emitir-pedido-indeferido! bus tx ente-id
            {:pedido-id pedido-id :protocolo (:protocolo pedido) :indeferido-em (str indeferido-em)})
          {:indeferido-em indeferido-em :protocolo (:protocolo pedido)}))))
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
                       ;; payload usa o vencimento EFETIVO (COALESCE prorrogado_ate/vence_em, generalizacao
                       ;; 0042) — se o objeto foi prorrogado, o evento cita a data que DE FATO venceu.
                       (producers/emitir-prazo-vencido! bus tx ente-id
                         {:objeto-tipo (:objeto-tipo p) :objeto-id (:objeto-id p)
                          :vence-em (str (logic/vencimento-efetivo p))})
                       {:id (:id p) :objeto-tipo (:objeto-tipo p) :objeto-id (:objeto-id p)
                        :de "pendente" :para "vencida"})))   ; pendente->vencida: a unica transicao deste sweep
             vec))))
  ;; ---- Slice 4: LGPD — solicitacao do titular + Encarregado ----
  (solicitar-titular! [this ente-id {:keys [id ano tipo detalhe titular-identidade-id recibo-em vence-em
                                            prazo-id base-dias prazo-fonte-ref created-by]}]
    (transacao this ente-id
      (fn [tx]
        (let [solic (db-solicitacao/protocolar! tx {:id id :ente-id ente-id :ano ano :tipo tipo
                                                    :titular-identidade-id titular-identidade-id
                                                    :detalhe detalhe :recibo-em recibo-em :created-by created-by})]
          ;; o RELOGIO LGPD comeca atomico com o protocolo (Arch B): prazo pendente sobre a solicitacao, mesma tx.
          ;; CONTADOR SEPARADO: objeto_tipo='solicitacao_titular', vence_em derivado de dias-titular (nao da LAI).
          (db-prazo/inserir! tx {:id prazo-id :ente-id ente-id :objeto-tipo "solicitacao_titular" :objeto-id id
                                 :vence-em vence-em :estado "pendente" :base-dias base-dias
                                 :prazo-fonte-ref prazo-fonte-ref :created-by created-by})
          (producers/emitir-solicitacao-titular-protocolada! bus tx ente-id
            {:solicitacao-id id :protocolo (:protocolo solic) :tipo tipo
             :recibo-em (str recibo-em) :vence-em (str vence-em)})
          {:id id :protocolo (:protocolo solic) :recibo-em recibo-em}))))
  (buscar-solicitacao-titular [this ente-id id]
    (transacao this ente-id #(db-solicitacao/buscar % ente-id id)))
  (solicitacao-titular-com-prazo [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [solic (db-solicitacao/buscar tx ente-id id)]
          {:solicitacao solic :prazo (db-prazo/buscar-do-objeto tx ente-id "solicitacao_titular" id)}))))
  (responder-solicitacao! [this ente-id {:keys [solicitacao-id resposta-id corpo respondido-por respondida-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS PRIMEIRO (short-circuit): so escreve a resposta/cumpre o prazo se a solicitacao AINDA e' respondivel.
        ;; nil = ja terminal (respondida/indeferida) -> aborta sem inserir nada (a borda desambigua p/ 409).
        (when-let [solic (db-solicitacao/responder! tx {:id solicitacao-id :ente-id ente-id})]
          (db-resposta-titular/inserir! tx {:id resposta-id :ente-id ente-id :solicitacao-id solicitacao-id
                                            :corpo corpo :respondido-por respondido-por :respondida-em respondida-em})
          ;; INVARIANTE (Inv.10, espelha responder-pedido!): a solicitacao so vira terminal ATOMICO com o
          ;; fechamento do seu prazo (CONTADOR SEPARADO). Sem prazo aberto = inconsistencia (prazo orfao) -> aborta.
          (when-not (db-prazo/cumprir! tx {:ente-id ente-id :objeto-tipo "solicitacao_titular"
                                           :objeto-id solicitacao-id :cumprida-em respondida-em})
            (throw (ex-info "prazo da solicitacao do titular nao estava aberto ao cumprir (invariante de compliance)"
                            {:tipo :invariante/prazo-orfao :objeto-tipo "solicitacao_titular"
                             :objeto-id solicitacao-id})))
          (producers/emitir-solicitacao-titular-respondida! bus tx ente-id
            {:solicitacao-id solicitacao-id :respondida-em (str respondida-em)})
          {:respondida-em respondida-em :protocolo (:protocolo solic)}))))
  (indeferir-solicitacao! [this ente-id {:keys [solicitacao-id resposta-id fundamentacao indeferido-por indeferida-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS PRIMEIRO (short-circuit), como o responder: nil = ja terminal -> aborta sem escrever (409 na borda).
        (when-let [solic (db-solicitacao/indeferir! tx {:id solicitacao-id :ente-id ente-id})]
          (db-resposta-titular/inserir! tx {:id resposta-id :ente-id ente-id :solicitacao-id solicitacao-id
                                            :corpo fundamentacao :respondido-por indeferido-por
                                            :respondida-em indeferida-em})
          ;; INVARIANTE (Inv.10): so vira terminal ATOMICO com o fechamento do prazo (CONTADOR SEPARADO do e-SIC).
          (when-not (db-prazo/cumprir! tx {:ente-id ente-id :objeto-tipo "solicitacao_titular"
                                           :objeto-id solicitacao-id :cumprida-em indeferida-em})
            (throw (ex-info "prazo da solicitacao do titular nao estava aberto ao indeferir (invariante de compliance)"
                            {:tipo :invariante/prazo-orfao :objeto-tipo "solicitacao_titular"
                             :objeto-id solicitacao-id})))
          (producers/emitir-solicitacao-titular-indeferida! bus tx ente-id
            {:solicitacao-id solicitacao-id :indeferida-em (str indeferida-em)})
          {:indeferida-em indeferida-em :protocolo (:protocolo solic)}))))
  (definir-encarregado! [this ente-id {:keys [id nome rotulo email atualizado-por]}]
    (transacao this ente-id
      (fn [tx]
        (db-encarregado/upsert! tx {:id id :ente-id ente-id :nome nome :rotulo rotulo :email email
                                    :atualizado-por atualizado-por}))))
  (buscar-encarregado [this ente-id]
    (transacao this ente-id #(db-encarregado/buscar % ente-id)))
  ;; ---- FAST-FOLLOW Slice 5: Ouvidoria (Lei 13.460 art. 10) ----
  (protocolar-manifestacao! [this ente-id {:keys [id ano tipo assunto descricao anonima
                                                   manifestante-identidade-id recibo-em vence-em
                                                   prazo-id base-dias prazo-fonte-ref created-by]}]
    (transacao this ente-id
      (fn [tx]
        (let [manif (db-manifestacao/protocolar! tx {:id id :ente-id ente-id :ano ano :tipo tipo
                                                      :assunto assunto :descricao descricao :anonima anonima
                                                      :manifestante-identidade-id manifestante-identidade-id
                                                      :recibo-em recibo-em :created-by created-by})]
          ;; o RELOGIO comeca atomico com o protocolo (Arch B): prazo pendente sobre a manifestacao, mesma tx.
          (db-prazo/inserir! tx {:id prazo-id :ente-id ente-id :objeto-tipo "manifestacao_ouvidoria"
                                 :objeto-id id :vence-em vence-em :estado "pendente" :base-dias base-dias
                                 :prazo-fonte-ref prazo-fonte-ref :created-by created-by})
          (producers/emitir-manifestacao-protocolada! bus tx ente-id
            {:manifestacao-id id :protocolo (:protocolo manif)
             :recibo-em (str recibo-em) :vence-em (str vence-em)})
          {:id id :protocolo (:protocolo manif) :recibo-em recibo-em}))))
  (buscar-manifestacao [this ente-id id] (transacao this ente-id #(db-manifestacao/buscar % ente-id id)))
  (manifestacao-com-prazo [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [manif (db-manifestacao/buscar tx ente-id id)]
          {:manifestacao manif :prazo (db-prazo/buscar-do-objeto tx ente-id "manifestacao_ouvidoria" id)}))))
  (acompanhar-manifestacao-por-protocolo [this ente-id protocolo]
    (transacao this ente-id
      (fn [tx]
        (when-let [manif (db-manifestacao/por-protocolo tx ente-id protocolo)]
          {:manifestacao manif
           :prazo (db-prazo/buscar-do-objeto tx ente-id "manifestacao_ouvidoria" (:id manif))}))))
  (responder-manifestacao! [this ente-id {:keys [manifestacao-id resposta-id corpo respondido-por respondida-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS PRIMEIRO (short-circuit): so escreve a resposta/cumpre o prazo se AINDA e' respondivel.
        (when-let [manif (db-manifestacao/responder! tx {:id manifestacao-id :ente-id ente-id})]
          (db-resposta-ouvidoria/inserir! tx {:id resposta-id :ente-id ente-id :manifestacao-id manifestacao-id
                                              :corpo corpo :respondido-por respondido-por
                                              :respondida-em respondida-em})
          ;; INVARIANTE (Inv.10, espelha responder-pedido!): so vira terminal ATOMICO com o fechamento do
          ;; prazo. Sem prazo aberto = inconsistencia (prazo orfao) -> aborta a tx (500 auditavel).
          (when-not (db-prazo/cumprir! tx {:ente-id ente-id :objeto-tipo "manifestacao_ouvidoria"
                                           :objeto-id manifestacao-id :cumprida-em respondida-em})
            (throw (ex-info "prazo da manifestacao nao estava aberto ao cumprir (invariante de compliance)"
                            {:tipo :invariante/prazo-orfao :objeto-tipo "manifestacao_ouvidoria"
                             :objeto-id manifestacao-id})))
          (producers/emitir-manifestacao-respondida! bus tx ente-id
            {:manifestacao-id manifestacao-id :protocolo (:protocolo manif) :respondida-em (str respondida-em)})
          {:respondida-em respondida-em :protocolo (:protocolo manif)}))))
  (arquivar-manifestacao! [this ente-id {:keys [manifestacao-id resposta-id motivo arquivado-por arquivada-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS PRIMEIRO: so escreve a justificativa/cancela o prazo se AINDA e' arquivavel.
        (when-let [manif (db-manifestacao/arquivar! tx {:id manifestacao-id :ente-id ente-id})]
          (db-resposta-ouvidoria/inserir! tx {:id resposta-id :ente-id ente-id :manifestacao-id manifestacao-id
                                              :corpo motivo :respondido-por arquivado-por
                                              :respondida-em arquivada-em})
          ;; INVARIANTE (Inv.10): so vira terminal ATOMICO com o CANCELAMENTO do prazo (SEM merito — nao
          ;; 'cumprida'). Sem prazo aberto = inconsistencia (prazo orfao) -> aborta a tx.
          (when-not (db-prazo/cancelar! tx {:ente-id ente-id :objeto-tipo "manifestacao_ouvidoria"
                                            :objeto-id manifestacao-id})
            (throw (ex-info "prazo da manifestacao nao estava aberto ao cancelar (invariante de compliance)"
                            {:tipo :invariante/prazo-orfao :objeto-tipo "manifestacao_ouvidoria"
                             :objeto-id manifestacao-id})))
          (producers/emitir-manifestacao-arquivada! bus tx ente-id
            {:manifestacao-id manifestacao-id :protocolo (:protocolo manif) :arquivada-em (str arquivada-em)})
          {:arquivada-em arquivada-em :protocolo (:protocolo manif)}))))
  (prorrogar-manifestacao! [this ente-id m] (prorrogar-prazo-impl this ente-id m))
  ;; ---- FAST-FOLLOW Slice 6: Comentarios/moderacao (feature 6.3) ----
  (comentar! [this ente-id {:keys [id proposicao-id autor-identidade-id corpo created-by]}]
    (transacao this ente-id
      (fn [tx]
        (let [com (db-comentario/inserir! tx {:id id :ente-id ente-id :proposicao-id proposicao-id
                                               :autor-identidade-id autor-identidade-id :corpo corpo
                                               :created-by created-by})]
          (producers/emitir-comentario-protocolado! bus tx ente-id
            {:comentario-id id :proposicao-id proposicao-id})
          {:id id :estado (:estado com)}))))
  (buscar-comentario [this ente-id id] (transacao this ente-id #(db-comentario/buscar % ente-id id)))
  (moderar-comentario! [this ente-id {:keys [id acao motivo-rejeicao moderacao-id moderado-por moderado-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS PRIMEIRO (short-circuit): so grava a trilha/emite se AINDA pendente. nil = ja terminal ->
        ;; aborta sem inserir nada (a borda desambigua p/ 409).
        (when-let [com (db-comentario/moderar! tx {:id id :ente-id ente-id :estado acao
                                                    :motivo-rejeicao motivo-rejeicao})]
          (db-moderacao/inserir! tx {:id moderacao-id :ente-id ente-id :comentario-id id :acao acao
                                     :motivo-rejeicao motivo-rejeicao :moderado-por moderado-por
                                     :moderado-em moderado-em})
          (producers/emitir-comentario-moderado! bus tx ente-id {:comentario-id id :acao acao})
          {:id id :estado (:estado com)}))))
  (denunciar-comentario! [this ente-id {:keys [denuncia-id comentario-id denunciante-identidade-id
                                               motivo denunciado-em]}]
    (transacao this ente-id
      (fn [tx]
        ;; INSERT idempotente (ON CONFLICT DO NOTHING): nil = repeticao do MESMO cidadao sobre o MESMO
        ;; comentario (a UNIQUE da mig 0043 ja' tem o registro) -> NAO re-marca a flag nem reemite (evita
        ;; evento espurio no double-click/retry).
        (when (db-denuncia/inserir! tx {:id denuncia-id :ente-id ente-id :comentario-id comentario-id
                                        :denunciante-identidade-id denunciante-identidade-id
                                        :motivo motivo :denunciado-em denunciado-em})
          ;; CAS: so' marca `denunciado` se o comentario AINDA esta pendente (nunca toca linha terminal —
          ;; ver docstring de db/comentario/marcar-denunciado!). nil aqui (ja terminal) NAO e' erro: o
          ;; registro em denuncia_comentario ja aconteceu, so' a flag de indexacao nao se aplica mais.
          (db-comentario/marcar-denunciado! tx {:id comentario-id :ente-id ente-id})
          (producers/emitir-comentario-denunciado! bus tx ente-id {:comentario-id comentario-id}))
        {:denunciado true})))
  (comentarios-da-materia [this ente-id proposicao-id]
    (transacao this ente-id #(db-comentario/listar-aprovados-da-materia % ente-id proposicao-id)))
  (fila-moderacao [this ente-id]
    (transacao this ente-id #(db-comentario/listar-fila-moderacao % ente-id)))
  (esic-cumprimento [this ente-id] (transacao this ente-id #(db-prazo/esic-cumprimento % ente-id)))
  (meus-protocolos [this ente-id identidade-id]
    (transacao this ente-id
      (fn [tx]
        ;; cada item com o seu prazo e a resposta MAIS RECENTE (a cidada precisa le-la para decidir se recorre)
        (let [com-prazo (fn [objeto-tipo respostas itens]
                          (mapv (fn [i] {:item i
                                         :prazo (db-prazo/buscar-do-objeto tx ente-id objeto-tipo (:id i))
                                         :resposta (last (respostas tx ente-id (:id i)))})
                                itens))]
          ;; a PRORROGACAO (LAI art. 11 §2º — o requerente e' cientificado da justificativa) e os ANEXOS da resposta: UMA
          ;; consulta por especie para todos os itens da lista (nao uma por item). Da prorrogacao fica a mais recente por
          ;; objeto (a CAS de prorrogar admite so' uma por prazo); dos anexos, todos, na ordem em que chegaram.
          (let [ids (fn [linhas] (mapv #(get-in % [:item :id]) linhas))
                com-prorrogacao (fn [objeto-tipo linhas]
                                  (let [por-objeto (->> (db-prorrogacao/listar-por-objetos tx ente-id objeto-tipo (ids linhas))
                                                        (reduce (fn [m p] (assoc m (:objeto-id p) p)) {}))]
                                    (mapv #(assoc % :prorrogacao (get por-objeto (get-in % [:item :id]))) linhas)))
                com-anexos (fn [objeto-tipo linhas]
                             (let [por-objeto (group-by :objeto-id (db-anexo/listar-por-objetos tx ente-id objeto-tipo (ids linhas)))]
                               (mapv #(assoc % :anexos (get por-objeto (get-in % [:item :id]) [])) linhas)))
                ;; o COMPLEMENTO DA RESPOSTA (ADR-0022): todos, na ordem em que chegaram, em UMA consulta por especie
                com-complementos (fn [objeto-tipo linhas]
                                   (let [por-objeto (group-by :objeto-id (db-complemento/listar-por-objetos tx ente-id objeto-tipo (ids linhas)))]
                                     (mapv #(assoc % :complementos (get por-objeto (get-in % [:item :id]) [])) linhas)))]
            {:pedidos-esic      (->> (com-prazo "pedido_esic" db-resposta/listar-do-pedido
                                                (db-pedido/listar-por-solicitante tx ente-id identidade-id))
                                     (com-prorrogacao "pedido_esic")
                                     (com-anexos "pedido_esic")
                                     (com-complementos "pedido_esic")
                                     (mapv (fn [{:keys [item] :as linha}]
                                             ;; o recurso ja' interposto (V1: um por pedido) + a decisao, se houver
                                             (assoc linha :recurso
                                                    (when-let [r (db-recurso/ultimo-do-pedido tx ente-id (:id item))]
                                                      (assoc r :resposta (last (db-resposta/listar-do-recurso tx ente-id (:id r)))))))))
             :solicitacoes-lgpd (->> (com-prazo "solicitacao_titular" db-resposta-titular/listar-da-solicitacao
                                                (db-solicitacao/listar-por-titular tx ente-id identidade-id))
                                     (com-anexos "solicitacao_titular")
                                     (com-complementos "solicitacao_titular"))
             :manifestacoes     (->> (com-prazo "manifestacao_ouvidoria" db-resposta-ouvidoria/listar-da-manifestacao
                                                (db-manifestacao/listar-por-manifestante tx ente-id identidade-id))
                                     (com-prorrogacao "manifestacao_ouvidoria")
                                     (com-anexos "manifestacao_ouvidoria")
                                     (com-complementos "manifestacao_ouvidoria"))})))))
  ;; ---- BALCAO interno de atendimento ----
  (fila-do-balcao [this ente-id especie situacao]
    (transacao this ente-id
      (fn [tx]
        (case especie
          :esic      (db-atendimento/fila-esic tx ente-id situacao)
          :ouvidoria (db-atendimento/fila-ouvidoria tx ente-id situacao)
          :lgpd      (db-atendimento/fila-lgpd tx ente-id situacao)))))
  (atendimento-esic [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [pedido (db-pedido/buscar tx ente-id id)]
          {:pedido       pedido
           :prazo        (db-prazo/buscar-do-objeto tx ente-id "pedido_esic" id)
           :respostas    (db-resposta/listar-do-pedido tx ente-id id)
           :prorrogacoes (db-prorrogacao/listar-do-objeto tx ente-id "pedido_esic" id)
           :anexos       (db-anexo/listar-do-objeto tx ente-id "pedido_esic" id)
           :complementos (db-complemento/listar-do-objeto tx ente-id "pedido_esic" id)
           ;; V1: uma instancia de recurso por pedido (UNIQUE (pedido, instancia))
           :recurso      (when-let [r (db-recurso/ultimo-do-pedido tx ente-id id)]
                           (assoc r :prazo (db-prazo/buscar-do-objeto tx ente-id "recurso_esic" (:id r))
                                    :respostas (db-resposta/listar-do-recurso tx ente-id (:id r))))}))))
  (atendimento-ouvidoria [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [manif (db-manifestacao/buscar tx ente-id id)]
          {:manifestacao manif
           :prazo        (db-prazo/buscar-do-objeto tx ente-id "manifestacao_ouvidoria" id)
           :respostas    (db-resposta-ouvidoria/listar-da-manifestacao tx ente-id id)
           :anexos       (db-anexo/listar-do-objeto tx ente-id "manifestacao_ouvidoria" id)
           :complementos (db-complemento/listar-do-objeto tx ente-id "manifestacao_ouvidoria" id)
           :prorrogacoes (db-prorrogacao/listar-do-objeto tx ente-id "manifestacao_ouvidoria" id)}))))
  (atendimento-lgpd [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [solic (db-solicitacao/buscar tx ente-id id)]
          {:solicitacao solic
           :prazo       (db-prazo/buscar-do-objeto tx ente-id "solicitacao_titular" id)
           :respostas   (db-resposta-titular/listar-da-solicitacao tx ente-id id)
           :anexos      (db-anexo/listar-do-objeto tx ente-id "solicitacao_titular" id)
           :complementos (db-complemento/listar-do-objeto tx ente-id "solicitacao_titular" id)}))))
  (anexar-ao-atendimento! [this ente-id {:keys [objeto-tipo objeto-id origem sha256 cota] :as m} limite]
    (transacao this ente-id
      (fn [tx]
        (db-anexo/travar! tx ente-id objeto-tipo objeto-id)
        (if-let [igual (db-anexo/achar-igual tx ente-id objeto-tipo objeto-id origem sha256)]
          (assoc igual :reenvio true)
          (do
            ;; o limite e' POR ORIGEM: os anexos da Casa nao tomam a vaga dos do requerente, e vice-versa; o retirado devolve a vaga
            (when (>= (db-anexo/contar-da-origem tx ente-id objeto-tipo objeto-id origem) (long limite))
              (throw (ex-info "o protocolo ja' tem o maximo de anexos desta origem"
                              {:tipo :conflito/anexos-demais :origem origem :limite limite})))
            (when cota
              (let [usados (db-anexo/somar-bytes-do-requerente tx ente-id (:enviado-por m) (:desde cota))]
                (when (> (+ usados (long (:bytes m))) (long (:max-bytes cota)))
                  (throw (ex-info "a cota de disco do requerente nas ultimas 24 horas acabou"
                                  {:tipo :conflito/cota-de-anexos :usados usados :cota (:max-bytes cota)})))))
            (db-anexo/inserir! tx (assoc m :ente-id ente-id)))))))
  (retirar-anexo! [this ente-id {:keys [objeto-tipo objeto-id anexo-id] :as m}]
    (transacao this ente-id
      (fn [tx]
        (db-anexo/travar! tx ente-id objeto-tipo objeto-id)
        (when (db-anexo/buscar tx ente-id objeto-tipo objeto-id anexo-id)
          (db-anexo/retirar! tx (assoc m :ente-id ente-id))
          (db-anexo/buscar tx ente-id objeto-tipo objeto-id anexo-id)))))
  (substituir-anexo! [this ente-id {:keys [objeto-tipo objeto-id anexo-id motivo retirado-por em novo]}]
    (transacao this ente-id
      (fn [tx]
        (db-anexo/travar! tx ente-id objeto-tipo objeto-id)
        (when-let [antigo (db-anexo/buscar tx ente-id objeto-tipo objeto-id anexo-id)]
          (when-let [por-que (logic-anexo/motivo-de-nao-substituir antigo)]
            (throw (ex-info "o anexo nao pode ser substituido" {:tipo :conflito/anexo-nao-substituivel :motivo por-que})))
          (when (db-anexo/achar-igual tx ente-id objeto-tipo objeto-id "casa" (:sha256 novo))
            (throw (ex-info "o arquivo ja' esta anexado a este protocolo" {:tipo :conflito/anexo-igual})))
          (db-anexo/retirar! tx {:ente-id ente-id :anexo-id anexo-id :retirado-em em :retirado-por retirado-por :motivo motivo})
          (let [inserido (db-anexo/inserir! tx (assoc novo :ente-id ente-id :objeto-tipo objeto-tipo :objeto-id objeto-id
                                                      :origem "casa" :enviado-em em :substitui-anexo-id anexo-id))]
            {:antigo (db-anexo/buscar tx ente-id objeto-tipo objeto-id anexo-id)
             :novo (db-anexo/buscar tx ente-id objeto-tipo objeto-id (:id inserido))})))))
  (anexo-do-atendimento [this ente-id objeto-tipo objeto-id anexo-id]
    (transacao this ente-id #(db-anexo/buscar % ente-id objeto-tipo objeto-id anexo-id)))
  (chaves-de-anexos [this ente-id]
    (transacao this ente-id #(db-anexo/chaves-da-casa % ente-id)))
  (complementar! [this ente-id m]
    (transacao this ente-id #(db-complemento/inserir! % (assoc m :ente-id ente-id))))
  (prorrogar-pedido! [this ente-id m]
    (prorrogar-prazo-impl this ente-id (assoc m :objeto-tipo "pedido_esic"))))

(defn- prorrogar-prazo-impl
  "A prorrogacao GENERICA (generalizacao 0042 — qualquer objeto_tipo de prazo): ouvidoria (Lei 13.460 art. 10) e
  e-SIC (LAI art. 11 §2º)."
  [this ente-id {:keys [prorrogacao-id objeto-tipo objeto-id de-data para-data
                        justificativa prorrogado-por prorrogado-em]}]
  (transacao this ente-id
    (fn [tx]
      ;; CAS PRIMEIRO (short-circuit): so registra/emite se DE FATO prorrogou (1x apenas — a CAS SQL exige
      ;; pendente + prorrogado_ate ainda nil). nil = ja prorrogado/nao-pendente -> aborta sem escrever nada
      ;; (a 2a tentativa NAO deixa rastro espurio na tabela de auditoria) — a borda desambigua p/ 409.
      (when-let [prazo (db-prazo/prorrogar! tx {:ente-id ente-id :objeto-tipo objeto-tipo :objeto-id objeto-id
                                                 :prorrogado-ate para-data})]
        (db-prorrogacao/inserir! tx {:id prorrogacao-id :ente-id ente-id :objeto-tipo objeto-tipo
                                     :objeto-id objeto-id :de-data de-data :para-data para-data
                                     :justificativa justificativa :prorrogado-por prorrogado-por
                                     :prorrogado-em prorrogado-em})
        (producers/emitir-prazo-prorrogado! (:bus this) tx ente-id
          {:objeto-tipo objeto-tipo :objeto-id objeto-id
           :de-data (str de-data) :para-data (str para-data)})
        {:prorrogado-ate (:prorrogado-ate prazo)}))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource + :bus via `using`)."
  []
  (->RepoParticipacaoPg nil nil))
