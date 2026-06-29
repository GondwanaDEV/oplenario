(ns oplenario.tempo-real.components
  "Backplane do tempo real (§22.9 Eixo 4): a CanalStore guarda as mensagens por canal com SEQ monotonica por
  canal e janela de replay (Last-Event-ID). Impl em MEMORIA aqui (atom) — suficiente p/ 1 no e p/ teste. A impl
  Valkey (stream + retencao de 5 min) e o endpoint SSE Pedestal sao G3 (infra-gated: cache stub + servidor HTTP
  nao de pe). O PROTOCOLO e' estavel p/ o swap — o projetor/endpoint dependem dele, nao da impl.")

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
  ela em sistema.clj). G3 troca por uma impl Valkey com Lifecycle (conecta no start)."
  []
  (->CanalStoreMemoria (atom {})))
