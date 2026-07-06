(ns oplenario.codegen.gerar-legislativo-test
  "Unit: o manifesto do codegen do LEGISLATIVO INTERNO (Onda B, Slices 1+2) — espelha gerar-portal-test,
  mas para oplenario.codegen.gerar-legislativo."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.codegen.gerar-legislativo :as gerar-legislativo]))

(deftest manifesto-inclui-proposicao-detalhe-out
  (is (some #(= "ProposicaoDetalheOut" (first %)) gerar-legislativo/manifesto)))

(deftest gerar-tudo-emite-todas-as-interfaces-legislativo-com-banner
  (let [out (gerar-legislativo/gerar-tudo)]
    (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["ProposicaoResumoOut" "ListaProposicoesOut" "ProposicaoDetalheOut"])
        "todas as interfaces do manifesto do legislativo presentes")
    (is (not (re-find #": unknown;" out))
        "nenhum campo caiu no fallback bare 'unknown'")))

(deftest proposicao-detalhe-out-tem-texto-opcional-e-lock-version
  ;; ProposicaoDetalheOut estende ProposicaoResumoOut com :texto (opcional, pode ser nil) e :lock-version.
  ;; Confirma que o codegen emite `texto?: string | null;` e `lockVersion: number;`.
  (let [out (gerar-legislativo/gerar-tudo)]
    (is (str/includes? out "texto?: string | null;")
        "ProposicaoDetalheOut.texto é opcional e pode ser null")
    (is (str/includes? out "lockVersion: number;")
        "ProposicaoDetalheOut.lockVersion é obrigatório e do tipo number")))
