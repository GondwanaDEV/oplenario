(ns oplenario.participacao.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo participacao (§22.10 diplomat/http/in, ADR-0001) — as rotas-dado
  Pedestal + os handlers (F6 Slice 1, a borda do e-SIC). O diplomat e' a UNICA camada que atravessa o gate de
  borda: adapters/in coage a entrada (fail-closed 400), adapters/out projeta+filtra a saida; o controller
  trabalha so em models. Le o `ator` (posto pela cadeia de auth em (:request :ator)); depende do Repo-Component
  (injetado por closure via `rotas`).

  TRES perfis de authz (§22.5): (1) POST /portal/esic/pedidos = cidadao ATRIBUIDO — SO `auth`, SEM exige-papel
  (a LAI diz que QUALQUER um pede; um vinculo cidadao nao tem papel). (2) GET /portal/esic/pedidos/:id = auth +
  policy FINA no controller (ator == solicitante). (3) GET /portal/casa/:ente/esic/acompanhar/:protocolo = PUBLICA
  SEM `auth` — o ente-id resolve do path param via `resolver-ente-publico` (seam do host) e o Repo abre
  com-tenant* com ele (a RLS ISOLA mesmo sem ator); a saida FILTRA PII no adapters/out.

  As rotas LGPD (Slice 4) REPLICAM os MESMOS tres perfis: (1) POST /portal/lgpd/solicitacoes = titular SO-`auth`
  (qualquer titular pede sobre os PROPRIOS dados, sem papel); GET .../solicitacoes/:id = auth + policy fina (ator
  == titular). (2) POST /lgpd/solicitacoes/:id/resposta|indeferir e PUT /lgpd/encarregado = SERVIDOR (auth + exige-papel
  'secretario'). (3) GET /portal/casa/:ente/encarregado = PUBLICA sem `auth` — o contato do DPO e' legalmente
  publico (LGPD art. 41 §1º); mesmo mecanismo resolver-ente-publico + RLS + filtro de saida no adapters/out.

  As rotas de OUVIDORIA (FAST-FOLLOW Slice 5, Lei 13.460 art. 10) tem um 4o perfil: (1) POST
  /portal/ouvidoria/manifestacoes = cidadao SO-`auth` (ANONIMA NAO E' SEM-AUTH — a escrita sempre exige
  token; `anonima?` so decide o que persiste, ver controllers/protocolar-manifestacao!). (2) GET
  /portal/ouvidoria/manifestacoes/:id = auth + policy fina, MAS devolve 404 (nao 403) quando `anonima=true`
  — nao ha dono persistido p/ comparar, nem para o proprio autor (controllers/minha-manifestacao ja decide
  isso — a rota so' aplica o nil->404 padrao). (3) GET /portal/casa/:ente/ouvidoria/acompanhar/:protocolo =
  PUBLICA sem `auth`, mesmo mecanismo resolver-ente-publico. (4) POST .../resposta|arquivar|prorrogar =
  SERVIDOR (auth + exige-papel 'secretario').

  As rotas de COMENTARIOS/MODERACAO (FAST-FOLLOW Slice 6, feature 6.3) reusam os MESMOS perfis: (1) POST
  /portal/materias/:proposicao_id/comentarios = cidadao SO-`auth` (SEM variante anonima — diferente da
  ouvidoria, autor sempre persiste). (2) GET /portal/casa/:ente/materias/:proposicao_id/comentarios =
  PUBLICA sem `auth`, so' aprovados (mesmo resolver-ente-publico). (3) POST /portal/comentarios/:id/
  denunciar = cidadao SO-`auth`, IDEMPOTENTE (nunca 409 numa 2a denuncia). (4) GET /moderacao/comentarios e
  POST /comentarios/:id/moderar = SERVIDOR (exige-papel 'secretario').

  DECISAO DE ROTEAMENTO (Slice 6): a fila de moderacao vive em `GET /moderacao/comentarios` (NAO
  `/comentarios/moderacao`) — o router prefix-tree do Pedestal 0.7 nao admite um literal ('moderacao') e um
  wildcard (':id', de POST /comentarios/:id/moderar) no MESMO nivel de path sob '/comentarios' (mesma
  limitacao ja documentada abaixo p/ o disambiguador 'casa/'). Trocar a ORDEM dos segmentos evita a colisao
  sem inventar mais um segmento estatico."
  (:require [io.pedestal.interceptor.chain :as chain]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.arquivo :as arquivo]
            [oplenario.participacao.adapters.in.arquivar-ouvidoria :as adapters-in-arquivar]
            [oplenario.participacao.adapters.in.atendimento :as adapters-in-atendimento]
            [oplenario.participacao.adapters.in.comentario :as adapters-in-comentario]
            [oplenario.participacao.adapters.in.denunciar-comentario :as adapters-in-denunciar]
            [oplenario.participacao.adapters.in.encarregado :as adapters-in-encarregado]
            [oplenario.participacao.adapters.in.indeferimento :as adapters-in-indeferimento]
            [oplenario.participacao.adapters.in.manifestacao-ouvidoria :as adapters-in-manifestacao]
            [oplenario.participacao.adapters.in.moderar-comentario :as adapters-in-moderar]
            [oplenario.participacao.adapters.in.pedido-esic :as adapters-in]
            [oplenario.participacao.adapters.in.prorrogar-ouvidoria :as adapters-in-prorrogar]
            [oplenario.participacao.adapters.in.recurso-esic :as adapters-in-recurso]
            [oplenario.participacao.adapters.in.resposta-esic :as adapters-in-resposta]
            [oplenario.participacao.adapters.in.resposta-ouvidoria :as adapters-in-resposta-ouvidoria]
            [oplenario.participacao.adapters.in.resposta-titular :as adapters-in-resposta-titular]
            [oplenario.participacao.adapters.in.solicitacao-titular :as adapters-in-titular]
            [oplenario.participacao.adapters.out.acompanhamento :as adapters-out-acomp]
            [oplenario.participacao.adapters.out.acompanhamento-ouvidoria :as adapters-out-acomp-ouvidoria]
            [oplenario.participacao.adapters.out.atendimento :as adapters-out-atendimento]
            [oplenario.participacao.adapters.out.comentario :as adapters-out-comentario]
            [oplenario.participacao.adapters.out.denuncia-comentario :as adapters-out-denunciar]
            [oplenario.participacao.adapters.out.encarregado :as adapters-out-encarregado]
            [oplenario.participacao.adapters.out.meus-protocolos :as adapters-out-meus]
            [oplenario.participacao.adapters.out.esic-cumprimento :as adapters-out-esic-cumprimento]
            [oplenario.participacao.adapters.out.manifestacao-ouvidoria :as adapters-out-manifestacao]
            [oplenario.participacao.adapters.out.moderacao-comentario :as adapters-out-moderacao]
            [oplenario.participacao.adapters.out.pedido-esic :as adapters-out-pedido]
            [oplenario.participacao.adapters.out.recurso-esic :as adapters-out-recurso]
            [oplenario.participacao.adapters.out.resposta-esic :as adapters-out-resposta]
            [oplenario.participacao.adapters.out.resposta-ouvidoria :as adapters-out-resposta-ouvidoria]
            [oplenario.participacao.adapters.out.solicitacao-titular :as adapters-out-titular]
            [oplenario.participacao.controllers :as controllers]
            [oplenario.participacao.logic.anexo :as anexo]))

(set! *warn-on-reflection* true)

(defn- responder-op
  "Resposta comum das operacoes de escrita do ciclo (Slice 2): executa `op` (thunk que chama o controller — devolve
  o mapa de dominio ou nil no ausente/CAS perdido) e desambigua: sucesso -> `status-ok` (adapters/out projeta+
  filtra via `->wire`); nil -> 404; ExceptionInfo :conflito/participacao -> 409 (nao 500). A validacao de borda
  (id/corpo) e a authz (403) sobem ANTES/fora daqui ao interceptor global. Espelha compliance/responder-transicao."
  [op ->wire status-ok]
  (try
    (if-let [r (op)]
      (http/json-resposta status-ok (->wire r))
      (http/json-resposta 404 {:erro "recurso nao encontrado"}))
    (catch clojure.lang.ExceptionInfo e
      (if (= :conflito/participacao (:tipo (ex-data e)))
        (http/json-resposta 409 {:erro "estado incompativel com a operacao"})
        (throw e)))))

(def resolver-ente-publico-uuid
  "Seam `resolver-ente-publico` DEFAULT do host (V1): o :ente do path = UUID do ente, coagido fail-closed
  (:validacao/invalido -> 400). Slug humano e' refino futuro. A fronteira de tenant da rota sem-ator e'
  confiada a RLS (com-tenant* com este ente-id). Fornecido pelo host a `rotas`; injetavel em teste."
  adapters-in/ente-param->uuid)

(defn- restrita-desde
  "ADR-0018: desde quando a Casa do ator esta' com o sistema restrito (seam do host), ou nil."
  [acesso-restrito-desde req]
  (when-let [ente (and acesso-restrito-desde (get-in req [:ator :ente-id]))]
    (acesso-restrito-desde ente)))

(defn- protocolar-handler
  "POST /portal/esic/pedidos (cidadao). adapters/in coage o corpo (fail-closed 400); o controller injeta o
  solicitante do ator + o recibo do relogio. 201 com {protocolo, recibo-em} (recibo instantaneo LAI)."
  [repo-participacao relogio acesso-restrito-desde]
  (fn [req]
    (let [entrada (adapters-in/coagir-pedido (:json-params req))
          r       (controllers/protocolar-pedido repo-participacao relogio (:ator req) entrada)]
      (http/json-resposta 201 (adapters-out-pedido/recibo->wire r (restrita-desde acesso-restrito-desde req))))))

(defn- meu-pedido-handler
  "GET /portal/esic/pedidos/:id (solicitante). Policy fina no controller (ator == solicitante -> 403 global).
  Ausente no tenant -> 404. adapters/out projeta+filtra (sem tenant, sem id de solicitante)."
  [repo-participacao relogio]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [detalhe (controllers/meu-pedido repo-participacao (:ator req) relogio id)]
        (http/json-resposta 200 (adapters-out-pedido/pedido->wire detalhe))
        (http/json-resposta 404 {:erro "pedido nao encontrado"})))))

(defn- meus-protocolos-handler
  "GET /portal/meus-protocolos (cidada autenticada): o que ELA protocolou na Casa da sessao — pedidos de e-SIC,
  solicitacoes LGPD e manifestacoes identificadas —, cada um com estado e prazo. adapters/out projeta por allowlist."
  [repo-participacao relogio]
  (fn [req]
    (http/json-resposta 200 (adapters-out-meus/meus-protocolos->wire
                             (controllers/meus-protocolos repo-participacao (:ator req) relogio)))))

(defn- acompanhar-handler
  "GET /portal/:ente/esic/acompanhar/:protocolo (PUBLICA, sem auth). resolver-ente-publico coage o :ente
  (-> 400 se malformado — NUNCA vaza cross-tenant); o controller le sob o tenant (RLS isola). Ausente -> 404;
  presente -> 200 com o view publico (protocolo, estado, dias-restantes) — adapters/out FILTRA toda PII."
  [repo-participacao relogio resolver-ente-publico]
  (fn [req]
    (let [ente-id   (resolver-ente-publico (get-in req [:path-params :ente]))
          protocolo (get-in req [:path-params :protocolo])]
      (if-let [acomp (controllers/acompanhar-por-protocolo repo-participacao ente-id relogio protocolo)]
        (http/json-resposta 200 (adapters-out-acomp/acompanhamento->wire acomp))
        (http/json-resposta 404 {:erro "pedido nao encontrado"})))))

(defn- interpor-recurso-handler
  "POST /portal/esic/pedidos/:id/recursos (CIDADAO, so-auth). Coage o :id do pedido + o corpo {motivo} (400 se
  malformado). O controller aplica a policy fina (dono -> 403; nao-recorrivel -> 409; ausente -> 404). Sucesso -> 201
  {protocolo, recibo-em} (prova do relogio proprio do recurso)."
  [repo-participacao relogio acesso-restrito-desde]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-recurso/coagir-recurso (:json-params req))]
      (responder-op #(controllers/interpor-recurso! repo-participacao relogio (:ator req) id entrada)
                    #(adapters-out-recurso/recibo->wire % (restrita-desde acesso-restrito-desde req)) 201))))

(defn- responder-pedido-handler
  "POST /esic/pedidos/:id/resposta (SERVIDOR, exige-papel). Coage o :id + o corpo {corpo}. nil -> 404;
  ja respondido -> 409. Sucesso -> 200 {respondida-em}."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-resposta/coagir-resposta (:json-params req))]
      (responder-op #(controllers/responder-pedido! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-resposta/recibo->wire 200))))

(defn- indeferir-pedido-handler
  "POST /esic/pedidos/:id/indeferir (SERVIDOR, exige-papel). Coage o :id + o corpo {fundamentacao} (obrigatoria — LAI
  art. 11 §1º II). nil -> 404 (inexistente ou de outra Casa); ja respondido/indeferido -> 409. Sucesso -> 200
  {indeferido-em}."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-indeferimento/coagir-indeferimento (:json-params req))]
      (responder-op #(controllers/indeferir-pedido! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-resposta/indeferimento-recibo->wire 200))))

(defn- decidir-recurso-handler
  "POST /esic/recursos/:id/decisao (SERVIDOR, exige-papel). Coage o :id do recurso + o corpo {corpo}. nil -> 404;
  ja decidido -> 409. Sucesso -> 200 {decidido-em}."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-resposta/coagir-resposta (:json-params req))]
      (responder-op #(controllers/decidir-recurso! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-recurso/decisao->wire 200))))

;; ========================= SLICE 4: LGPD — solicitacao do titular + Encarregado/DPO =========================

(defn- solicitar-titular-handler
  "POST /portal/lgpd/solicitacoes (TITULAR autenticado, SO-auth — qualquer titular pede sobre os PROPRIOS dados,
  sem papel). adapters/in coage o corpo {tipo, detalhe?} (fail-closed 400); o controller injeta o titular do ator
  + o recibo do relogio. 201 com {protocolo, recibo-em} (recibo instantaneo do relogio LGPD)."
  [repo-participacao relogio acesso-restrito-desde]
  (fn [req]
    (let [entrada (adapters-in-titular/coagir-solicitar (:json-params req))
          r       (controllers/solicitar-titular! repo-participacao relogio (:ator req) entrada)]
      (http/json-resposta 201 (adapters-out-titular/recibo->wire r (restrita-desde acesso-restrito-desde req))))))

(defn- minha-solicitacao-handler
  "GET /portal/lgpd/solicitacoes/:id (TITULAR). Policy fina no controller (ator == titular -> 403 global). Ausente
  no tenant -> 404. adapters/out projeta+filtra (sem tenant, sem id do titular)."
  [repo-participacao relogio]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [detalhe (controllers/minha-solicitacao repo-participacao (:ator req) relogio id)]
        (http/json-resposta 200 (adapters-out-titular/solicitacao->wire detalhe))
        (http/json-resposta 404 {:erro "solicitacao nao encontrada"})))))

(defn- encarregado-publico-handler
  "GET /portal/casa/:ente/encarregado (PUBLICA, sem auth — o contato do DPO e' legalmente publico, LGPD art. 41
  §1º). resolver-ente-publico coage o :ente (-> 400 se malformado — NUNCA vaza cross-tenant); o controller le sob
  o tenant (RLS isola). Ausente (ente sem DPO definido) -> 404; presente -> 200 com {nome, rotulo, email} — o
  adapters/out FILTRA todo interno (ids, atualizado-por, timestamps)."
  [repo-participacao resolver-ente-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if-let [dpo (controllers/encarregado-publico repo-participacao ente-id)]
        (http/json-resposta 200 (adapters-out-encarregado/publico->wire dpo))
        (http/json-resposta 404 {:erro "encarregado nao definido"})))))

(defn- responder-solicitacao-handler
  "POST /lgpd/solicitacoes/:id/resposta (SERVIDOR, exige-papel). Coage o :id + o corpo {corpo}. nil -> 404;
  ja respondida -> 409. Sucesso -> 200 {respondida-em}."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-resposta-titular/coagir-resposta (:json-params req))]
      (responder-op #(controllers/responder-solicitacao! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-titular/resposta-recibo->wire 200))))

(defn- indeferir-solicitacao-handler
  "POST /lgpd/solicitacoes/:id/indeferir (SERVIDOR, exige-papel). Coage o :id + o corpo {fundamentacao} (obrigatoria —
  LGPD art. 18 §4º). nil -> 404; ja respondida/indeferida -> 409. Sucesso -> 200 {indeferida-em}."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-indeferimento/coagir-indeferimento (:json-params req))]
      (responder-op #(controllers/indeferir-solicitacao! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-titular/indeferimento-recibo->wire 200))))

(defn- definir-encarregado-handler
  "PUT /lgpd/encarregado (SERVIDOR, exige-papel). Coage o corpo {nome, rotulo, email}; o controller injeta o
  atualizado-por do ator + faz UPSERT (1 por ente). 200 com o contato publico salvo {nome, rotulo, email}."
  [repo-participacao]
  (fn [req]
    (let [entrada (adapters-in-encarregado/coagir-encarregado (:json-params req))
          r       (controllers/definir-encarregado! repo-participacao (:ator req) entrada)]
      (http/json-resposta 200 (adapters-out-encarregado/publico->wire r)))))

;; ========================= FAST-FOLLOW Slice 5: Ouvidoria (Lei 13.460/2017 art. 10) =========================

(defn- protocolar-manifestacao-handler
  "POST /portal/ouvidoria/manifestacoes (cidadao, SO-auth — ANONIMA NAO E' SEM-AUTH). adapters/in coage o
  corpo (fail-closed 400); o controller decide o que persiste (manifestante/created-by nil quando anonima).
  201 com {protocolo, recibo-em}."
  [repo-participacao relogio acesso-restrito-desde]
  (fn [req]
    (let [entrada (adapters-in-manifestacao/coagir-manifestacao (:json-params req))
          r       (controllers/protocolar-manifestacao! repo-participacao relogio (:ator req) entrada)]
      (http/json-resposta 201 (adapters-out-manifestacao/recibo->wire r (restrita-desde acesso-restrito-desde req))))))

(defn- minha-manifestacao-handler
  "GET /portal/ouvidoria/manifestacoes/:id (manifestante). Policy fina no controller: ANONIMA -> nil SEMPRE
  (404, mesmo pro proprio autor); NAO-anonima -> ator == manifestante (403 global se nao). Ausente -> 404."
  [repo-participacao relogio]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [detalhe (controllers/minha-manifestacao repo-participacao (:ator req) relogio id)]
        (http/json-resposta 200 (adapters-out-manifestacao/manifestacao->wire detalhe))
        (http/json-resposta 404 {:erro "manifestacao nao encontrada"})))))

(defn- acompanhar-manifestacao-handler
  "GET /portal/casa/:ente/ouvidoria/acompanhar/:protocolo (PUBLICA, sem auth). resolver-ente-publico coage
  o :ente (-> 400 se malformado); o controller le sob o tenant (RLS isola). adapters/out FILTRA toda PII."
  [repo-participacao relogio resolver-ente-publico]
  (fn [req]
    (let [ente-id   (resolver-ente-publico (get-in req [:path-params :ente]))
          protocolo (get-in req [:path-params :protocolo])]
      (if-let [acomp (controllers/acompanhar-manifestacao-por-protocolo repo-participacao ente-id relogio protocolo)]
        (http/json-resposta 200 (adapters-out-acomp-ouvidoria/acompanhamento->wire acomp))
        (http/json-resposta 404 {:erro "manifestacao nao encontrada"})))))

(defn- responder-manifestacao-handler
  "POST /ouvidoria/manifestacoes/:id/resposta (SERVIDOR, exige-papel). nil -> 404; ja terminal -> 409."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-resposta-ouvidoria/coagir-resposta (:json-params req))]
      (responder-op #(controllers/responder-manifestacao! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-resposta-ouvidoria/resposta-recibo->wire 200))))

(defn- arquivar-manifestacao-handler
  "POST /ouvidoria/manifestacoes/:id/arquivar (SERVIDOR, exige-papel; `motivo` obrigatorio). nil -> 404;
  ja terminal -> 409."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-arquivar/coagir-arquivar (:json-params req))]
      (responder-op #(controllers/arquivar-manifestacao! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-resposta-ouvidoria/arquivar-recibo->wire 200))))

(defn- prorrogar-manifestacao-handler
  "POST /ouvidoria/manifestacoes/:id/prorrogar (SERVIDOR, exige-papel; `justificativa` obrigatoria).
  nil -> 404 (manifestacao/prazo inexistente); ja prorrogada/nao-pendente -> 409."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-prorrogar/coagir-prorrogar (:json-params req))]
      (responder-op #(controllers/prorrogar-manifestacao! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-resposta-ouvidoria/prorrogar-recibo->wire 200))))

;; ========================= FAST-FOLLOW Slice 6: Comentarios/moderacao (feature 6.3) =========================

(defn- comentar-handler
  "POST /portal/materias/:proposicao_id/comentarios (cidadao, SO-auth — SEM variante anonima). adapters/in
  coage o corpo (fail-closed 400); o :proposicao_id do path coage p/ UUID (400 se malformado). O controller
  injeta o autor do ator. 201 com {id, estado} (estado sempre 'pendente')."
  [repo-participacao]
  (fn [req]
    (let [proposicao-id (adapters-in/id-param->uuid (get-in req [:path-params :proposicao_id]))
          entrada       (adapters-in-comentario/coagir-comentario (:json-params req))
          r             (controllers/comentar! repo-participacao (:ator req) proposicao-id entrada)]
      (http/json-resposta 201 (adapters-out-comentario/recibo->wire r)))))

(defn- comentarios-da-materia-handler
  "GET /portal/casa/:ente/materias/:proposicao_id/comentarios (PUBLICA, sem auth). resolver-ente-publico
  coage o :ente (-> 400 se malformado); :proposicao_id tambem coage p/ UUID. So' aprovados — adapters/out
  FILTRA autor/tenant/estado de cada item."
  [repo-participacao resolver-ente-publico]
  (fn [req]
    (let [ente-id       (resolver-ente-publico (get-in req [:path-params :ente]))
          proposicao-id (adapters-in/id-param->uuid (get-in req [:path-params :proposicao_id]))
          cs            (controllers/comentarios-da-materia repo-participacao ente-id proposicao-id)]
      (http/json-resposta 200 (adapters-out-comentario/publicos->wire cs)))))

(defn- denunciar-comentario-handler
  "POST /portal/comentarios/:id/denunciar (cidadao, SO-auth). Coage o :id do comentario + o corpo
  {motivo?}. IDEMPOTENTE: nil -> 404 (comentario inexistente); existe -> 200 {denunciado true} SEMPRE
  (mesmo numa repeticao — nunca 409, denunciar de novo nao e' conflito de negocio)."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-denunciar/coagir-denunciar (:json-params req))]
      (if-let [r (controllers/denunciar-comentario! repo-participacao relogio (:ator req) id entrada)]
        (http/json-resposta 200 (adapters-out-denunciar/recibo->wire r))
        (http/json-resposta 404 {:erro "comentario nao encontrado"})))))

(defn- fila-moderacao-handler
  "GET /moderacao/comentarios (SERVIDOR, exige-papel). So' pendentes, denunciados primeiro — adapters/out
  NAO filtra PII (rota interna; o moderador ve o autor)."
  [repo-participacao]
  (fn [req]
    (let [cs (controllers/fila-moderacao repo-participacao (:ator req))]
      (http/json-resposta 200 (adapters-out-moderacao/fila->wire cs)))))

(defn- moderar-comentario-handler
  "POST /comentarios/:id/moderar (SERVIDOR, exige-papel). Coage o :id + o corpo {acao, motivo-rejeicao?}
  (a obrigatoriedade condicional + o vocabulario fixo do motivo sao checados no adapters/in). nil -> 404;
  ja moderado -> 409."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-moderar/coagir-moderar (:json-params req))]
      (responder-op #(controllers/moderar-comentario! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-moderacao/recibo->wire 200))))

;; ========================= BALCAO interno de atendimento (6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD) =========================

(defn- fila-do-balcao-handler
  "GET /atendimento/<especie>?situacao=abertos|respondidos|todos (SERVIDOR, exige-papel). A fila da Casa do ator,
  pelo prazo que vence primeiro (abertos) — ver db/atendimento. situacao invalida -> 400."
  [repo-participacao relogio especie]
  (fn [req]
    (let [situacao (adapters-in-atendimento/situacao (:query-params req))]
      (http/json-resposta 200 (adapters-out-atendimento/fila->wire
                               especie situacao
                               (controllers/fila-do-balcao repo-participacao relogio (:ator req) especie situacao))))))

(defn- detalhe-do-balcao-handler
  "GET /atendimento/<especie>/:id (SERVIDOR, exige-papel). O item inteiro, com o historico e as acoes cabiveis.
  Ausente na Casa -> 404. A identidade segue a lei (ver wire/out/atendimento)."
  [repo-participacao relogio pessoas detalhe ->wire]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [d (detalhe repo-participacao relogio pessoas (:ator req) id)]
        (http/json-resposta 200 (->wire d))
        (http/json-resposta 404 {:erro "nao encontrado"})))))

(defn- prorrogar-pedido-handler
  "POST /esic/pedidos/:id/prorrogar (SERVIDOR, exige-papel; `justificativa` obrigatoria — LAI art. 11 §2º). nil -> 404;
  ja' prorrogado/prazo nao pendente -> 409. Sucesso -> 200 {prorrogado-ate}."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-atendimento/coagir-prorrogar-pedido (:json-params req))]
      (responder-op #(controllers/prorrogar-pedido! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-resposta-ouvidoria/prorrogar-recibo->wire 200))))

;; ---------- o complemento da resposta (e-SIC, ouvidoria, LGPD): um texto, JSON ----------

(defn- complementar-handler
  "POST /atendimento/<especie>/:id/complementos (SERVIDOR, exige-papel; corpo JSON {corpo}, o texto obrigatorio). Acrescenta um
  COMPLEMENTO a um protocolo que a Casa ja' respondeu, a qualquer tempo (ADR-0022): nao muda estado nem prazo. 201 com o
  complemento (id, texto, instante — nunca quem escreveu); 400 texto vazio/grande; 404 protocolo inexistente ou de outra
  Casa; 409 em palavras se a Casa ainda nao respondeu (protocolo aberto, ou manifestacao so' arquivada)."
  [repo-participacao relogio especie]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-atendimento/coagir-complemento (:json-params req))]
      (try
        (if-let [c (controllers/complementar-resposta! repo-participacao relogio (:ator req) especie id entrada)]
          (assoc (http/json-resposta 201 (adapters-out-atendimento/complemento->wire c))
                 :auditoria {:rotulo "complementou a resposta do protocolo" :recurso-tipo (anexo/objeto-tipo-da-especie especie)
                             :recurso-id (str id)})
          (http/json-resposta 404 {:erro "protocolo nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/complemento-sem-resposta (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "Responda o pedido antes de complementar."})
            (throw e)))))))

;; ---------- anexos da resposta (e-SIC, ouvidoria, LGPD): um arquivo por requisicao, multipart ----------

(def ^:private anexo-multipart
  "O interceptor do upload: o generico do host com o teto de 10 MB do anexo do atendimento (413 acima, 400 sem arquivo)."
  (it/anexo-multipart {:max-bytes anexo/max-bytes-anexo}))

(def ^:private substituicao-multipart
  "O mesmo upload, para SUBSTITUIR um anexo: alem do arquivo, le o campo de texto `motivo` (ate' 4 KB), em
  `(:request :campos-do-envio)`. Nome distinto no cadeia (o do anexo comum e' outro interceptor)."
  (assoc (it/anexo-multipart {:max-bytes anexo/max-bytes-anexo :campos #{"motivo"}})
         :name ::substituicao-multipart))

(defn- erro-de-anexo
  "Os conflitos de anexo -> a resposta nomeada em portugues (nunca 500). nil = nao e' conflito daqui. `quem` = `:casa` (a
  secretaria anexa a RESPOSTA) ou `:requerente` (o cidadao anexa ao PEDIDO): o mesmo conflito, a frase de cada lado."
  [quem e]
  (case (:tipo (ex-data e))
    :conflito/tipo-de-anexo
    (http/json-resposta 415 {:erro (str "Tipo de arquivo não aceito. Aceitamos " anexo/descricao-dos-tipos
                                        ", e a extensão do nome tem de combinar com o tipo do arquivo.")})
    :conflito/conteudo-do-anexo
    (http/json-resposta 415 {:erro (str "O conteúdo do arquivo não confere com o formato do nome. Confira se é mesmo um arquivo "
                                        "de um destes tipos: " anexo/descricao-dos-tipos ".")})
    :conflito/cota-de-anexos
    (http/json-resposta 409 {:erro "Você já enviou muitos arquivos nas últimas 24 horas: o espaço por pessoa tem um limite. Tente de novo mais tarde."})
    :conflito/anexo-sem-resposta
    (http/json-resposta 409 {:erro "Este protocolo ainda não tem resposta da Casa: anexe depois de responder."})
    :conflito/anexo-fora-da-janela
    (http/json-resposta 409 {:erro (if (= :requerente quem)
                                     "Os anexos vão junto com o pedido: os 10 minutos depois do protocolo já passaram."
                                     "Os anexos vão junto com a resposta: os 10 minutos depois do último ato já passaram.")})
    :conflito/anexos-demais
    (http/json-resposta 409 {:erro (if (= :requerente quem)
                                     (str "Este protocolo já tem " anexo/max-anexos-do-requerente " anexos seus.")
                                     (str "Este protocolo já tem " anexo/max-anexos-da-casa " anexos da Casa."))})
    :conflito/anexo-nao-substituivel
    (http/json-resposta 409 {:erro (case (:motivo (ex-data e))
                                     :anexo-do-requerente "Só se substitui o arquivo da Casa. O que o requerente anexou pode ser retirado, mas não trocado."
                                     :ja-substituido "Este arquivo já foi substituído por outro."
                                     "Este arquivo já foi retirado e não pode ser substituído.")})
    :conflito/anexo-igual
    (http/json-resposta 409 {:erro "Este mesmo arquivo já está anexado ao protocolo. Escolha o arquivo certo para a troca."})
    nil))

(defn- pre-conferir-anexo
  "O interceptor que roda ANTES do multipart (e portanto antes de qualquer byte do corpo): confere, com o que o protocolo ja'
  tem, que o alvo existe e e' da Casa e (cidadao) que quem pede e' o dono -> senao 404 uniforme; que cabe anexar (janela,
  limite) -> senao 409. O instante da conferencia fica em `(:request :anexo-instante)` e e' o que vale para a janela, mesmo
  que o upload demore. E' economia, nao garantia: o controller confere tudo de novo, e o Repo o limite e a cota na tx.
  `pre-conferir` = (fn [repo relogio ator especie id] -> {:instante} | nil), que lanca o conflito."
  [quem pre-conferir repo-participacao relogio especie]
  {:name (keyword "oplenario.participacao.diplomat.http.in" (str "pre-conferir-anexo-" (name quem) "-" (name especie)))
   :enter (fn [ctx]
            (let [req (:request ctx)
                  id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
                  recusa (fn [resp] (chain/terminate (assoc ctx :response resp)))]
              (try
                (if-let [{:keys [instante]} (pre-conferir repo-participacao relogio (:ator req) especie id)]
                  (assoc-in ctx [:request :anexo-instante] instante)
                  (recusa (http/json-resposta 404 {:erro "protocolo nao encontrado"})))
                (catch clojure.lang.ExceptionInfo e
                  (if-let [resp (erro-de-anexo quem e)] (recusa resp) (throw e))))))})

(defn- pre-conferir-substituicao
  "O interceptor que roda ANTES do multipart da substituicao (nenhum byte do corpo lido): o protocolo e o anexo existem nesta
  Casa (senao 404) e o anexo pode ser substituido (senao 409). Sem janela de tempo: substituir vale a qualquer hora."
  [repo-participacao relogio especie]
  {:name (keyword "oplenario.participacao.diplomat.http.in" (str "pre-conferir-substituicao-" (name especie)))
   :enter (fn [ctx]
            (let [req (:request ctx)
                  id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
                  aid (adapters-in/id-param->uuid (get-in req [:path-params :anexo]))
                  recusa (fn [resp] (chain/terminate (assoc ctx :response resp)))]
              (try
                (if (controllers/pre-conferir-substituir-anexo repo-participacao relogio (:ator req) especie id aid)
                  ctx
                  (recusa (http/json-resposta 404 {:erro "anexo nao encontrado"})))
                (catch clojure.lang.ExceptionInfo e
                  (if-let [resp (erro-de-anexo :casa e)] (recusa resp) (throw e))))))})

(defn- anexar-handler
  "POST /atendimento/<especie>/:id/anexos (SERVIDOR, exige-papel; multipart, UM arquivo). 201 com o anexo (sem chave, sem
  sha256, sem quem enviou); 404 protocolo inexistente/de outra Casa; 415 tipo fora da lista; 409 sem resposta, fora da
  janela de 10 minutos ou 5 anexos; 413 acima de 10 MB (no interceptor)."
  [repo-participacao objeto-store relogio especie]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (try
        (if-let [a (controllers/anexar-ao-atendimento! repo-participacao objeto-store relogio (:ator req) especie id
                                                                  (assoc (:anexo req) :instante (:anexo-instante req)))]
          (assoc (http/json-resposta 201 (adapters-out-atendimento/anexo->wire a))
                 :auditoria {:rotulo (str "anexo em " (:protocolo a)) :recurso-tipo (anexo/objeto-tipo-da-especie especie)
                             :recurso-id (str id)})
          (http/json-resposta 404 {:erro "protocolo nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e (or (erro-de-anexo :casa e) (throw e)))))))

(defn- anexar-do-requerente-handler
  "POST /portal/meus-protocolos/<especie>/:id/anexos (CIDADAO, so-auth; multipart, UM arquivo). So' o DONO do protocolo,
  nos 10 minutos do protocolo, ate' 5 seus. 201 com o anexo (origem `requerente`; sem chave, sha256 nem quem enviou); 404
  para qualquer nao-dono, outra Casa, protocolo inexistente e manifestacao ANONIMA (uniforme); 415/409/413 como os da Casa."
  [repo-participacao objeto-store relogio especie]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (try
        (if-let [a (controllers/anexar-do-requerente! repo-participacao objeto-store relogio (:ator req) especie id
                                                                     (assoc (:anexo req) :instante (:anexo-instante req)))]
          (assoc (http/json-resposta 201 (adapters-out-atendimento/anexo->wire a))
                 :auditoria {:rotulo (str "anexo em " (:protocolo a)) :recurso-tipo (anexo/objeto-tipo-da-especie especie)
                             :recurso-id (str id)})
          (http/json-resposta 404 {:erro "protocolo nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e (or (erro-de-anexo :requerente e) (throw e)))))))

(defn- baixar-resposta
  "O anexo como ARQUIVO, sempre: `attachment` (o navegador nao renderiza o que a pessoa enviou) + nosniff, igual ao
  download dos comunicados. nil -> 404 (uniforme: nao distingue 'nao existe' de 'nao e' seu')."
  [achado]
  (if-let [{a :anexo in :stream} achado]
    {:status 200
     :headers {"Content-Type" (:tipo-midia a)
               "Content-Length" (str (:bytes a))
               "Content-Disposition" (arquivo/content-disposition (:nome a))
               "X-Content-Type-Options" "nosniff"}
     :body in}
    (http/json-resposta 404 {:erro "anexo nao encontrado"})))

(defn- baixar-anexo-handler
  "GET /atendimento/<especie>/:id/anexos/:anexo (SERVIDOR, exige-papel): o anexo do protocolo da Casa do ator."
  [repo-participacao objeto-store especie]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          aid (adapters-in/id-param->uuid (get-in req [:path-params :anexo]))
          resposta (baixar-resposta (controllers/baixar-anexo-do-atendimento repo-participacao objeto-store (:ator req) especie id aid))]
      ;; a secretaria que LE o arquivo de um cidadao fica na trilha (leitura sensivel): quem leu o que, e quando
      (cond-> resposta
        (= 200 (:status resposta))
        (assoc :auditoria {:classe "leitura_sensivel" :rotulo "baixou um anexo do protocolo"
                           :recurso-tipo (anexo/objeto-tipo-da-especie especie) :recurso-id (str id)})))))

(defn- retirar-anexo-handler
  "POST /atendimento/<especie>/:id/anexos/:anexo/retirar (SERVIDOR, exige-papel; `{motivo}` obrigatorio). Retira o anexo de
  QUALQUER origem: o blob sai do object storage, o download vira 404 para todos, a lista o mostra retirado. Idempotente.
  200 com o anexo (com `retirado-em` e `motivo-da-retirada`); 404 anexo/protocolo inexistente ou de outra Casa; 400 sem motivo."
  [repo-participacao objeto-store relogio especie]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          aid (adapters-in/id-param->uuid (get-in req [:path-params :anexo]))
          {:keys [motivo]} (adapters-in-atendimento/coagir-retirar-anexo (:json-params req))]
      (if-let [a (controllers/retirar-anexo! repo-participacao objeto-store relogio (:ator req) especie id aid motivo)]
        (assoc (http/json-resposta 200 (adapters-out-atendimento/anexo->wire a))
               :auditoria {:rotulo "retirou um anexo do protocolo" :recurso-tipo (anexo/objeto-tipo-da-especie especie)
                           :recurso-id (str id)})
        (http/json-resposta 404 {:erro "anexo nao encontrado"})))))

(defn- substituir-anexo-handler
  "POST /atendimento/<especie>/:id/anexos/:anexo/substituir (SERVIDOR, exige-papel; multipart com UM arquivo e o campo
  `motivo`, obrigatorio). Troca o anexo da CASA pelo arquivo novo num so' ato, a qualquer tempo: o antigo e' retirado (motivo
  gravado, blob fora, download 404) e o novo ocupa a vaga dele. 201 com o anexo NOVO; 404 protocolo/anexo inexistente ou de
  outra Casa; 409 do requerente, ja' substituido, ja' retirado ou o mesmo arquivo; 400 sem motivo (conferido ANTES de gravar
  o blob); 415 tipo; 413 acima de 10 MB (no interceptor)."
  [repo-participacao objeto-store relogio especie]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          aid (adapters-in/id-param->uuid (get-in req [:path-params :anexo]))
          {:keys [motivo]} (adapters-in-atendimento/coagir-substituir-anexo (:campos-do-envio req))]
      (try
        (if-let [{:keys [novo]} (controllers/substituir-anexo! repo-participacao objeto-store relogio (:ator req) especie id aid
                                                               motivo (:anexo req))]
          (assoc (http/json-resposta 201 (adapters-out-atendimento/anexo->wire novo))
                 :auditoria {:rotulo (str "substituiu um anexo em " (:protocolo novo)) :recurso-tipo (anexo/objeto-tipo-da-especie especie)
                             :recurso-id (str id)})
          (http/json-resposta 404 {:erro "anexo nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e (or (erro-de-anexo :casa e) (throw e)))))))

(defn- baixar-meu-anexo-handler
  "GET /portal/meus-protocolos/<especie>/:id/anexos/:anexo (REQUERENTE, so-auth): o anexo do PROPRIO protocolo. Quem nao e'
  o dono recebe 404 igual ao de 'nao existe'."
  [repo-participacao objeto-store especie]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          aid (adapters-in/id-param->uuid (get-in req [:path-params :anexo]))]
      (baixar-resposta (controllers/baixar-meu-anexo repo-participacao objeto-store (:ator req) especie id aid)))))

(defn- encarregado-da-casa-handler
  "GET /lgpd/encarregado (SERVIDOR, exige-papel): o contato do Encarregado da Casa do ATOR, para o balcao mostrar e
  editar sem conhecer o id do ente (a tela interna nao o tem). A mesma projecao publica {nome, rotulo, email} — o
  contato e' publico por lei (LGPD art. 41 §1º). Sem encarregado definido -> 404."
  [repo-participacao]
  (fn [req]
    (if-let [dpo (controllers/encarregado-publico repo-participacao (get-in req [:ator :ente-id]))]
      (http/json-resposta 200 (adapters-out-encarregado/publico->wire dpo))
      (http/json-resposta 404 {:erro "encarregado nao definido"}))))

(defn- rotas-do-balcao
  "As rotas do balcao (SERVIDOR, exige-papel 'secretario'), FORA de /portal. `/atendimento/<especie>` e
  `/atendimento/<especie>/:id`: o nivel 2 e' sempre literal (esic|ouvidoria|lgpd) e o :id fica sozinho no nivel 3 —
  sem literal e wildcard no MESMO nivel (o prefix-tree do Pedestal 0.7 nao admite, ver a docstring do ns)."
  [{:keys [auth repo-participacao relogio pessoas objeto-store]}]
  (let [servidor (fn [& its] (into [auth (it/exige-papel "secretario")] its))]
    #{["/atendimento/esic" :get (servidor (fila-do-balcao-handler repo-participacao relogio :esic))
       :route-name :participacao/fila-esic]
      ["/atendimento/ouvidoria" :get (servidor (fila-do-balcao-handler repo-participacao relogio :ouvidoria))
       :route-name :participacao/fila-ouvidoria]
      ["/atendimento/lgpd" :get (servidor (fila-do-balcao-handler repo-participacao relogio :lgpd))
       :route-name :participacao/fila-lgpd]
      ["/atendimento/esic/:id" :get
       (servidor (detalhe-do-balcao-handler repo-participacao relogio pessoas
                                            controllers/atendimento-esic adapters-out-atendimento/esic->wire))
       :route-name :participacao/atendimento-esic]
      ["/atendimento/ouvidoria/:id" :get
       ;; o controller so' nomeia quem respondeu pela Casa: o manifestante nunca chega ao seam
       (servidor (detalhe-do-balcao-handler repo-participacao relogio pessoas
                                            controllers/atendimento-ouvidoria adapters-out-atendimento/ouvidoria->wire))
       :route-name :participacao/atendimento-ouvidoria]
      ["/atendimento/lgpd/:id" :get
       (servidor (detalhe-do-balcao-handler repo-participacao relogio pessoas
                                            controllers/atendimento-lgpd adapters-out-atendimento/lgpd->wire))
       :route-name :participacao/atendimento-lgpd]
      ;; LAI art. 11 §2º: +10 dias, uma vez, com justificativa — irma de /esic/pedidos/:id/resposta (mesmo nivel)
      ["/lgpd/encarregado" :get (servidor (encarregado-da-casa-handler repo-participacao))
       :route-name :participacao/encarregado-da-casa]
      ["/esic/pedidos/:id/prorrogar" :post
       (servidor it/corpo-json (prorrogar-pedido-handler repo-participacao relogio))
       :route-name :participacao/prorrogar-pedido]
      ;; os ANEXOS da resposta: irmaos do detalhe (`:id` e depois um literal — o prefix-tree aceita, como em
      ;; `/comunicados/:id/anexos`). O upload e' multipart (nao `corpo-json`); o papel e' conferido ANTES de ler o corpo.
      ["/atendimento/esic/:id/anexos" :post
       (servidor (pre-conferir-anexo :casa controllers/pre-conferir-anexo-da-casa repo-participacao relogio :esic)
                anexo-multipart (anexar-handler repo-participacao objeto-store relogio :esic))
       :route-name :participacao/anexar-esic]
      ["/atendimento/ouvidoria/:id/anexos" :post
       (servidor (pre-conferir-anexo :casa controllers/pre-conferir-anexo-da-casa repo-participacao relogio :ouvidoria)
                anexo-multipart (anexar-handler repo-participacao objeto-store relogio :ouvidoria))
       :route-name :participacao/anexar-ouvidoria]
      ["/atendimento/lgpd/:id/anexos" :post
       (servidor (pre-conferir-anexo :casa controllers/pre-conferir-anexo-da-casa repo-participacao relogio :lgpd)
                anexo-multipart (anexar-handler repo-participacao objeto-store relogio :lgpd))
       :route-name :participacao/anexar-lgpd]
      ;; o COMPLEMENTO da resposta (ADR-0022): irmao de `/anexos` (`:id` e depois um literal). Corpo JSON, so' a secretaria.
      ["/atendimento/esic/:id/complementos" :post
       (servidor it/corpo-json (complementar-handler repo-participacao relogio :esic))
       :route-name :participacao/complementar-esic]
      ["/atendimento/ouvidoria/:id/complementos" :post
       (servidor it/corpo-json (complementar-handler repo-participacao relogio :ouvidoria))
       :route-name :participacao/complementar-ouvidoria]
      ["/atendimento/lgpd/:id/complementos" :post
       (servidor it/corpo-json (complementar-handler repo-participacao relogio :lgpd))
       :route-name :participacao/complementar-lgpd]
      ;; RETIRAR um anexo (incidente de conteudo): o corpo e' JSON {motivo}; so' a secretaria. Irma do download (mesmo nivel)
      ["/atendimento/esic/:id/anexos/:anexo/retirar" :post
       (servidor it/corpo-json (retirar-anexo-handler repo-participacao objeto-store relogio :esic))
       :route-name :participacao/retirar-anexo-esic]
      ["/atendimento/ouvidoria/:id/anexos/:anexo/retirar" :post
       (servidor it/corpo-json (retirar-anexo-handler repo-participacao objeto-store relogio :ouvidoria))
       :route-name :participacao/retirar-anexo-ouvidoria]
      ["/atendimento/lgpd/:id/anexos/:anexo/retirar" :post
       (servidor it/corpo-json (retirar-anexo-handler repo-participacao objeto-store relogio :lgpd))
       :route-name :participacao/retirar-anexo-lgpd]
      ;; SUBSTITUIR um anexo da Casa (ADR-0022): multipart com o arquivo novo + o campo `motivo`; so' a secretaria, a qualquer tempo.
      ;; Alvo e estado conferidos ANTES de ler o corpo.
      ["/atendimento/esic/:id/anexos/:anexo/substituir" :post
       (servidor (pre-conferir-substituicao repo-participacao relogio :esic)
                substituicao-multipart (substituir-anexo-handler repo-participacao objeto-store relogio :esic))
       :route-name :participacao/substituir-anexo-esic]
      ["/atendimento/ouvidoria/:id/anexos/:anexo/substituir" :post
       (servidor (pre-conferir-substituicao repo-participacao relogio :ouvidoria)
                substituicao-multipart (substituir-anexo-handler repo-participacao objeto-store relogio :ouvidoria))
       :route-name :participacao/substituir-anexo-ouvidoria]
      ["/atendimento/lgpd/:id/anexos/:anexo/substituir" :post
       (servidor (pre-conferir-substituicao repo-participacao relogio :lgpd)
                substituicao-multipart (substituir-anexo-handler repo-participacao objeto-store relogio :lgpd))
       :route-name :participacao/substituir-anexo-lgpd]
      ["/atendimento/esic/:id/anexos/:anexo" :get
       (servidor (baixar-anexo-handler repo-participacao objeto-store :esic))
       :route-name :participacao/baixar-anexo-esic]
      ["/atendimento/ouvidoria/:id/anexos/:anexo" :get
       (servidor (baixar-anexo-handler repo-participacao objeto-store :ouvidoria))
       :route-name :participacao/baixar-anexo-ouvidoria]
      ["/atendimento/lgpd/:id/anexos/:anexo" :get
       (servidor (baixar-anexo-handler repo-participacao objeto-store :lgpd))
       :route-name :participacao/baixar-anexo-lgpd]}))

(defn rotas
  "Fragmento de rotas do modulo participacao (table syntax Pedestal). Recebe o interceptor `auth`
  (compartilhado), o `repo-participacao` (Repo-Component), o `resolver-ente-publico` (seam do host p/ a rota
  publica) e o `relogio` (kernel/tempo — injetavel em teste). `oplenario.rotas` funde este fragmento ao
  conjunto. POST usa `corpo-json`; as rotas GET nao tem corpo. `acesso-restrito-desde` (opcional, ADR-0018) = seam
  do host (fn [ente-id] -> Instant|nil): o recibo dos protocolos do cidadao diz que a Casa esta' com o sistema restrito.
  `pessoas` (opcional) = seam do host do BALCAO (fn [identidade-ids] -> {id {:nome :cpf-mascarado}}, o CPF ja' mascarado
  no banco): o nome do requerente do e-SIC/titular LGPD e de quem respondeu. Sem ele, ninguem e' nomeado."
  [{:keys [auth repo-participacao resolver-ente-publico relogio acesso-restrito-desde objeto-store] :as deps}]
  (into
   (rotas-do-balcao deps)
   #{["/portal/esic/pedidos" :post
      [auth it/corpo-json (protocolar-handler repo-participacao relogio acesso-restrito-desde)]
      :route-name :participacao/protocolar-esic]
     ;; CIDADA: o que ela protocolou (formularios do cidadao). Literal no nivel 2 — sem colisao com `casa/:ente`.
     ["/portal/meus-protocolos" :get
      [auth (meus-protocolos-handler repo-participacao relogio)]
      :route-name :participacao/meus-protocolos]
     ;; CIDADA: baixar o anexo que a Casa juntou a resposta do PROPRIO protocolo (so' o dono; qualquer outro, 404).
     ;; Literal `esic|ouvidoria|lgpd` no nivel 3 e o `:id` so' no nivel 4: sem wildcard ao lado de literal.
     ["/portal/meus-protocolos/esic/:id/anexos/:anexo" :get
      [auth (baixar-meu-anexo-handler repo-participacao objeto-store :esic)]
      :route-name :participacao/baixar-meu-anexo-esic]
     ["/portal/meus-protocolos/ouvidoria/:id/anexos/:anexo" :get
      [auth (baixar-meu-anexo-handler repo-participacao objeto-store :ouvidoria)]
      :route-name :participacao/baixar-meu-anexo-ouvidoria]
     ["/portal/meus-protocolos/lgpd/:id/anexos/:anexo" :get
      [auth (baixar-meu-anexo-handler repo-participacao objeto-store :lgpd)]
      :route-name :participacao/baixar-meu-anexo-lgpd]
     ;; CIDADA: anexar ao PROPRIO pedido (so' o dono, 10 min do protocolo, ate' 5). `auth` SEM papel (como protocolar); o
     ;; upload e' multipart. NAO existe rota publica de upload: a manifestacao anonima nao tem dono, nao tem anexo.
     ["/portal/meus-protocolos/esic/:id/anexos" :post
      [auth (pre-conferir-anexo :requerente controllers/pre-conferir-anexo-do-requerente repo-participacao relogio :esic)
       anexo-multipart (anexar-do-requerente-handler repo-participacao objeto-store relogio :esic)]
      :route-name :participacao/anexar-meu-esic]
     ["/portal/meus-protocolos/ouvidoria/:id/anexos" :post
      [auth (pre-conferir-anexo :requerente controllers/pre-conferir-anexo-do-requerente repo-participacao relogio :ouvidoria)
       anexo-multipart (anexar-do-requerente-handler repo-participacao objeto-store relogio :ouvidoria)]
      :route-name :participacao/anexar-meu-ouvidoria]
     ["/portal/meus-protocolos/lgpd/:id/anexos" :post
      [auth (pre-conferir-anexo :requerente controllers/pre-conferir-anexo-do-requerente repo-participacao relogio :lgpd)
       anexo-multipart (anexar-do-requerente-handler repo-participacao objeto-store relogio :lgpd)]
      :route-name :participacao/anexar-meu-lgpd]
     ["/portal/esic/pedidos/:id" :get
      [auth (meu-pedido-handler repo-participacao relogio)]
      :route-name :participacao/meu-pedido-esic]
     ;; disambiguador estatico `casa/` (NAO `/portal/:ente/...`): o router prefix-tree do Pedestal 0.7 nao
     ;; admite um wildcard (`:ente`) e um literal (`esic`) no MESMO nivel de path — o wildcard sombrearia
     ;; `/portal/esic/pedidos` (404). O segmento `casa/` mantem depth-2 sempre literal; o :ente cai em subarvore
     ;; propria. (Alternativa: router :linear-search global — descartada, impacto/perf host-wide.)
     ["/portal/casa/:ente/esic/acompanhar/:protocolo" :get
      [(acompanhar-handler repo-participacao relogio resolver-ente-publico)]
      :route-name :participacao/acompanhar-esic]
     ;; ---- Slice 2: ciclo de resposta + recurso ----
     ;; CIDADAO: interpor recurso (so-auth, sem papel — LAI: qualquer solicitante recorre; a policy fina [dono]
     ;; mora no controller). Sob /portal (superficie do cidadao).
     ["/portal/esic/pedidos/:id/recursos" :post
      [auth it/corpo-json (interpor-recurso-handler repo-participacao relogio acesso-restrito-desde)]
      :route-name :participacao/interpor-recurso]
     ;; SERVIDOR: responder pedido / decidir recurso (exige-papel "secretario"). FORA de /portal (balcao interno).
     ["/esic/pedidos/:id/resposta" :post
      [auth (it/exige-papel "secretario") it/corpo-json (responder-pedido-handler repo-participacao relogio)]
      :route-name :participacao/responder-pedido]
     ;; a recusa fundamentada (LAI art. 11 §1º II): irma de /resposta (mesmo nivel, mesmo papel)
     ["/esic/pedidos/:id/indeferir" :post
      [auth (it/exige-papel "secretario") it/corpo-json (indeferir-pedido-handler repo-participacao relogio)]
      :route-name :participacao/indeferir-pedido]
     ["/esic/recursos/:id/decisao" :post
      [auth (it/exige-papel "secretario") it/corpo-json (decidir-recurso-handler repo-participacao relogio)]
      :route-name :participacao/decidir-recurso]
     ;; ---- Slice 4: LGPD — portal do titular + contato do Encarregado/DPO ----
     ;; TITULAR: solicitar exercicio de direito (SO-auth, sem papel — qualquer titular pede sobre os PROPRIOS
     ;; dados). Sob /portal (superficie do cidadao/titular).
     ["/portal/lgpd/solicitacoes" :post
      [auth it/corpo-json (solicitar-titular-handler repo-participacao relogio acesso-restrito-desde)]
      :route-name :participacao/solicitar-titular]
     ["/portal/lgpd/solicitacoes/:id" :get
      [auth (minha-solicitacao-handler repo-participacao relogio)]
      :route-name :participacao/minha-solicitacao]
     ;; PUBLICA (sem auth): contato do Encarregado/DPO e' legalmente publico (LGPD art. 41 §1º). Reusa o
     ;; disambiguador estatico `casa/` (o :ente cai na subarvore propria; ver a rota de acompanhar acima).
     ["/portal/casa/:ente/encarregado" :get
      [(encarregado-publico-handler repo-participacao resolver-ente-publico)]
      :route-name :participacao/encarregado-publico]
     ;; SERVIDOR (exige-papel "secretario"), FORA de /portal (balcao interno): responder a solicitacao + definir
     ;; o contato do Encarregado (upsert 1-por-ente).
     ["/lgpd/solicitacoes/:id/resposta" :post
      [auth (it/exige-papel "secretario") it/corpo-json (responder-solicitacao-handler repo-participacao relogio)]
      :route-name :participacao/responder-solicitacao]
     ;; a recusa fundamentada (LGPD art. 18 §4º): irma de /resposta (mesmo nivel, mesmo papel)
     ["/lgpd/solicitacoes/:id/indeferir" :post
      [auth (it/exige-papel "secretario") it/corpo-json (indeferir-solicitacao-handler repo-participacao relogio)]
      :route-name :participacao/indeferir-solicitacao]
     ["/lgpd/encarregado" :put
      [auth (it/exige-papel "secretario") it/corpo-json (definir-encarregado-handler repo-participacao)]
      :route-name :participacao/definir-encarregado]
     ;; ---- FAST-FOLLOW Slice 5: Ouvidoria (Lei 13.460 art. 10) ----
     ;; CIDADAO: protocolar manifestacao (SO-auth — ANONIMA NAO E' SEM-AUTH, ver docstring do handler).
     ["/portal/ouvidoria/manifestacoes" :post
      [auth it/corpo-json (protocolar-manifestacao-handler repo-participacao relogio acesso-restrito-desde)]
      :route-name :participacao/protocolar-manifestacao]
     ["/portal/ouvidoria/manifestacoes/:id" :get
      [auth (minha-manifestacao-handler repo-participacao relogio)]
      :route-name :participacao/minha-manifestacao]
     ;; PUBLICA (sem auth): reusa o disambiguador estatico `casa/` (mesmo racional de acompanhar-esic/
     ;; encarregado-publico — o router prefix-tree do Pedestal 0.7 nao admite wildcard+literal no mesmo nivel).
     ["/portal/casa/:ente/ouvidoria/acompanhar/:protocolo" :get
      [(acompanhar-manifestacao-handler repo-participacao relogio resolver-ente-publico)]
      :route-name :participacao/acompanhar-manifestacao]
     ;; SERVIDOR (exige-papel "secretario"), FORA de /portal (balcao interno).
     ["/ouvidoria/manifestacoes/:id/resposta" :post
      [auth (it/exige-papel "secretario") it/corpo-json (responder-manifestacao-handler repo-participacao relogio)]
      :route-name :participacao/responder-manifestacao]
     ["/ouvidoria/manifestacoes/:id/arquivar" :post
      [auth (it/exige-papel "secretario") it/corpo-json (arquivar-manifestacao-handler repo-participacao relogio)]
      :route-name :participacao/arquivar-manifestacao]
     ["/ouvidoria/manifestacoes/:id/prorrogar" :post
      [auth (it/exige-papel "secretario") it/corpo-json (prorrogar-manifestacao-handler repo-participacao relogio)]
      :route-name :participacao/prorrogar-manifestacao]
     ;; ---- FAST-FOLLOW Slice 6: Comentarios/moderacao (feature 6.3) ----
     ;; CIDADAO: comentar (SO-auth, SEM variante anonima). Sob /portal (superficie do cidadao).
     ["/portal/materias/:proposicao_id/comentarios" :post
      [auth it/corpo-json (comentar-handler repo-participacao)]
      :route-name :participacao/comentar]
     ;; PUBLICA (sem auth): reusa o disambiguador estatico `casa/` (mesmo racional das demais rotas publicas
     ;; — o router prefix-tree do Pedestal 0.7 nao admite wildcard+literal no mesmo nivel).
     ["/portal/casa/:ente/materias/:proposicao_id/comentarios" :get
      [(comentarios-da-materia-handler repo-participacao resolver-ente-publico)]
      :route-name :participacao/comentarios-da-materia]
     ;; CIDADAO: denunciar (SO-auth, IDEMPOTENTE). Sob /portal.
     ["/portal/comentarios/:id/denunciar" :post
      [auth it/corpo-json (denunciar-comentario-handler repo-participacao relogio)]
      :route-name :participacao/denunciar-comentario]
     ;; SERVIDOR (exige-papel "secretario"), FORA de /portal (balcao interno). A fila vive em
     ;; `/moderacao/comentarios` (NAO `/comentarios/moderacao`) — ver a DECISAO DE ROTEAMENTO na docstring do
     ;; ns (colisao wildcard/literal do Pedestal 0.7 com POST /comentarios/:id/moderar).
     ["/moderacao/comentarios" :get
      [auth (it/exige-papel "secretario") (fila-moderacao-handler repo-participacao)]
      :route-name :participacao/fila-moderacao]
     ["/comentarios/:id/moderar" :post
      [auth (it/exige-papel "secretario") it/corpo-json (moderar-comentario-handler repo-participacao relogio)]
      :route-name :participacao/moderar-comentario]}))

;; ========================= FE Onda A1: cumprimento de prazo do e-SIC (§16.11) =========================

(defn esic-cumprimento-wire
  "Ponto de entrada IN-PROCESS do cumprimento e-SIC (FE Onda A1) — gemeo nao-HTTP p/ o host compor o
  dashboard da Mesa (mirror `painel-wire` de compliance). Passa pelo controller (nunca pelo Repo-Component
  direto — ADR-0001) + o MESMO gate adapters/out (deriva percentual+valida) que uma rota HTTP usaria.

  CONVENCAO DE AUTHZ (mesmo contrato de `painel-wire`/`consultar-sessao`): esta fn NAO re-verifica papel/
  permissao; o ENDPOINT COMPONHEDOR e' o unico ponto de enforcement (GET /paineis/mesa, papel 'secretario').
  QUALQUER novo caller DEVE aplicar o gate antes — senao expoe cumprimento de prazo tenant-wide a um papel
  qualquer. Nao ha lint que force isso: e' convencao, mantida por revisao."
  [repo-participacao ente-id]
  (adapters-out-esic-cumprimento/esic-cumprimento->wire (controllers/esic-cumprimento repo-participacao ente-id)))
