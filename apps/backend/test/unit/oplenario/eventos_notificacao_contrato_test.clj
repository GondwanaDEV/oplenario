(ns oplenario.eventos-notificacao-contrato-test
  "Unit (HOST-level, por isso fora da pasta de um modulo): `notificacao.requisitada` tem DOIS produtores em
  modulos que §22.10 proibe de se importarem (`transparencia`, o fan-out do cidadao; `legislativo`, a
  notificacao interna). O contrato e', portanto, DUPLICADO de proposito — o nome do evento e' contrato de
  FIACAO do bus, nao um tipo compartilhado (mesma disciplina de tempo_real/canais.clj e dos consumers, que
  hardcodam strings). Este teste e' o unico lugar do repo autorizado a olhar os dois: se as duas copias
  driftarem, o consumidor (`paineis`) passa a receber formas diferentes do mesmo evento e o bug so'
  apareceria em runtime."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.events.notificacao :as leg]
            [oplenario.transparencia.events.notificacao :as transp]))

(deftest o-nome-do-evento-e-o-mesmo
  (is (= transp/requisitada-tipo leg/requisitada-tipo "notificacao.requisitada")))

(deftest os-dois-payloads-sao-estruturalmente-iguais
  (is (= transp/RequisitadaPayload leg/RequisitadaPayload)
      "as duas copias do contrato driftaram — reconcilie ANTES de mergear"))
