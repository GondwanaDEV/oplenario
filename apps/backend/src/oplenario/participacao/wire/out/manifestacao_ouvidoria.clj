(ns oplenario.participacao.wire.out.manifestacao-ouvidoria
  "Representacao EXTERNA de SAIDA da manifestacao de ouvidoria (§22.10 wire/out, ADR-0001) — os contratos de
  borda que o `adapters/out` produz e do qual o Eixo 8 gera os tipos TS do front. Tudo JSON-serializavel:
  uuid/Instant viram string. NAO expoe o tenant (ente-id) — o manifestante (PII) so aparece no detalhe DO
  PROPRIO dono (ManifestacaoOut), NUNCA no publico (AcompanhamentoOuvidoriaOut, wire proprio). A defesa
  anti-vazamento mora no adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ReciboOut
  "O RECIBO do protocolo (resposta 201 de POST /portal/ouvidoria/manifestacoes): a prova instantanea do
  inicio do relogio Lei 13.460. So o protocolo (chave publica de acompanhamento) + o instante do recibo.
  Sem PII, sem id interno — nem para manifestacoes NAO-anonimas (simetria: o recibo nunca vaza o manifestante)."
  [:map {:closed true}
   [:protocolo :string]
   [:recibo-em :string]])

(def ManifestacaoOut
  "Detalhe da manifestacao para o proprio MANIFESTANTE autenticado (GET /portal/ouvidoria/manifestacoes/:id
  — NUNCA servida p/ anonimas, ver adapters/in do controller). Inclui o que o cidadao submeteu (tipo/
  assunto/descricao) + o estado do ciclo + o prazo. NAO expoe o tenant nem o id do manifestante (o dono ja e'
  o requester)."
  [:map {:closed true}
   [:id :string]
   [:protocolo :string]
   [:tipo (km/enum-de logic/tipos-manifestacao)]
   ;; CONTEUDO DO USUARIO (texto livre, NAO sanitizado no backend): o consumidor DEVE escapar antes de
   ;; renderizar como HTML (React/Next escapa por padrao; dashboard a mao, cuidado).
   [:assunto :string]
   [:descricao :string]
   [:estado (km/enum-de logic/estados-manifestacao)]
   [:recibo-em :string]
   ;; o adapters/out SEMPRE emite estas chaves (nil quando nao ha prazo) — presente por chave, nulavel por valor.
   [:vence-em [:maybe :string]]
   [:dias-restantes [:maybe :int]]])
