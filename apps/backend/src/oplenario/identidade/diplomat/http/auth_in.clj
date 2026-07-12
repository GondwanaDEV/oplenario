(ns oplenario.identidade.diplomat.http.auth-in
  "Fronteira de IO HTTP de ENTRADA do modulo identidade (§22.10 diplomat/http/in, ADR-0001) — Onda D Slice 2
  Task 3: GET /auth/descoberta/:ente. Rota PUBLICA (SEM `auth`) — e' descoberta PRE-login: o FE (BFF) ainda
  nao tem token, precisa saber QUAL realm Keycloak e QUAIS parametros publicos usar pra comecar o Authorization
  Code+PKCE. O :ente do path e' o UUID do ente-cru (mesmo perfil de resolver-ente-publico do portal
  transparencia/participacao — V1 sem slug humano), coagido fail-closed AQUI (identidade nao pode importar o
  seam de outro modulo — §22.10 — replica a mesma forma localmente).

  `ente-existe?` chega INJETADA pelo host (cross-modulo por inversao de dependencia sobre o Repo de cadastros —
  mesmo padrao de consultar-sessao/membros-da-casa/info-ente em oplenario.rotas/montar; identidade NUNCA importa
  cadastros). Ente inexistente -> 404 fail-closed (nunca vaza o realm/URL de um tenant que nao existe).

  Distinto do mint de token pos-callback (Task 4): la' o ente-id vem do ISSUER do token VERIFICADO (nunca do
  path/corpo — anti-forge). Aqui e' o INVERSO por desenho: e' descoberta pre-auth, nao ha token ainda pra
  extrair ente-id — o path e' a UNICA fonte possivel, e a resposta so' expoe metadado publico de login
  (realm/base-url/client-id), nada sensivel."
  (:require [clojure.string :as str]
            [oplenario.http :as http])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- ente-param->uuid
  "Path-param :ente (rota PUBLICA) -> UUID do ente. Malformado/ausente -> 400 fail-closed. Coercao LOCAL
  (nao importa transparencia/participacao — §22.10 proibe cross-modulo; mesma forma, replicada)."
  [s]
  (when (str/blank? s)
    (throw (ex-info "ente ausente na rota publica" {:tipo :validacao/invalido :campo :ente})))
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (throw (ex-info "ente invalido" {:tipo :validacao/invalido :campo :ente})))))

(defn- descoberta-handler
  "GET /auth/descoberta/:ente — resolve o realm/base-url-publico/client-id do tenant p/ o FE comecar o PKCE.
  `ente-existe?` fail-closed -> 404 antes de devolver qualquer metadado do tenant."
  [ente-existe? keycloak]
  (fn [req]
    (let [ente-id (ente-param->uuid (get-in req [:path-params :ente]))]
      (if (ente-existe? ente-id)
        (http/json-resposta 200
          {:ente-id   (str ente-id)
           :realm     (str (:realm-prefixo keycloak) ente-id)
           :base-url  (:base-url-publico keycloak)
           :client-id (:web-client-id keycloak)})
        (http/json-resposta 404 {:erro "ente nao encontrado"})))))

(defn rotas
  "Fragmento de rotas do modulo identidade (table syntax Pedestal). Recebe `ente-existe?` (seam do host) e
  `keycloak` (a config carregada, `:keycloak` do config.edn). `oplenario.rotas` funde este fragmento."
  [{:keys [ente-existe? keycloak]}]
  #{["/auth/descoberta/:ente" :get
     [(descoberta-handler ente-existe? keycloak)]
     :route-name :identidade/descoberta]})
