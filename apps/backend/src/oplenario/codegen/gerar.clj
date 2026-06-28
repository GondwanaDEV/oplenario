(ns oplenario.codegen.gerar
  "Entrypoint do codegen Malli->TS (FE0). Host-level: declara o MANIFESTO de models a exportar (lendo
  os models/ dos modulos — permitido p/ ferramenta de build) e escreve o .ts. Roda via:
    clojure -M -m oplenario.codegen.gerar [caminho-de-saida]
  Default = target/generated-ts/oplenario-tipos.ts (FE0 repontara p/ o pacote do front)."
  (:require [clojure.java.io :as io]
            [oplenario.cadastros.models.cadastro :as cad]
            [oplenario.codegen.malli-ts :as ts]))
;; NOTA (ADR-0001/§22.10): a fonte CORRETA dos tipos TS e' o `wire/out` (externo, ja filtrado), nao
;; models/ internos. Provisorio (sem wire/out ainda): so cadastros (sem PII). identidade FORA — o model
;; Identidade carrega :cpf; exporta-lo vazaria PII no contrato do front. Entra via wire/out sem-CPF (FE0).

(def manifesto
  "Os models internos exportados como tipos do front (1o corte). Ordem deterministica."
  [["Ente" cad/Ente]
   ["Legislatura" cad/Legislatura]
   ["Vereador" cad/Vereador]
   ["Mandato" cad/Mandato]
   ["Comissao" cad/Comissao]
   ["ComissaoMembro" cad/ComissaoMembro]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/oplenario-tipos.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS gerados em" caminho "(" (count manifesto) "interfaces)")))
