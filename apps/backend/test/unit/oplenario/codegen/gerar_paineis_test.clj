(ns oplenario.codegen.gerar-paineis-test
  "Unit: o manifesto do codegen do modulo PAINEIS (Onda E fatia 1) — espelha gerar-portal-test. `paineis`
  ainda nao tinha manifesto proprio (existiam gerar/gerar-legislativo/gerar-cadastros/gerar-portal); os
  tipos da Mesa/board saem por `gerar.clj` (host-level, FE Onda A1) e ficam onde estao."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.codegen.gerar-paineis :as gerar-paineis]))

(deftest gerar-tudo-emite-as-interfaces-da-inbox-com-banner
  (let [out (gerar-paineis/gerar-tudo)]
    (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["NotificacaoOut" "MinhasNotificacoesOut" "MarcarLidaOut"])
        "as 3 interfaces do manifesto presentes")
    (is (not (re-find #": unknown;" out)) "nenhum campo caiu no fallback bare 'unknown'")))

(deftest minhas-notificacoes-referencia-notificacao-por-nome
  ;; NotificacaoOut vem ANTES no manifesto p/ a igualdade estrutural casar no campo aninhado
  ;; (mesmo racional de NormaOut/FichaOut em gerar-portal).
  (let [out (gerar-paineis/gerar-tudo)]
    (is (str/includes? out "notificacoes: NotificacaoOut[];")
        "referencia nomeada, nao Record<string, unknown> inlinado")))

(deftest lida-em-e-nulavel-no-contrato
  (let [out (gerar-paineis/gerar-tudo)]
    (is (str/includes? out "lidaEm: string | null;")
        "null = nao lida — o FE precisa enxergar isso no tipo")))
