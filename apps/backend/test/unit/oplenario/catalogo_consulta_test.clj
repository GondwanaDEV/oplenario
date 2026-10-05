(ns oplenario.catalogo-consulta-test
  "Fatia 4 da Clara (ADR-0024): o publico `:consulta` (juridico, auditor, admin_ente) so' LE, e nada do catalogo le a
  trilha da Casa nem o historico da Clara — o auditor le os dois pela tela, nunca pela Clara (decisao do Daouda,
  05/10/2026): o que a Clara consulta vai ao fornecedor de IA. Teste estrutural, sobre o catalogo inteiro e a tabela de
  rotas do host: ferramenta nova que aponte para essas rotas, ou que as leia por fora delas, reprova aqui."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.catalogo :as catalogo]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(def ^:private papeis-da-consulta #{"juridico" "auditor" "admin_ente"})

(def ^:private caminho-proibido
  "Os caminhos que nenhuma ferramenta pode ser: a trilha da Casa e o historico da Clara."
  #"^/(auditoria|agente/historico|agente/conversas)(/|$)")

(def ^:private caminho-por-rota
  (delay (into {} (keep (fn [r] (let [v (vec r) i (.indexOf v :route-name)]
                                  (when (>= i 0) [(nth v (inc i)) (first v)]))))
               (rotas/montar {:idp (idp-dev/idp-dev) :repo-integracao-ia :lint :repo-auditoria :lint}))))

(deftest o-conjunto-de-consulta-so-le
  (let [consulta (get catalogo/conjuntos :consulta)]
    (is (seq consulta))
    (doseq [nome consulta
            :let [e (get catalogo/por-nome nome)]]
      (is (some? e) (str nome ": nao existe no catalogo"))
      (is (= :leitura (:classe e)) (str nome ": o publico de consulta so' le"))
      (is (some papeis-da-consulta (:papeis e))
          (str nome ": nenhum papel da consulta a alcanca — ou o papel entra na entrada (se a rota da tela ja' o atende),"
               " ou a ferramenta sai do conjunto")))))

(deftest nenhuma-ferramenta-le-a-trilha-nem-o-historico-da-clara
  (testing "o detector enxerga a tabela do host (senao passaria vazio)"
    (is (= "/auditoria" (get @caminho-por-rota :auditoria/trilha)))
    (is (= "/agente/historico" (get @caminho-por-rota :agente/historico)))
    (is (= "/agente/conversas/:conversa-id" (get @caminho-por-rota :agente/conversa))))
  (testing "nenhuma entrada aponta para rota da trilha ou do historico"
    (doseq [e catalogo/entradas
            r (:rotas e)
            :let [caminho (get @caminho-por-rota r)]]
      (is (some? caminho) (str (:nome e) " -> " r ": rota que o host nao monta"))
      (is (not (re-find caminho-proibido (str caminho))) (str (:nome e) " -> " caminho))))
  (testing "nem pelo nome"
    (is (not-any? #(re-find #"auditoria|trilha|historico|conversa" %) (map :nome catalogo/entradas))))
  (testing "nenhum modulo do catalogo le a trilha ou o historico por fora da rota"
    (let [fontes (into ["src/oplenario/catalogo.clj"]
                       (for [d (.listFiles (io/file "src/oplenario"))
                             :let [f (io/file d "diplomat/catalogo.clj")]
                             :when (.exists f)]
                         (.getPath f)))]
      (is (>= (count fontes) 5) (str "varreu o host e os modulos com catalogo: " fontes))
      (doseq [f fontes
              :let [corpo (slurp f)]]
        (is (not (str/includes? corpo "oplenario.auditoria")) f)
        (is (not (re-find #"historico-assistente|conversa-assistente|oplenario\.agente\b" corpo)) f)))))

(deftest o-detector-tem-dentes
  (is (re-find caminho-proibido "/auditoria"))
  (is (re-find caminho-proibido "/auditoria/exportar"))
  (is (re-find caminho-proibido "/agente/historico"))
  (is (re-find caminho-proibido "/agente/conversas/:conversa-id"))
  (is (nil? (re-find caminho-proibido "/agente/perguntas")))
  (is (nil? (re-find caminho-proibido "/auditorias-externas"))))
