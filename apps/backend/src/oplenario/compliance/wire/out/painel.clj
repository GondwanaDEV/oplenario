(ns oplenario.compliance.wire.out.painel
  "Representacao EXTERNA de SAIDA do PAINEL de compliance (§22.10 wire/out, ADR-0001) — o contrato de borda
  que o `adapters/out` produz e do qual o Eixo 8 gera os tipos TS do front (a tela do comprador, §16.11).
  Tudo JSON-serializavel: uuid/Instant/LocalDate viram string. NAO expoe ponteiros/proveniencia internos
  (objeto_store_ref, hash, registry_versao_ref, spec_layout_versao) nem o tenant (ente-id) — a defesa
  anti-vazamento mora no adapters/out."
  (:require [oplenario.compliance.logic :as logic]
            [oplenario.kernel.malli :as km]))

(def ResumoOut
  "O placar de obrigacoes por fase (§16.11: 'cumpridas·pendentes·vencidas'). As 5 fases SEMPRE presentes
  (0-filadas pelo logic) — o front nunca precisa tratar chave ausente."
  [:map {:closed true}
   [:pendente :int]
   [:cumprida :int]
   [:vencida :int]
   [:dispensada :int]
   [:cancelada :int]])

(def ObrigacaoEmAbertoOut
  "Item de 'o que vence' (§16.11): uma obrigacao em aberto (pendente/vencida). Strings p/ uuid/LocalDate."
  [:map {:closed true}
   [:id :string]
   [:template-chave :string]
   [:objeto-tipo :string]
   [:objeto-id :string]
   [:vence-em :string]
   [:estado (km/enum-de logic/fases-obrigacao)]])

(def RemessaRecenteOut
  "Item do pipeline de remessas (§16.11): metadados publicos de uma remessa. NAO expoe o ponteiro do store
  nem a proveniencia interna (hash/registry/spec-layout)."
  [:map {:closed true}
   [:id :string]
   [:template-chave :string]
   [:sistema :string]
   [:competencia :string]
   [:versao :int]
   [:estado (km/enum-de logic/estados-remessa)]
   ;; o adapters/out SEMPRE emite estas chaves (nil quando ausente) — chave nunca falta -> NAO `{:optional}`
   ;; (senao o codegen TS de F1.5 geraria `submetidaEm?: string|null`, sugerindo ausencia que nao ocorre —
   ;; review clojure M2/Opcao A). Nulavel por valor, presente por chave.
   [:submetida-em [:maybe :string]]
   [:resposta-em [:maybe :string]]
   [:criado-em :string]])

(def PainelOut
  "O painel 'a Casa esta em dia com o TCE' (resposta de GET /compliance/painel): placar + o-que-vence +
  pipeline de remessas. Read-model composto (§16.11)."
  [:map {:closed true}
   [:resumo ResumoOut]
   [:em-aberto [:sequential ObrigacaoEmAbertoOut]]
   [:remessas-recentes [:sequential RemessaRecenteOut]]])
