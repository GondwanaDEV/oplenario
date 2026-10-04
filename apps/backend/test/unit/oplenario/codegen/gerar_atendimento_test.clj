(ns oplenario.codegen.gerar-atendimento-test
  "Unit: o manifesto do codegen do BALCAO de atendimento cobre o wire/out inteiro, sem campo caido em `unknown`, e o
  .gen.ts guardado no front e' o que o codegen emite hoje (sem drift)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.codegen.gerar-atendimento :as gerar]))

(deftest emite-todas-as-interfaces-sem-unknown
  (let [out (gerar/gerar-tudo)]
    (is (str/starts-with? out "// GERADO"))
    (doseq [[nome] gerar/manifesto]
      (is (str/includes? out (str "export interface " nome " {")) nome))
    (is (not (re-find #"unknown" out)) "nenhum campo inlinado como Record<string, unknown>")
    (is (str/includes? out "acoes: AcoesEsicOut;"))
    (is (str/includes? out "requerente: PessoaOut | null;"))))

(deftest manifesto-cobre-todo-o-wire-out
  (let [publicos (->> (ns-publics 'oplenario.participacao.wire.out.atendimento) keys (map name) set)]
    (is (= publicos (set (map first gerar/manifesto))))))

(deftest o-arquivo-do-front-esta-em-dia
  (let [f (io/file "../frontend/src/lib/contrato-atendimento.gen.ts")]
    (when (.exists f)
      (is (= (gerar/gerar-tudo) (slurp f))
          "regere: clojure -M -m oplenario.codegen.gerar-atendimento ../frontend/src/lib/contrato-atendimento.gen.ts"))))
