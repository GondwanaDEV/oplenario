(ns oplenario.kernel.catalogo-test
  "ADR-0009 — o formato do catalogo de acoes e a mecanica generica de execucao (papel, entrada, saida)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.kernel.catalogo :as catalogo]))

(def ^:private eco
  {:nome "eco_de_teste"
   :descricao "Devolve o numero recebido; so' existe para exercitar a mecanica do catalogo nos testes."
   :papeis #{"secretario"}
   :entrada [:map {:closed true} [:n [:int {:min 1}]] [:quando {:optional true} :uuid]]
   :saida [:map [:n :int]]
   :rotas #{}
   :executar (fn [_ _ {:keys [n]}] (when (< n 100) {:n n}))})

(def ^:private secretaria {:identidade-id (random-uuid) :ente-id (random-uuid) :papeis #{"secretario"}})

(defn- tipo-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest sem-classe-e-ato
  (is (= :ato (:classe (catalogo/entrada eco))) "quem esquece de classificar nunca ganha leitura livre")
  (is (= :leitura (:classe (catalogo/entrada (assoc eco :classe :leitura))))))

(deftest formato-mal-formado-nao-sobe
  (testing "nome fora de snake_case"
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco :nome "EcoDeTeste")))))
  (testing "descricao curta demais para o agente decidir quando usar"
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco :descricao "eco")))))
  (testing "sem papel ninguem usa"
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco :papeis #{})))))
  (testing "classe desconhecida"
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco :classe :livre)))))
  (testing "chave estranha"
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco :publico :todos))))))

(deftest nomes-repetidos-nao-sobem
  (let [e (catalogo/entrada eco)]
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/validar-catalogo! [e e])))
    (is (= #{"eco_de_teste"} (set (keys (catalogo/validar-catalogo! [e])))))))

(deftest descrever-da'-json-schema
  (let [d (catalogo/descrever (catalogo/entrada (assoc eco :classe :leitura)))]
    (is (= "leitura" (:classe d)))
    (is (= "object" (get-in d [:entrada :type])))
    (is (= ["n"] (map name (get-in d [:entrada :required]))))
    (is (= "integer" (get-in d [:entrada :properties :n :type])))))

(deftest executar
  (let [e (catalogo/entrada (assoc eco :classe :leitura))]
    (testing "o papel e' exigido antes de tudo"
      (is (= :autorizacao/negado
             (tipo-do-erro #(catalogo/executar e {} (assoc secretaria :papeis #{"vereador"}) {:n 1})))))
    (testing "a entrada chega como JSON: string vira uuid, chave estranha cai"
      (is (= {:n 3} (catalogo/executar e {} secretaria {:n 3 :quando (str (random-uuid)) :lixo "x"}))))
    (testing "entrada invalida e' erro de validacao (400), com o que faltou"
      (is (= :validacao/invalido (tipo-do-erro #(catalogo/executar e {} secretaria {:n 0}))))
      (is (= :validacao/invalido (tipo-do-erro #(catalogo/executar e {} secretaria nil)))))
    (testing "nil = nao encontrado"
      (is (nil? (catalogo/executar e {} secretaria {:n 500}))))
    (testing "saida fora do contrato e' bug de servidor, nunca vai ao agente"
      (let [torta (catalogo/entrada (assoc eco :executar (fn [_ _ _] {:n "tres"})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"viola o contrato"
                              (catalogo/executar torta {} secretaria {:n 3})))))))

;; ---------- ADR-0010: o ator de AGENTE (com `:via`) — interseccao de classes e audit das escritas ----------

(defn- via [classes & {:as extra}]
  (merge {:agente "assistente" :execucao-id (random-uuid) :publico :secretaria :classes classes :institucional? false}
         extra))

(defn- gravador []
  (let [a (atom [])] [a (fn [_ e desfecho] (swap! a conj [(:nome e) desfecho]))]))

(deftest classe-precisa-estar-concedida
  (let [leitura (catalogo/entrada (assoc eco :classe :leitura))
        rascunho (catalogo/entrada (assoc eco :classe :rascunho))
        [_ reg] (gravador)]
    (is (= {:n 1} (catalogo/executar leitura {} (assoc secretaria :via (via #{:leitura})) {:n 1})))
    (is (= :autorizacao/negado
           (tipo-do-erro #(catalogo/executar rascunho {:registrar-chamada reg}
                                             (assoc secretaria :via (via #{:leitura})) {:n 1})))
        "execucao que so' recebeu leitura nao escreve")
    (testing "a tela (sem :via) nao passa pela regra de classe"
      (is (= {:n 1} (catalogo/executar rascunho {} secretaria {:n 1}))))))

(deftest ato-por-agente-nunca-executa-direto
  (let [ato (catalogo/entrada eco)
        [_ reg] (gravador)]
    (is (= :ato-so-por-proposta
           (try (catalogo/executar ato {:registrar-chamada reg}
                                   (assoc secretaria :via (via #{:leitura :rascunho :ato})) {:n 1})
                (catch clojure.lang.ExceptionInfo e (:razao (ex-data e))))))
    (is (= :institucional-nunca-ato
           (try (catalogo/executar ato {:registrar-chamada reg}
                                   (assoc secretaria :via (via #{:ato} :institucional? true)) {:n 1})
                (catch clojure.lang.ExceptionInfo e (:razao (ex-data e))))))))

(deftest escrita-por-agente-vai-sempre-ao-audit
  (let [rascunho (catalogo/entrada (assoc eco :classe :rascunho))
        ator (assoc secretaria :via (via #{:leitura :rascunho}))
        [a reg] (gravador)]
    (catalogo/executar rascunho {:registrar-chamada reg} ator {:n 1})
    (catalogo/executar rascunho {:registrar-chamada reg} ator {:n 500})
    (tipo-do-erro #(catalogo/executar rascunho {:registrar-chamada reg} ator {:n 0}))
    (tipo-do-erro #(catalogo/executar rascunho {:registrar-chamada reg} (assoc ator :papeis #{"vereador"}) {:n 1}))
    (is (= [["eco_de_teste" "ok"] ["eco_de_teste" "nao_encontrado"] ["eco_de_teste" "invalido"]
            ["eco_de_teste" "negado"]]
           @a))
    (testing "leitura por agente segue a regra das telas: sem audit por chamada"
      (let [[b reg2] (gravador)]
        (catalogo/executar (catalogo/entrada (assoc eco :classe :leitura)) {:registrar-chamada reg2} ator {:n 1})
        (is (empty? @b))))
    (testing "sem o seam de audit, a escrita por agente nao roda"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sem registro de audit"
                            (catalogo/executar rascunho {} ator {:n 1}))))))
