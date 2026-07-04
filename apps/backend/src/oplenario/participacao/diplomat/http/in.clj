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
  == titular). (2) POST /lgpd/solicitacoes/:id/resposta e PUT /lgpd/encarregado = SERVIDOR (auth + exige-papel
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
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.participacao.adapters.in.arquivar-ouvidoria :as adapters-in-arquivar]
            [oplenario.participacao.adapters.in.comentario :as adapters-in-comentario]
            [oplenario.participacao.adapters.in.denunciar-comentario :as adapters-in-denunciar]
            [oplenario.participacao.adapters.in.encarregado :as adapters-in-encarregado]
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
            [oplenario.participacao.adapters.out.comentario :as adapters-out-comentario]
            [oplenario.participacao.adapters.out.denuncia-comentario :as adapters-out-denunciar]
            [oplenario.participacao.adapters.out.encarregado :as adapters-out-encarregado]
            [oplenario.participacao.adapters.out.esic-cumprimento :as adapters-out-esic-cumprimento]
            [oplenario.participacao.adapters.out.manifestacao-ouvidoria :as adapters-out-manifestacao]
            [oplenario.participacao.adapters.out.moderacao-comentario :as adapters-out-moderacao]
            [oplenario.participacao.adapters.out.pedido-esic :as adapters-out-pedido]
            [oplenario.participacao.adapters.out.recurso-esic :as adapters-out-recurso]
            [oplenario.participacao.adapters.out.resposta-esic :as adapters-out-resposta]
            [oplenario.participacao.adapters.out.resposta-ouvidoria :as adapters-out-resposta-ouvidoria]
            [oplenario.participacao.adapters.out.solicitacao-titular :as adapters-out-titular]
            [oplenario.participacao.controllers :as controllers]))

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

(defn- protocolar-handler
  "POST /portal/esic/pedidos (cidadao). adapters/in coage o corpo (fail-closed 400); o controller injeta o
  solicitante do ator + o recibo do relogio. 201 com {protocolo, recibo-em} (recibo instantaneo LAI)."
  [repo-participacao relogio]
  (fn [req]
    (let [entrada (adapters-in/coagir-pedido (:json-params req))
          r       (controllers/protocolar-pedido repo-participacao relogio (:ator req) entrada)]
      (http/json-resposta 201 (adapters-out-pedido/recibo->wire r)))))

(defn- meu-pedido-handler
  "GET /portal/esic/pedidos/:id (solicitante). Policy fina no controller (ator == solicitante -> 403 global).
  Ausente no tenant -> 404. adapters/out projeta+filtra (sem tenant, sem id de solicitante)."
  [repo-participacao relogio]
  (fn [req]
    (let [id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [detalhe (controllers/meu-pedido repo-participacao (:ator req) relogio id)]
        (http/json-resposta 200 (adapters-out-pedido/pedido->wire detalhe))
        (http/json-resposta 404 {:erro "pedido nao encontrado"})))))

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
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-recurso/coagir-recurso (:json-params req))]
      (responder-op #(controllers/interpor-recurso! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-recurso/recibo->wire 201))))

(defn- responder-pedido-handler
  "POST /esic/pedidos/:id/resposta (SERVIDOR, exige-papel). Coage o :id + o corpo {corpo}. nil -> 404;
  ja respondido -> 409. Sucesso -> 200 {respondida-em}."
  [repo-participacao relogio]
  (fn [req]
    (let [id      (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          entrada (adapters-in-resposta/coagir-resposta (:json-params req))]
      (responder-op #(controllers/responder-pedido! repo-participacao relogio (:ator req) id entrada)
                    adapters-out-resposta/recibo->wire 200))))

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
  [repo-participacao relogio]
  (fn [req]
    (let [entrada (adapters-in-titular/coagir-solicitar (:json-params req))
          r       (controllers/solicitar-titular! repo-participacao relogio (:ator req) entrada)]
      (http/json-resposta 201 (adapters-out-titular/recibo->wire r)))))

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
  [repo-participacao relogio]
  (fn [req]
    (let [entrada (adapters-in-manifestacao/coagir-manifestacao (:json-params req))
          r       (controllers/protocolar-manifestacao! repo-participacao relogio (:ator req) entrada)]
      (http/json-resposta 201 (adapters-out-manifestacao/recibo->wire r)))))

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

(defn rotas
  "Fragmento de rotas do modulo participacao (table syntax Pedestal). Recebe o interceptor `auth`
  (compartilhado), o `repo-participacao` (Repo-Component), o `resolver-ente-publico` (seam do host p/ a rota
  publica) e o `relogio` (kernel/tempo — injetavel em teste). `oplenario.rotas` funde este fragmento ao
  conjunto. POST usa `corpo-json`; as rotas GET nao tem corpo."
  [{:keys [auth repo-participacao resolver-ente-publico relogio]}]
  #{["/portal/esic/pedidos" :post
     [auth it/corpo-json (protocolar-handler repo-participacao relogio)]
     :route-name :participacao/protocolar-esic]
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
     [auth it/corpo-json (interpor-recurso-handler repo-participacao relogio)]
     :route-name :participacao/interpor-recurso]
    ;; SERVIDOR: responder pedido / decidir recurso (exige-papel "secretario"). FORA de /portal (balcao interno).
    ["/esic/pedidos/:id/resposta" :post
     [auth (it/exige-papel "secretario") it/corpo-json (responder-pedido-handler repo-participacao relogio)]
     :route-name :participacao/responder-pedido]
    ["/esic/recursos/:id/decisao" :post
     [auth (it/exige-papel "secretario") it/corpo-json (decidir-recurso-handler repo-participacao relogio)]
     :route-name :participacao/decidir-recurso]
    ;; ---- Slice 4: LGPD — portal do titular + contato do Encarregado/DPO ----
    ;; TITULAR: solicitar exercicio de direito (SO-auth, sem papel — qualquer titular pede sobre os PROPRIOS
    ;; dados). Sob /portal (superficie do cidadao/titular).
    ["/portal/lgpd/solicitacoes" :post
     [auth it/corpo-json (solicitar-titular-handler repo-participacao relogio)]
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
    ["/lgpd/encarregado" :put
     [auth (it/exige-papel "secretario") it/corpo-json (definir-encarregado-handler repo-participacao)]
     :route-name :participacao/definir-encarregado]
    ;; ---- FAST-FOLLOW Slice 5: Ouvidoria (Lei 13.460 art. 10) ----
    ;; CIDADAO: protocolar manifestacao (SO-auth — ANONIMA NAO E' SEM-AUTH, ver docstring do handler).
    ["/portal/ouvidoria/manifestacoes" :post
     [auth it/corpo-json (protocolar-manifestacao-handler repo-participacao relogio)]
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
     :route-name :participacao/moderar-comentario]})

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
