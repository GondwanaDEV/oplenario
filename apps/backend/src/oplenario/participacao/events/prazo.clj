(ns oplenario.participacao.events.prazo
  "Evento de dominio do PRAZO do e-SIC (§22.10 events/, ADR-0001). `participacao.prazo.vencido` e' o
  `acao_no_vencimento` V1 (§22.7.7): quando o sweep de F6.3 detecta um prazo pendente estritamente vencido,
  emite este evento na MESMA tx do CAS. Escalada/notificacao ao servidor sao CONSUMERS futuros (nao logica
  aqui). Sem PII: so as chaves de rastreamento (objeto_tipo/objeto_id + vence_em como ISO string — jsonista
  nao serializa java.time.LocalDate). O `ente-id` mora no envelope."
  (:require [oplenario.kernel.eventos :as eventos]))

(def vencido-tipo "participacao.prazo.vencido")

(def VencidoPayload
  "Payload do vencimento de um prazo. Sem PII (assunto/descricao/solicitante ficam no banco); so o objeto
  polimorfico sob prazo (objeto_tipo/objeto_id) + a data de vencimento cruzada."
  [:map {:closed true}
   [:objeto-tipo :string]
   [:objeto-id :uuid]
   [:vence-em :string]])  ; ISO date (o vencimento cruzado)

(defn vencido [ente-id payload]
  (eventos/evento-validado VencidoPayload vencido-tipo ente-id payload))
