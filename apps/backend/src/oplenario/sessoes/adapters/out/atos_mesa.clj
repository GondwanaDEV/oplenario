(ns oplenario.sessoes.adapters.out.atos-mesa
  "Gate de SAIDA `models -> wire/out` dos ATOS DA MESA (docs/23 Fatia 2): as decisoes sobre questao de ordem e os
  incidentes processuais de uma sessao, como `GET /sessoes/:id/atos-mesa` os projeta. Validado contra o contrato
  wire/out (drift de campo = bug de servidor -> 500, nunca resposta malformada que envenena o codegen do front,
  Eixo 8). `created-by` nao sai: e' trilha de auditoria, nao conteudo da ata."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- decisao->wire
  [{:keys [id presidente-id questao decisao decidido-em fundamentacao fala-id]}]
  (cond-> {:id (->str id) :presidente-id (->str presidente-id) :questao questao :decisao decisao
           :decidido-em (->str decidido-em)}
    fundamentacao (assoc :fundamentacao fundamentacao)
    fala-id       (assoc :fala-id (->str fala-id))))

(defn- incidente->wire
  [{:keys [id tipo resultado descricao ocorrido-em objeto-tipo objeto-id requerente-id deliberacao]}]
  (cond-> {:id (->str id) :tipo tipo :resultado resultado :descricao descricao :ocorrido-em (->str ocorrido-em)}
    objeto-tipo   (assoc :objeto-tipo objeto-tipo)
    objeto-id     (assoc :objeto-id (->str objeto-id))
    requerente-id (assoc :requerente-id (->str requerente-id))
    deliberacao   (assoc :deliberacao deliberacao)))

(defn atos-mesa->wire
  "{:sessao-id uuid :decisoes [...] :incidentes [...]} (dominio, kebab, uuid/Instant) -> AtosMesaOut (validado).
  Preserva a ordem recebida (cronologica, vinda do Repo)."
  [{:keys [sessao-id decisoes incidentes]}]
  (let [out {:sessao-id  (->str sessao-id)
             :decisoes   (mapv decisao->wire decisoes)
             :incidentes (mapv incidente->wire incidentes)}]
    (when-not (m/validate wire/AtosMesaOut out)
      (throw (ex-info "atos da mesa violam o contrato AtosMesaOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/AtosMesaOut out)))})))
    out))
