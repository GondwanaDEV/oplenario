(ns oplenario.tempo-real.diplomat.sse
  "Fronteira de IO do endpoint SSE GET /sessoes/:id/plenario (§22.6 eixo G / §22.10 diplomat, ADR-0001). O
  diplomat e' a UNICA camada que cruza o gate: adapters/in (params da conexao) na entrada, adapters/out (frame
  SSE) na saida; o controller trabalha so em models. A authz e' avaliada na ABERTURA da conexao por um
  interceptor que roda ANTES do switch async — start-event-stream SEMPRE faz o async, entao a RECUSA tem de
  terminar a cadeia antes dele (senao nao da p/ devolver 401/403/404). Cross-modulo (ler a sessao p/ autorizar)
  entra por `consultar-sessao` INJETADA pelo host — tempo_real nao importa sessoes (§22.10).

  Streaming: o `stream-ready` faz replay desde o Last-Event-ID e depois poll incremental da CanalStore ate o
  cliente cair. Em G3 a CanalStore e' em memoria (poll); a impl Valkey (XREAD bloqueante, retencao 5min) entra
  atras do MESMO protocolo (G3b) sem mexer aqui.

  CARRIES PRE-PROD (review de seguranca G3 — infra-gated, fora desta fatia):
   - MAJOR-1 (cap de conexao): cada conexao SSE ocupa uma thread do Jetty ate cair; sem teto, um ator autenticado
     pode esgotar o pool (DoS cross-tenant). Resolver antes de prod com maxConnections + rate-limit de abertura
     no ingress (k8s) e/ou um cap por ente/ator (Semaphore adquirido na authz, liberado no fim do stream).
   - MINOR-4 (revogacao em tempo real): a authz e' so na ABERTURA; token/vinculo revogado ou sessao que vira
     secreta NO MEIO mantem a conexao. Mitigar com idleTimeout do Jetty (~limite de uma sessao plenaria) + canal
     de invalidacao por identidade-id quando o backplane Valkey (G3b) + Keycloak/JWKS (F1.4) estiverem ativos."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.interceptor.chain :as chain]
            [oplenario.http :as http]
            [oplenario.tempo-real.adapters.in.evento-sse :as adapters-in]
            [oplenario.tempo-real.adapters.out.evento-sse :as adapters-out]
            [oplenario.tempo-real.canais :as canais]
            [oplenario.tempo-real.controllers :as controllers]))

(set! *warn-on-reflection* true)

(def ^:private poll-ms
  "Intervalo de poll do canal em memoria (G3). Tambem detecta a desconexao quando nao ha evento novo (probe de
  keep-alive). A impl Valkey (G3b) troca o poll por leitura bloqueante e este valor deixa de pesar."
  1000)

;; chaves namespaced onde o interceptor de authz deixa o que o stream-ready consome (no :request, que viaja no
;; context* passado ao stream-ready-fn pelo start-event-stream).
(def ^:private k-canal ::canal)
(def ^:private k-cursor ::cursor)

(defn autorizar-plenario-interceptor
  "Interceptor de AUTORIZACAO da conexao (roda ANTES do start-event-stream). Coage o :id (400 se malformado) e o
  Last-Event-ID; carrega+autoriza a sessao via controller (consultar-sessao injetada). Recusa = TERMINA a cadeia
  (start-event-stream nunca entra): sessao inexistente -> 404; o controller LANCA negacao (sessao secreta / Casa
  alheia) -> o `erro` global mapeia 403. Sucesso -> grava o canal + cursor no :request p/ o stream-ready."
  [consultar-sessao]
  {:name  ::autorizar-plenario
   :enter (fn [ctx]
            (let [ator   (get-in ctx [:request :ator])
                  id     (adapters-in/id-param->uuid (get-in ctx [:request :path-params :id]))
                  cursor (adapters-in/last-event-id->cursor (get-in ctx [:request :headers "last-event-id"]))]
              (if (controllers/autorizar-plenario consultar-sessao ator id)
                (-> ctx
                    (assoc-in [:request k-canal] (canais/canal-plenario id))
                    (assoc-in [:request k-cursor] cursor))
                (chain/terminate
                 (assoc ctx :response (http/json-resposta 404 {:erro "sessao nao encontrada"}))))))})

(defn- stream-ready
  "stream-ready-fn do Pedestal SSE: roda numa thread por conexao. Faz replay desde o cursor (Last-Event-ID) e
  poll incremental ate o cliente cair. CONTRATO do Pedestal (start-dispatch-loop): a app poe MAPAS
  {:name :data :id} no event-channel — o loop e' quem encoda (chama send-event internamente). Por isso NAO
  chamamos `sse/send-event` aqui (isso poria o byte-array ja-encodado no canal, e o loop o re-encodaria como
  `data: [B@...`). `>!!` devolve false em canal fechado (cliente caiu) -> encerra. Sem evento novo, um probe
  (mapa de data vazia) sonda a conexao. Bug pego no E2E do FE.1 (G3 nao cobriu o glue de wire feliz)."
  [canal-store]
  (fn [event-ch ctx]
    (let [nome-canal (get-in ctx [:request k-canal])
          cursor0    (get-in ctx [:request k-cursor])
          enviar!    (fn [msg]
                       ;; poe o MAPA no canal (o dispatch loop do Pedestal encoda); true=ok, false=canal fechado
                       (let [{event-name :name :keys [data id]} (adapters-out/mensagem->frame msg)]
                         (async/>!! event-ch {:name event-name :data data :id id})))]
      (try
        (loop [c cursor0]
          (let [[novo vivo?] (controllers/enviar-desde canal-store nome-canal c enviar!)]
            (cond
              (not vivo?) (async/close! event-ch)            ; cliente desconectou (envio falhou) -> encerra
              ;; sem evento novo: probe de liveness (mapa de data vazia = linha `data:` que o cliente ignora);
              ;; false = canal fechado pelo Pedestal na desconexao -> encerra
              (false? (async/>!! event-ch {:name nil :data "" :id nil})) (async/close! event-ch)
              :else (do (Thread/sleep ^long poll-ms) (recur novo)))))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))               ; restaura o status de interrupcao (interop Java)
          (async/close! event-ch))
        (catch Throwable t
          (log/warn t "stream SSE encerrado por excecao")
          (async/close! event-ch))))))

(defn rotas
  "Fragmento de rota do endpoint SSE (table syntax Pedestal). Recebe o `auth` (compartilhado), a `canal-store`
  (CanalStore) e `consultar-sessao` (injetada pelo host — cross-modulo p/ a authz). A cadeia: auth -> authz da
  conexao -> start-event-stream (so alcancado no sucesso). `erro`/`cabecalhos` sao GLOBAIS (it/globais)."
  [{:keys [auth canal-store consultar-sessao]}]
  #{["/sessoes/:id/plenario" :get
     [auth
      (autorizar-plenario-interceptor consultar-sessao)
      (sse/start-event-stream (stream-ready canal-store))]
     :route-name :tempo-real/plenario]})
