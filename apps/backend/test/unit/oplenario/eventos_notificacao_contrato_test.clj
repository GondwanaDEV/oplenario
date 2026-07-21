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
  ;; DOIS asserts separados de proposito (achado de review: `(is (= a b "msg"))` e' `=` de aridade 3, nao
  ;; uma comparacao com mensagem — mais forte por acidente, nao por intencao). O 1o prova que as duas COPIAS
  ;; nao driftaram (o motivo deste ns existir); o 2o ancora o valor esperado, pra um typo nos DOIS produtores
  ;; ao mesmo tempo (fora do alcance do 1o assert) nao passar em silencio.
  (is (= transp/requisitada-tipo leg/requisitada-tipo)
      "as duas copias do NOME do evento (transparencia x legislativo) nao driftaram")
  (is (= "notificacao.requisitada" leg/requisitada-tipo)
      "o nome e' o contrato de fiacao esperado por `paineis` (o consumidor), nao um valor arbitrario"))

(deftest os-dois-payloads-sao-estruturalmente-iguais
  (is (= transp/RequisitadaPayload leg/RequisitadaPayload)
      "as duas copias do contrato driftaram — reconcilie ANTES de mergear"))
