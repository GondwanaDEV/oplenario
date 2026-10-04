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

(def ^:private Resposta [:maybe [:map {:closed true} [:corpo :string] [:respondida-em :string]]])

(def ^:private Data [:re #"^\d{4}-\d{2}-\d{2}$"])

(def Prorrogacao
  "A prorrogacao do prazo, para o PROPRIO requerente ler (LAI art. 11 §2º: 'justificativa expressa, da qual sera
  cientificado o requerente'): a data original, a nova (AAAA-MM-DD, dia civil), a justificativa e o instante em que foi
  prorrogado. So' estas quatro chaves — quem prorrogou (servidor) e o resto da tabela nunca saem. A justificativa e'
  TEXTO LIVRE do servidor e CONTEUDO DO USUARIO: o consumidor escapa antes de renderizar como HTML (React ja' escapa).
  NUNCA vai para o acompanhamento PUBLICO por protocolo (sequencial, adivinhavel) — so' para o dono autenticado."
  [:map {:closed true}
   [:de-data Data]
   [:para-data Data]
   [:justificativa :string]
   [:prorrogado-em :string]])

(def PedidoEsicItem
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         ;; CONTEUDO DO USUARIO: o consumidor escapa antes de renderizar como HTML (React ja' escapa).
         [:assunto :string]
         [:estado (km/enum-de logic/estados-pedido)]
         ;; prorrogada com justificativa (LAI art. 11 §2º)? nil enquanto nao houve (presente por chave)
         [:prorrogacao [:maybe Prorrogacao]]
         ;; o recurso ja' interposto (V1: um por pedido) — a tela o mostra no lugar do botao de recorrer
         [:recurso [:maybe [:map {:closed true}
                            [:protocolo :string] [:estado [:enum "protocolado" "decidido"]]
                            [:recibo-em :string] [:resposta Resposta]]]]]
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
         [:estado (km/enum-de logic/estados-manifestacao)]
         ;; prorrogada com justificativa (Lei 13.460 art. 10)? nil enquanto nao houve. A manifestacao ANONIMA nao esta
         ;; nesta lista (nao ha dono persistido): ninguem a recebe aqui.
         [:prorrogacao [:maybe Prorrogacao]]]
        prazo))

(def MeusProtocolosOut
  [:map {:closed true}
   [:pedidos-esic [:vector PedidoEsicItem]]
   [:solicitacoes-lgpd [:vector SolicitacaoLgpdItem]]
   [:manifestacoes [:vector ManifestacaoItem]]])
