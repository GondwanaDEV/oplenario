(ns oplenario.codegen.gerar-cadastros
  "Entrypoint do codegen Malli->TS da leitura de vereadores (Onda D Slice 3, Eixo 8). Espelha
  oplenario.codegen.gerar-legislativo (mesmo racional/ferramenta, manifesto proprio) — schema-fonte e' o
  wire/out de GET /cadastros/vereadores (lista) e GET /cadastros/vereadores/:id (ficha). Roda via:
    clojure -M -m oplenario.codegen.gerar-cadastros [caminho-de-saida]
  Default = target/generated-ts/contrato-cadastros.gen.ts."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.cadastros.wire.out.legislatura :as legislatura]
            [oplenario.cadastros.wire.out.vereador :as vereador]))

(def manifesto
  [["VereadorLinhaOut" vereador/VereadorLinhaOut]
   ["ComissaoDoVereadorOut" vereador/ComissaoDoVereadorOut]
   ["MandatoVigenteOut" vereador/MandatoVigenteOut]
   ["VereadorFichaOut" vereador/VereadorFichaOut]
   ["ListaVereadoresOut" vereador/ListaVereadoresOut]
   ["LegislaturaVigenteOut" legislatura/LegislaturaVigenteOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-cadastros.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de cadastros gerados em" caminho "(" (count manifesto) "interfaces)")))
