(ns oplenario.agente-test
  "UNIT: o pedido de uma pergunta a' Clara — o publico sai do papel da pessoa (secretaria > vereador > consulta) e
  nunca e' um publico cujo papel ela nao tem. A credencial de cada publico leva so' as classes que ele pode usar."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.agente :as agente]))

(defn- ator [& papeis] {:identidade-id (random-uuid) :ente-id (random-uuid) :papeis (set papeis)})

(defn- erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(defn- publico [a & [pedido]]
  (:publico (agente/pedido a (cond-> {"pergunta" "Qual a pauta?"} pedido (assoc "publico" pedido)))))

(deftest o-publico-sai-do-papel
  (testing "cada papel tem o seu publico"
    (is (= :secretaria (publico (ator "secretario"))))
    (is (= :vereador (publico (ator "vereador"))))
    (doseq [p ["juridico" "auditor" "admin_ente"]]
      (is (= :consulta (publico (ator p))) p)))
  (testing "sem publico no corpo: secretaria > vereador > consulta"
    (is (= :secretaria (publico (ator "admin_ente" "vereador" "secretario"))))
    (is (= :vereador (publico (ator "juridico" "vereador"))))
    (is (= :vereador (publico (ator "auditor" "admin_ente" "vereador"))))
    (is (= :consulta (publico (ator "auditor" "juridico")))))
  (testing "com publico no corpo, so' o que um papel da pessoa alcanca"
    (is (= :consulta (publico (ator "secretario" "juridico") "consulta")))
    (is (= :vereador (publico (ator "vereador" "admin_ente") "vereador")))
    (is (= :consulta (publico (ator "vereador" "admin_ente") "consulta"))))
  (testing "publico de outro papel: negado"
    (is (= :autorizacao/negado (erro #(publico (ator "juridico") "secretaria"))))
    (is (= :autorizacao/negado (erro #(publico (ator "auditor") "vereador"))))
    (is (= :autorizacao/negado (erro #(publico (ator "secretario") "consulta"))))
    (is (= :autorizacao/negado (erro #(publico (ator "vereador") "consulta"))))
    (is (= :autorizacao/negado (erro #(publico (ator "admin_ente") "institucional"))) "o do agente da Casa, nunca")
    (is (= :autorizacao/negado (erro #(publico (ator "juridico") 12)))))
  (testing "sem papel que alcance a Clara: negado"
    (is (= :autorizacao/negado (erro #(publico (ator)))))
    (is (= :autorizacao/negado (erro #(publico (ator "agente_institucional")))))))

(deftest o-pedido-continua-validando-a-pergunta-e-a-conversa
  (is (= :validacao/invalido (erro #(agente/pedido (ator "juridico") {"pergunta" " "}))))
  (is (= :validacao/invalido (erro #(agente/pedido (ator "auditor") {"pergunta" "oi?" "conversa" "abc"}))))
  (let [c (random-uuid)]
    (is (= {:pergunta "oi?" :publico :consulta :conversa-id c}
           (agente/pedido (ator "admin_ente") {"pergunta" " oi? " "conversa" (str c)})))))

(deftest a-consulta-nunca-recebe-ato
  (is (= #{:leitura} (agente/classes-do-publico :consulta)) "juridico, auditor e admin_ente nao propoem ato pela Clara")
  (is (= #{:leitura :ato} (agente/classes-do-publico :secretaria)))
  (is (= #{:leitura :ato} (agente/classes-do-publico :vereador))))
