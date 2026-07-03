(ns oplenario.participacao.adapters.out.acompanhamento-ouvidoria
  "Gate de SAIDA `models -> wire/out` do ACOMPANHAMENTO PUBLICO de ouvidoria (§22.10 adapters/out, ADR-0001)
  — chamado SO pelo diplomat/. Projeta o andamento de dominio {:protocolo :estado :dias-restantes} p/ o view
  publico e FILTRA tudo que e' PII/interno: tipo, assunto, descricao, manifestante, id — NENHUM chega ao
  wire. A rota e' PUBLICA (sem ator); a defesa anti-vazamento de saida mora AQUI. Validada contra wire/out."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.acompanhamento-ouvidoria :as wire]))

(set! *warn-on-reflection* true)

(defn acompanhamento->wire
  "Andamento de dominio {:protocolo :estado :dias-restantes} -> AcompanhamentoOuvidoriaOut (validada). So o
  minimo publico; NADA de PII/interno."
  [a]
  (let [out {:protocolo      (:protocolo a)
             :estado         (:estado a)
             :dias-restantes (:dias-restantes a)}]
    (when-not (m/validate wire/AcompanhamentoOuvidoriaOut out)
      (throw (ex-info "projecao do acompanhamento de ouvidoria viola o contrato (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/AcompanhamentoOuvidoriaOut out))})))
    out))
