(ns oplenario.compliance.wire.out.remessa
  "Representacao EXTERNA de SAIDA de uma REMESSA (§22.10 wire/out, ADR-0001) — o contrato de borda que o
  `adapters/out` produz na resposta das transicoes do ciclo (validar/submeter/registrar-resposta, F5.5b) e
  do qual o Eixo 8 gera os tipos TS do front. Tudo JSON-serializavel: uuid/Instant viram string. NAO expoe
  os ponteiros/proveniencia internos (objeto_store_ref, hash, registry_versao_ref, spec_layout_versao) nem
  o tenant (ente-id) — a defesa anti-vazamento mora no adapters/out."
  (:require [oplenario.compliance.logic :as logic]
            [oplenario.kernel.malli :as km]))

(def RemessaOut
  "Uma remessa apos uma transicao do ciclo (resposta de POST /compliance/remessas/:id/{validar|submeter|
  resposta}): metadados publicos + o estado atual do ciclo. Mesma FORMA que `wire.out.painel/RemessaRecenteOut`
  (item do pipeline) — separados DE PROPOSITO por serem contratos de endpoints distintos. NAO unificar sem
  ajustar o codegen TS (Eixo 8/F1.5): se os campos divergirem em refactor, o codegen geraria tipos
  inconsistentes sem alarme de compilacao (review clj M3)."
  [:map {:closed true}
   [:id :string]
   [:template-chave :string]
   [:sistema :string]
   [:competencia :string]
   [:versao :int]
   [:estado (km/enum-de logic/estados-remessa)]
   ;; o adapters/out SEMPRE emite estas chaves (nil quando ausente) — chave nunca falta -> NAO `{:optional}`
   ;; (senao o codegen TS de F1.5 geraria `submetidaEm?: string|null`, sugerindo ausencia que nao ocorre —
   ;; precedente RemessaRecenteOut/painel, review clj M2/Opcao A). Nulavel por valor, presente por chave.
   [:submetida-em [:maybe :string]]
   [:resposta-em [:maybe :string]]
   [:criado-em :string]])
