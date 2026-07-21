(ns oplenario.legislativo.events.notificacao
  "Contrato de `notificacao.requisitada` PARA O PRODUTOR INTERNO (Onda E fatia 1) — ADR-0001: events/ =
  nome + schema Malli do payload.

  POR QUE UMA COPIA: o mesmo evento ja' e' produzido por `transparencia` (fan-out do cidadao, F7 E2), mas
  §22.10 PROIBE `legislativo` importar `transparencia`. O nome do evento e' o CONTRATO DE FIACAO do bus,
  nao um tipo compartilhado — mesma disciplina que ja' faz `tempo_real/canais.clj` e os `diplomat/consumers`
  hardcodarem strings de tipo. A duplicacao e' DELIBERADA e o drift entre as duas copias e' barrado em CI
  por `oplenario.eventos-notificacao-contrato-test`.

  USO INTERNO: `canal` = \"in_app\" (a inbox, `paineis.notificacao_caixa`), `consent-base` = \"vinculo\"
  (a pessoa e' agente da Casa; comunicacao institucional do sistema que ela opera, nao marketing),
  `categoria` = \"norma_publicada\".

  SEM PII: `destinatario-identidade-id` e' o UUID de identidade (handle pseudonimo); assunto/corpo derivam
  da norma publicada (ato publico). Viajam como STRING no jsonb do outbox (jsonista nao tem modulo UUID)."
  (:require [malli.core :as m]
            [oplenario.kernel.eventos :as eventos]))

(def requisitada-tipo
  "Nome do evento. IDENTICO ao de transparencia — e' a mesma fiacao de bus, consumida por `paineis`."
  "notificacao.requisitada")

(def RequisitadaPayload
  "Payload de `notificacao.requisitada`. COPIA ESTRUTURAL do de transparencia (ver docstring do ns) —
  manter em sincronia; o drift e' barrado por `eventos-notificacao-contrato-test`."
  [:map {:closed true}
   [:destinatario-identidade-id :string]
   [:canal :string]
   [:consent-base :string]
   [:idempotency-key :string]
   [:assunto :string]
   [:corpo :string]
   [:objeto-tipo :string]
   [:objeto-id :string]
   [:categoria {:optional true} :string]])

(defn requisitada
  "Constroi o envelope de `notificacao.requisitada` p/ o tenant `ente-id`, VALIDANDO o payload contra o
  contrato. Lanca :payload-invalido se nao casa — defesa na fonte: o outbox so recebe evento bem-formado.
  NOTA: quem chama e' o consumer (`notificar-autor-da-norma!`), que envolve TUDO num try/catch — este throw
  nunca alcanca o relay."
  [ente-id payload]
  (when-not (m/validate RequisitadaPayload payload)
    (throw (ex-info "payload de notificacao.requisitada invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain RequisitadaPayload payload)})))
  (eventos/evento requisitada-tipo ente-id payload))
