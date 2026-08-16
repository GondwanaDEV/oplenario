(ns oplenario.sessoes.folha-serie-test
  "UNIT (puro, sem banco) — `logic/agrupar-serie-por-vereador` (§22.6 eixo C, Etapa 5 fatia 1): a UNICA
  transformacao pura entre a serie CRUA (`db/presenca/serie-de-eventos-da-sessao`, ja' ordenada por
  vereador_id,ocorrido_em asc) e o formato que o documento da folha consome ({vereador-id -> [eventos]})."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.logic :as logic]))

(def ^:private v1 #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private v2 #uuid "00000000-0000-0000-0000-0000000000a2")

(defn- ev [vid tipo ocorrido-em] {:vereador-id vid :tipo tipo :ocorrido-em ocorrido-em})

(deftest agrupa-eventos-por-vereador-preservando-a-ordem-cronologica
  (let [e1 (ev v1 "entrada" "10:00")
        e2 (ev v1 "saida" "11:00")
        e3 (ev v2 "entrada" "10:30")
        eventos [e1 e2 e3]] ; ja' ordenados por (vereador_id, ocorrido_em) como a query devolve
    (is (= {v1 [e1 e2] v2 [e3]}
           (logic/agrupar-serie-por-vereador eventos))
        "cada vereador vira uma chave; a lista de eventos preserva a ordem cronologica de entrada")))

(deftest vereador-sem-evento-nenhum-nao-aparece-como-chave
  (is (= {v1 [(ev v1 "entrada" "10:00")]}
         (logic/agrupar-serie-por-vereador [(ev v1 "entrada" "10:00")]))
      "v2 nunca apareceu na serie -> nao existe como chave (quem itera trata ausencia como [])"))

(deftest serie-vazia-agrupa-para-mapa-vazio
  (is (= {} (logic/agrupar-serie-por-vereador []))))
