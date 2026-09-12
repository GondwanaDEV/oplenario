(ns oplenario.legislativo.relator-pendente-adapters-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.relator-pendente :as adapters]
            [oplenario.legislativo.wire.out.relator-pendente :as wire]))

(deftest relatores-pendentes-projeta-e-valida
  (let [linhas [{:id (random-uuid) :proposicao-id (random-uuid) :tipo "pl" :ano 2026 :sequencial 51
                 :urn-lex "urn:lex:..." :ementa "Arborização viária" :indisponivel false
                 :criado-em (java.time.Instant/parse "2026-07-01T12:00:00Z")}]
        out (adapters/relatores-pendentes->wire {:itens linhas :truncado false})]
    (is (m/validate wire/RelatoresPendentesOut out))
    (is (= 1 (count (:itens out))))
    (is (string? (:proposicao-id (first (:itens out)))))
    (is (false? (:indisponivel (first (:itens out)))))))

(deftest relatores-pendentes-vazio
  (is (= {:itens [] :truncado false} (adapters/relatores-pendentes->wire {:itens [] :truncado false}))))

;; ---------- fatia 'truncamento-familia' ----------

(deftest relatores-pendentes-item-orfao-vem-indisponivel-nao-omitido
  ;; achado 'classe JOIN': um item cujo cabecalho nao resolveu (LEFT JOIN sem par no db/) chega aqui com
  ;; o cabecalho todo nil E `:indisponivel true` — nunca some da fila.
  (let [linha {:id (random-uuid) :proposicao-id (random-uuid) :tipo nil :ano nil :sequencial nil
               :urn-lex nil :ementa nil :indisponivel true
               :criado-em (java.time.Instant/parse "2026-07-01T12:00:00Z")}
        out (adapters/relatores-pendentes->wire {:itens [linha] :truncado false})]
    (is (m/validate wire/RelatoresPendentesOut out))
    (is (true? (:indisponivel (first (:itens out)))))
    (is (nil? (:ementa (first (:itens out)))))))

(deftest relatores-pendentes-truncado-projetado-verbatim
  (is (true? (:truncado (adapters/relatores-pendentes->wire {:itens [] :truncado true})))))

(deftest relatores-pendentes-truncado-ausente-reprova
  ;; a armadilha do {:closed true}: `:truncado` nil (produtor incompleto) tem de LANCAR, nunca virar
  ;; `false` silencioso fingindo fila completa.
  (is (thrown? clojure.lang.ExceptionInfo (adapters/relatores-pendentes->wire {:itens []}))))
