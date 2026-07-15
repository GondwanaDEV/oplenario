(ns oplenario.cadastros.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do cadastros (§22.10 diplomat/http/in, ADR-0001) — a borda HTTP do modulo.
  Task 5: duas rotas de LEITURA (GET) — lista de vereadores (`/cadastros/vereadores`) + ficha composta de um
  vereador (`/cadastros/vereadores/:id`). Task 6: 4 rotas de ESCRITA (criar/editar vereador, registrar
  mandato/licenca) + GET `/cadastros/legislatura-vigente` (leitura auxiliar p/ o seletor do form de mandato).
  Mesmo gate grosso (papel 'secretario') em TODAS; sem authz fina (nenhum recurso 'de posse' aqui). `data`
  (LocalDate `hoje`) e' resolvida AQUI, na borda, a partir do `relogio` injetado (mesmo padrao de
  `meu-voto-handler` no legislativo, review MEDIUM fe-11-parecer) — o controller nunca le o relogio. `id` do
  path e' um UUID coagido AQUI (`parse-uuid`); um path param que nao parseia (nem um vereador de outro
  tenant, nem um id mal formado) -> 404 uniforme, NUNCA 500 (mesmo contrato de 404 das rotas irmas de
  legislativo). Conflitos de dominio (`:conflito/mandato-sobreposto`, `:conflito/sem-mandato-vigente`) sao
  capturados LOCALMENTE em cada handler de escrita -> 409 (nunca sobem ao interceptor global `erro`, que so'
  sabe mapear `:validacao/invalido` -> 400)."
  (:require [oplenario.cadastros.adapters.in.vereador :as adapters-in]
            [oplenario.cadastros.adapters.out.legislatura :as adapters-leg]
            [oplenario.cadastros.adapters.out.vereador :as adapters]
            [oplenario.cadastros.controllers :as controllers]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time ZoneId)))

(set! *warn-on-reflection* true)

;; fuso civil p/ `hoje` — mesma constante de legislativo.diplomat.http.in/zona-civil e
;; participacao.controllers/zona-civil (mandato/cargo-na-Mesa vigentes correm por data civil, nao UTC).
(def ^:private zona-civil (ZoneId/of "America/Fortaleza"))

(defn- listar-handler
  "GET /cadastros/vereadores. Envelope {:vereadores [VereadorLinhaOut ...]} — mapa fechado, espaco p/
  metadata futura (nunca o array cru na raiz). O corpo 200 e' o envelope INTEIRO validado contra
  wire/ListaVereadoresOut (`lista-envelope->wire`), nao montado inline aqui."
  [repo relogio]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)]
      (http/json-resposta 200 (adapters/lista-envelope->wire (controllers/listar-vereadores repo ente-id hoje))))))

(defn- ficha-handler
  "GET /cadastros/vereadores/:id. `id` invalido (nao-UUID) -> `parse-uuid` nil -> mesmo caminho 404 do
  vereador inexistente/de-outro-tenant (o controller so' e' chamado com id nao-nil, `and` curto-circuita)."
  [repo relogio]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (parse-uuid (get-in req [:path-params :id]))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)]
      (if-let [f (and id (controllers/ficha-vereador repo ente-id id hoje))]
        (http/json-resposta 200 (adapters/ficha->wire f))
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

(defn- criar-vereador-handler
  "POST /cadastros/vereadores. Corpo invalido -> adapters/in lanca :validacao/invalido -> 400 (interceptor
  global `erro`, nunca tratado aqui)."
  [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in/criar-vereador->dominio ator (:json-params req))]
      (http/json-resposta 201 (controllers/criar-vereador repo ente-id m)))))

(defn- editar-vereador-handler
  "PATCH /cadastros/vereadores/:id. `id` malformado -> `parse-uuid` nil -> mesmo caminho 404 (nunca 500)."
  [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          campos (adapters-in/editar-vereador->dominio (:json-params req))]
      (if (and id (pos? (controllers/editar-vereador repo ente-id id campos)))
        (http/json-resposta 200 {:id (str id)})
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

(defn- registrar-mandato-handler
  "POST /cadastros/vereadores/:id/mandatos. 409 (mandato sobreposto) capturado AQUI, local — nunca vira 500
  via o interceptor global `erro`."
  [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          m (adapters-in/registrar-mandato->dominio ator id (:json-params req))]
      (try
        (if-let [r (and id (controllers/registrar-mandato repo ente-id m))]
          (http/json-resposta 201 r)
          (http/json-resposta 404 {:erro "vereador ou legislatura nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/mandato-sobreposto (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "ja existe mandato vigente sobreposto para este vereador"})
            (throw e)))))))

(defn- registrar-licenca-handler
  "POST /cadastros/vereadores/:id/licencas. `hoje` resolvido AQUI, na borda, a partir do `relogio` injetado
  (mesmo padrao de listar-handler/ficha-handler). 409 (sem mandato vigente) capturado local."
  [repo relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)
          l (adapters-in/registrar-licenca->dominio ator (:json-params req))]
      (try
        (if-let [r (and id (controllers/registrar-licenca repo ente-id id l hoje))]
          (http/json-resposta 201 r)
          (http/json-resposta 404 {:erro "vereador nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sem-mandato-vigente (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "vereador sem mandato vigente para licenciar"})
            (throw e)))))))

(defn- legislatura-vigente-handler
  "GET /cadastros/legislatura-vigente. Sem legislatura vigente -> 404 (nunca corpo vazio 200)."
  [repo]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))]
      (if-let [leg (controllers/legislatura-vigente repo ente-id)]
        (http/json-resposta 200 (adapters-leg/->wire leg))
        (http/json-resposta 404 {:erro "nenhuma legislatura vigente"})))))

(defn rotas
  "Fragmento de rotas da leitura de vereadores (table syntax Pedestal). Recebe o interceptor `auth`
  (compartilhado), o `repo-cadastros` (Repo-Component do proprio modulo) e o `relogio` (kernel/tempo,
  injetado pelo host — producao le o relogio do sistema, teste crava o instante; mesmo contrato de
  `legislativo-http/rotas`/`participacao-http/rotas`). Ambas as rotas EXIGEM a authz grossa (papel
  'secretario')."
  [{:keys [auth repo-cadastros relogio]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/cadastros/vereadores"     :get [auth papel (listar-handler repo-cadastros relogio)]
       :route-name :cadastros/listar-vereadores]
      ["/cadastros/vereadores/:id" :get [auth papel (ficha-handler repo-cadastros relogio)]
       :route-name :cadastros/ficha-vereador]
      ["/cadastros/vereadores" :post [auth papel it/corpo-json (criar-vereador-handler repo-cadastros)]
       :route-name :cadastros/criar-vereador]
      ["/cadastros/vereadores/:id" :patch [auth papel it/corpo-json (editar-vereador-handler repo-cadastros)]
       :route-name :cadastros/editar-vereador]
      ["/cadastros/vereadores/:id/mandatos" :post
       [auth papel it/corpo-json (registrar-mandato-handler repo-cadastros)]
       :route-name :cadastros/registrar-mandato]
      ["/cadastros/vereadores/:id/licencas" :post
       [auth papel it/corpo-json (registrar-licenca-handler repo-cadastros relogio)]
       :route-name :cadastros/registrar-licenca]
      ["/cadastros/legislatura-vigente" :get [auth papel (legislatura-vigente-handler repo-cadastros)]
       :route-name :cadastros/legislatura-vigente]}))
