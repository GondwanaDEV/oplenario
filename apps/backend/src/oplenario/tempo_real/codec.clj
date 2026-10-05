(ns oplenario.tempo-real.codec
  "Formato da mensagem de canal DENTRO do Valkey: texto EDN de dado puro, com um prefixo de versao. Puro (sem IO).

  POR QUE EXISTE: o Carmine congela/descongela valores com Nippy sozinho, e o descongelamento instancia a classe
  que o BYTE diz (record/deftype por nome, Serializable da allowlist, leitor etiquetado dos `*data-readers*`). Com
  isso, quem conseguisse escrever no Valkey escolhia o que o backend instanciava e executava. Aqui a leitura parte
  de BYTES crus e so' constroi o que esta' na allowlist abaixo — nenhuma classe e' carregada por nome, nenhum
  leitor etiquetado roda. O que nao for exatamente isto e' RECUSADO (ex-info com `:tempo-real/mensagem-recusada?`),
  e quem le o canal transforma a recusa em lacuna.

  ALLOWLIST (os tipos que de fato trafegam — o payload do evento vem do jsonb do outbox):
  nil · String · Boolean · Long/Integer · Double · BigDecimal · BigInt/BigInteger · Keyword · UUID ·
  mapa (nao-record) · vetor · conjunto · lista. Nada de data, simbolo, record ou objeto."
  (:require [clojure.edn :as edn])
  (:import (java.io PushbackReader StringReader)
           (java.nio.charset StandardCharsets)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def prefixo
  "Marca de versao do formato. Um valor do Valkey que nao comeca por ela (inclusive o Nippy antigo) e' recusado."
  "edn1:")

(def ^:private teto-bytes
  "Tamanho maximo de uma mensagem. As mensagens do plenario tem centenas de bytes; o teto limita o que um valor
  hostil faz o leitor alocar e percorrer."
  (* 256 1024))

(def ^:private teto-profundidade 32)

(defn- recusar! [razao]
  (throw (ex-info (str "mensagem de canal recusada: " (name razao))
                  {:tempo-real/mensagem-recusada? true :razao razao})))

(defn- dado-puro?
  "true se `x` (e tudo dentro dele, ate' `teto-profundidade`) esta' na allowlist do ns."
  ([x] (dado-puro? x 0))
  ([x ^long nivel]
   (cond
     (> nivel (long teto-profundidade)) false
     (nil? x)                    true
     (string? x)                 true
     (boolean? x)                true
     (keyword? x)                true
     (instance? Long x)          true
     (instance? Integer x)       true
     (instance? Double x)        true
     (instance? BigDecimal x)    true
     (instance? clojure.lang.BigInt x) true
     (instance? BigInteger x)    true
     (instance? UUID x)          true
     (record? x)                 false
     (map? x)                    (every? (fn [[k v]] (and (dado-puro? k (inc nivel)) (dado-puro? v (inc nivel)))) x)
     ;; `seq?` cobre a lista lida do EDN e a sequencia preguicosa que uma projecao produza (grava-se como lista)
     (or (vector? x) (set? x) (seq? x)) (every? #(dado-puro? % (inc nivel)) x)
     :else                       false)))

(defn- ler-edn
  "Le UMA forma EDN de `texto`, sem nenhum leitor etiquetado alem de #uuid/#inst (embutidos, sem efeito) — etiqueta
  desconhecida LANCA em vez de virar objeto. Sobra depois da forma tambem e' recusada."
  [^String texto]
  (let [opcoes {:readers {} :default (fn [_etiqueta _valor] (recusar! :etiqueta-desconhecida)) :eof ::fim}]
    (with-open [r (PushbackReader. (StringReader. texto))]
      (let [forma (edn/read opcoes r)]
        (when (= ::fim forma) (recusar! :vazia))
        (when-not (= ::fim (edn/read opcoes r)) (recusar! :sobra-depois-da-forma))
        forma))))

(defn decodificar
  "Bytes crus lidos do Valkey -> mensagem (dado puro). LANCA ex-info `:tempo-real/mensagem-recusada?` para tudo o
  que nao for uma mensagem deste formato: sem o prefixo (inclui valor congelado por Nippy), grande demais, EDN
  invalido, etiqueta desconhecida ou tipo fora da allowlist. Nunca devolve objeto arbitrario."
  [ba]
  (when-not (bytes? ba) (recusar! :nao-e-bytes))
  (let [^bytes ba ba
        n         (alength ba)
        ^bytes pb (.getBytes ^String prefixo StandardCharsets/UTF_8)
        np        (alength pb)]
    (when (> n (long teto-bytes)) (recusar! :grande-demais))
    (when-not (and (>= n np) (java.util.Arrays/equals pb (java.util.Arrays/copyOfRange ba 0 np)))
      (recusar! :sem-prefixo))
    (let [forma (try
                  (ler-edn (String. ba np (- n np) StandardCharsets/UTF_8))
                  (catch clojure.lang.ExceptionInfo e
                    (if (:tempo-real/mensagem-recusada? (ex-data e)) (throw e) (recusar! :edn-invalido)))
                  (catch Exception _ (recusar! :edn-invalido))
                  ;; aninhamento profundo demais estoura a pilha do leitor: e' recusa, nao queda do processo
                  (catch StackOverflowError _ (recusar! :aninhada-demais)))]
      (when-not (dado-puro? forma) (recusar! :tipo-fora-da-allowlist))
      forma)))

(defn codificar
  "Mensagem (dado puro) -> String que vai para o Valkey. So' grava o que `decodificar` devolve IGUAL: tipo fora da
  allowlist, mensagem grande demais ou algo que nao relê identico LANCA ex-info com
  `:tempo-real/payload-malformado?` — o marcador que o consumidor do relay reconhece para descartar o evento com
  lacuna em vez de travar o barramento."
  [mensagem]
  (letfn [(malformado! [razao]
            (throw (ex-info (str "mensagem de canal nao pode ser gravada: " (name razao))
                            {:tempo-real/payload-malformado? true :razao razao})))]
    (when-not (dado-puro? mensagem) (malformado! :tipo-fora-da-allowlist))
    (let [^String texto (binding [*print-length* nil *print-level* nil *print-meta* false *print-dup* false
                          *print-readably* true *print-namespace-maps* false]
                  (str prefixo (pr-str mensagem)))
          relida (try (decodificar (.getBytes texto StandardCharsets/UTF_8))
                      (catch clojure.lang.ExceptionInfo e (malformado! (or (:razao (ex-data e)) :nao-rele))))]
      (when-not (= mensagem relida) (malformado! :nao-rele-identica))
      texto)))
