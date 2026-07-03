(ns oplenario.participacao.wire.out.pedido-esic
  "Representacao EXTERNA de SAIDA do pedido e-SIC (§22.10 wire/out, ADR-0001) — o contrato de borda que o
  `adapters/out` produz e do qual o Eixo 8 gera os tipos TS do front. Tudo JSON-serializavel: uuid/Instant
  viram string. NAO expoe o tenant (ente-id) nem o solicitante (PII) — a defesa anti-vazamento mora no
  adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ReciboOut
  "O RECIBO do protocolo (resposta 201 de POST /portal/esic/pedidos): a prova instantanea do inicio do
  relogio LAI. So o protocolo (chave publica de acompanhamento) + o instante do recibo. Sem PII, sem id interno."
  [:map {:closed true}
   [:protocolo :string]
   [:recibo-em :string]])

(def PedidoOut
  "Detalhe do pedido para o proprio SOLICITANTE autenticado (GET /portal/esic/pedidos/:id). Inclui o que o
  cidadao submeteu (assunto/descricao) + o estado do ciclo + o prazo. NAO expoe o tenant nem o id do
  solicitante (o dono ja e' o requester; o campo nao precisa vazar)."
  [:map {:closed true}
   [:id :string]
   [:protocolo :string]
   ;; CONTEUDO DO USUARIO (e-SIC amplo = texto livre, NAO sanitizado no backend): o consumidor DEVE escapar
   ;; assunto/descricao antes de renderizar como HTML (React/Next escapa por padrao; dashboard a mao, cuidado).
   [:assunto :string]
   [:descricao :string]
   [:estado (km/enum-de logic/estados-pedido)]
   [:recibo-em :string]
   ;; o adapters/out SEMPRE emite estas chaves (nil quando nao ha prazo) — presente por chave, nulavel por valor.
   [:vence-em [:maybe :string]]
   [:dias-restantes [:maybe :int]]])
