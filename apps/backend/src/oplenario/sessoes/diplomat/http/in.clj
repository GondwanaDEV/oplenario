(ns oplenario.sessoes.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo sessoes (§22.10 diplomat/http/in, ADR-0001): as rotas-dado Pedestal
  + os handlers. O diplomat e' a UNICA camada que atravessa o gate de borda: chama adapters/in (wire->models) na
  entrada e adapters/out (models->wire) na saida; o controller trabalha so em models. Le o `ator` (posto pela
  cadeia de auth em (:request :ator)), nunca fala com db/ direto (depende do Repo-Component, injetado por closure
  via `rotas`). A authz GROSSA (exige-papel) entra na rota; a FINA (policy.check) roda no controller."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.sessoes.adapters.in.gravacao :as adapters-in-grav]
            [oplenario.sessoes.adapters.in.sessao :as adapters-in]
            [oplenario.sessoes.adapters.out.gravacao :as adapters-out-grav]
            [oplenario.sessoes.adapters.out.pauta :as adapters-out-pauta]
            [oplenario.sessoes.adapters.out.sessao :as adapters-out]
            [oplenario.sessoes.controllers :as controllers]))

(set! *warn-on-reflection* true)

(def ^:private ^:const max-upload-bytes
  "Teto do corpo binario da ingestao de gravacao (2 GiB). O `corpo-json` (256 KiB) nao incide aqui (corpo
  binario), mas um upload ilimitado seria DoS de storage (review sec MAJOR). Estouro -> 413. (Limite mais
  fino + retomada/dedup = utilitario CLI da §16.4, carry de infra.)"
  (* 2 1024 1024 1024))

(defn- limitar-stream
  "Envolve o InputStream contando os bytes lidos; estoura :corpo/grande ao exceder `limite` (nunca deixa o
  store gravar alem do teto). Estende InputStream e fecha sobre `in` type-hinted (sem proxy-super = sem
  reflexao). Cobre os 3 `read` + close/available que o SDK do MinIO pode usar."
  ^java.io.InputStream [^java.io.InputStream in ^long limite]
  (let [lido   (java.util.concurrent.atomic.AtomicLong. 0)
        checar (fn [n] (when (and (pos? (long n)) (> (.addAndGet lido (long n)) limite))
                         (throw (ex-info "upload grande demais" {:tipo :corpo/grande})))
                 n)]
    (proxy [java.io.InputStream] []
      (read
        ([]            (let [b (.read in)] (checar (if (neg? b) 0 1)) b))
        ([buf]         (checar (.read in ^bytes buf)))
        ([buf off len] (checar (.read in ^bytes buf (int off) (int len)))))
      (available [] (.available in))
      (close [] (.close in)))))

(defn- buscar-handler
  "GET /sessoes/:id. adapters/in coage o :id; controller carrega+autoriza; adapters/out projeta. nil -> 404."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [s (controllers/buscar-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out/sessao->wire s))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- pauta-handler
  "GET /sessoes/:id/pauta. adapters/in coage o :id; controller carrega+autoriza a sessao e le a pauta viva;
  adapters/out projeta. nil (sessao inexistente) -> 404."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [p (controllers/pauta-da-sessao repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-pauta/pauta->wire p))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn- agendar-handler
  "POST /sessoes. corpo JSON parseado em (:json-params req) pelo corpo-json; adapters/in valida+coage+injeta o
  ente/autor do ator; controller agenda; adapters/out projeta o recibo. Sucesso -> 201."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          m    (adapters-in/agendar-sessao->dominio ator (:json-params req))]
      (http/json-resposta 201 (adapters-out/recibo-agendamento->wire
                               (controllers/agendar-sessao repo-sessoes ator m))))))

(defn- ingestao-handler
  "POST /gravacoes. Ingestao AGNOSTICA de sessao (§22.3.4 / Opcao A: o segmento chega do CLI/watch-folder SEM
  vinculo; URL de topo, fora de /sessoes/:id). O CORPO e' o binario (container bruto) — NAO passa pelo
  corpo-json; a metadata vem da QUERY (:query-params, keyword-keyed). adapters/in valida+coage a metadata; o
  controller transmite o stream ao objeto_store, computa o hash e registra+emite. Sucesso -> 201; sessao-id
  (link-at-ingest) informado mas inexistente -> 404."
  [repo-sessoes objeto-store]
  (fn [req]
    (let [ator (:ator req)
          metadata (adapters-in-grav/ingestao-meta->dominio (:query-params req))]
      ;; corpo ausente (proxy consumiu / sem Content-Length) = requisicao invalida (-> 400), nunca NPE -> 500
      (when-not (:body req)
        (throw (ex-info "corpo de ingestao ausente" {:tipo :validacao/invalido})))
      (try
        (if-let [recibo (controllers/ingerir-segmento repo-sessoes objeto-store ator metadata
                                                       (limitar-stream (:body req) max-upload-bytes))]
          (http/json-resposta 201 (adapters-out-grav/recibo-ingestao->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :corpo/grande (:tipo (ex-data e)))
            (http/json-resposta 413 {:erro "upload grande demais"})
            (throw e)))))))

(defn- listar-gravacoes-handler
  "GET /sessoes/:id/gravacao. adapters/in coage o :id; controller carrega+autoriza a sessao e lista os
  segmentos vinculados; adapters/out projeta (filtra internos). nil (sessao inexistente) -> 404."
  [repo-sessoes]
  (fn [req]
    (let [ator (:ator req)
          id   (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [r (controllers/listar-gravacoes repo-sessoes ator id)]
        (http/json-resposta 200 (adapters-out-grav/segmentos->wire (:sessao-id r) (:segmentos r)))
        (http/json-resposta 404 {:erro "sessao nao encontrada"})))))

(defn rotas
  "Fragmento de rotas do modulo (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado), o
  `repo-sessoes` (Repo-Component) + o `objeto-store` (p/ a ingestao de gravacao) e devolve as rotas-dado.
  `oplenario.rotas` funde este fragmento ao conjunto. POST exige a authz GROSSA (papel 'secretario'); a
  ingestao NAO usa corpo-json (o corpo e' binario); GET so autentica (a camada fina decide no controller)."
  [{:keys [auth repo-sessoes objeto-store]}]
  #{["/sessoes"     :post [auth (it/exige-papel "secretario") it/corpo-json (agendar-handler repo-sessoes)]
     :route-name :sessoes/agendar]
    ;; ingestao no TOPO (nao /sessoes/...): o segmento e' agnostico de sessao (Opcao A) e isto evita a colisao
    ;; de roteamento literal-vs-param com /sessoes/:id (o param sombrearia o POST -> 404).
    ["/gravacoes" :post [auth (it/exige-papel "secretario") (ingestao-handler repo-sessoes objeto-store)]
     :route-name :sessoes/ingerir-gravacao]
    ["/sessoes/:id" :get  [auth (buscar-handler repo-sessoes)] :route-name :sessoes/buscar]
    ["/sessoes/:id/pauta" :get [auth (pauta-handler repo-sessoes)] :route-name :sessoes/pauta]
    ["/sessoes/:id/gravacao" :get [auth (listar-gravacoes-handler repo-sessoes)] :route-name :sessoes/listar-gravacoes]})
