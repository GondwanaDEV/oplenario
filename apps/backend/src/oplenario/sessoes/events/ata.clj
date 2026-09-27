(ns oplenario.sessoes.events.ata
  "Eventos da ata (Faixa A, §22.3.3). (1) `ata.rascunho-solicitado` (A.6b) — a secretaria pediu o rascunho da ata;
  `integracao_ia` o promove a `AtaSolicitada`. (2) `ata.publicada` (A.6c) — uma versao da ata foi publicada (primeira
  ou retificacao); `integracao_ia` promove a `AtaRevisadaEPublicada` so' a que partiu de rascunho da IA (a metrica de
  aceitacao). NAO sao do projetor SSE. So' ids e o hash: o texto e' lido sob demanda."
  (:require [oplenario.kernel.eventos :as eventos]))

(def rascunho-solicitado-tipo "ata.rascunho-solicitado")

(def RascunhoSolicitadoPayload
  [:map {:closed true}
   [:solicitacao-id :uuid]
   [:sessao-id :uuid]])

(defn rascunho-solicitado [ente-id payload]
  (eventos/evento-validado RascunhoSolicitadoPayload rascunho-solicitado-tipo ente-id payload))

(def publicada-tipo "ata.publicada")

(def PublicadaPayload
  [:map {:closed true}
   [:ata-id :uuid]
   [:sessao-id :uuid]
   [:versao :int]
   [:origem-redacao :string]
   [:rascunho-id {:optional true} [:maybe :uuid]]
   [:publicada-por :uuid]
   [:conteudo-sha256 :string]])

(defn publicada [ente-id payload]
  (eventos/evento-validado PublicadaPayload publicada-tipo ente-id payload))
