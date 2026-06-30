(ns oplenario.compliance.adapters.out.remessa
  "Gate de SAIDA `models -> wire/out` de uma REMESSA (§22.10 adapters/out, ADR-0001 §3) — chamado SO pelo
  diplomat/. Projeta a remessa de dominio (kebab) p/ a representacao externa (strings, JSON) e FILTRA o que
  nao deve vazar: o tenant (ente-id) e os ponteiros/proveniencia internos (objeto_store_ref, hash,
  registry_versao_ref, spec_layout_versao). A defesa anti-vazamento de saida mora AQUI. A projecao e'
  VALIDADA contra wire/out.RemessaOut (drift de campo = bug de servidor -> 500, nunca resposta malformada
  que envenena o codegen do front)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.compliance.wire.out.remessa :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn remessa->wire
  "Remessa de dominio (kebab) -> RemessaOut (validada). FILTRA tenant + ponteiros/proveniencia internos."
  [r]
  (let [out {:id            (->str (:id r))
             :template-chave (:template-chave r)
             :sistema       (:sistema r)
             :competencia   (:competencia r)
             :versao        (:versao r)
             :estado        (:estado r)
             :submetida-em  (->str (:submetida-em r))
             :resposta-em   (->str (:resposta-em r))
             :criado-em     (->str (:criado-em r))}]
    (when-not (m/validate wire/RemessaOut out)
      ;; guarda os nomes-de-campo humanizados; o erro global LOGA, nao vai ao corpo (precedente painel, M1).
      (throw (ex-info "projecao da remessa viola o contrato RemessaOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/RemessaOut out))})))
    out))
