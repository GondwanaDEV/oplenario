(ns oplenario.transparencia.adapters.out.dados-abertos
  "Gate de SAIDA dos DADOS ABERTOS: o catalogo (validado contra o wire) e a resposta CSV (texto + headers de
  download). O CSV ja' sai pronto da logic; aqui so' o transporte."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.dados-abertos :as wire]))

(set! *warn-on-reflection* true)

(defn catalogo->wire [datasets]
  (let [out {:datasets (mapv (fn [d]
                               {:chave (:chave d) :titulo (:titulo d) :descricao (:descricao d)
                                :arquivo (:arquivo d) :formato "csv" :linhas (long (or (:linhas d) 0))
                                :atualizado-em (some-> (:atualizado-em d) str)
                                :colunas (mapv (fn [[nome descricao]] {:nome nome :descricao descricao})
                                               (:colunas d))})
                             datasets)}]
    (when-not (m/validate wire/DadosAbertosOut out)
      (throw (ex-info "catalogo de dados abertos viola o contrato (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/DadosAbertosOut out)))})))
    out))

(defn ->download
  "A resposta do arquivo: text/csv UTF-8, com o nome sugerido para salvar."
  [{:keys [dataset csv]}]
  {:status 200
   :headers {"Content-Type" "text/csv; charset=utf-8"
             "Content-Disposition" (str "attachment; filename=\"" (:arquivo dataset) "\"")}
   :body csv})
