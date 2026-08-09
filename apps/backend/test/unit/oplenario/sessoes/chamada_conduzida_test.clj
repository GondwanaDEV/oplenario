(ns oplenario.sessoes.chamada-conduzida-test
  "UNIT (puro) — §22.6 eixo C, Etapa 2d: o denominador CONGELADO do ato de chamada conduzida
  (`logic/membros-da-casa-do-roster`) e o guard numerico (`logic/validar-membros-da-casa`)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic]))

(defn- membro
  ([vid] (membro vid nil))
  ([vid estado-mandato] {:vereador-id vid :nome "Fulano" :nome-parlamentar nil :partido "X"
                         :estado-mandato estado-mandato}))

;; ---------- membros-da-casa-do-roster: MESMA fonte que contar-quorum, nunca uma conta a parte ----------

(deftest membros-da-casa-do-roster-conta-a-casa-toda-quando-ninguem-esta-licenciado
  (let [roster [(membro (random-uuid)) (membro (random-uuid)) (membro (random-uuid) "vigente")]]
    (is (= 3 (logic/membros-da-casa-do-roster roster)))))

(deftest membros-da-casa-do-roster-exclui-licenciados-do-denominador
  ;; MESMA regra de `contar-quorum`: durante a licenca o vereador nao compoe a Casa para efeito de quorum
  ;; (quem compoe e' o suplente, que entra no roster com mandato proprio).
  (let [roster [(membro (random-uuid) "vigente") (membro (random-uuid) "licenciado")
                (membro (random-uuid) "vigente")]]
    (is (= 2 (logic/membros-da-casa-do-roster roster)))))

(deftest membros-da-casa-do-roster-roster-vazio-e-zero
  (is (zero? (logic/membros-da-casa-do-roster []))))

(deftest membros-da-casa-do-roster-lanca-para-estado-de-mandato-desconhecido
  ;; fail-closed: `derivar-linha-chamada` -> `estado-de-presenca` -> `validar-estado-mandato` — o mesmo
  ;; cinto de seguranca que protege a LEITURA da chamada protege este calculo, porque e' O MESMO calculo.
  (is (thrown? Exception
               (logic/membros-da-casa-do-roster [(membro (random-uuid) "suplente")]))))

;; ---------- validar-membros-da-casa: fail-closed antes do banco ----------

(deftest validar-membros-da-casa-aceita-zero-e-positivo
  (is (nil? (logic/validar-membros-da-casa 0)))
  (is (nil? (logic/validar-membros-da-casa 21))))

(deftest validar-membros-da-casa-lanca-para-negativo
  (is (thrown? Exception (logic/validar-membros-da-casa -1))))

(deftest validar-membros-da-casa-lanca-para-nao-inteiro
  (is (thrown? Exception (logic/validar-membros-da-casa 1.5)))
  (is (thrown? Exception (logic/validar-membros-da-casa nil))))

;; ---------- mensagem-de-recusa-de-chamada: redacao propria do recurso, mesmo motivo do gate de presenca ----------

(deftest mensagem-de-recusa-de-chamada-nomeia-o-estado
  (testing "fala de 'conducao da chamada', nao de 'presenca' (recurso proprio, tag propria)"
    (let [msg (logic/mensagem-de-recusa-de-chamada :estado-nao-aceita-presenca {:estado "encerrada"} nil)]
      (is (re-find #"chamada" msg))
      (is (re-find #"encerrada" msg)))))
