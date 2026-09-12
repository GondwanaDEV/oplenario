(ns oplenario.tempo-real.components
  "Backplane do tempo real (§22.9 Eixo 4): a CanalStore guarda as mensagens por canal com SEQ monotonica por
  canal e janela de replay (Last-Event-ID). Duas impls atras do MESMO protocolo: MEMORIA (atom — 1 no / teste)
  e VALKEY (Carmine streams — multi-no, retencao por tempo). O PROTOCOLO e' estavel p/ o swap — o projetor (G2)
  e o endpoint SSE (G3, diplomat) dependem dele, NUNCA da impl. A selecao e' por config (sistema.clj)."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [oplenario.tempo-real.canais :as canais]
            [taoensso.carmine :as car])
  (:import (java.io Closeable)))

(set! *warn-on-reflection* true)

(defprotocol CanalStore
  (publicar! [this canal mensagem]
    "Anexa `mensagem` ao `canal` atribuindo a SEQ monotonica do canal. Devolve {:canal :seq}.")
  (ler-desde [this canal apos-seq]
    "Mensagens do canal com seq > `apos-seq` (replay via Last-Event-ID), em ordem de seq."))

(defrecord CanalStoreMemoria [estado]   ; estado = atom {canal {:seq n :mensagens [{:seq :tipo :dados}]}}
  CanalStore
  (publicar! [_ canal mensagem]
    (let [r (swap! estado update canal
                   (fn [{seq-atual :seq :keys [mensagens] :or {seq-atual 0 mensagens []}}]
                     (let [s (inc seq-atual)]
                       {:seq s :mensagens (conj mensagens (assoc mensagem :seq s))})))]
      {:canal canal :seq (get-in r [canal :seq])}))
  (ler-desde [_ canal apos-seq]
    (->> (get-in @estado [canal :mensagens])
         (filterv #(> (:seq %) apos-seq)))))

(defn canal-store-memoria
  "CanalStore em memoria (atom). Sem Lifecycle — construivel eagerly (o registro de consumidores fecha sobre
  ela em sistema.clj). A impl Valkey (canal-store-valkey) tem Lifecycle (conecta no start)."
  []
  (->CanalStoreMemoria (atom {})))

;; ---------- impl VALKEY (Carmine streams) — G3b ----------

(def ^:private retencao-ms-padrao
  "Janela de replay: 5 min. Materializada como poda por TEMPO do stream (XADD MINID = agora - retencao) — a
  reconexao curta do painel reencontra o historico recente; entradas mais velhas saem da janela."
  (* 5 60 1000))

(def ^:private ttl-canal-s
  "TTL das chaves de canal (stream + contador de seq), renovado a cada publish. >> a janela de reconexao p/ que
  o contador NUNCA reinicie enquanto um cliente vivo depende dele (reset de seq faria o filtro Last-Event-ID
  engolir eventos novos); um canal ocioso por 1 dia e' GC integral (ninguem ainda assiste)."
  86400)

(defn- chave-stream [canal] (str "tr:canal:" canal))
(defn- chave-seq    [canal] (str "tr:seq:"   canal))

(def ^:private lua-publicar
  "Publica ATOMICAMENTE (review G3b — clj-MAJOR/sec-MAJOR-1): INCR da seq + XADD (com poda por TEMPO via MINID
  exato + id auto `*` + a seq como campo proprio `s`) + EXPIRE de ambas as chaves, num unico EVAL. Atomico =>
  (a) sem swallow silencioso (o pipeline multi-reply do Carmine enterra excecoes num vetor; EVAL e' reply unica
  e LANCA no erro) e (b) sem GAP de seq (INCR e XADD nunca se separam por crash/blip). A seq e' calculada AQUI,
  entao NAO vai embutida na mensagem congelada (ARGV[1]) — vira o campo `s`, reanexado como :seq na leitura.
  KEYS[1]=stream KEYS[2]=contador ARGV[1]=mensagem(frozen) ARGV[2]=minid ARGV[3]=ttl-s. Retorna a seq."
  (str "local s = redis.call('INCR', KEYS[2]) "
       "redis.call('XADD', KEYS[1], 'MINID', ARGV[2], '*', 'm', ARGV[1], 's', s) "
       "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
       "redis.call('EXPIRE', KEYS[2], ARGV[3]) "
       "return s"))

(defn- mensagem-valida?
  "Defesa-em-profundidade (review sec-HIGH-1): valida a FORMA da mensagem descongelada antes de devolve-la. Toda
  mensagem legitima do projetor e' {:ente-id :tipo string :dados map}; uma entrada com forma estranha (corrupcao
  ou escrita externa nao-confiavel) NAO e' propagada como se fosse integra (`ler-desde`, abaixo, vira-a sinal
  de lacuna em vez de descarta-la — frente 'truncamento-familia' sitio (d)). NOTA: nao e' escudo de RCE — um
  gadget Nippy executa no THAW (dentro do XRANGE), antes daqui; o escudo real e' infra (auth/TLS/isolamento de
  rede do Valkey) — ver o carry HIGH-1 no docstring de `canal-store-valkey`."
  [m]
  (and (map? m) (string? (:tipo m)) (map? (:dados m))))

;; A CONEXAO Carmine vive num ATOM mutado in-place no start (NAO um campo assoc'd pelo Lifecycle): o registro de
;; consumidores (consumer.clj) fecha sobre ESTA instancia eagerly em sistema.clj, antes do start; compartilhar o
;; atom faz o handler enxergar o pool aberto sem rewire do relay.
(defrecord CanalStoreValkey [config conn-atom retencao-ms]
  component/Lifecycle
  (start [this]
    (when-not @conn-atom
      (let [conn {:pool (car/connection-pool {})
                  :spec {:uri (get-in config [:valkey :uri])}}]
        ;; PING fail-fast (review sec-MINOR-3): o pool e' lazy; sem isto o boot "sobe" com o Valkey fora e so
        ;; quebra no 1o publish (que o relay poderia engolir). Falhar aqui bloqueia o boot — o certo p/ prod.
        (car/wcar conn (car/ping))
        (reset! conn-atom conn)))
    this)
  (stop [this]
    (when-let [c @conn-atom]
      (when-let [p (:pool c)] (.close ^Closeable p))
      (reset! conn-atom nil))
    this)

  CanalStore
  (publicar! [_ canal mensagem]
    (let [conn  (or @conn-atom (throw (ex-info "CanalStoreValkey nao iniciado (conn-atom nil)" {:canal canal})))
          ;; MINID = agora - retencao na MESMA base de relogio do id `*` (= relogio de parede do servidor, que
          ;; no mesmo host coincide com System/currentTimeMillis a menos de ms — irrelevante p/ janela de 5 min).
          minid (str (- (System/currentTimeMillis) (long retencao-ms)))
          s     (car/wcar conn (car/eval lua-publicar 2
                                         (chave-stream canal) (chave-seq canal)
                                         mensagem minid ttl-canal-s))]
      {:canal canal :seq (long s)}))
  (ler-desde [_ canal apos-seq]
    (let [conn    (or @conn-atom (throw (ex-info "CanalStoreValkey nao iniciado (conn-atom nil)" {:canal canal})))
          entries (car/wcar conn (car/xrange (chave-stream canal) "-" "+"))]
      (->> entries
           (map (fn [entry]
                  ;; entry = [id ["m" <msg> "s" "<seq>"]] — le por NOME de campo (nao por posicao; review clj-MINOR)
                  (let [campos    (apply hash-map (second entry))
                        msg       (get campos "m")
                        seq-lida  (parse-long (get campos "s"))]
                    (if (mensagem-valida? msg)
                      (assoc msg :seq seq-lida)
                      ;; frente 'truncamento-familia' sitio (d): NAO descarta — vira sinal (`canais/tipo-lacuna`)
                      ;; na MESMA seq da entrada corrompida. Descartar (o comportamento antigo) fazia o cursor do
                      ;; cliente avancar por cima do buraco como se o replay estivesse integro; sem cursor
                      ;; correspondente para o buraco, o resume por Last-Event-ID nunca teria como saber que
                      ;; algo faltou. `:dados {}` nunca carrega o payload corrompido ao cliente.
                      (do (log/warn "ler-desde: mensagem de forma invalida virou sinal de lacuna (cliente avisado, cursor preservado)"
                                    {:canal canal :seq seq-lida})
                          {:tipo canais/tipo-lacuna :dados {} :seq seq-lida})))))
           (filterv #(> (long (:seq %)) (long apos-seq)))   ; replay: seq > Last-Event-ID
           (sort-by :seq)                                    ; ordem de seq (defesa; lider unico ja serializa)
           vec))))

(defn canal-store-valkey
  "CanalStore viva em Valkey (Carmine streams). Lifecycle: start abre o pool (+ PING fail-fast), stop fecha.
  `opts` injeta `:retencao-ms` (janela de replay; default 5 min) — teste usa janela curta + sleep real.

  >>> CARRY PRE-PROD HIGH-1 (review de seguranca G3b — GATE ANTES DE STAGING/PROD) <<<
  O pool abre com `redis://` (SEM TLS) e SEM auth, e o Carmine descongela (Nippy) os valores lidos. Hoje o
  PRODUTOR e' 100%% interno (relay->outbox->projetor); NENHUM ator externo escreve no store. Mas se o Valkey
  ficar alcancavel sem auth, uma escrita crua com header Nippy forjado vira RCE no thaw. Antes de prod:
   1. VALKEY_URI = `rediss://:<senha-forte>@host:6379` (TLS + auth) — o override ja propaga (config.clj).
   2. Valkey so na rede interna do cluster (bind/network-policy negando ingress externo).
   3. (Endurecimento em processo) restringir o allowlist de Serializable do Nippy p/ recusar classes arbitrarias
      no thaw — nossas mensagens sao so dado Clojure puro (map/string/uuid/keyword), nao precisam de Serializable.
  `mensagem-valida?` ja descarta entradas de forma estranha (defesa-em-profundidade, NAO escudo de RCE)."
  ([config] (canal-store-valkey config {}))
  ([config {:keys [retencao-ms]}]
   (map->CanalStoreValkey {:config      config
                           :conn-atom   (atom nil)
                           :retencao-ms (or retencao-ms retencao-ms-padrao)})))
