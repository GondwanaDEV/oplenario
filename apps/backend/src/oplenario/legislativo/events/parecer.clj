(ns oplenario.legislativo.events.parecer
  "Eventos de dominio do parecer (eixo F; ADR-0001: events/ = nome + schema Malli do payload). F3.6a emite
  UM evento de transicao — `parecer.transicionou` (espelho de proposicao.transicionou). Carrega objeto_tipo/
  objeto_id para o CONSUMER da proposicao-mae (F3.6c) achar o objeto e reavaliar transicoes (§22.4:26).
  Os 7 eventos TIPADOS do eixo F (ParecerComissaoIniciado/RelatorDesignado/Apresentado/Aprovado|Rejeitado|
  Prejudicado|PrazoVencido) sao realizados em F3.6c (mapeamento para_estado->evento + agregadores da mae)."
  (:require [malli.core :as m]
            [oplenario.kernel.eventos :as eventos]))

(def transicionou-tipo "parecer.transicionou")

(def TransicionouPayload
  "Payload de `parecer.transicionou` — a transicao OCORRIDA (espelha a linha de
  parecer_transicao_historico). objeto_tipo/objeto_id identificam o objeto opinado (proposicao|emenda),
  necessarios ao consumer da mae em F3.6c."
  [:map {:closed true}
   [:parecer-id :uuid]
   [:objeto-tipo :string]
   [:objeto-id :uuid]
   [:template-id :uuid]
   [:de :string]
   [:para :string]
   [:gatilho :string]
   [:transicao-id :uuid]
   [:ator-id {:optional true} [:maybe :uuid]]])

(defn transicionou
  "Constroi o envelope de `parecer.transicionou` p/ o tenant `ente-id`, VALIDANDO o payload. Lanca
  :payload-invalido se nao casa (defesa na fonte: o shared.outbox so recebe evento bem-formado)."
  [ente-id payload]
  (when-not (m/validate TransicionouPayload payload)
    (throw (ex-info "payload de parecer.transicionou invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain TransicionouPayload payload)})))
  (eventos/evento transicionou-tipo ente-id payload))
