(ns oplenario.participacao.wire.out.acompanhamento-ouvidoria
  "Representacao EXTERNA de SAIDA do ACOMPANHAMENTO PUBLICO de uma manifestacao de ouvidoria (§22.10
  wire/out, ADR-0001) — o contrato da rota PUBLICA sem-auth GET /portal/casa/:ente/ouvidoria/acompanhar/
  :protocolo. E' o MINIMO publico do andamento: protocolo, estado e dias restantes (vencimento EFETIVO,
  ja mostra a prorrogacao). NAO carrega PII (tipo/assunto/descricao/manifestante), NEM ids internos — a rota
  nao tem ator; a defesa anti-vazamento mora no adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def AcompanhamentoOuvidoriaOut
  "Andamento publico de uma manifestacao de ouvidoria (por protocolo). `dias-restantes` nulavel (sem prazo
  ativo; presente por chave)."
  [:map {:closed true}
   [:protocolo :string]
   [:estado (km/enum-de logic/estados-manifestacao)]
   [:dias-restantes [:maybe :int]]])
