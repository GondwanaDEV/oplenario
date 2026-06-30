(ns oplenario.compliance.adapters.out.painel
  "Gate de SAIDA `models -> wire/out` do painel de compliance (§22.10 adapters/out, ADR-0001 §3) — chamado
  SO pelo diplomat/. Projeta o read-model do dominio (resumo cru + obrigacoes + remessas, kebab) p/ a
  representacao externa (strings, JSON) e FILTRA o que nao deve vazar: o tenant (ente-id) e os ponteiros/
  proveniencia internos da remessa (objeto_store_ref, hash, registry_versao_ref, spec_layout_versao). A
  defesa anti-vazamento de saida mora AQUI. A projecao e' VALIDADA contra wire/out.PainelOut (drift de
  campo = bug de servidor -> 500, nunca resposta malformada que envenena o codegen do front)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.compliance.logic :as logic]
            [oplenario.compliance.wire.out.painel :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- obrigacao->wire [o]
  {:id            (->str (:id o))
   :template-chave (:template-chave o)
   :objeto-tipo   (:objeto-tipo o)
   :objeto-id     (->str (:objeto-id o))
   :vence-em      (->str (:vence-em o))
   :estado        (:estado o)})

(defn- remessa->wire [r]
  {:id            (->str (:id r))
   :template-chave (:template-chave r)
   :sistema       (:sistema r)
   :competencia   (:competencia r)
   :versao        (:versao r)
   :estado        (:estado r)
   :submetida-em  (->str (:submetida-em r))
   :resposta-em   (->str (:resposta-em r))
   :criado-em     (->str (:criado-em r))})

(defn painel->wire
  "Read-model do painel {:resumo (pares crus) :em-aberto [...] :remessas-recentes [...]} -> PainelOut
  (validada). O resumo e' 0-filado pelo logic (placar sempre com as 5 fases)."
  [{:keys [resumo em-aberto remessas-recentes]}]
  (let [out {:resumo            (logic/normalizar-resumo resumo)
             :em-aberto         (mapv obrigacao->wire em-aberto)
             :remessas-recentes (mapv remessa->wire remessas-recentes)}]
    (when-not (m/validate wire/PainelOut out)
      (throw (ex-info "projecao do painel viola o contrato PainelOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/PainelOut out)))})))
    out))
