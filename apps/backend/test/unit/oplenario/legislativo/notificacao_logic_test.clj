(ns oplenario.legislativo.notificacao-logic-test
  "Unit: logica PURA da notificacao 'a sua proposicao virou lei' (Onda E fatia 1). Renderizacao (info
  PUBLICA — a norma publicada e' ato publico) + chave de idempotencia DETERMINISTICA."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.legislativo.logic.notificacao :as logic]))

(def ^:private norma
  {:tipo-norma "lei" :numero 3 :ano 2026 :ementa "Dispoe sobre as hortas comunitarias."
   :urn "urn:lex:br;ceara;fortaleza:municipal:lei:2026-06-28;3"})

(deftest chave-e-deterministica
  (let [nid (random-uuid) dest (str (random-uuid))]
    (is (= (logic/chave-idempotencia nid dest) (logic/chave-idempotencia nid dest))
        "mesma (norma, destinatario) -> mesma chave (redrive vira no-op no ON CONFLICT)")
    (is (not= (logic/chave-idempotencia nid dest) (logic/chave-idempotencia nid (str (random-uuid))))
        "destinatarios diferentes -> chaves diferentes")))

(deftest renderiza-assunto-e-corpo-publicos
  (let [{:keys [assunto corpo]} (logic/renderizar norma)]
    (is (str/includes? assunto "Lei 3/2026") "assunto identifica a norma")
    (is (str/includes? corpo "hortas comunitarias") "corpo carrega a ementa (dado publico)")
    (is (not (str/includes? corpo "CPF")) "nenhuma PII no conteudo")))
