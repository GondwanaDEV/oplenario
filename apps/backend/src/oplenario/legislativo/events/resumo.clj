(ns oplenario.legislativo.events.resumo
  "Evento de dominio do RESUMO CIDADAO publicado (Faixa A / A.8b) — ADR-0001: events/ = nome + schema Malli do
  payload. `proposicao.resumo-publicado` sai DENTRO da tx que grava a versao (atomicidade outbox-com-o-ato §22.9 E2)
  e carrega o snapshot PUBLICO que o portal (transparencia) projeta sem consultar o legislativo (§22.10): o texto, a
  versao, se partiu da IA e quando foi publicado. `publicado-em` viaja como STRING ISO-8601 (jsonb do outbox nao tem
  java.time) — mesma disciplina de events.norma e events.artefato-publicacao."
  (:require [oplenario.kernel.eventos :as eventos]))

(def publicado-tipo "proposicao.resumo-publicado")

(def PublicadoPayload
  [:map {:closed true}
   [:proposicao-id :uuid]
   [:versao :int]
   [:texto :string]
   [:origem-redacao [:enum "gerada_automaticamente" "redigida_pela_casa"]]
   [:publicado-em :string]])

(defn publicado
  "Envelope de `proposicao.resumo-publicado` p/ o tenant `ente-id`, VALIDANDO o payload."
  [ente-id payload]
  (eventos/evento-validado PublicadoPayload publicado-tipo ente-id payload))
