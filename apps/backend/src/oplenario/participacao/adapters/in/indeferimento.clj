(ns oplenario.participacao.adapters.in.indeferimento
  "Gate de ENTRADA `wire/in -> models` do INDEFERIMENTO fundamentado (§22.10 adapters/in, ADR-0001) — chamado SO pelo
  diplomat/. Coage o corpo {fundamentacao} das rotas de SERVIDOR (indeferir pedido e-SIC / solicitacao LGPD), fail-closed
  (-> 400). ALLOWLIST estrita (so' `fundamentacao`): indeferido-por/indeferido-em e o estado de chegada sao INJETADOS
  do ator/relogio/rota na borda (anti-forge). O ALVO (pedido vs solicitacao) e' decidido pela ROTA — a mesma coercao
  serve as duas."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.indeferimento :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["fundamentacao"])

(defn coagir-indeferimento
  "Corpo JSON {fundamentacao} (chaves STRING) -> mapa de dominio {:fundamentacao}. ALLOWLIST descarta campo forjado
  (estado/respondido_por/pedido_id/indeferido_em); schema CLOSED + nao-vazio (a recusa PRECISA indicar as razoes — a
  fundamentacao so' com espacos nao vale) espelham a CHECK de `corpo` das migs 0040/0041."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {fundamentacao}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/IndeferimentoIn mp)]
      (invalido! "corpo de indeferimento invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:fundamentacao mp))
      (invalido! "fundamentacao obrigatoria nao pode ser vazia" {:campo :fundamentacao}))
    {:fundamentacao (:fundamentacao mp)}))
