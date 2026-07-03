(ns oplenario.participacao.adapters.out.acompanhamento
  "Gate de SAIDA `models -> wire/out` do ACOMPANHAMENTO PUBLICO (§22.10 adapters/out, ADR-0001) — chamado SO
  pelo diplomat/. Projeta o andamento de dominio {:protocolo :estado :dias-restantes} p/ o view publico e
  FILTRA tudo que e' PII/interno: assunto, descricao, solicitante, id do pedido, ente-id — NENHUM chega ao
  wire. A rota e' PUBLICA (sem ator); a defesa anti-vazamento de saida mora AQUI. Validada contra wire/out
  (drift de campo = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.acompanhamento :as wire]))

(set! *warn-on-reflection* true)

(defn acompanhamento->wire
  "Andamento de dominio {:protocolo :estado :dias-restantes} -> AcompanhamentoOut (validada). So o minimo
  publico da LAI (protocolo, estado, dias restantes); NADA de PII/interno."
  [a]
  (let [out {:protocolo      (:protocolo a)
             :estado         (:estado a)
             :dias-restantes (:dias-restantes a)}]
    (when-not (m/validate wire/AcompanhamentoOut out)
      (throw (ex-info "projecao do acompanhamento viola o contrato AcompanhamentoOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/AcompanhamentoOut out))})))
    out))
