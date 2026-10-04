(ns oplenario.participacao.models.complemento
  "Representacao INTERNA (dominio) do COMPLEMENTO DA RESPOSTA (§22.10 models/, ADR-0001) — Malli. APPEND-ONLY (Inv.10): o
  texto que a Casa acrescenta a um protocolo ja' respondido. Polimorfico (objeto-tipo/objeto-id, a mesma forma de
  prorrogacao/anexo). `complementado-por` = a secretaria autora (injetada do ator)."
  (:require [oplenario.kernel.malli :as km]))

(def Complemento
  "Complemento persistido (participacao.complemento)."
  [:map {:closed true}
   [:id :uuid]
   [:objeto-tipo [:enum "pedido_esic" "manifestacao_ouvidoria" "solicitacao_titular"]]
   [:objeto-id :uuid]
   [:corpo [:string {:min 1 :max 50000}]]
   [:complementado-em km/Instante]
   [:complementado-por :uuid]])
