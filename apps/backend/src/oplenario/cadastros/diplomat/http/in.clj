(ns oplenario.cadastros.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do cadastros (§22.10 diplomat/http/in, ADR-0001) — PRIMEIRA borda HTTP do
  modulo (Task 5). Duas rotas de LEITURA (GET) so': lista de vereadores (`/cadastros/vereadores`) + ficha
  composta de um vereador (`/cadastros/vereadores/:id`). Mesmo gate grosso (papel 'secretario') das rotas
  irmas de legislativo/paineis; sem authz fina (nenhum recurso 'de posse' aqui). `data` (LocalDate `hoje`) e'
  resolvida AQUI, na borda, a partir do `relogio` injetado (mesmo padrao de `meu-voto-handler` no
  legislativo, review MEDIUM fe-11-parecer) — o controller nunca le o relogio. `id` do path e' um UUID
  coagido AQUI (`parse-uuid`); um path param que nao parseia (nem um vereador de outro tenant, nem um id mal
  formado) -> 404 uniforme, NUNCA 500 (mesmo contrato de 404 das rotas irmas de legislativo)."
  (:require [oplenario.cadastros.adapters.out.vereador :as adapters]
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
  metadata futura (nunca o array cru na raiz)."
  [repo relogio]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)]
      (http/json-resposta 200 {:vereadores (adapters/lista->wire (controllers/listar-vereadores repo ente-id hoje))}))))

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

(defn rotas
  "Fragmento de rotas da leitura de vereadores (table syntax Pedestal). Recebe o interceptor `auth`
  (compartilhado), o `repo-cadastros` (Repo-Component do proprio modulo) e o `relogio` (kernel/tempo,
  injetado pelo host — producao le o relogio do sistema, teste crava o instante; mesmo contrato de
  `legislativo-http/rotas`/`participacao-http/rotas`). Ambas as rotas EXIGEM a authz grossa (papel
  'secretario')."
  [{:keys [auth repo-cadastros relogio]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/cadastros/vereadores"     :get [auth papel (listar-handler repo-cadastros relogio)]
       :route-name :cadastros-vereadores-listar]
      ["/cadastros/vereadores/:id" :get [auth papel (ficha-handler repo-cadastros relogio)]
       :route-name :cadastros-vereador-ficha]}))
