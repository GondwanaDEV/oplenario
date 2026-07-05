(ns oplenario.codegen.gerar-legislativo
  "Entrypoint do codegen Malli->TS da leitura interna de proposicoes (Onda B Slice 1, Eixo 8). Espelha
  oplenario.codegen.gerar-portal (mesmo racional/ferramenta, manifesto proprio) — schema-fonte e' o
  wire/out da rota GET /legislativo/proposicoes (papel 'secretario'). Roda via:
    clojure -M -m oplenario.codegen.gerar-legislativo [caminho-de-saida]
  Default = target/generated-ts/contrato-legislativo.gen.ts."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.legislativo.wire.out.proposicao :as proposicao]))

(def manifesto
  [["ProposicaoResumoOut" proposicao/ProposicaoResumoOut]
   ["ListaProposicoesOut" proposicao/ListaProposicoesOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-legislativo.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de legislativo gerados em" caminho "(" (count manifesto) "interfaces)")))
