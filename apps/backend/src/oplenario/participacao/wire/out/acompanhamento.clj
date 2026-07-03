(ns oplenario.participacao.wire.out.acompanhamento
  "Representacao EXTERNA de SAIDA do ACOMPANHAMENTO PUBLICO de um pedido e-SIC (§22.10 wire/out, ADR-0001) —
  o contrato da rota PUBLICA sem-auth GET /portal/:ente/esic/acompanhar/:protocolo. E' o MINIMO que a LAI
  torna publico do andamento: o protocolo consultado, o estado do ciclo e quantos dias restam do prazo legal
  (o 'anel do prazo' do cidadao). NAO carrega PII (assunto/descricao/solicitante), NEM ids internos (id do
  pedido, ente-id) — a rota nao tem ator; a defesa anti-vazamento mora no adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def AcompanhamentoOut
  "Andamento publico de um pedido e-SIC (por protocolo). `dias-restantes` nulavel (pedido sem prazo ativo —
  ex.: ja cumprido/cancelado; presente por chave)."
  [:map {:closed true}
   [:protocolo :string]
   [:estado (km/enum-de logic/estados-pedido)]
   [:dias-restantes [:maybe :int]]])
