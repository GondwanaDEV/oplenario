(ns oplenario.admin-sistema.components.exportacao
  "O EXECUTOR da exportacao completa da Casa (ADR-0018 fatia 2, 9.6). Gerar o arquivo e' trabalho longo (o banco da Casa
  inteiro + os arquivos do object storage), entao nao roda no worker HTTP: a borda abre a linha `gerando` e devolve 202;
  a geracao roda aqui e fecha a linha em `pronta` ou `falhou`.

  Pool PROPRIO e pequeno, com fila limitada (mesmo racional do renderizador de PDF da folha): exportar e' raro e manual,
  e nao pode disputar o processo com o resto. Saturado, RECUSA (a borda responde 503 e a linha fecha como falha), nunca
  cresce sem teto. Threads daemon: nada segura o desligamento da JVM — a geracao que morrer com o processo fica
  `gerando` e a proxima pedida a fecha como interrompida (`logic/geracao-abandonada?`)."
  (:import (java.util.concurrent ArrayBlockingQueue RejectedExecutionException ThreadFactory ThreadPoolExecutor
                                 ThreadPoolExecutor$AbortPolicy TimeUnit)))

(set! *warn-on-reflection* true)

(def ^:const paralelismo 1)
(def ^:const fila 8)

(defonce ^:private executor
  (delay
    (let [n (atom 0)
          fabrica (reify ThreadFactory
                    (newThread [_ r]
                      (doto (Thread. ^Runnable r (str "exportacao-casa-" (swap! n inc)))
                        (.setDaemon true))))]
      (ThreadPoolExecutor. (int paralelismo) (int paralelismo) 0 TimeUnit/MILLISECONDS
                           (ArrayBlockingQueue. (int fila)) fabrica (ThreadPoolExecutor$AbortPolicy.)))))

(defn em-segundo-plano
  "Submete `f` (sem argumentos) ao pool da exportacao. Pool cheio -> ex-info `:admin-sistema/indisponivel`."
  [f]
  (try
    (.submit ^ThreadPoolExecutor @executor ^Runnable f)
    nil
    (catch RejectedExecutionException _
      (throw (ex-info "a fila de exportacoes esta' cheia — tente de novo em alguns minutos"
                      {:tipo :admin-sistema/indisponivel :causa "fila-cheia"})))))

(defn agora-mesmo
  "O executor SINCRONO (testes): roda `f` na propria thread."
  [f]
  (f)
  nil)
