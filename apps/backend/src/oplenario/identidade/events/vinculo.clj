(ns oplenario.identidade.events.vinculo
  "Evento de dominio do VINCULO (ADR-0001: events/). `identidade.vinculo.primeiro_acesso` sai UMA vez por vinculo,
  quando a pessoa entra na Casa pela primeira vez. O `admin_sistema` o consome para passar a Casa de 'provisionar' a
  'ativa' quando quem entra e' o 1o administrador (ADR-0016) — o handoff do provisionamento."
  (:require [oplenario.kernel.eventos :as eventos]))

(def primeiro-acesso-tipo "identidade.vinculo.primeiro_acesso")

(def PrimeiroAcessoPayload
  [:map {:closed true}
   [:vinculo-id :uuid]
   [:identidade-id :uuid]
   [:tipo-vinculo :string]
   [:papeis [:vector :string]]])

(defn primeiro-acesso [ente-id payload]
  (eventos/evento-validado PrimeiroAcessoPayload primeiro-acesso-tipo ente-id payload))
