(ns oplenario.auditoria.logic-test
  "ADR-0017 — a logica pura da trilha: o que entra, o selo encadeado e a verificacao que acha a adulteracao, o IP, o
  escopo por papel, o pseudonimo e os filtros."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.auditoria.adapters.in.filtro :as filtro]
            [oplenario.auditoria.adapters.out.trilha :as out]
            [oplenario.auditoria.logic :as logic])
  (:import (java.time Instant)))

(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def maria #uuid "40000000-0000-0000-0000-000000000004")
(def prop "30000000-0000-0000-0000-000000000003")

(defn- req [metodo & {:as extra}]
  (merge {:request-method metodo :headers {} :remote-addr "10.0.0.9"
          :ator {:ente-id ente :identidade-id maria :papeis #{"secretario"} :tipo-vinculo "servidor"}}
         extra))

(deftest o-que-entra-na-trilha
  (testing "escrita permitida, com o recurso do caminho e o resumo que o handler deixou"
    (let [r (logic/registro-da-requisicao (req :post :path-params {:proposicao-id prop})
                                          {:status 201 :auditoria {:rotulo "PL 7/2026" :campos [:ementa]}}
                                          :legislativo/protocolar)]
      (is (= ["legislativo/protocolar" "escrita" "permitido" "pessoa" "proposicao" prop "PL 7/2026" ["ementa"]]
             ((juxt :acao :classe :decisao :ator-tipo :recurso-tipo :recurso-id :rotulo :campos) r)))
      (is (= ["secretario"] (:papeis r)))
      (is (= "10.0.0.9" (:ip r)))))
  (testing "negacao por politica entra — ate' em leitura"
    (is (= ["negacao" "negado"] ((juxt :classe :decisao) (logic/registro-da-requisicao (req :get) {:status 403} :x/ler)))))
  (testing "escrita que falhou entra como falhou"
    (is (= "falhou" (:decisao (logic/registro-da-requisicao (req :post) {:status 400} :x/y)))))
  (testing "leitura comum, anonimo e operador (sem Casa) nao entram"
    (is (nil? (logic/registro-da-requisicao (req :get) {:status 200} :x/ler)))
    (is (nil? (logic/registro-da-requisicao (req :post :ator nil) {:status 201} :x/y)))
    (is (nil? (logic/registro-da-requisicao (req :post :ator {:operador-id maria}) {:status 201} :x/y)))
    (is (nil? (logic/registro-da-requisicao (req :post) {:status 201} nil)) "sem rota (404 do roteador)"))
  (testing "o login entrega o ator pela resposta"
    (let [r (logic/registro-da-requisicao (req :post :ator nil)
                                          {:status 200 :auditoria {:classe "entrada" :rotulo "entrou"
                                                                   :ator {:ente-id ente :identidade-id maria}}}
                                          :identidade/mint-sessao)]
      (is (= ["entrada" "permitido" maria] ((juxt :classe :decisao :identidade-id) r)))))
  (testing "cidadao e agente"
    (is (= "cidadao" (:ator-tipo (logic/registro-da-requisicao (req :post :ator {:ente-id ente :identidade-id maria
                                                                                 :tipo-vinculo "cidadao"})
                                                               {:status 201} :participacao/abrir-esic))))
    (let [r (logic/registro-da-requisicao (req :post :ator {:ente-id ente :identidade-id maria :papeis #{"vereador"}
                                                           :via {:agente :assistente :execucao-id "e1"}})
                                          {:status 201} :legislativo/protocolar)]
      (is (= ["agente" "assistente" "agente"] ((juxt :ator-tipo :via-agente :canal) r)))
      (is (= "e1" (get-in r [:detalhe :execucao]))))))

(deftest a-entrada-tem-tentativa-e-o-desfecho-a-aponta
  ;; ADR-0017, adendo de 05/10/2026: o mint nao tem :ator na requisicao; o ator vem do handler, depois de resolvido
  (let [ator {:ente-id ente :identidade-id maria :papeis #{} :tipo-vinculo "cidadao"}
        t (logic/registro-da-tentativa-de-entrada (req :post :ator nil) ator :identidade/mint-sessao)]
    (is (= ["identidade/mint-sessao" "entrada" "iniciado" nil "cidadao" maria ente]
           ((juxt :acao :classe :decisao :status-http :ator-tipo :identidade-id :ente-id) t)))
    (is (= "10.0.0.9" (:ip t)) "o IP segue a regra de sempre (e anulado depois de 6 meses)")
    (testing "o desfecho da entrada aponta a tentativa em detalhe.tentativa"
      (let [d (logic/registro-da-requisicao (req :post :ator nil)
                                            {:status 200 :auditoria {:classe "entrada" :ator ator :rotulo "entrou"}}
                                            :identidade/mint-sessao 7)]
        (is (= ["entrada" "permitido" 7] ((juxt :classe :decisao (comp :tentativa :detalhe)) d)))))
    (testing "sem Casa ou sem rota nao ha tentativa (nada a quem atribuir)"
      (is (nil? (logic/registro-da-tentativa-de-entrada (req :post :ator nil) (dissoc ator :ente-id) :identidade/mint-sessao)))
      (is (nil? (logic/registro-da-tentativa-de-entrada (req :post :ator nil) ator nil)))
      (is (nil? (logic/registro-da-tentativa-de-entrada (req :post :ator nil) nil :identidade/mint-sessao))))
    (testing "so' a rota de entrada declarada entrega a tentativa (a lista e' fechada)"
      (is (= #{:identidade/mint-sessao} logic/acoes-de-entrada)))))

(defn- corrente [n]
  (reduce (fn [acc i]
            (let [r {:ente-id ente :seq i :id (random-uuid) :ocorrido-em (Instant/parse "2026-09-29T12:00:00Z")
                     :ator-tipo "pessoa" :identidade-id maria :papeis ["secretario"] :acao "x/y" :classe "escrita"
                     :decisao "permitido" :status-http 201 :canal "web" :campos [] :detalhe {:metodo "POST"}}
                  ant (or (:selo (peek acc)) "")]
              (conj acc (assoc r :selo-anterior ant :selo (logic/selo-de ant r)))))
          [] (range 1 (inc n))))

(deftest o-selo-encadeado-acha-a-adulteracao
  (let [c (corrente 5)]
    (is (= {:integra true :total 5 :cabeca (:selo (peek c)) :quebra-em nil} (logic/verificar c)))
    (testing "mudar um campo de um registro quebra a corrente ali"
      (is (= 3 (:quebra-em (logic/verificar (assoc-in c [2 :acao] "outra/coisa"))))))
    (testing "apagar um registro quebra no seguinte (falta o seq 3: o 4 nao encaixa)"
      (is (= 4 (:quebra-em (logic/verificar (into (subvec c 0 2) (subvec c 3)))))))
    (testing "o IP nao entra no selo (e' o unico campo que pode ser anulado)"
      (is (:integra (logic/verificar (mapv #(assoc % :ip "1.2.3.4") c)))))))

(deftest ip-e-canal
  (is (= "200.18.4.9" (logic/ip-de {:headers {"x-forwarded-for" "200.18.4.9, 10.0.0.1"} :remote-addr "10.0.0.1"})))
  (is (nil? (logic/ip-de {:headers {"x-forwarded-for" "<script>"}})))
  (is (= "painel-mesa" (logic/canal-de {:headers {"x-oplenario-canal" "painel-mesa"}} {})))
  (is (= "web" (logic/canal-de {:headers {"x-oplenario-canal" "inventado"}} {})))
  (is (= "189.45.x.x" (out/ip-truncado "189.45.12.7")))
  (is (= "2001:db8::x" (out/ip-truncado "2001:db8:1:2::9"))))

(deftest escopo-por-papel
  (is (= {:tipo :casa} (logic/escopo {:papeis #{"auditor" "secretario"}})))
  (is (= :acessos (:tipo (logic/escopo {:papeis #{"admin_ente"} :identidade-id maria}))))
  (is (= {:tipo :propria :identidade-id maria} (logic/escopo {:papeis #{"vereador"} :identidade-id maria}))))

(deftest pseudonimo-estavel-por-casa
  (is (= (logic/pseudonimo ente maria) (logic/pseudonimo ente maria)))
  (is (not= (logic/pseudonimo ente maria) (logic/pseudonimo (random-uuid) maria)) "outra Casa, outro pseudonimo")
  (is (re-matches #"#[0-9a-f]{12}" (logic/pseudonimo ente maria))))

(deftest filtros-da-tela
  (let [f (filtro/query->filtro {:desde "2026-09-01" :ate "2026-09-29" :ator "cidadao" :classe "negacao"
                                 :objeto "legislativo" :antes-de "40"})]
    (is (= (Instant/parse "2026-09-01T03:00:00Z") (:desde f)) "o dia civil de Fortaleza")
    (is (= (Instant/parse "2026-09-30T03:00:00Z") (:ate f)) "`ate` inclusivo")
    (is (= ["cidadao" "negacao" "legislativo" 40] ((juxt :ator-tipo :classe :objeto :antes-de) f))))
  (doseq [q [{:ator "operador"} {:classe "tudo"} {:objeto "admin-sistema"} {:desde "29/09/2026"} {:antes-de "-1"}
             {:desde "2026-09-10" :ate "2026-09-01"}]]
    (is (= :validacao/invalido (try (filtro/query->filtro q) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))
        (pr-str q))))

(deftest o-csv-tem-cabecalho-e-escapa
  (let [csv (out/->csv [{:seq 1 :ocorrido-em (Instant/parse "2026-09-29T12:00:00Z") :ator-tipo "pessoa"
                         :ator-nome "Maria, a secretária" :papeis ["secretario"] :acao "x/y" :classe "escrita"
                         :decisao "permitido" :campos [] :canal "web" :selo "b" :selo-anterior "a"}])]
    (is (.startsWith ^String csv "﻿seq,quando,quem"))
    (is (.contains ^String csv "\"Maria, a secretária\""))
    (is (.endsWith ^String csv "\r\n"))))
