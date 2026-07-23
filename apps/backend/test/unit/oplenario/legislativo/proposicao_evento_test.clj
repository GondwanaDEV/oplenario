(ns oplenario.legislativo.proposicao-evento-test
  "Unit: o CONTRATO de `proposicao.editada` (Task 1-N1 — fix do achado N-1 da revisao da Onda E fatia 2:
  edicoes de autoria nunca chegavam ao portal porque `editar-proposicao!` nao emitia evento nenhum). Mesma
  disciplina de transparencia/notificacao_evento_test — payload valido/invalido contra o schema Malli."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.events.proposicao :as ev]))

(def ^:private base
  {:proposicao-id (random-uuid) :ementa "Dispoe sobre X"})

(deftest payload-minimo-e-valido
  (is (m/validate ev/EditadaPayload base) "so' :proposicao-id + :ementa sao obrigatorios"))

(deftest payload-com-autoria-vereador-e-valido
  (is (m/validate ev/EditadaPayload
                  (assoc base :autor-tipo "vereador" :autor-id (str (random-uuid)) :autor-texto nil))
      "autor-id viaja como STRING (jsonb do outbox nao tem UUID)"))

(deftest payload-com-autor-id-nulo-e-valido
  (is (m/validate ev/EditadaPayload (assoc base :autor-tipo "executivo" :autor-id nil))
      "autoria nao-parlamentar zera :autor-id (Peca A) — o payload carrega null, nao ausencia"))

(deftest payload-fechado-recusa-campo-desconhecido
  (is (not (m/validate ev/EditadaPayload (assoc base :estado "protocolada")))
      ":closed true — :estado nao faz parte do contrato de editada (so' protocolada tem :estado)"))

(deftest construtor-lanca-em-payload-invalido
  (is (thrown? clojure.lang.ExceptionInfo (ev/editada (random-uuid) (dissoc base :ementa)))
      ":ementa e' obrigatorio — o outbox so' recebe evento bem-formado"))
