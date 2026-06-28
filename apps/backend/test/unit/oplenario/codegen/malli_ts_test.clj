(ns oplenario.codegen.malli-ts-test
  "Unit: o codegen Malli->TS (1o corte, FE0). Verifica o emissor contra os models reais — a fronteira
  core->TS (Inv.5) e' derivada do schema, nunca escrita a mao; este teste e' o gate dessa derivacao."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.cadastros.models.cadastro :as cad]
            [oplenario.codegen.gerar :as gerar]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.identidade.models.identidade :as idn]))

(deftest interface-simples-uuid-e-string
  (is (= "export interface Identidade {\n  id: string;\n  cpf: string;\n  nome: string;\n}\n"
         (ts/interface-ts "Identidade" idn/Identidade))
      "uuid e [:re ...] viram string; chaves simples"))

(deftest enum-vira-uniao-de-literais-ordenada
  (let [out (ts/interface-ts "Vinculo" idn/Vinculo)]
    (is (str/includes? out "tipo: \"admin_ente\" | \"cidadao\" | \"servidor\" | \"vereador\";") "enum tipo (ordenado)")
    (is (str/includes? out "estado: \"ativo\" | \"encerrado\" | \"suspenso\";") "enum estado (ordenado)")))

(deftest data-vira-string-e-optional-maybe
  (let [out (ts/interface-ts "Mandato" cad/Mandato)]
    (is (str/includes? out "vigenciaInicio: string;") "LocalDate -> string + chave camelCase")
    (is (str/includes? out "vigenciaFim?: string | null;") "optional + maybe -> campo? : T | null")
    (is (str/includes? out "partido?: string | null;") "string opcional anulavel")
    (is (str/includes? out "estado: \"cassado\" | \"concluido\" | \"falecido\" | \"licenciado\" | \"renunciado\" | \"vigente\";")
        "enum de estado do mandato")))

(deftest gerar-tudo-emite-todas-as-interfaces-com-banner
  (let [out (gerar/gerar-tudo)]
    (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["Ente" "Legislatura" "Vereador" "Mandato" "Comissao" "ComissaoMembro"])
        "todas as interfaces do manifesto (cadastros) presentes")
    ;; identidade NAO entra no manifesto — o model Identidade carrega :cpf; exporta-lo vazaria PII no
    ;; contrato do front (so via wire/out sem-CPF, carry FE0). Guarda anti-regressao.
    (is (not (str/includes? out "export interface Identidade {")) "Identidade (com CPF) NAO vaza nos tipos TS")
    (is (not (str/includes? out "cpf")) "nenhum campo cpf no contrato do front")
    (is (not (str/includes? out "unknown")) "nenhum tipo caiu em 'unknown' (cobertura do 1o corte basta p/ os models)")))
