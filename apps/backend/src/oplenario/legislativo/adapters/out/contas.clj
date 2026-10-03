(ns oplenario.legislativo.adapters.out.contas
  "Gate de SAIDA `dominio -> wire/out` do julgamento das contas (ADR-0021 Parte B). Projeta campo a campo e valida contra
  o contrato fechado. O estado, a pauta e a frase do resultado saem da logica pura (logic/contas) sobre o dia civil
  `hoje` da Casa e a `base-membros` do quorum, que o diplomat resolve. Campo sem valor nao sai (nunca null)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.logic.contas :as contas]
            [oplenario.legislativo.wire.out.contas :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [schema out]
  (when-not (m/validate schema out)
    (throw (ex-info "prestacao de contas viola o contrato (bug de servidor)"
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- ->str [x] (some-> x str))

(defn- sem-nils [m] (into {} (remove (comp nil? val)) m))

(defn- rotulo [p] (when p (logic/numero-exibicao p)))

(defn- frase [p base-membros]
  (contas/frase-resultado (:resultado p) (get-in p [:votacao :total-sim])
                          (when (and (:votacao p) base-membros) (contas/necessarios-para-rejeitar base-membros))))

(defn- resumo [p hoje]
  (sem-nils {:id (->str (:id p)) :tipo (:tipo p) :exercicio (:exercicio p) :responsavel (:responsavel p)
             :parecer-previo (:parecer-previo p) :estado (contas/estado p hoje) :resultado (:resultado p)
             :prazo-julgamento-ate (->str (:prazo-julgamento-ate p))}))

(defn- documento [d]
  {:id (->str (:id d)) :tipo (:tipo d) :nome (:nome d) :tamanho-bytes (:tamanho-bytes d)
   :criado-em (->str (:criado-em d))})

(defn prestacoes->wire
  "GET /contas: {:prestacoes [PrestacaoResumo]}."
  [ps hoje]
  (validado wire/PrestacoesOut {:prestacoes (mapv #(resumo % hoje) ps)}))

(defn prestacao->wire
  "A ficha (PrestacaoOut). `base-membros` = a composicao do quorum (a da votacao que julgou, ou a de hoje)."
  [p hoje base-membros]
  (let [motivo (contas/motivo-nao-pautavel p hoje)
        v (:votacao p)]
    (validado wire/PrestacaoOut
              (merge (resumo p hoje)
                     (sem-nils
                      {:recebida-em (->str (:recebida-em p))
                       :processo-tce (:processo-tce p)
                       :proposicao (when-let [pr (:proposicao p)]
                                     {:id (->str (:id pr)) :rotulo (rotulo pr) :estado (:estado pr)})
                       :notificado-em (->str (:notificado-em p))
                       :notificacao-meio (:notificacao-meio p)
                       :prazo-defesa-ate (->str (:prazo-defesa-ate p))
                       :defesa-juntada-em (->str (:defesa-juntada-em p))
                       :julgada-em (->str (:julgada-em p))
                       :situacao-tce (:situacao-tce p)
                       :votacao (when v {:id (->str (:id v)) :sim (or (:total-sim v) 0) :nao (or (:total-nao v) 0)
                                         :abstencao (or (:total-abstencao v) 0)})
                       :frase-resultado (frase p base-membros)
                       :motivo-nao-pautavel motivo})
                     {:quorum {:base-membros base-membros
                               :necessarios-para-rejeitar (contas/necessarios-para-rejeitar base-membros)}
                      :pautavel (nil? motivo)
                      :documentos (mapv documento (:documentos p))}))))

(defn- publica [p hoje]
  (merge (sem-nils {:id (->str (:id p)) :tipo (:tipo p) :exercicio (:exercicio p) :responsavel (:responsavel p)
                    :parecer-previo (:parecer-previo p) :estado (contas/estado p hoje) :resultado (:resultado p)
                    :julgada-em (->str (:julgada-em p))
                    :proposicao-rotulo (rotulo (:proposicao p))
                    :situacao-tce (:situacao-tce p)
                    ;; a frase publica usa a composicao gravada no encerramento (sem votacao, so' a conclusao)
                    :frase-resultado (frase p (get-in p [:votacao :base-membros]))})
         {:documentos (into [] (comp (filter #(contains? contas/documentos-publicos (:tipo %)))
                                     (map #(select-keys (documento %) [:id :tipo :nome])))
                            (:documentos p))}))

(defn publicas->wire
  "GET /portal/casa/:ente/contas: {:prestacoes [PrestacaoPublica]} — so' os documentos do TCE."
  [ps hoje]
  (validado wire/PrestacoesPublicasOut {:prestacoes (mapv #(publica % hoje) ps)}))

(defn documento->wire [d] (validado wire/DocumentoAnexadoOut (documento d)))

(defn parametros->wire [p] (validado wire/ParametrosOut p))
