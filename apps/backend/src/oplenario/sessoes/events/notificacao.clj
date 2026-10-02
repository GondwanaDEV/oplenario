(ns oplenario.sessoes.events.notificacao
  "Contrato de `notificacao.requisitada` PARA O AVISO AUTOMATICO da pauta publicada (ADR-0020 fatia 2) — ADR-0001:
  events/ = nome + schema Malli do payload.

  TERCEIRA COPIA de proposito (as outras: `transparencia`, o fan-out do cidadao; `legislativo`, a notificacao
  interna): §22.10 proibe `sessoes` de importar os dois. O nome do evento e' o CONTRATO DE FIACAO do bus; o drift
  entre as copias e' barrado em CI por `oplenario.eventos-notificacao-contrato-test`.

  USO: `canal` = \"in_app\" (a caixa do sistema, `paineis.notificacao_caixa`), `consent-base` = \"vinculo\" (o
  vereador e' agente da Casa), `categoria` = \"pauta_publicada\", objeto = a sessao. SEM PII: o destinatario e' o
  UUID de identidade; assunto/corpo dizem so' o que a pauta publicada ja' torna publico."
  (:require [oplenario.kernel.eventos :as eventos]))

(def requisitada-tipo
  "Nome do evento. IDENTICO ao de transparencia e legislativo — a mesma fiacao de bus, consumida por `paineis`."
  "notificacao.requisitada")

(def RequisitadaPayload
  "COPIA ESTRUTURAL do de transparencia/legislativo — manter em sincronia (`eventos-notificacao-contrato-test`)."
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
  "O envelope de `notificacao.requisitada` VALIDADO contra o contrato (lanca :payload-invalido se nao casa)."
  [ente-id payload]
  (eventos/evento-validado RequisitadaPayload requisitada-tipo ente-id payload))
