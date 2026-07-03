(ns oplenario.legislativo.events.norma
  "Eventos de dominio da NORMA (F3.8b) — ADR-0001: events/ = nome + schema Malli do payload. O ENVELOPE vem
  do kernel (eventos/evento); aqui mora o VOCABULARIO. `norma.publicada` e' emitido quando a norma passa
  'promulgada' -> 'publicada' (db/norma/publicar!) — o marco de EFICACIA. Carrega o snapshot PUBLICO do ato
  promulgado p/ o read-model do portal (transparencia, §16.5) projetar a legislacao publicada SEM consultar o
  legislativo (§22.10). O DONO da verdade da publicacao segue sendo o legislativo (estado + publicado_em na
  norma); o evento so' NOTIFICA a projecao.

  `publicado-em` viaja como STRING ISO-8601 (nao java.time.Instant): o outbox serializa o payload em jsonb e
  a serializacao de Instant quebra em silencio na fronteira (jsonista sem modulo java.time). O consumidor
  re-parseia (Instant/parse) ao projetar — mesma disciplina dos demais payloads com tempo (tribuna/`:string`)."
  (:require [malli.core :as m]
            [oplenario.kernel.eventos :as eventos]))

(def publicada-tipo
  "Nome do evento emitido quando a norma e' publicada (promulgada -> publicada; eficacia legal)."
  "norma.publicada")

(def PublicadaPayload
  "Payload de `norma.publicada` — snapshot PUBLICO da norma publicada. Dado publico por natureza."
  [:map {:closed true}
   [:norma-id :uuid]
   [:proposicao-id :uuid]
   [:tipo-norma :string]
   [:numero :int]
   [:ano :int]
   [:urn :string]
   [:ementa :string]
   [:publicado-em :string]                                 ; ISO-8601 (ver docstring do ns: sem Instant no jsonb)
   [:veiculo-publicacao :string]])

(defn publicada
  "Constroi o envelope de `norma.publicada` p/ o tenant `ente-id`, VALIDANDO o payload. Lanca :payload-invalido
  se nao casa — o outbox so recebe evento bem-formado."
  [ente-id payload]
  (when-not (m/validate PublicadaPayload payload)
    (throw (ex-info "payload de norma.publicada invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain PublicadaPayload payload)})))
  (eventos/evento publicada-tipo ente-id payload))
