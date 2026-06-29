(ns oplenario.sessoes.events.gravacao
  "Evento de fronteira CORE->IA (§22.3.3, carry do eixo D / F4.4b): um segmento de gravacao foi CAPTADO e esta
  disponivel p/ a Plataforma de IA processar (extracao de audio + transcricao). Consumidor = pipeline de IA,
  NAO o projetor SSE. `acesso-restrito` viaja p/ a IA respeitar sigilo (sessao secreta). `sessao-id` opcional
  (Opcao A: o segmento pode chegar antes do vinculo)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def segmento-captado-tipo "gravacao.segmento-captado")

(def SegmentoCaptadoPayload
  [:map {:closed true}
   [:segmento-id :uuid]
   [:container-bruto-uri :string]
   [:fonte-ingestao :string]
   [:acesso-restrito :boolean]
   [:sessao-id {:optional true} [:maybe :uuid]]])

(defn segmento-captado [ente-id payload]
  (eventos/evento-validado SegmentoCaptadoPayload segmento-captado-tipo ente-id payload))
