(ns oplenario.tempo-real.components
  "Backplane do tempo real (§22.9 Eixo 4): a CanalStore guarda as mensagens por canal com SEQ monotonica por
  canal e janela de replay (Last-Event-ID). Duas impls atras do MESMO protocolo: MEMORIA (atom — 1 no / teste)
  e VALKEY (Carmine streams — multi-no, retencao por tempo). O PROTOCOLO e' estavel p/ o swap — o projetor (G2)
  e o endpoint SSE (G3, diplomat) dependem dele, NUNCA da impl. A selecao e' por config (sistema.clj)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [oplenario.tempo-real.canais :as canais]
            [oplenario.tempo-real.codec :as codec]
            [taoensso.carmine :as car])
  (:import (java.io Closeable)
           (java.net URI)
           (java.nio.charset StandardCharsets)))

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
  entao NAO vai embutida na mensagem gravada (ARGV[1]) — vira o campo `s`, reanexado como :seq na leitura.
  KEYS[1]=stream KEYS[2]=contador ARGV[1]=mensagem(texto de `codec/codificar`) ARGV[2]=minid ARGV[3]=ttl-s.
  Retorna a seq."
  (str "local s = redis.call('INCR', KEYS[2]) "
       "redis.call('XADD', KEYS[1], 'MINID', ARGV[2], '*', 'm', ARGV[1], 's', s) "
       "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
       "redis.call('EXPIRE', KEYS[2], ARGV[3]) "
       "return s"))

(defn- mensagem-valida?
  "Valida a FORMA da mensagem ja' decodificada antes de devolve-la. Toda mensagem legitima do projetor e'
  {:ente-id :tipo string :dados map}; uma entrada com forma estranha (corrupcao ou escrita externa nao-confiavel)
  NAO e' propagada como se fosse integra (`ler-desde`, abaixo, vira-a sinal de lacuna em vez de descarta-la —
  frente 'truncamento-familia' sitio (d)). O que impede objeto arbitrario e' `codec/decodificar`, ANTES daqui: a
  leitura parte de bytes crus e so' constroi dado puro (ver `ler-mensagem`)."
  [m]
  (and (map? m) (string? (:tipo m)) (map? (:dados m))))

(defn- texto
  "Bytes crus de um campo do stream -> String UTF-8 (nil se nao for bytes)."
  [ba]
  (when (bytes? ba) (String. ^bytes ba StandardCharsets/UTF_8)))

(defn- ler-mensagem
  "Bytes crus do campo `m` -> mensagem, ou nil quando `codec/decodificar` RECUSA (valor congelado por Nippy, EDN
  invalido, etiqueta desconhecida, tipo fora da allowlist...). Fail-closed por entrada: a recusa e' registrada (so'
  a razao e o tamanho — nunca o conteudo) e a entrada vira lacuna em `ler-desde`; uma entrada ruim nao derruba a
  leitura das outras nem o canal de ninguem."
  [canal ba]
  (try
    (codec/decodificar ba)
    (catch clojure.lang.ExceptionInfo e
      (if (:tempo-real/mensagem-recusada? (ex-data e))
        (do (log/warn "ler-desde: valor do Valkey recusado na desserializacao (nao vira objeto)"
                      {:canal canal :razao (:razao (ex-data e)) :bytes (when (bytes? ba) (alength ^bytes ba))})
            nil)
        (throw e)))))

(defn- senha-na-uri?
  "true se a URI do Valkey carrega senha (`redis://:senha@host` ou `redis://usuario:senha@host`)."
  [uri]
  (boolean
   (when-not (str/blank? uri)
     (when-let [info (try (.getUserInfo (URI. uri)) (catch Exception _ nil))]
       (let [[_ senha] (str/split info #":" 2)]
         (not (str/blank? senha)))))))

(defn tem-senha?
  "true se a config do Valkey traz senha — em `[:valkey :password]` (VALKEY_PASSWORD) ou embutida na URI."
  [config]
  (or (not (str/blank? (get-in config [:valkey :password])))
      (senha-na-uri? (get-in config [:valkey :uri]))))

(defn spec-de-conexao
  "A spec de conexao do Carmine a partir de `[:valkey]` da config: a URI (`redis://` ou `rediss://` = TLS, pelo
  truststore padrao da JVM) + usuario/senha quando configurados. Senha embutida na URI prevalece sobre
  `:password` (e' a regra de merge do Carmine). Valor em branco = ausente."
  [config]
  (let [{:keys [uri username password]} (:valkey config)]
    (cond-> {:uri uri}
      (not (str/blank? username)) (assoc :username username)
      (not (str/blank? password)) (assoc :password password))))

;; A CONEXAO Carmine vive num ATOM mutado in-place no start (NAO um campo assoc'd pelo Lifecycle): o registro de
;; consumidores (consumer.clj) fecha sobre ESTA instancia eagerly em sistema.clj, antes do start; compartilhar o
;; atom faz o handler enxergar o pool aberto sem rewire do relay.
(defrecord CanalStoreValkey [config conn-atom retencao-ms]
  component/Lifecycle
  (start [this]
    (when-not @conn-atom
      (let [conn {:pool (car/connection-pool {})
                  :spec (spec-de-conexao config)}]
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
                                         (codec/codificar mensagem) minid ttl-canal-s))]
      {:canal canal :seq (long s)}))
  (ler-desde [_ canal apos-seq]
    (let [conn    (or @conn-atom (throw (ex-info "CanalStoreValkey nao iniciado (conn-atom nil)" {:canal canal})))
          ;; `parse-raw`: o Carmine devolve BYTES crus e NAO descongela nada — sem isto ele roda o thaw do Nippy em
          ;; todo valor com o marcador dele, dentro do XRANGE, antes de qualquer validacao nossa.
          entries (car/wcar conn (car/parse-raw (car/xrange (chave-stream canal) "-" "+")))]
      (->> entries
           (reduce
            (fn [{:keys [saida ultima-seq-confiavel]} entry]
              ;; entry = [id ["m" <msg> "s" "<seq>"]] — le por NOME de campo (nao por posicao; review clj-MINOR)
              (let [campos   (->> (partition 2 (second entry))
                                  (reduce (fn [m [k v]] (assoc m (texto k) v)) {}))
                    msg      (ler-mensagem canal (get campos "m"))
                    s-cru    (let [s (get campos "s")]             ; a seq legitima tem no maximo 19 digitos
                               (when (and (bytes? s) (<= (alength ^bytes s) 19)) (texto s)))
                    ;; `parse-long` so' roda quando `s-cru` JA e' string (review adversarial 2a rodada,
                    ;; CRITICO/IMPORTANTE): `(parse-long nil)` lanca IllegalArgumentException — o campo `s`
                    ;; ausente e' a forma MAIS natural de uma escrita externa/corrompida (a mesma classe que
                    ;; `mensagem-valida?` existe pra tratar), e antes disso o `ler-desde` inteiro MORRIA no
                    ;; meio do replay (o `catch Throwable` de diplomat/sse.clj fecha o event-channel).
                    seq-lida (when (string? s-cru) (parse-long s-cru))]
                (if (and (mensagem-valida? msg) seq-lida)
                  {:saida                (conj saida (assoc msg :seq seq-lida))
                   :ultima-seq-confiavel seq-lida}
                  ;; frente 'truncamento-familia' sitio (d): NAO descarta — vira sinal (`canais/tipo-lacuna`).
                  ;; A seq do sinal NUNCA vem do campo `s` de uma entrada que reprovou (review adversarial
                  ;; 2a rodada, CRITICO): promover um `s` externo a cursor deixa um escritor nao-confiavel
                  ;; escolher onde o cliente reconecta — um `s` forjado MAIOR que tudo sequestra o cursor e
                  ;; apaga do replay todo evento real subsequente (provado ao vivo contra o Valkey). A seq e'
                  ;; CLAMPADA a' `ultima-seq-confiavel` (a maior seq de uma entrada de fato validada nesta
                  ;; MESMA leitura, em ordem de stream) — nunca ultrapassa uma mensagem legitima, vista ou por
                  ;; vir. Descartar (comportamento anterior a esta fatia) fazia o cursor avancar por cima do
                  ;; buraco como se o replay estivesse integro; `:dados {}` nunca carrega o payload corrompido.
                  (do (log/warn "ler-desde: mensagem de forma invalida virou sinal de lacuna (cliente avisado, cursor preservado)"
                                {:canal canal :s-bruta s-cru :seq-sinal ultima-seq-confiavel})
                      {:saida                (conj saida {:tipo canais/tipo-lacuna :dados {} :seq ultima-seq-confiavel})
                       :ultima-seq-confiavel ultima-seq-confiavel}))))
            {:saida [] :ultima-seq-confiavel 0})
           :saida
           (filterv #(> (long (:seq %)) (long apos-seq)))   ; replay: seq > Last-Event-ID
           (sort-by :seq)                                    ; ordem de seq (defesa; lider unico ja serializa)
           vec))))

(defn canal-store-valkey
  "CanalStore viva em Valkey (Carmine streams). Lifecycle: start abre o pool (+ PING fail-fast), stop fecha.
  `opts` injeta `:retencao-ms` (janela de replay; default 5 min) — teste usa janela curta + sleep real.

  SEGURANCA (fecha o carry HIGH-1 da review do G3b):
   - Desserializacao: a mensagem vai e volta como texto EDN de dado puro (`tempo-real.codec`); a leitura pede
     BYTES crus ao Carmine (`parse-raw`), que por isso NAO descongela Nippy. Escrever no Valkey nao faz o backend
     instanciar classe nem rodar leitor etiquetado — o valor estranho e' registrado e vira lacuna.
   - Autenticacao: senha por `VALKEY_PASSWORD` (ou na URI) e usuario opcional por `VALKEY_USERNAME`
     (`spec-de-conexao`). Fora de dev/test e sem senha, `sistema/novo-sistema` avisa em nivel error a cada boot;
     com `VALKEY_EXIGIR_SENHA=true` recusa subir.
   - TLS: `VALKEY_URI=rediss://...`.
   - Rede: o Valkey fica so' na rede interna (no deploy; ver docs/27, secao do Valkey)."
  ([config] (canal-store-valkey config {}))
  ([config {:keys [retencao-ms]}]
   (map->CanalStoreValkey {:config      config
                           :conn-atom   (atom nil)
                           :retencao-ms (or retencao-ms retencao-ms-padrao)})))
