(ns oplenario.legislativo.models.norma
  "Representacao INTERNA (dominio) da NORMA promulgada — Malli (§22.10 models/, F3.8b). Artefato legal com
  imutabilidade parcial: conteudo congela na promulgacao, so a publicacao muta (promulgada -> publicada). Os
  enums vem de legislativo.logic (fonte unica; os CHECK da migration 0023 espelham). `texto-versao-id`/
  `promulgado-por` sao forward-refs (sem FK). Carimbos via kernel.malli/Instante (timestamptz)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def Norma
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:proposicao-id :uuid]
   [:autografo-id :uuid]
   [:tipo-norma (enum-de logic/tipos-norma)]
   [:numero :int]
   [:ano :int]
   [:urn :string]
   [:ementa :string]
   ;; chave sempre presente na leitura; nunca nil em normas nativas (guard em promulgar! + CHECK efetivado);
   ;; [:maybe] cobre staging/importacao de legado nao-efetivada.
   [:texto-versao-id [:maybe :uuid]]
   [:estado (enum-de logic/estados-norma)]
   [:promulgado-em km/Instante]
   [:promulgado-por {:optional true} [:maybe :uuid]]
   ;; preenchidos na publicacao (promulgada -> publicada)
   [:publicado-em {:optional true} [:maybe km/Instante]]
   [:veiculo-publicacao {:optional true} [:maybe :string]]
   [:lock-version :int]])
