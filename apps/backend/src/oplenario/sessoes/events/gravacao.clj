(ns oplenario.sessoes.events.gravacao
  "Eventos de fronteira CORE->IA (§22.3.3, carry do eixo D / F4.4b). (1) `gravacao.segmento-captado`: um segmento
  foi CAPTADO e esta disponivel p/ a Plataforma de IA processar (extracao de audio + transcricao). (2)
  `gravacao.segmento-vinculado`: o segmento foi VINCULADO a uma sessao (Opcao A pos-upload) — carrega a `sessao-id`
  e o `acesso-restrito` DEFINITIVO. Consumidor = pipeline de IA, NAO o projetor SSE. `acesso-restrito` viaja p/ a
  IA respeitar sigilo (sessao secreta): no fluxo Opcao A o captado pode ter saido com `acesso-restrito=false`
  (sem vinculo); o vinculado RE-deriva o sigilo (sessao secreta forca true) e RE-NOTIFICA a IA."
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

(def segmento-vinculado-tipo "gravacao.segmento-vinculado")

(def SegmentoVinculadoPayload
  "Vinculo Opcao A: `sessao-id` agora obrigatorio (o vinculo o estabelece); `acesso-restrito` = o valor
  DEFINITIVO pos-vinculo (re-derivado p/ sessao secreta). A IA reconcilia o sigilo do segmento por este evento."
  [:map {:closed true}
   [:segmento-id :uuid]
   [:sessao-id :uuid]
   [:acesso-restrito :boolean]])

(defn segmento-vinculado [ente-id payload]
  (eventos/evento-validado SegmentoVinculadoPayload segmento-vinculado-tipo ente-id payload))
