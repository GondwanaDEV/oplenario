(ns oplenario.transparencia.adapters.out.votacao
  "Gate de SAIDA `models -> wire/out` das votacoes publicas (§22.10 adapters/out, ADR-0001) — chamado SO pelo diplomat/.
  O dominio chega do host (votacao + sessao) e da materia projetada; aqui so' a forma publica, validada contra o wire
  (drift = bug de servidor -> 500). O voto por vereador so' sai de votacao NOMINAL — defesa em profundidade: mesmo se
  a fonte trouxer `:votos` numa secreta ou simbolica, esta camada nao os repassa."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.votacao :as wire]))

(set! *warn-on-reflection* true)

(def ^:private sem-nome "Vereador sem nome publicado")

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- ->str [x] (some-> x str))

(defn- placar [v]
  (when (some? (:total-sim v))
    {:sim (:total-sim v) :nao (or (:total-nao v) 0) :abstencoes (or (:total-abstencao v) 0)
     :base-membros (:base-membros v)}))

(defn- campos [v]
  (let [s (:sessao v) mt (:materia v)]
    {:votacao-id (->str (:id v))
     :encerrada-em (->str (:encerrada-em v))
     :sessao {:sessao-id (->str (:sessao-id s)) :tipo-sessao (:tipo-sessao s)
              :numero-sequencial (:numero-sequencial s) :data (->str (:data s))}
     :objeto-tipo (:objeto-tipo v)
     :materia (when mt {:proposicao-id (->str (:proposicao-id mt)) :tipo (:tipo mt) :sequencial (:sequencial mt)
                        :ano (:ano mt) :ementa (:ementa mt)})
     :modalidade (:modalidade v)
     :quorum-tipo (:quorum-tipo v)
     :resultado (:resultado v)
     :placar (placar v)}))

(defn lista->wire
  "{:votacoes :total :pagina :por-pagina} -> VotacoesPublicasOut."
  [{:keys [votacoes total pagina por-pagina]}]
  (validar! wire/VotacoesPublicasOut
            {:votacoes (mapv campos votacoes) :total total :pagina pagina :por-pagina por-pagina}
            "VotacoesPublicasOut"))

(defn detalhe->wire
  "A votacao + `nomes` ({vereador-id nome}) -> VotacaoDetalheOut. Os votos saem por nome, em ordem alfabetica; so' de
  votacao nominal."
  [v nomes]
  (let [votos (when (= "nominal" (:modalidade v))
                (->> (:votos v)
                     (map (fn [{:keys [vereador-id voto]}]
                            {:vereador-id (->str vereador-id) :vereador (or (get nomes vereador-id) sem-nome) :voto voto}))
                     (sort-by (juxt (comp str/lower-case :vereador) :vereador-id))
                     vec))]
    (validar! wire/VotacaoDetalheOut (assoc (campos v) :votos (or votos [])) "VotacaoDetalheOut")))
