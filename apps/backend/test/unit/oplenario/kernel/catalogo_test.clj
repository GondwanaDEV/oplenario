(ns oplenario.kernel.catalogo-test
  "ADR-0009 — o formato do catalogo de acoes e a mecanica generica de execucao (papel, entrada, saida); ADR-0010 — o
  ator de agente; ADR-0012 — ato por agente vira proposta, e a leitura de terceiro contamina a execucao."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.kernel.catalogo :as catalogo]))

(def ^:private eco
  {:nome "eco_de_teste"
   :descricao "Devolve o numero recebido; so' existe para exercitar a mecanica do catalogo nos testes."
   :classe :leitura
   :papeis #{"secretario"}
   :entrada [:map {:closed true} [:n [:int {:min 1}]] [:quando {:optional true} :uuid]]
   :saida [:map [:n :int]]
   :rotas #{}
   :executar (fn [_ _ {:keys [n]}] (when (< n 100) {:n n}))})

(def ^:private eco-ato
  (-> eco
      (dissoc :classe)
      (assoc :ritual :confirmar
             :apresentar (fn [_ _ {:keys [n]}] (when (< n 100) {:titulo "Ecoar" :texto (str "Ecoar o numero " n)})))))

(def ^:private secretaria {:identidade-id (random-uuid) :ente-id (random-uuid) :papeis #{"secretario"}})

(defn- tipo-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))
(defn- razao-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:razao (ex-data e)))))

(deftest sem-classe-e-ato
  (is (= :ato (:classe (catalogo/entrada eco-ato))) "quem esquece de classificar nunca ganha leitura livre")
  (is (= :leitura (:classe (catalogo/entrada eco))))
  (testing "sem classe e sem o que a pessoa confirmaria, nem carrega (fail-closed)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ato" (catalogo/entrada (dissoc eco :classe))))))

(deftest ato-declara-o-que-a-pessoa-confirma
  (testing "ato sem ritual ou sem apresentar nao carrega"
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (dissoc eco-ato :ritual))))
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (dissoc eco-ato :apresentar))))
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco-ato :ritual :carimbo)))))
  (testing "ritual e apresentar sao so' de ato"
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco :ritual :confirmar))))
    (is (thrown? clojure.lang.ExceptionInfo (catalogo/entrada (assoc eco :apresentar (fn [_ _ _])))))))

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
  (let [d (catalogo/descrever (catalogo/entrada eco))]
    (is (= "leitura" (:classe d)))
    (is (= "object" (get-in d [:entrada :type])))
    (is (= ["n"] (map name (get-in d [:entrada :required]))))
    (is (= "integer" (get-in d [:entrada :properties :n :type]))))
  (testing "para o agente, a saida de um ato e' a proposta (o ato nao executa por ele)"
    (let [d (catalogo/descrever (catalogo/entrada eco-ato))]
      (is (= "ato" (:classe d)))
      (is (contains? (get-in d [:saida :properties]) :proposta-id)))))

(deftest executar
  (let [e (catalogo/entrada eco)]
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
  (let [leitura (catalogo/entrada eco)
        rascunho (catalogo/entrada (assoc eco :classe :rascunho))
        [_ reg] (gravador)]
    (is (= {:n 1} (catalogo/executar leitura {} (assoc secretaria :via (via #{:leitura})) {:n 1})))
    (is (= :autorizacao/negado
           (tipo-do-erro #(catalogo/executar rascunho {:registrar-chamada reg}
                                             (assoc secretaria :via (via #{:leitura})) {:n 1})))
        "execucao que so' recebeu leitura nao escreve")
    (is (= :classe-nao-concedida
           (razao-do-erro #(catalogo/executar (catalogo/entrada eco-ato) {:registrar-chamada reg}
                                              (assoc secretaria :via (via #{:leitura})) {:n 1})))
        "sem a classe ato, nem proposta")
    (testing "a tela (sem :via) nao passa pela regra de classe"
      (is (= {:n 1} (catalogo/executar rascunho {} secretaria {:n 1}))))))

;; ---------- ADR-0012: ato por agente vira PROPOSTA ----------

(defn- propositor []
  (let [a (atom [])]
    [a (fn [ator e entrada apresentacao]
         (swap! a conj [(:identidade-id ator) (:nome e) entrada apresentacao])
         {:proposta-id "p-1" :titulo (:titulo apresentacao) :estado "aguardando_confirmacao"
          :mensagem "Confirme na tela."})]))

(deftest ato-por-agente-vira-proposta
  (let [ato (catalogo/entrada eco-ato)
        [chamadas reg] (gravador)
        [propostas propor] (propositor)
        ator (assoc secretaria :via (via #{:leitura :ato}))]
    (is (= {:proposta-id "p-1" :titulo "Ecoar" :estado "aguardando_confirmacao" :mensagem "Confirme na tela."}
           (catalogo/executar ato {:registrar-chamada reg :propor propor} ator {:n 7 :lixo 1})))
    (is (= [[(:identidade-id secretaria) "eco_de_teste" {:n 7} {:titulo "Ecoar" :texto "Ecoar o numero 7"}]]
           @propostas)
        "a proposta leva a entrada EXATA ja' validada e o que a pessoa vai ver; o ato NAO executou")
    (testing "o que nao existe nao vira proposta"
      (is (nil? (catalogo/executar ato {:registrar-chamada reg :propor propor} ator {:n 500})))
      (is (= 1 (count @propostas))))
    (testing "entrada invalida tambem nao"
      (is (= :validacao/invalido (tipo-do-erro #(catalogo/executar ato {:registrar-chamada reg :propor propor}
                                                                    ator {:n 0})))))
    (is (= [["eco_de_teste" "proposta"] ["eco_de_teste" "nao_encontrado"] ["eco_de_teste" "invalido"]] @chamadas)
        "toda tentativa de ato por agente vai ao audit")
    (testing "sem o seam de proposta, o ato por agente nao roda"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"proposta" (catalogo/executar ato {:registrar-chamada reg}
                                                                                       ator {:n 1}))))
    (testing "agente institucional nunca, nem por proposta"
      (is (= :institucional-nunca-ato
             (razao-do-erro #(catalogo/executar ato {:registrar-chamada reg :propor propor}
                                                (assoc secretaria :via (via #{:ato} :institucional? true)) {:n 1})))))
    (testing "a pessoa, pela tela (sem :via), executa o ato"
      (is (= {:n 7} (catalogo/executar ato {} secretaria {:n 7}))))))

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
        (catalogo/executar (catalogo/entrada eco) {:registrar-chamada reg2} ator {:n 1})
        (is (empty? @b))))
    (testing "sem o seam de audit, a escrita por agente nao roda"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sem registro de audit"
                            (catalogo/executar rascunho {} ator {:n 1}))))))

;; ---------- ADR-0012 / Eixo 4.5: leitura de conteudo de TERCEIRO contamina a execucao ----------

(def ^:private pedido-esic
  (assoc eco :terceiro (fn [{:keys [n]}] (when (odd? n) [{:origem "e-SIC" :referencia (str "nº " n "/2026")}]))))

(deftest leitura-de-terceiro-marca-a-execucao
  (let [e (catalogo/entrada pedido-esic)
        marcas (atom [])
        marcar (fn [ator e ms] (swap! marcas conj [(get-in ator [:via :execucao-id]) (:nome e) ms]))
        ator (assoc secretaria :via (via #{:leitura}))]
    (catalogo/executar e {:marcar-terceiro marcar} ator {:n 3})
    (catalogo/executar e {:marcar-terceiro marcar} ator {:n 4})
    (is (= [[(get-in ator [:via :execucao-id]) "eco_de_teste" [{:origem "e-SIC" :referencia "nº 3/2026"}]]] @marcas)
        "so' o que de fato trouxe conteudo de terceiro marca")
    (is (= "terceiro" (catalogo/origem e {:n 3})))
    (is (= "interno" (catalogo/origem e {:n 4})))
    (is (= "interno" (catalogo/origem (catalogo/entrada eco) {:n 3})))
    (testing "a tela nao marca execucao nenhuma"
      (catalogo/executar e {:marcar-terceiro marcar} secretaria {:n 5})
      (is (= 1 (count @marcas))))
    (testing "conteudo de terceiro sem onde marcar nao vai ao agente (fail-closed)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"terceiro" (catalogo/executar e {} ator {:n 3}))))))
