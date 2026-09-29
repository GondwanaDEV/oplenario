(ns oplenario.admin-sistema.adapters.out.ia
  "Gate de SAIDA da observabilidade da IA (Onda E). Recebe o mapa do satelite (chaves keyword; nil = IA fora) e
  projeta campo a campo — allowlist: o que o satelite mandar a mais nao atravessa — e valida contra wire/out."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.admin-sistema.wire.out.ia :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [out]
  (when-not (m/validate wire/ObservabilidadeIAOut out)
    (throw (ex-info "observabilidade da IA viola o contrato (bug de servidor)"
                    {:erros (me/humanize (m/explain wire/ObservabilidadeIAOut out))})))
  out)

(defn- decimal [x] (.toPlainString (bigdec (or x 0))))

(defn- inteiro [x] (some-> x long))

(defn- agregado [a]
  {:execucoes (long (or (:execucoes a) 0)) :indisponiveis (long (or (:indisponiveis a) 0))
   :latencia-p50-ms (inteiro (:latencia-p50-ms a)) :latencia-p95-ms (inteiro (:latencia-p95-ms a))
   :custo (decimal (:custo a)) :parcial (boolean (:parcial a))})

(defn observabilidade->wire
  "`horas` = a janela pedida (sai mesmo com a IA fora, para a tela manter o seletor)."
  [horas o]
  (validado
   (if-not o
     {:disponivel false :horas horas :desde nil :ate nil :casas nil :moeda nil :total nil
      :por-operacao [] :por-fornecedor [] :motivos-indisponivel [] :por-hora []}
     {:disponivel true
      :horas (long (or (:horas o) horas))
      :desde (some-> (:desde o) str) :ate (some-> (:ate o) str)
      :casas (long (or (:casas o) 0))
      :moeda (:moeda o)
      :total (agregado (:total o))
      :por-operacao (mapv #(assoc (agregado %) :operacao (str (:operacao %))) (:por-operacao o))
      :por-fornecedor (mapv #(assoc (agregado %) :vendor (str (:vendor %)) :modelo (some-> (:modelo %) str))
                            (:por-fornecedor o))
      :motivos-indisponivel (mapv (fn [x] {:motivo (str (:motivo x)) :execucoes (long (or (:execucoes x) 0))})
                                  (:motivos-indisponivel o))
      :por-hora (mapv (fn [h] {:inicio (str (:inicio h)) :execucoes (long (or (:execucoes h) 0))
                               :indisponiveis (long (or (:indisponiveis h) 0))})
                      (:por-hora o))})))
