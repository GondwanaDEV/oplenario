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
            [oplenario.transparencia.wire.out.norma :as norma]
            [oplenario.transparencia.wire.out.parlamentar :as parlamentar]))

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
   ["AcompanhamentoOuvidoriaOut" ac-ouv/AcompanhamentoOuvidoriaOut]
   ;; Onda E fatia 2 / carry I-5 fatia 6 — perfil PUBLICO do vereador. Os 4 tipos aninhados vem ANTES de
   ;; PerfilVereadorOut pelo mesmo racional de NormaOut/FichaOut (referencia nomeada por igualdade
   ;; estrutural). ENTRARAM AGORA, e nao na Task 4: o wire deste perfil nunca esteve no manifesto, entao o
   ;; criterio "codegen re-emitido" das fatias anteriores era vacuo — o .ts do portal nao tinha o perfil.
   ;; A tela (Task 5) desbloqueou com esta fatia e precisa dos DOIS campos novos
   ;; (janelaDeExercicioConhecida, presencaProjetadaDesde) tipados, nao adivinhados.
   ["LegislaturaOut" parlamentar/LegislaturaOut]
   ["MateriaDeAutoriaOut" parlamentar/MateriaDeAutoriaOut]
   ["VotoPublicoOut" parlamentar/VotoPublicoOut]
   ["PresencaOut" parlamentar/PresencaOut]
   ["PerfilVereadorOut" parlamentar/PerfilVereadorOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-portal.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS do portal gerados em" caminho "(" (count manifesto) "interfaces)")))
