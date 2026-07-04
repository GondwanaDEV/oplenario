(ns oplenario.transparencia.notificacao-logic-test
  "UNIT (puro, sem PG) — F7 E2: renderizacao do conteudo + chave de idempotencia deterministica do fan-out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.transparencia.logic.notificacao :as logic]))

(def ^:private materia
  {:tipo "pl" :ano 2026 :sequencial 12 :ementa "Dispoe sobre a arborizacao urbana." :estado "em_pauta"})

(deftest chave-idempotencia-e-deterministica
  (let [tid "11111111-1111-1111-1111-111111111111"
        dst "22222222-2222-2222-2222-222222222222"]
    (is (= (logic/chave-idempotencia tid dst) (logic/chave-idempotencia tid dst))
        "mesma (transicao, destinatario) -> mesma chave (dedup no-op em replay)")
    (is (not= (logic/chave-idempotencia tid dst) (logic/chave-idempotencia tid "outro"))
        "destinatarios diferentes -> chaves diferentes (uma entrega por seguidor)")
    (is (not= (logic/chave-idempotencia "outra" dst) (logic/chave-idempotencia tid dst))
        "transicoes diferentes -> chaves diferentes (uma entrega por transicao)")))

(deftest renderizar-monta-assunto-e-corpo-de-info-publica
  (let [{:keys [assunto corpo]} (logic/renderizar materia "sancionada")]
    (is (str/includes? assunto "PL 12/2026") "assunto tem o identificador humano da materia (tipo maiusculo)")
    (is (str/includes? corpo "arborizacao urbana") "corpo tem a ementa (info publica)")
    (is (str/includes? corpo "sancionada") "corpo tem a NOVA fase (do evento, autoritativa)")
    (is (not (str/includes? corpo "2222")) "corpo NAO tem o destinatario (entrega 1:1, sem PII no texto)")))
