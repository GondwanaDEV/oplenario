(ns oplenario.participacao.wire.out.solicitacao-titular
  "Representacao EXTERNA de SAIDA da SOLICITACAO do titular LGPD (§22.10 wire/out, ADR-0001) — os contratos de
  borda que o `adapters/out` produz (e dos quais o Eixo 8 gera os tipos TS). Tudo JSON-serializavel (uuid/Instant/
  LocalDate viram string). NAO expoe o tenant (ente-id) nem o titular (PII) — a defesa anti-vazamento mora no
  adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def SolicitacaoTitularReciboOut
  "O RECIBO do protocolo (resposta 201 de POST /portal/lgpd/solicitacoes): a prova instantanea do inicio do
  relogio LGPD (CONTADOR SEPARADO do e-SIC). So o protocolo + o instante do recibo. Sem PII, sem id interno."
  [:map {:closed true}
   [:protocolo :string]
   [:recibo-em :string]])

(def RespostaTitularReciboOut
  "Recibo da resposta a uma solicitacao (resposta 200 de POST /lgpd/solicitacoes/:id/resposta): so o instante
  da resposta. (Contrato proprio — nao reusa o do e-SIC p/ nao acoplar as duas superficies.)"
  [:map {:closed true}
   [:respondida-em :string]])

(def SolicitacaoTitularOut
  "Detalhe da solicitacao para o proprio TITULAR autenticado (GET /portal/lgpd/solicitacoes/:id). Inclui o que
  o titular submeteu (tipo/detalhe = SEUS proprios dados, ok devolver ao dono) + o estado + o prazo. NAO expoe o
  tenant nem o id do titular (o dono ja e' o requester)."
  [:map {:closed true}
   [:id :string]
   [:protocolo :string]
   [:tipo (km/enum-de logic/tipos-solicitacao-titular)]
   ;; `detalhe` = conteudo do proprio titular (texto livre, NAO sanitizado no backend): o consumidor DEVE
   ;; escapar antes de renderizar como HTML. Nulavel (detalhe e' opcional); presente por chave.
   [:detalhe [:maybe :string]]
   [:estado (km/enum-de logic/estados-solicitacao-titular)]
   [:recibo-em :string]
   ;; o adapters/out SEMPRE emite estas chaves (nil quando nao ha prazo) — presente por chave, nulavel por valor.
   [:vence-em [:maybe :string]]
   [:dias-restantes [:maybe :int]]])
