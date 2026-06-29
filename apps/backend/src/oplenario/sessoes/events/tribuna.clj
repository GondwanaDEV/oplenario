(ns oplenario.sessoes.events.tribuna
  "Eventos de dominio da TRIBUNA (ADR-0001: events/). Fonte do projetor SSE (§22.6 eixo G): o cronometro ao
  vivo e' marcos + calculo client-side — o servidor emite as TRANSICOES ESTRUTURAIS (iniciada/encerrada/marcos
  do cronometro), o cliente computa o display local (descartado: ticks por segundo). Instantes viajam como
  ISO-8601 string (jsonista nao serializa java.time.Instant)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def fala-iniciada-tipo "fala.iniciada")

(def FalaIniciadaPayload
  "Fala iniciada (orador X com a palavra a partir de `iniciou-em` — ancora do cronometro client-side)."
  [:map {:closed true}
   [:fala-id :uuid]
   [:sessao-id :uuid]
   [:orador-id :uuid]
   [:tipo-fala :string]
   [:fase :string]
   [:iniciou-em :string]
   [:inscricao-id {:optional true} [:maybe :uuid]]])

(defn fala-iniciada [ente-id payload]
  (eventos/evento-validado FalaIniciadaPayload fala-iniciada-tipo ente-id payload))

(def fala-encerrada-tipo "fala.encerrada")

(def FalaEncerradaPayload
  "Fala encerrada — `tempo-segundos` = tempo efetivamente usado (projecao computada ao encerrar)."
  [:map {:closed true}
   [:fala-id :uuid]
   [:sessao-id :uuid]
   [:tempo-segundos :int]
   [:encerrou-em :string]])

(defn fala-encerrada [ente-id payload]
  (eventos/evento-validado FalaEncerradaPayload fala-encerrada-tipo ente-id payload))

(def fala-cronometro-tipo "fala.cronometro")

(def FalaCronometroPayload
  "Marco estrutural do cronometro (pausada|retomada|aparte_concedido|tempo_adicional_concedido) — o cliente
  recomputa o display a partir destes marcos."
  [:map {:closed true}
   [:fala-id :uuid]
   [:sessao-id :uuid]
   [:tipo :string]
   [:ocorrido-em :string]
   [:segundos-adicionais {:optional true} [:maybe :int]]])

(defn fala-cronometro [ente-id payload]
  (eventos/evento-validado FalaCronometroPayload fala-cronometro-tipo ente-id payload))

(def inscricao-registrada-tipo "inscricao.registrada")

(def InscricaoRegistradaPayload
  "Inscricao de orador registrada — atualiza a fila ao vivo (canal plenario/dashboard)."
  [:map {:closed true}
   [:inscricao-id :uuid]
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:origem-inscricao :string]
   [:fase :string]
   [:ordem :int]])

(defn inscricao-registrada [ente-id payload]
  (eventos/evento-validado InscricaoRegistradaPayload inscricao-registrada-tipo ente-id payload))

(def inscricao-desistida-tipo "inscricao.desistida")

(def InscricaoDesistidaPayload
  "Inscricao retirada (desistencia) — remove da fila ao vivo."
  [:map {:closed true}
   [:inscricao-id :uuid]
   [:sessao-id :uuid]])

(defn inscricao-desistida [ente-id payload]
  (eventos/evento-validado InscricaoDesistidaPayload inscricao-desistida-tipo ente-id payload))
