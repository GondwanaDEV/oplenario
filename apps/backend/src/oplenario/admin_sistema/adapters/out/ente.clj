(ns oplenario.admin-sistema.adapters.out.ente
  "Gate de SAIDA do registro de Casas (ADR-0016): allowlist + validacao contra wire/out (drift = 500, nunca
  resposta malformada). O CPF do administrador nunca sai daqui — nem entra no registro."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.admin-sistema.wire.out.ente :as wire]))

(defn- ->str [x] (some-> x str))

(defn- validado [schema nome out]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " nome " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- casa [c]
  {:ente-id (str (:ente-id c)) :nome (:nome c) :nome-curto (:nome-curto c) :uf (:uf c)
   :municipio (when (:municipio-ibge c) {:ibge (:municipio-ibge c) :nome (:municipio-nome c)})
   :estado (:estado c) :criada-em (->str (:criado-em c)) :convite-enviado-em (->str (:convite-enviado-em c))
   :ativada-em (->str (:ativada-em c))})

(defn lista->wire [casas]
  (validado wire/ListaDeCasasOut "ListaDeCasasOut"
            {:casas (mapv casa casas)
             :resumo {:total (count casas)
                      :ativas (count (filter #(= "ativo" (:estado %)) casas))
                      :aguardando-admin (count (filter #(= "provisionar" (:estado %)) casas))}}))

(defn ficha->wire [{:keys [primeiro-admin atuacao] :as f}]
  (validado wire/FichaDaCasaOut "FichaDaCasaOut"
            {:casa (casa (:casa f))
             :primeiro-admin (when primeiro-admin (select-keys primeiro-admin [:nome :email]))
             :atuacao (mapv (fn [a] {:id (str (:id a)) :em (str (:em a)) :acao (:acao a)
                                     :operador (:operador-nome a) :detalhe (or (:detalhe a) {}) :selo (:selo a)})
                            atuacao)}))

(defn provisionada->wire [{c :casa convite :convite}]
  (validado wire/ProvisionadaOut "ProvisionadaOut" {:casa (casa c) :convite (name convite)}))

(defn casa->wire [c] (validado wire/CasaOut "CasaOut" (casa c)))
