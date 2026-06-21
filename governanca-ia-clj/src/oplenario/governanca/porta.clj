(ns oplenario.governanca.porta
  "A PORTA de inferência VENDOR-AGNÓSTICA (§22.9 Eixo 10): único ponto de saída ao LLM externo.
  Trocar Bedrock/Claude <-> Sabiá <-> self-host = trocar a impl do protocolo, nada mais (A: default
  Claude/Bedrock sa-east-1; Sabiá como modo soberano on-demand).")

(defprotocol PortaInferencia
  (inferir [this payload] "Chama o LLM externo com o payload JÁ filtrado. Devolve a resposta.")
  (nome-vendor [this]))

;; Adapter FAKE p/ teste: ecoa e REGISTRA tudo que recebeu — usado p/ provar que sigiloso nunca chega.
(defn fake-vendor [recebidos-atom rotulo]
  (reify PortaInferencia
    (inferir [_ payload] (swap! recebidos-atom conj payload) {:resposta (str "eco:" rotulo)})
    (nome-vendor [_] rotulo)))
