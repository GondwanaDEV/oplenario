(ns oplenario.legislativo.juridico-adapters-test
  "Os gates de borda do caminho da comissao e do parecer juridico (ADR-0019 fatia 1): `adapters/in` (json -> dominio,
  fail-closed) e `adapters/out` (dominio -> wire, contrato fechado, sem id de pessoa)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.adapters.in.juridico :as in]
            [oplenario.legislativo.adapters.out.juridico :as out])
  (:import (java.time Instant LocalDate)))

(defn- invalido? [f & args]
  (try (apply f args) false
       (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

(deftest pedido->dominio
  (let [pid (random-uuid)]
    (is (= {:assunto "Análise jurídica da matéria" :proposicao-id pid}
           (in/pedido->dominio {"proposicao-id" (str pid)})) "com materia, o assunto tem padrao")
    (is (= {:assunto "Prazo da leitura" :prazo (LocalDate/parse "2026-10-15") :em-nome-de "Presidência"}
           (in/pedido->dominio {"assunto" "  Prazo da leitura " "prazo" "2026-10-15" "em-nome-de" " Presidência "})))
    (testing "fail-closed"
      (is (invalido? in/pedido->dominio {}) "consulta avulsa sem assunto")
      (is (invalido? in/pedido->dominio {"assunto" "abc"}) "assunto curto")
      (is (invalido? in/pedido->dominio {"assunto" (apply str (repeat 301 "a"))}))
      (is (invalido? in/pedido->dominio {"proposicao-id" "nao-uuid"}))
      (is (invalido? in/pedido->dominio {"assunto" "Consulta valida" "prazo" "15/10/2026"}))
      (is (invalido? in/pedido->dominio {"assunto" "Consulta valida" "prazo" 20261015}))
      (is (invalido? in/pedido->dominio {"assunto" "Consulta valida" "em-nome-de" (apply str (repeat 81 "a"))}))
      (is (invalido? in/pedido->dominio "texto")))
    (testing "o corpo nao decide quem pede nem a origem"
      (is (not (contains? (in/pedido->dominio {"assunto" "Consulta valida" "origem" "relator" "pedido-por" "x"})
                          :origem))))))

(deftest texto->dominio
  (is (= {:relatorio "R" :fundamentacao "" :conclusao nil} (in/texto->dominio {"relatorio" "R"}))
      "o rascunho aceita texto incompleto")
  (is (= "contrario" (:conclusao (in/texto->dominio {"relatorio" "R" "fundamentacao" "F" "conclusao" "contrario"}))))
  (is (invalido? in/texto->dominio {"conclusao" "talvez"}))
  (is (invalido? in/texto->dominio {"relatorio" (apply str (repeat 30001 "a"))}))
  (is (invalido? in/texto->dominio {"fundamentacao" (apply str (repeat 60001 "a"))})))

(deftest estado-da-fila
  (is (= "pendente" (in/estado-da-fila {})))
  (is (= "atendido" (in/estado-da-fila {:estado "atendido"})))
  (is (nil? (in/estado-da-fila {:estado "todos"})))
  (is (invalido? in/estado-da-fila {:estado "xyz"})))

(deftest comissoes->dominio
  (let [a (random-uuid) b (random-uuid) r (random-uuid)]
    (is (= [{:comissao-id a :relator-id r} {:comissao-id b}]
           (in/comissoes->dominio {"comissoes" [{"comissao-id" (str a) "relator-id" (str r)} {"comissao-id" (str b)}]})))
    (is (invalido? in/comissoes->dominio {"comissoes" []}))
    (is (invalido? in/comissoes->dominio {}))
    (is (invalido? in/comissoes->dominio {"comissoes" (vec (repeat 11 {"comissao-id" (str (random-uuid))}))}) "mais de 10")
    (is (invalido? in/comissoes->dominio {"comissoes" [{"comissao-id" (str a)} {"comissao-id" (str a)}]}) "repetida")
    (is (invalido? in/comissoes->dominio {"comissoes" [{"comissao-id" "x"}]}))
    (is (invalido? in/comissoes->dominio {"comissoes" [{}]}))
    (is (invalido? in/comissoes->dominio {"comissoes" ["x"]}))))

(deftest relator->dominio
  (let [r (random-uuid)]
    (is (= r (in/relator->dominio {"relator-id" (str r)})))
    (is (invalido? in/relator->dominio {}))
    (is (invalido? in/relator->dominio {"relator-id" "x"}))))

(def ^:private assinado
  {:id (random-uuid) :pedido-id (random-uuid) :estado "assinado" :numero 3 :ano 2026 :relatorio "R" :fundamentacao "F"
   :conclusao "favoravel" :assinado-em (Instant/parse "2026-09-30T12:00:00Z") :assinatura-nome "Paulo" :assinatura-oab "CE 1"
   :assinatura-qualificacao "efetivo" :substituido false :substitui-id nil})

(deftest saida-do-pedido
  (let [pedido {:id (random-uuid) :proposicao-id (random-uuid) :assunto "A" :prazo (LocalDate/parse "2026-10-01")
                :estado "atendido" :pedido-por (random-uuid) :pedido-por-nome "Marina" :em-nome-de "Presidência"
                :origem "secretaria" :criado-em (Instant/parse "2026-09-30T10:00:00Z") :materia-tipo "projeto_lei"
                :materia-sequencial 7 :materia-ano 2026 :materia-ementa "Ementa" :parecer assinado}
        w (out/pedido->wire pedido)]
    (is (= "PL 007/2026" (get-in w [:proposicao :ref])))
    (is (= "2026-10-01" (:prazo w)))
    (is (= "Marina" (:pedido-por w)) "o nome, nunca o id de quem pediu")
    (is (= {:nome "Paulo" :oab "CE 1" :qualificacao "efetivo" :em "2026-09-30T12:00:00Z"}
           (get-in w [:parecer :assinatura])))
    (testing "consulta avulsa: sem materia"
      (is (nil? (:proposicao (out/pedido->wire (assoc pedido :proposicao-id nil))))))
    (testing "a fila nao leva o texto"
      (is (not (contains? (get-in (out/pedidos->wire [(update pedido :parecer dissoc :relatorio :fundamentacao)])
                                  [:pedidos 0 :parecer])
                          :relatorio))))
    (testing "rascunho nao tem assinatura nem numero"
      (let [r (out/pedido->wire (assoc pedido :estado "pendente"
                                              :parecer (assoc assinado :estado "rascunho" :numero nil :ano nil)))]
        (is (nil? (get-in r [:parecer :assinatura])))
        (is (nil? (get-in r [:parecer :numero])))))))

(deftest saida-da-ficha-e-do-portal
  (let [d (out/da-materia->wire {:pareceres [assinado]
                                 :pedidos-abertos [{:id (random-uuid) :assunto "A" :prazo nil :criado-em (Instant/now)}]})]
    (is (= [3 "F"] ((juxt #(get-in % [:pareceres 0 :numero]) #(get-in % [:pareceres 0 :fundamentacao])) d)))
    (is (string? (get-in d [:pareceres 0 :pedido-id]))))
  (let [p (out/publicos->wire [assinado])]
    (is (= ["favoravel" "Paulo"] ((juxt #(get-in % [:pareceres 0 :conclusao]) #(get-in % [:pareceres 0 :assinatura :nome])) p)))
    (is (not (contains? (get-in p [:pareceres 0]) :pedido-id)) "o portal nao expoe o pedido"))
  (is (= {:pareceres []} (out/publicos->wire []))))

(deftest saida-do-caminho-da-comissao
  (let [id (random-uuid)]
    (is (= [{:id (str id) :nome "CCJ"}] (:comissoes (out/comissoes->wire [{:id id :nome "CCJ" :tipo "permanente"}]))))
    (let [w (out/pareceres-abertos->wire [{:id id :comissao-id id :comissao-nome "CCJ" :relator-id nil :relator-nome nil
                                            :estado "aguardando_designacao" :ja-existia nil}])]
      (is (= [false nil] ((juxt #(get-in % [:pareceres 0 :ja-existia]) #(get-in % [:pareceres 0 :relator-id])) w))))
    (is (= {:id (str id) :relator-id (str id) :relator-nome "Ana"}
           (out/relator->wire {:id id :relator-id id :relator-nome "Ana"})))))

;; ---------------- ADR-0019 fatia 2a: nota tecnica como rascunho + antecipar o portal ----------------

(deftest parametros->dominio-so-aceita-booleano
  (is (= {:publicar-ao-assinar true} (in/parametros->dominio {"publicar-ao-assinar" true})))
  (is (= {:publicar-ao-assinar false} (in/parametros->dominio {"publicar-ao-assinar" false})))
  (testing "fail-closed: texto, numero, nulo, ausente e corpo que nao e' objeto"
    (doseq [ruim [{"publicar-ao-assinar" "false"} {"publicar-ao-assinar" "true"} {"publicar-ao-assinar" 1}
                  {"publicar-ao-assinar" nil} {} "texto" nil]]
      (is (invalido? in/parametros->dominio ruim) (pr-str ruim)))))

(deftest saida-da-origem-do-rascunho-e-do-parametro
  (let [rascunho (assoc assinado :estado "rascunho" :numero nil :ano nil :origem-rascunho "nota_tecnica")
        pedido {:id (random-uuid) :proposicao-id (random-uuid) :assunto "Análise jurídica da matéria" :prazo nil
                :estado "pendente" :pedido-por-nome "Paulo" :em-nome-de nil :origem "nota_tecnica"
                :criado-em (Instant/parse "2026-09-30T10:00:00Z") :materia-tipo "projeto_lei" :materia-sequencial 7
                :materia-ano 2026 :materia-ementa "Ementa" :parecer rascunho}
        w (out/pedido->wire pedido)]
    (is (= ["nota_tecnica" "nota_tecnica"] [(:origem w) (get-in w [:parecer :origem-rascunho])])
        "o pedido e o rascunho dizem de onde vieram")
    (testing "escrito do zero: origem nil"
      (is (nil? (get-in (out/pedido->wire (assoc pedido :origem "secretaria" :parecer (dissoc rascunho :origem-rascunho)))
                        [:parecer :origem-rascunho]))))
    (testing "a ficha carrega a origem do assinado"
      (is (= ["nota_tecnica"]
             (mapv :origem-rascunho (:pareceres (out/da-materia->wire {:pareceres [(assoc assinado :origem-rascunho "nota_tecnica")]
                                                                       :pedidos-abertos []}))))))
    (testing "o portal NAO mostra a origem"
      (is (not-any? #{:origem-rascunho}
                    (keys (first (:pareceres (out/publicos->wire [(assoc assinado :origem-rascunho "nota_tecnica")])))))))
    (testing "origem fora do vocabulario e' bug de servidor (contrato fechado)"
      (is (thrown? clojure.lang.ExceptionInfo (out/pedido->wire (assoc-in pedido [:parecer :origem-rascunho] "ia")))))
    (is (= {:publicar-ao-assinar true} (out/parametros->wire {:publicar-ao-assinar true})))
    (is (= {:publicar-ao-assinar false} (out/parametros->wire {})))))
