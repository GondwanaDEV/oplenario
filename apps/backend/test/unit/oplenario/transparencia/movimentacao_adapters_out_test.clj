(ns oplenario.transparencia.movimentacao-adapters-out-test
  "UNIT — gate de saida da linha do tempo publica da materia: so' instante, etapa e abertura saem; o contrato e'
  FECHADO (campo a mais = bug de servidor, nunca vazamento) e o total nao tem default silencioso."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.transparencia.adapters.out.movimentacao :as a])
  (:import (java.time Instant)))

(def ^:private t1 (Instant/parse "2026-09-01T12:00:00Z"))
(def ^:private t0 (Instant/parse "2026-08-01T09:30:00Z"))

(def ^:private dominio
  {:movimentacoes [{:ocorrido-em t1 :etapa "Em análise nas comissões" :inicial false}
                   {:ocorrido-em t0 :etapa "Recebida pela Mesa" :inicial true}]
   :total 2 :completo? true :desde t0})

(deftest projeta-so-o-publico
  (is (= {:movimentacoes [{:ocorrido-em "2026-09-01T12:00:00Z" :etapa "Em análise nas comissões" :abertura false}
                          {:ocorrido-em "2026-08-01T09:30:00Z" :etapa "Recebida pela Mesa" :abertura true}]
          :movimentacoes-total 2 :historico-completo true :historico-desde "2026-08-01T09:30:00Z"}
         (a/movimentacoes->wire dominio))))

(deftest etapa-sem-rotulo-sai-nula-e-nunca-vira-a-chave
  (is (nil? (:etapa (first (:movimentacoes (a/movimentacoes->wire
                                            (assoc-in dominio [:movimentacoes 0] {:ocorrido-em t1 :etapa nil :inicial false}))))))))

(deftest campo-extra-do-dominio-nao-atravessa
  ;; ator-id/gatilho/etapa-chave existem em outros lugares do dominio: o adapter escolhe as colunas, nao repassa o mapa
  (let [r (a/movimentacoes->wire
           (assoc-in dominio [:movimentacoes 0] {:ocorrido-em t1 :etapa "X" :inicial false
                                                 :ator-id (random-uuid) :gatilho "despachar" :etapa-chave "x_chave"}))]
    (is (= #{:ocorrido-em :etapa :abertura} (set (keys (first (:movimentacoes r))))))))

(deftest total-ausente-e-bug-de-servidor-nao-zero-silencioso
  (is (thrown? clojure.lang.ExceptionInfo (a/movimentacoes->wire (dissoc dominio :total)))))

(deftest historico-que-comeca-no-meio
  (let [r (a/movimentacoes->wire (assoc dominio :completo? false))]
    (is (false? (:historico-completo r)))
    (is (= "2026-08-01T09:30:00Z" (:historico-desde r)))))
