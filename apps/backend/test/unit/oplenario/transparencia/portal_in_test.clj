(ns oplenario.transparencia.portal-in-test
  "UNIT (puro) — F6c Slice 3: coercao dos query-params do acervo de legislacao (adapters/in/portal). Todos
  OPCIONAIS: ausente/blank -> nil (nao filtra). ano/numero nao-inteiro -> :validacao/invalido (400 na borda).
  especie tolerante ao valor, mas com teto de tamanho (anti-abuso)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.transparencia.adapters.in.portal :as portal]))

(defn- invalido? [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

;; ---------- query-inteiro (ano/numero) ----------

(deftest inteiro-parseia
  (is (= 2026 (portal/query-inteiro "2026" :ano)))
  (is (= 1 (portal/query-inteiro "  1  " :numero)) "trima os espacos"))

(deftest inteiro-ausente-ou-blank-nil
  (is (nil? (portal/query-inteiro nil :ano)))
  (is (nil? (portal/query-inteiro "" :ano)))
  (is (nil? (portal/query-inteiro "   " :ano))))

(deftest inteiro-malformado-invalido
  (is (invalido? #(portal/query-inteiro "abc" :ano)) "nao-inteiro -> :validacao/invalido")
  (is (invalido? #(portal/query-inteiro "12x" :numero)))
  (is (invalido? #(portal/query-inteiro "1.5" :ano)) "decimal nao e' inteiro"))

(deftest inteiro-fora-do-range-int4-invalido
  ;; review db+seg: ano/numero sao colunas int4; um valor acima do range devolvia lista vazia em SILENCIO
  ;; (Long/parseLong casava contra int4 e nunca batia) — agora e' 400 consistente com o resto do malformado.
  (is (invalido? #(portal/query-inteiro "99999999999999" :ano)) "acima do range int4 -> 400, nao vazio-silencioso"))

(deftest inteiro-repetido-invalido
  ;; review clojure MAJOR: ?ano=1&ano=2 -> Pedestal entrega VETOR -> str/trim lancava ClassCastException
  ;; nao-capturada -> 500. Agora: parametro repetido = ambiguo -> 400 fail-closed (nunca 500).
  (is (invalido? #(portal/query-inteiro ["1" "2"] :ano)) "param repetido (vetor) -> 400, nunca ClassCastException/500"))

;; ---------- query-especie ----------

(deftest especie-trima-e-tolera-valor
  (is (= "lei" (portal/query-especie " lei ")))
  (is (= "especie_inexistente" (portal/query-especie "especie_inexistente"))
      "valor desconhecido NAO e' erro — so' nao casa nenhuma norma"))

(deftest especie-ausente-ou-blank-nil
  (is (nil? (portal/query-especie nil)))
  (is (nil? (portal/query-especie "  "))))

(deftest especie-grande-demais-invalido
  (is (invalido? #(portal/query-especie (apply str (repeat 65 "x"))))
      "acima do teto -> :validacao/invalido (nunca vira predicado gigante)"))

(deftest especie-case-insensitive
  ;; review clojure MINOR: o vocabulario e' armazenado minusculo (lei/resolucao/lei_complementar/...); o
  ;; cidadao digita "Lei" — case-insensitive evita o foot-gun de "lista vazia" por caixa.
  (is (= "lei" (portal/query-especie "Lei")))
  (is (= "lei_complementar" (portal/query-especie "  LEI_COMPLEMENTAR "))))

(deftest especie-repetida-invalido
  (is (invalido? #(portal/query-especie ["lei" "resolucao"])) "param repetido (vetor) -> 400, nunca 500"))

;; ---------- filtro-legislacao (montagem) ----------

(deftest filtro-monta-todos-opcionais
  (is (= {:tipo "lei" :ano 2026 :numero 123}
         (portal/filtro-legislacao {:tipo "lei" :ano "2026" :numero "123"})))
  (is (= {:tipo nil :ano nil :numero nil} (portal/filtro-legislacao nil))
      "query-params ausentes -> filtro todo-nil (compat Slice 1)")
  (is (= {:tipo "resolucao" :ano nil :numero nil}
         (portal/filtro-legislacao {:tipo "resolucao"})) "so' a especie"))
