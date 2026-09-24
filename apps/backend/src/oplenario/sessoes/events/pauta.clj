(ns oplenario.sessoes.events.pauta
  "Evento de dominio do ANUNCIO DE ITEM DA PAUTA (docs/23 Fatia 4b, ADR-0001 events/). Fonte do projetor SSE do
  painel ao vivo (§22.6 eixo G): a TV e o telao do plenario mudam para 'Em apreciacao' quando a Mesa anuncia a
  materia. So' os ids + o instante: quem assiste ja' tem a pauta (com o resumo da materia e o autor) e resolve o
  item por `item-id` — o evento nao carrega texto nenhum. `anunciado-em` viaja como ISO-8601 string (jsonista
  nao serializa java.time.Instant). Sem campo sensivel (anunciar a materia e' ato publico de conducao)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def item-anunciado-tipo "pauta.item-anunciado")

(def ItemAnunciadoPayload
  "Um item da pauta passou a ser apreciado. `proposicao-id` so' quando o item e' materia (leitura/comunicado/
  homenagem nao tem) — a TV casa o item com a votacao que vier a abrir pelo MESMO id do placar."
  [:map {:closed true}
   [:anuncio-id :uuid]
   [:sessao-id :uuid]
   [:item-id :uuid]
   [:anunciado-em :string]
   [:proposicao-id {:optional true} :uuid]])

(defn item-anunciado [ente-id payload]
  (eventos/evento-validado ItemAnunciadoPayload item-anunciado-tipo ente-id payload))
