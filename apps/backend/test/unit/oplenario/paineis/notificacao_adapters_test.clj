(ns oplenario.paineis.notificacao-adapters-test
  "UNIT (puro, sem DB) — Onda E fatia 1 (inbox): o gate adapters/out da notificacao (models -> wire/out).
  Prova o caminho feliz das duas conversoes (minhas-notificacoes->wire, marcar-lida->wire) E a validacao de
  contrato (drift -> lanca). Sem este teste, apagar o `when-not/throw` do gate e' um no-op para a suite
  inteira em dados bem-formados — achado de revisao desta branch (ja' pego 3x no modulo `mesa`)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.paineis.adapters.out.notificacao :as notificacao]
            [oplenario.paineis.wire.out.notificacao :as wire]))

(def ^:private notificacao-fake
  {:id "550e8400-e29b-41d4-a716-446655440000"
   :categoria "esic_resposta"
   :assunto "Sua solicitacao foi respondida"
   :corpo "O orgao respondeu ao seu pedido."
   :objeto-tipo "esic_pedido"
   :objeto-id "660e8400-e29b-41d4-a716-446655440001"
   :criado-em "2026-07-19T12:00:00Z"
   :lida-em nil})

(deftest minhas-notificacoes-caminho-feliz-valida-o-contrato
  (let [out (notificacao/minhas-notificacoes->wire {:notificacoes [notificacao-fake] :nao-lidas 3})]
    (is (m/validate wire/MinhasNotificacoesOut out) "a projecao satisfaz MinhasNotificacoesOut")
    (is (= 3 (:nao-lidas out)))
    (is (= 1 (count (:notificacoes out))))
    (is (nil? (get-in out [:notificacoes 0 :lida-em])) "nao lida = ausencia do carimbo")))

(deftest minhas-notificacoes-nao-lidas-default-zero-quando-nil
  (let [out (notificacao/minhas-notificacoes->wire {:notificacoes [] :nao-lidas nil})]
    (is (m/validate wire/MinhasNotificacoesOut out))
    (is (= 0 (:nao-lidas out)))
    (is (= [] (:notificacoes out)))))

(deftest minhas-notificacoes-drift-de-contrato-lanca
  ;; categoria como keyword (nao :string) viola NotificacaoOut -> adapters/out lanca (nunca corpo malformado).
  (is (thrown? clojure.lang.ExceptionInfo
               (notificacao/minhas-notificacoes->wire
                {:notificacoes [(assoc notificacao-fake :categoria :esic_resposta)] :nao-lidas 1}))))

(deftest marcar-lida-caminho-feliz-valida-o-contrato
  (let [out (notificacao/marcar-lida->wire {:id "550e8400-e29b-41d4-a716-446655440000"
                                             :lida-em "2026-07-19T12:05:00Z"})]
    (is (m/validate wire/MarcarLidaOut out) "o recibo satisfaz MarcarLidaOut")
    (is (= "550e8400-e29b-41d4-a716-446655440000" (:id out)))
    (is (= "2026-07-19T12:05:00Z" (:lida-em out)))))

(deftest marcar-lida-drift-de-contrato-lanca
  ;; :id ausente -> (->str nil) = nil, viola :string obrigatorio (nao :maybe) de MarcarLidaOut -> lanca.
  (is (thrown? clojure.lang.ExceptionInfo
               (notificacao/marcar-lida->wire {:lida-em "2026-07-19T12:05:00Z"}))))
