(ns oplenario.codegen.gerar-sessoes
  "Entrypoint do codegen Malli->TS do modulo SESSOES (§22.6, primeira emissao — fatia 1b-WIRE da chamada,
  Eixo 8). Espelha oplenario.codegen.gerar-cadastros/gerar-legislativo (mesmo racional/ferramenta,
  manifesto proprio) — schema-fonte e' `sessoes/wire/out.clj` inteiro (SessaoOut ate' ChamadaOut), nao so'
  a chamada: o modulo nunca tinha manifesto proprio ate' aqui. Ordem: tipos referenciados (PautaItemOut,
  SegmentoOut, LinhaChamadaOut, ChamadaQuorumOut) entram ANTES dos compostos que os referenciam
  (PautaOut, SegmentosOut, ChamadaOut), p/ a referencia nomeada casar por igualdade estrutural (mesmo
  racional de ficha-materia/mesa). Roda via:
    clojure -M -m oplenario.codegen.gerar-sessoes [caminho-de-saida]
  Default = target/generated-ts/contrato-sessoes.gen.ts; o repo commita em
  `apps/frontend/src/lib/contrato-sessoes.gen.ts` (passar o caminho na chamada, como os modulos irmaos).

  O manifesto e' COMPLETO por regra, nao por conveniencia: `gerar-sessoes-test/b4-manifesto-cobre-TODO-o-
  wire-out` compara esta lista com `ns-publics` do wire/out e falha se as duas divergirem. O gate existe
  porque a lista envelheceu em silencio uma vez — as Etapas 1 e 2 acrescentaram os contratos de
  justificativa, o lote de presenca e ChamadaConduzidaOut sem toca-la, e o sintoma nao foi um erro, foi
  `chamadasConduzidas: Record<string, unknown>[]` no arquivo gerado: o FE perdendo o tipo justamente do dado
  novo, que e' o que o codegen existe para impedir."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.sessoes.wire.out :as out]))

(def manifesto
  [["SessaoOut" out/SessaoOut]
   ;; GET /sessoes (ledger de prontidao #16) — SessoesOut DEPOIS de SessaoOut (campo :sessoes aninhado),
   ;; mesmo racional de PautaItemOut/PautaOut.
   ["SessoesOut" out/SessoesOut]
   ["TransicaoSessaoOut" out/TransicaoSessaoOut]
   ;; PresencaReciboOut ANTES de PresencaLoteReciboOut (campo :recibos aninhado).
   ["PresencaReciboOut" out/PresencaReciboOut]
   ["PresencaLoteReciboOut" out/PresencaLoteReciboOut]
   ;; §22.6 eixo C — justificativa de ausencia (Etapa 2). LinhaJustificativaOut ANTES de JustificativasOut.
   ["JustificativaAbertaOut" out/JustificativaAbertaOut]
   ["LinhaJustificativaOut" out/LinhaJustificativaOut]
   ["JustificativasOut" out/JustificativasOut]
   ["JustificativaDecididaOut" out/JustificativaDecididaOut]
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
   ["ChamadaConduzidaOut" out/ChamadaConduzidaOut]
   ["ChamadaOut" out/ChamadaOut]
   ;; Etapa 4a — a leitura MAGRA de quorum; reusa ChamadaQuorumOut por referencia nomeada (um so' shape de
   ;; contagem no TS, espelhando a unica aritmetica no servidor).
   ["QuorumSessaoOut" out/QuorumSessaoOut]
   ;; Tribuna nominal — a COMPOSICAO da sessao. ComposicaoMembroOut ANTES de ComposicaoSessaoOut (campo
   ;; :membros aninhado), mesmo racional de PautaItemOut/PautaOut.
   ["ComposicaoMembroOut" out/ComposicaoMembroOut]
   ["ComposicaoSessaoOut" out/ComposicaoSessaoOut]
   ;; Tribuna nominal — o ORADOR e a FILA (GET /sessoes/:id/tribuna, ledger de prontidao #7).
   ;; OradorAtualOut/MarcoCronometroOut/InscritoTribunaOut ANTES de TribunaOut (campos aninhados).
   ["OradorAtualOut" out/OradorAtualOut]
   ["MarcoCronometroOut" out/MarcoCronometroOut]
   ["InscritoTribunaOut" out/InscritoTribunaOut]
   ["TribunaOut" out/TribunaOut]
   ;; Etapa 5 fatia 5 — a FOLHA DA SESSAO. FolhaMetadadosOut ANTES de FolhasDaSessaoOut (campo :folhas
   ;; aninhado), mesmo racional de PautaItemOut/PautaOut e SegmentoOut/SegmentosOut acima.
   ["FolhaMetadadosOut" out/FolhaMetadadosOut]
   ["FolhasDaSessaoOut" out/FolhasDaSessaoOut]
   ;; Etapa 6 fatia 3 — a APURACAO DE ASSIDUIDADE. As quatro folhas ANTES de AssiduidadeOut (campos
   ;; :sessoes/:vereadores/:por-vereador/:detalhe/:totais aninhados), mesmo racional de PautaItemOut/PautaOut.
   ["AssiduidadeSessaoOut" out/AssiduidadeSessaoOut]
   ["AssiduidadeVereadorOut" out/AssiduidadeVereadorOut]
   ["AssiduidadePorVereadorOut" out/AssiduidadePorVereadorOut]
   ["AssiduidadeDetalheLinhaOut" out/AssiduidadeDetalheLinhaOut]
   ["AssiduidadeTotaisOut" out/AssiduidadeTotaisOut]
   ["AssiduidadeOut" out/AssiduidadeOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-sessoes.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de sessoes gerados em" caminho "(" (count manifesto) "interfaces)")))
