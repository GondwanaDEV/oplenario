(ns oplenario.transparencia.notificacao-evento-test
  "Unit: o CONTRATO de `notificacao.requisitada` (Onda E fatia 1, spec §4.2). Duas mudancas COMPATIVEIS:
  `canal` admite \"in_app\" alem de \"email\" (o schema ja' era :string — o que muda e' o vocabulario) e
  `categoria` entra OPCIONAL (nao quebra o produtor do cidadao, que nao a manda)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.transparencia.events.notificacao :as ev]))

(def ^:private base
  {:destinatario-identidade-id (str (random-uuid))
   :canal "email" :consent-base "acompanhamento" :idempotency-key "k"
   :assunto "a" :corpo "c" :objeto-tipo "proposicao" :objeto-id (str (random-uuid))})

(deftest payload-sem-categoria-continua-valido
  (is (m/validate ev/RequisitadaPayload base) "o produtor do cidadao (F7 E2) nao manda categoria"))

(deftest payload-com-categoria-e-canal-in-app-e-valido
  (is (m/validate ev/RequisitadaPayload
                  (assoc base :canal "in_app" :consent-base "vinculo" :categoria "norma_publicada"))
      "notificacao INTERNA: canal in_app + consent-base vinculo + categoria"))

(deftest payload-fechado-recusa-campo-desconhecido
  (is (not (m/validate ev/RequisitadaPayload (assoc base :urgencia "alta")))
      ":closed true — campo fora do contrato nao passa"))

(deftest construtor-lanca-em-payload-invalido
  (is (thrown? clojure.lang.ExceptionInfo
               (ev/requisitada (random-uuid) (assoc base :categoria 42)))
      "categoria nao-string e' recusada na fonte (o outbox so' recebe evento bem-formado)"))
