(ns oplenario.gatilho-compliance-test
  "ADR-0021 (fatia 3) — o que e' PURO no gatilho das obrigacoes legais e nas duas regras-dado: o builtin
  `fim_do_mes_seguinte`, o quadrimestre da competencia, as duas regras passando no verificador do save time (com os
  fatos novos tipados no catalogo e costurados nas relacoes dos modulos), quais competencias o gatilho avalia em cada
  dia, o objeto derivado e a composicao nas rotas."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.gatilho-compliance :as g]
            [oplenario.legislativo.relacoes :as rel-legis]
            [oplenario.motor.api :as motor]
            [oplenario.motor.catalogo :as cat]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.motor.runtime :as rt]
            [oplenario.motor.tipos :as t]
            [oplenario.sessoes.logic.audiencia :as logic-aud]
            [oplenario.sessoes.relacoes.audiencia :as rel-aud])
  (:import (java.time LocalDate)))

(defn- d [s] (LocalDate/parse s))

(deftest builtin-fim-do-mes-seguinte
  (let [f #(rt/eval-expr "fim_do_mes_seguinte(c)" {"c" (rt/competencia %1 %2)} (rt/estado) (d "2026-10-03"))]
    (is (= (d "2026-05-31") (f 2026 4)) "1o quadrimestre: ate' o fim de maio")
    (is (= (d "2026-09-30") (f 2026 8)) "2o quadrimestre: ate' o fim de setembro")
    (is (= (d "2027-01-31") (f 2026 12)) "dezembro vira o ano (literal: o mes seguinte e' janeiro)")
    (is (= (d "2028-02-29") (f 2028 1)) "fevereiro bissexto"))
  (is (= t/DATA (:retorno (cat/buscar-assinatura "fim_do_mes_seguinte"))))
  (is (= "builtin" (:categoria (cat/buscar-assinatura "fim_do_mes_seguinte")))))

(deftest quadrimestre-da-competencia
  (is (= "2026-Q1" (logic-aud/referencia-do-quadrimestre {:ano 2026 :mes 4})))
  (is (= "2026-Q2" (logic-aud/referencia-do-quadrimestre {:ano 2026 :mes 8})))
  (is (= "2025-Q3" (logic-aud/referencia-do-quadrimestre {:ano 2025 :mes 12})))
  (doseq [m [1 2 3 5 6 7 9 10 11]]
    (is (nil? (logic-aud/referencia-do-quadrimestre {:ano 2026 :mes m})) (str "o mes " m " nao fecha quadrimestre"))))

(deftest as-duas-regras-passam-no-verificador
  (doseq [[chave {:keys [fonte]}] g/regras]
    (let [r (motor/verificar-fonte fonte)]
      (is (= "VALIDA" (:status r)) (str chave ": " (:erros r)))
      (is (true? (:expressao-ok r)))
      (is (= chave (:chave r)))
      (is (= cat/CATALOGO-VERSAO (:registry-versao-ref r)))))
  (testing "um tipo errado no fato e' recusado ao salvar (o verificador le as assinaturas novas)"
    (is (= "INVALIDA" (:status (motor/verificar-fonte (.replace ^String g/fonte-contas
                                                                "PrestacaoContasId" "ProposicaoId")))))
    (is (= "INVALIDA" (:status (motor/verificar-fonte (.replace ^String g/fonte-metas-fiscais
                                                                "\"metas_fiscais\"" "123")))))))

(deftest os-fatos-novos-costuram-com-o-catalogo
  (is (:ok (rf/verificar-costura rel-legis/relacoes)) "legislativo: contas_julgadas + as duas datas")
  (is (:ok (rf/verificar-costura rel-aud/relacoes)) "sessoes: audiencia_publica_realizada")
  (is (= [t/TEXTO t/COMPETENCIA] (:params (cat/buscar-assinatura "audiencia_publica_realizada"))))
  (is (= [t/PRESTACAO-CONTAS-ID] (:params (cat/buscar-assinatura "contas_julgadas"))))
  (is (= t/DATA (:retorno (cat/buscar-assinatura "prazo_julgamento_contas"))))
  (is (= t/PRESTACAO-CONTAS-ID (cat/resolver-tipo-nome "PrestacaoContasId"))))

(deftest competencias-que-o-gatilho-avalia
  (testing "03/10/2026: o 2o quadrimestre acabou (prazo 30/09) e os recentes; o 2o de 2025 (prazo 30/09/2025) saiu"
    (is (= [{:ano 2025 :mes 12} {:ano 2026 :mes 4} {:ano 2026 :mes 8}]
           (g/competencias-de-metas-fiscais (d "2026-10-03")))))
  (testing "o quadrimestre so' entra depois do ultimo dia dele"
    (is (not-any? #{{:ano 2026 :mes 4}} (g/competencias-de-metas-fiscais (d "2026-04-30"))))
    (is (some #{{:ano 2026 :mes 4}} (g/competencias-de-metas-fiscais (d "2026-05-01")))))
  (testing "o prazo de ha' exatamente 365 dias ainda entra; um dia a mais, nao"
    (is (some #{{:ano 2025 :mes 8}} (g/competencias-de-metas-fiscais (d "2026-09-30"))))
    (is (not-any? #{{:ano 2025 :mes 8}} (g/competencias-de-metas-fiscais (d "2026-10-01"))))))

(deftest objeto-da-competencia-e-derivado
  (let [a (random-uuid) b (random-uuid)]
    (is (= (g/objeto-da-competencia a {:ano 2026 :mes 8}) (g/objeto-da-competencia a {:ano 2026 :mes 8}))
        "o mesmo objeto a cada corrida — a chave de idempotencia da obrigacao")
    (is (not= (g/objeto-da-competencia a {:ano 2026 :mes 8}) (g/objeto-da-competencia a {:ano 2026 :mes 4})))
    (is (not= (g/objeto-da-competencia a {:ano 2026 :mes 8}) (g/objeto-da-competencia b {:ano 2026 :mes 8})))))

(deftest com-gatilho-entra-logo-antes-do-handler
  (let [auth {:name ::auth} papel {:name ::papel} h (fn [_] {:status 200}) i {:name ::gatilho}
        rotas #{["/compliance/painel" :get [auth papel h] :route-name :p]
                ["/contas" :post h :route-name :c]
                ["/outra" :get [auth h] :route-name :o]}
        r (into {} (map (juxt first identity)) (g/com-gatilho rotas {["/compliance/painel" :get] i
                                                                    ["/contas" :post] i}))]
    (is (= ["/compliance/painel" :get [auth papel i h] :route-name :p] (r "/compliance/painel")))
    (is (= ["/contas" :post [i h] :route-name :c] (r "/contas")) "handler solto vira cadeia")
    (is (= ["/outra" :get [auth h] :route-name :o] (r "/outra")) "fora dos casos, intacta")))

(deftest interceptores-nunca-derrubam-a-resposta
  (let [chamadas (atom [])
        ok (fn [e o] (swap! chamadas conj [e o]))
        quebra (fn [_ _] (throw (ex-info "boom" {})))
        ctx {:request {:ator {:ente-id :casa}} :response {:status 201 :body "ok"}}]
    (testing "depois: so' 2xx e so' quando `quando?` aceita"
      (is (= ctx ((:leave (g/interceptor-depois ok {:origem "evento"} (constantly true))) ctx)))
      ((:leave (g/interceptor-depois ok {:origem "evento"} (constantly false))) ctx)
      ((:leave (g/interceptor-depois ok {:origem "evento"} (constantly true))) (assoc-in ctx [:response :status] 409))
      (is (= [[:casa {:origem "evento"}]] @chamadas)))
    (testing "a falha (do gatilho ou de `quando?`) e' engolida: a resposta segue"
      (is (= ctx ((:leave (g/interceptor-depois quebra {} (constantly true))) ctx)))
      (is (= ctx ((:leave (g/interceptor-depois ok {} (fn [_] (throw (ex-info "x" {}))))) ctx)))
      (is (= ctx ((:enter (g/interceptor-antes quebra {})) ctx))))))
