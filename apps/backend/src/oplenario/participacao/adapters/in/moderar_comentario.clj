(ns oplenario.participacao.adapters.in.moderar-comentario
  "Gate de ENTRADA `wire/in -> models` da MODERACAO de um comentario (§22.10 adapters/in, ADR-0001) —
  chamado SO pelo diplomat/. Coage o corpo {acao, motivo-rejeicao?} da rota de SERVIDOR (POST
  /comentarios/:id/moderar), fail-closed (-> 400). ALEM do schema estatico (wire/in), aplica DUAS regras
  condicionais (Malli nao expressa bem sem multi-schema — checagem manual explicita, mesmo padrao dos
  demais adapters do modulo): (a) `motivo-rejeicao` e' OBRIGATORIO quando `acao='rejeitado'`; (b) quando
  presente, DEVE ser um dos 5 motivos FIXOS (participacao/logic/motivos-rejeicao-comentario — reusa o
  validador, nao reimplementa o vocabulario). Quando `acao='aprovado'`, `motivo-rejeicao` e' DESCARTADO
  (nunca chega ao dominio) — defesa-em-profundidade: mesmo que o cliente mande um motivo por engano, o
  banco nunca ve um motivo_rejeicao num comentario aprovado."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.logic :as logic]
            [oplenario.participacao.wire.in.moderar-comentario :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["acao" "motivo-rejeicao"])

(defn coagir-moderar
  "Corpo JSON {acao, motivo-rejeicao?} -> {:acao :motivo-rejeicao}. `acao='pendente'` e' rejeitado pelo
  schema estatico ([:enum \"aprovado\" \"rejeitado\"] — 'pendente' nao e' uma ACAO, so' um estado de
  chegada). `motivo-rejeicao` obrigatorio+valido quando rejeitado; SEMPRE nil quando aprovado."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {acao, motivo-rejeicao?}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/ModerarComentarioIn mp)]
      (invalido! "corpo de moderacao de comentario invalido" {:campos (keys (me/humanize erros))}))
    (if (= "rejeitado" (:acao mp))
      (do
        (when (str/blank? (:motivo-rejeicao mp))
          (invalido! "motivo-rejeicao obrigatorio quando acao=rejeitado" {:campo :motivo-rejeicao}))
        (try
          (logic/validar-motivo-rejeicao-comentario (:motivo-rejeicao mp))
          (catch clojure.lang.ExceptionInfo _
            (invalido! "motivo-rejeicao fora do vocabulario fixo dos 5 motivos" {:campo :motivo-rejeicao})))
        {:acao "rejeitado" :motivo-rejeicao (:motivo-rejeicao mp)})
      {:acao "aprovado" :motivo-rejeicao nil})))
