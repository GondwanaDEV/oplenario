(ns oplenario.codegen.gerar-portal
  "Entrypoint do codegen Malli->TS do PORTAL PUBLICO (FE Onda A2, Fatia A2.0, Task 0.2). Espelha
  oplenario.codegen.gerar (mesmo racional/ferramenta, manifesto proprio) — schemas-fonte sao os wire/out
  das rotas PUBLICAS sem-auth (§16.5/6.1/5.10): transparencia (materia/norma) + participacao (encarregado/
  acompanhamento e-SIC/acompanhamento ouvidoria). Roda via:
    clojure -M -m oplenario.codegen.gerar-portal [caminho-de-saida]
  Default = target/generated-ts/contrato-portal.gen.ts."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.participacao.wire.out.acompanhamento :as ac-esic]
            [oplenario.participacao.wire.out.acompanhamento-ouvidoria :as ac-ouv]
            [oplenario.participacao.wire.out.encarregado :as encarregado]
            [oplenario.transparencia.wire.out.materia :as materia]
            [oplenario.transparencia.wire.out.norma :as norma]))

(def manifesto
  "NormaOut ANTES de MateriaOut/FichaOut (referencia nomeada: FichaOut aninha NormaOut em :norma —
  igualdade estrutural exige a entrada ja presente no mapa nome-por-schema, mesmo racional do manifesto
  da Mesa). Os nomes das interfaces TS sao os do PLANO (Task 0.2), independentes do nome Clojure do def
  (ex.: EncarregadoPublicoOut -> 'EncarregadoOut'; AcompanhamentoOut -> 'AcompanhamentoEsicOut' — dois
  schemas de nome 'AcompanhamentoOut' existem em modulos distintos, o alias evita colisao no TS)."
  [["NormaOut" norma/NormaOut]
   ["MateriaOut" materia/MateriaOut]
   ["FichaOut" materia/FichaOut]
   ["EncarregadoOut" encarregado/EncarregadoPublicoOut]
   ["AcompanhamentoEsicOut" ac-esic/AcompanhamentoOut]
   ["AcompanhamentoOuvidoriaOut" ac-ouv/AcompanhamentoOuvidoriaOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-portal.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS do portal gerados em" caminho "(" (count manifesto) "interfaces)")))
