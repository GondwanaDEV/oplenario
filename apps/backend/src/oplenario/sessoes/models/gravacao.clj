(ns oplenario.sessoes.models.gravacao
  "Representacao INTERNA (dominio) da GRAVACAO de sessao (§22.6 eixo D, F4.4b) — Malli (§22.10 models/).
  `gravacao_segmento` = unidade tecnica do arquivo; `sessao-id` opcional (vinculacao Opcao A). audio/video uri
  e hash nullable. Enums de sessoes.logic (fonte unica; os CHECK da mig 0031 espelham)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def GravacaoSegmento
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id {:optional true} [:maybe :uuid]]
   [:iniciou-em km/Instante]
   [:encerrou-em {:optional true} [:maybe km/Instante]]
   [:motivo-inicio (enum-de logic/motivos-inicio-gravacao)]
   [:motivo-fim {:optional true} [:maybe (enum-de logic/motivos-fim-gravacao)]]
   [:container-bruto-uri [:string {:min 1}]]
   [:audio-uri {:optional true} [:maybe :string]]
   [:video-uri {:optional true} [:maybe :string]]
   [:audio-hash {:optional true} [:maybe :string]]
   [:fonte-ingestao (enum-de logic/fontes-ingestao-gravacao)]
   [:acesso-restrito :boolean]
   [:lock-version :int]])
