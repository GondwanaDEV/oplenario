(ns oplenario.codegen.gerar-sessoes
  "Entrypoint do codegen Malli->TS do modulo SESSOES (§22.6, primeira emissao — fatia 1b-WIRE da chamada,
  Eixo 8). Espelha oplenario.codegen.gerar-cadastros/gerar-legislativo (mesmo racional/ferramenta,
  manifesto proprio) — schema-fonte e' `sessoes/wire/out.clj` inteiro (SessaoOut ate' ChamadaOut), nao so'
  a chamada: o modulo nunca tinha manifesto proprio ate' aqui. Ordem: tipos referenciados (PautaItemOut,
  SegmentoOut, LinhaChamadaOut, ChamadaQuorumOut) entram ANTES dos compostos que os referenciam
  (PautaOut, SegmentosOut, ChamadaOut), p/ a referencia nomeada casar por igualdade estrutural (mesmo
  racional de ficha-materia/mesa). Roda via:
    clojure -M -m oplenario.codegen.gerar-sessoes [caminho-de-saida]
  Default = target/generated-ts/contrato-sessoes.gen.ts."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.sessoes.wire.out :as out]))

(def manifesto
  [["SessaoOut" out/SessaoOut]
   ["TransicaoSessaoOut" out/TransicaoSessaoOut]
   ["PresencaReciboOut" out/PresencaReciboOut]
   ["InscricaoReciboOut" out/InscricaoReciboOut]
   ["DesistenciaInscricaoOut" out/DesistenciaInscricaoOut]
   ["FalaReciboOut" out/FalaReciboOut]
   ["CronometroEventoReciboOut" out/CronometroEventoReciboOut]
   ["FalaEncerradaOut" out/FalaEncerradaOut]
   ["DecisaoMesaReciboOut" out/DecisaoMesaReciboOut]
   ["IncidenteReciboOut" out/IncidenteReciboOut]
   ;; PautaItemOut ANTES de PautaOut (campo :itens aninhado).
   ["PautaItemOut" out/PautaItemOut]
   ["PautaOut" out/PautaOut]
   ["GravacaoReciboOut" out/GravacaoReciboOut]
   ;; SegmentoOut ANTES de SegmentosOut (campo :segmentos aninhado).
   ["SegmentoOut" out/SegmentoOut]
   ["SegmentosOut" out/SegmentosOut]
   ["VinculoGravacaoOut" out/VinculoGravacaoOut]
   ["PautaItemAdicionadoOut" out/PautaItemAdicionadoOut]
   ["PautaItemReordenadoOut" out/PautaItemReordenadoOut]
   ["PautaItemRemovidoOut" out/PautaItemRemovidoOut]
   ["PresencaResumoOut" out/PresencaResumoOut]
   ;; §22.6 eixo C — a CHAMADA (fatia 1b-WIRE). LinhaChamadaOut/ChamadaQuorumOut ANTES de ChamadaOut
   ;; (campos :linhas/:quorum aninhados).
   ["LinhaChamadaOut" out/LinhaChamadaOut]
   ["ChamadaQuorumOut" out/ChamadaQuorumOut]
   ["ChamadaOut" out/ChamadaOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-sessoes.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de sessoes gerados em" caminho "(" (count manifesto) "interfaces)")))
