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
  com-tenant* com ele (a RLS ISOLA mesmo sem ator); a saida FILTRA PII no adapters/out."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.participacao.adapters.in.pedido-esic :as adapters-in]
            [oplenario.participacao.adapters.out.acompanhamento :as adapters-out-acomp]
            [oplenario.participacao.adapters.out.pedido-esic :as adapters-out-pedido]
            [oplenario.participacao.controllers :as controllers]))

(set! *warn-on-reflection* true)

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
     :route-name :participacao/acompanhar-esic]})
