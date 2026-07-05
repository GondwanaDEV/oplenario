(ns oplenario.transparencia.ente-adapters-test
  "UNIT (puro, sem DB) — o gate adapters/out do perfil publico do ente (FE Onda A2 fast-follow: a barra
  institucional do portal mostrava o UUID da rota em vez do nome real da Casa por falta desta projecao).
  Prova a forma (so' nome-oficial/nome-curto, sem vazar campos internos do perfil cadastral) e a validacao
  de contrato."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.transparencia.adapters.out.ente :as ente]
            [oplenario.transparencia.wire.out.ente :as wire]))

(deftest projeta-nome-oficial-e-curto
  (let [out (ente/->wire {:ente-id (random-uuid) :municipio-ibge "2304400"
                          :nome-oficial "Câmara Municipal de Fortaleza" :nome-curto "Câmara de Fortaleza"
                          :brasao-ref nil})]
    (is (m/validate wire/EnteOut out) "a projecao satisfaz EnteOut")
    (is (= {:nome-oficial "Câmara Municipal de Fortaleza" :nome-curto "Câmara de Fortaleza"} out)
        "so' os 2 campos publicos — ente-id/municipio-ibge/brasao-ref NAO vazam")))

(deftest nome-curto-ausente-fica-nil
  (let [out (ente/->wire {:ente-id (random-uuid) :nome-oficial "Câmara Municipal de Aquiraz" :nome-curto nil})]
    (is (m/validate wire/EnteOut out))
    (is (nil? (:nome-curto out)))))
