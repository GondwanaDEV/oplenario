(ns oplenario.participacao.wire.out.meus-protocolos
  "Representacao EXTERNA de SAIDA de GET /portal/meus-protocolos (§22.10 wire/out, ADR-0001): o que a cidada
  protocolou na Casa da sessao, para acompanhar. Cada item leva o `id` (o detalhe e' rota ja' existente, so' do
  dono), o protocolo, o estado e o prazo — NUNCA o tenant, o dono (PII) nem o corpo do pedido (esse fica no detalhe)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ^:private prazo
  [[:recibo-em :string]
   [:vence-em [:maybe :string]]
   [:dias-restantes [:maybe :int]]
   ;; a resposta MAIS RECENTE da Casa, para a propria cidada ler (e decidir se recorre); nil enquanto nao ha'.
   [:resposta [:maybe [:map {:closed true} [:corpo :string] [:respondida-em :string]]]]])

(def PedidoEsicItem
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         ;; CONTEUDO DO USUARIO: o consumidor escapa antes de renderizar como HTML (React ja' escapa).
         [:assunto :string]
         [:estado (km/enum-de logic/estados-pedido)]]
        prazo))

(def SolicitacaoLgpdItem
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-solicitacao-titular)]
         [:estado (km/enum-de logic/estados-solicitacao-titular)]]
        prazo))

(def ManifestacaoItem
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-manifestacao)]
         [:assunto :string]
         [:estado (km/enum-de logic/estados-manifestacao)]]
        prazo))

(def MeusProtocolosOut
  [:map {:closed true}
   [:pedidos-esic [:vector PedidoEsicItem]]
   [:solicitacoes-lgpd [:vector SolicitacaoLgpdItem]]
   [:manifestacoes [:vector ManifestacaoItem]]])
