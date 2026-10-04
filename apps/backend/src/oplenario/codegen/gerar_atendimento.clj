(ns oplenario.codegen.gerar-atendimento
  "Entrypoint do codegen Malli->TS do BALCAO interno de atendimento (participacao: 6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD).
  Espelha oplenario.codegen.gerar-cadastros (mesma ferramenta, manifesto proprio) — schema-fonte e'
  `participacao/wire/out/atendimento.clj` inteiro. Roda via:
    clojure -M -m oplenario.codegen.gerar-atendimento [caminho-de-saida]
  Default = target/generated-ts/contrato-atendimento.gen.ts (o front guarda em src/lib/contrato-atendimento.gen.ts)."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.participacao.wire.out.atendimento :as atendimento]))

(def manifesto
  "Folhas ANTES de quem as aninha (referencia nomeada por igualdade estrutural)."
  [["AnexoOut" atendimento/AnexoOut]
   ["ComplementoOut" atendimento/ComplementoOut]
   ["PessoaOut" atendimento/PessoaOut]
   ["RecursoPendenteOut" atendimento/RecursoPendenteOut]
   ["ItemEsicOut" atendimento/ItemEsicOut]
   ["ItemOuvidoriaOut" atendimento/ItemOuvidoriaOut]
   ["ItemLgpdOut" atendimento/ItemLgpdOut]
   ["FilaEsicOut" atendimento/FilaEsicOut]
   ["FilaOuvidoriaOut" atendimento/FilaOuvidoriaOut]
   ["FilaLgpdOut" atendimento/FilaLgpdOut]
   ["EventoOut" atendimento/EventoOut]
   ["RecursoOut" atendimento/RecursoOut]
   ["AcoesEsicOut" atendimento/AcoesEsicOut]
   ["AcoesOuvidoriaOut" atendimento/AcoesOuvidoriaOut]
   ["AcoesLgpdOut" atendimento/AcoesLgpdOut]
   ["DetalheEsicOut" atendimento/DetalheEsicOut]
   ["DetalheOuvidoriaOut" atendimento/DetalheOuvidoriaOut]
   ["DetalheLgpdOut" atendimento/DetalheLgpdOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-atendimento.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS do balcao de atendimento gerados em" caminho "(" (count manifesto) "interfaces)")))
