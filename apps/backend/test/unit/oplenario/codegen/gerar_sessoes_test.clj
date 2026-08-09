(ns oplenario.codegen.gerar-sessoes-test
  "Unit: o manifesto do codegen do modulo SESSOES (fatia 1b-WIRE, primeira emissao — espelha
  gerar-paineis-test/gerar-portal-test)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.codegen.gerar-sessoes :as gerar-sessoes]))

(deftest gerar-tudo-emite-todas-as-interfaces-com-banner
  (let [out (gerar-sessoes/gerar-tudo)]
    (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["SessaoOut" "PautaOut" "SegmentosOut" "PresencaResumoOut"
                 "LinhaChamadaOut" "ChamadaQuorumOut" "ChamadaOut"])
        "as interfaces do manifesto presentes, inclusive as da chamada")
    (is (not (re-find #": unknown;" out)) "nenhum campo caiu no fallback bare 'unknown'")))

(deftest chamada-referencia-linha-e-quorum-por-nome
  ;; LinhaChamadaOut/ChamadaQuorumOut vem ANTES de ChamadaOut no manifesto p/ a igualdade estrutural casar
  ;; nos campos aninhados (mesmo racional de PautaItemOut/PautaOut).
  (let [out (gerar-sessoes/gerar-tudo)]
    (is (str/includes? out "linhas: LinhaChamadaOut[];")
        "referencia nomeada, nao Record<string, unknown> inlinado")
    (is (str/includes? out "quorum: ChamadaQuorumOut;")
        "referencia nomeada, nao Record<string, unknown> inlinado")))

(deftest linha-chamada-tem-os-campos-nulaveis-certos
  (let [out (gerar-sessoes/gerar-tudo)]
    (is (str/includes? out "nomeParlamentar: string | null;"))
    (is (str/includes? out "cargoMesa: string | null;"))
    (is (str/includes? out "desde: string | null;"))
    (is (str/includes? out "justificativa: ") "justificativa e' nulavel (map aninhado sem entrada propria no manifesto)")))
