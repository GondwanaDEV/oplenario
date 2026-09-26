(ns oplenario.legislativo.events.proposicao
  "Eventos de dominio do legislativo (ADR-0001: events/ = nome + schema Malli do payload). O ENVELOPE
  (tipo + ente-id + idempotency-key) vem do kernel (eventos/evento); aqui mora o VOCABULARIO — o nome do
  evento e o CONTRATO do payload, validado na construcao: um evento mal-formado nunca chega ao
  shared.outbox (que e' cross-modulo e duravel — §22.9 E2). O `wire/out` (Eixo 8) projeta este contrato
  p/ os consumidores; aqui e' a representacao interna do payload."
  (:require [malli.core :as m]
            [oplenario.kernel.eventos :as eventos]))

(def protocolada-tipo
  "Nome do evento emitido no PROTOCOLO da proposicao (gate eixo H). Carrega o SNAPSHOT PUBLICO — o que o
  read-model do portal (transparencia, §16.5) precisa p/ exibir a materia SEM consultar o legislativo (§22.10:
  o consumer nao importa nem faz JOIN cross-schema; projeta so do evento). A tramitacao subsequente chega por
  `proposicao.transicionou` (que so carrega a mudanca de estado)."
  "proposicao.protocolada")

(def ProtocoladaPayload
  "Payload de `proposicao.protocolada` — snapshot PUBLICO do ato legislativo no protocolo. So dado publico por
  natureza (proposicao e' ato publico). `autor-id` (Onda E fatia 2) e' o UUID do VEREADOR autor — o elo que o
  PERFIL PUBLICO do vereador precisa p/ listar 'materias de autoria' sem casar string de nome. Viaja como
  STRING (jsonb do outbox nao tem modulo UUID); OPCIONAL porque autor pode ser comissao/mesa/executivo/cidadao,
  que nao tem vereador-id. NAO e' PII: vereador e' ator publico da Casa."
  [:map {:closed true}
   [:proposicao-id :uuid]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :string]]
   [:estado :string]])

(defn protocolada
  "Constroi o envelope de `proposicao.protocolada` p/ o tenant `ente-id`, VALIDANDO o payload. Lanca
  :payload-invalido se nao casa — o outbox so recebe evento bem-formado."
  [ente-id payload]
  (when-not (m/validate ProtocoladaPayload payload)
    (throw (ex-info "payload de proposicao.protocolada invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain ProtocoladaPayload payload)})))
  (eventos/evento protocolada-tipo ente-id payload))

(def transicionou-tipo
  "Nome do evento emitido quando a maquina de tramitacao (eixo C) move a proposicao de estado."
  "proposicao.transicionou")

(def TransicionouPayload
  "Payload de `proposicao.transicionou` — a transicao OCORRIDA (espelha a linha de
  proposicao_transicao_historico, a prova duravel da mudanca, Inv.10). `ocorrido-em` (F7 carry, review
  architect/database MEDIUM da fatia de paineis/tramitacao-board): o instante REAL da transicao no
  dominio — `proposicao_transicao_historico.ocorrido_em`, RETURNING da INSERT (mig 0016) — nao o momento em
  que um consumer eventualmente PROJETA o evento. Sem isto, um projetor de staleness (paineis.tramitacao)
  so' tinha 'agora' (tempo de processamento) para carimbar, que reseta sob qualquer atraso comum do relay.
  Viaja como STRING ISO (jsonista nao serializa java.time.Instant, mesma disciplina de :publicado-em/
  :criado-em nos demais eventos)."
  [:map {:closed true}
   [:proposicao-id :uuid]
   [:template-id :uuid]
   [:de :string]
   [:para :string]
   [:gatilho :string]
   [:transicao-id :uuid]
   [:ocorrido-em :string]
   [:ator-id {:optional true} [:maybe :uuid]]])

(defn transicionou
  "Constroi o envelope de `proposicao.transicionou` p/ o tenant `ente-id`, VALIDANDO o payload contra o
  contrato. Lanca :payload-invalido se nao casa — defesa na fonte: o outbox so recebe evento bem-formado."
  [ente-id payload]
  (when-not (m/validate TransicionouPayload payload)
    (throw (ex-info "payload de proposicao.transicionou invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain TransicionouPayload payload)})))
  (eventos/evento transicionou-tipo ente-id payload))

(def editada-tipo
  "Nome do evento emitido no PATCH de metadados da proposicao (Onda B Slice 2 `editar-proposicao!`). Task
  1-N1 (fix do achado N-1 da revisao da Onda E fatia 2): so' `protocolar!` emitia evento — qualquer edicao
  de autoria (trocar de vereador, virar autoria nao-parlamentar, corrigir a ementa) ficava invisivel pro
  read-model publico (transparencia), o portal congelava no estado do protocolo. Carrega o MESMO tipo de
  snapshot publico de `protocolada-tipo`, so' que pos-edicao."
  "proposicao.editada")

(def EditadaPayload
  "Payload de `proposicao.editada` — snapshot PUBLICO pos-PATCH dos campos que o read-model do portal
  exibe (mesmo vocabulario de ProtocoladaPayload). `ementa` obrigatoria (NOT NULL na linha, sempre presente
  no RETURNING de db/proposicao/editar!); `autor-tipo`/`autor-texto`/`autor-id` OPCIONAIS e podem chegar
  `nil` (autoria nao-parlamentar, ou proposicao sem autor-id) — o snapshot e' o ESTADO REAL da linha
  pos-UPDATE, nunca o PATCH parcial que o cliente mandou. `autor-id` viaja como STRING (jsonb do outbox
  nao tem modulo UUID), mesma disciplina de ProtocoladaPayload."
  [:map {:closed true}
   [:proposicao-id :uuid]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :string]]])

(defn editada
  "Constroi o envelope de `proposicao.editada` p/ o tenant `ente-id`, VALIDANDO o payload. Lanca
  :payload-invalido se nao casa — o outbox so recebe evento bem-formado."
  [ente-id payload]
  (when-not (m/validate EditadaPayload payload)
    (throw (ex-info "payload de proposicao.editada invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain EditadaPayload payload)})))
  (eventos/evento editada-tipo ente-id payload))

(def recebida-tipo
  "Fatia 2b (mig 0083): alguem RECEBEU e assinou a movimentacao que trouxe a materia a um estado que o rito da
  Casa marca como carga a receber."
  "proposicao.recebida")

(def RecebidaPayload
  "O recibo assinado, sem a assinatura em si (ela mora na linha de `recebimento_tramitacao`). `movimentacao-id`
  = a linha de `proposicao_transicao_historico` recebida (nao o id da transicao do TEMPLATE, que e' o
  `transicao-id` de `proposicao.transicionou`). Instante como STRING ISO."
  [:map {:closed true}
   [:proposicao-id :uuid]
   [:movimentacao-id :uuid]
   [:estado :string]
   [:recebido-por :uuid]
   [:recebido-em :string]
   [:assinatura-algoritmo :string]])

(defn recebida [ente-id payload]
  (when-not (m/validate RecebidaPayload payload)
    (throw (ex-info "payload de proposicao.recebida invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain RecebidaPayload payload)})))
  (eventos/evento recebida-tipo ente-id payload))
