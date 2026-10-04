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

(def Anexo
  "Um arquivo do protocolo, para a PROPRIA cidada baixar (`/portal/meus-protocolos/<especie>/<id>/anexos/<anexo>`) — o da
  Casa (na resposta) ou o dela (no pedido), pela `origem`: id, nome, tipo, tamanho, de quem veio e quando. Nunca a chave no object storage, o sha256 nem quem enviou. O nome e'
  conteudo de quem enviou: o consumidor escapa antes de renderizar (React ja' escapa)."
  [:map {:closed true}
   [:id :string]
   [:nome :string]
   [:tipo-midia :string]
   [:bytes :int]
   [:origem [:enum "casa" "requerente"]]
   [:enviado-em :string]
   ;; retirado pela Casa (incidente de conteudo): so' QUANDO, nunca o motivo; sem link para baixar (o download e' 404)
   [:retirado-em {:optional true} :string]])

(def PedidoEsicItem
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         ;; CONTEUDO DO USUARIO: o consumidor escapa antes de renderizar como HTML (React ja' escapa).
         [:assunto :string]
         [:estado (km/enum-de logic/estados-pedido)]
         ;; prorrogada com justificativa (LAI art. 11 §2º)? nil enquanto nao houve (presente por chave)
         [:prorrogacao [:maybe Prorrogacao]]
         ;; os arquivos do protocolo ([] se nao ha): os da Casa na resposta e os do requerente no pedido (`origem`)
         [:anexos [:vector Anexo]]
         ;; o requerente ainda pode juntar arquivo ao PROPRIO pedido? (10 minutos do protocolo, ate' 5 seus)
         [:pode-anexar :boolean]
         ;; o recurso ja' interposto (V1: um por pedido) — a tela o mostra no lugar do botao de recorrer
         [:recurso [:maybe [:map {:closed true}
                            [:protocolo :string] [:estado [:enum "protocolado" "decidido"]]
                            [:recibo-em :string] [:resposta Resposta]]]]]
        prazo))

(def SolicitacaoLgpdItem
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-solicitacao-titular)]
         [:estado (km/enum-de logic/estados-solicitacao-titular)]
         [:anexos [:vector Anexo]]
         [:pode-anexar :boolean]]
        prazo))

(def ManifestacaoItem
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-manifestacao)]
         [:assunto :string]
         [:estado (km/enum-de logic/estados-manifestacao)]
         ;; prorrogada com justificativa (Lei 13.460 art. 10)? nil enquanto nao houve. A manifestacao ANONIMA nao esta
         ;; nesta lista (nao ha dono persistido): ninguem a recebe aqui.
         [:prorrogacao [:maybe Prorrogacao]]
         [:anexos [:vector Anexo]]
         [:pode-anexar :boolean]]
        prazo))

(def MeusProtocolosOut
  [:map {:closed true}
   [:pedidos-esic [:vector PedidoEsicItem]]
   [:solicitacoes-lgpd [:vector SolicitacaoLgpdItem]]
   [:manifestacoes [:vector ManifestacaoItem]]])
