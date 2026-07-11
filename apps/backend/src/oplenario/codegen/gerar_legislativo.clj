(ns oplenario.codegen.gerar-legislativo
  "Entrypoint do codegen Malli->TS da leitura interna de proposicoes (Onda B Slice 1, Eixo 8). Espelha
  oplenario.codegen.gerar-portal (mesmo racional/ferramenta, manifesto proprio) — schema-fonte e' o
  wire/out da rota GET /legislativo/proposicoes (papel 'secretario'). Roda via:
    clojure -M -m oplenario.codegen.gerar-legislativo [caminho-de-saida]
  Default = target/generated-ts/contrato-legislativo.gen.ts."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.legislativo.wire.out.autografo :as autografo]
            [oplenario.legislativo.wire.out.documento :as documento]
            [oplenario.legislativo.wire.out.documento-modelo :as documento-modelo]
            [oplenario.legislativo.wire.out.ficha-materia :as ficha]
            [oplenario.legislativo.wire.out.parecer :as parecer]
            [oplenario.legislativo.wire.out.pos-aprovacao :as pos-aprovacao]
            [oplenario.legislativo.wire.out.proposicao :as proposicao]
            [oplenario.legislativo.wire.out.protocolo-geral :as protocolo-geral]
            [oplenario.legislativo.wire.out.tramitacao-executiva :as tramitacao-executiva]))

(def manifesto
  [["ProposicaoResumoOut" proposicao/ProposicaoResumoOut]
   ["ListaProposicoesOut" proposicao/ListaProposicoesOut]
   ["ProposicaoDetalheOut" proposicao/ProposicaoDetalheOut]
   ;; Onda B Slice 3 (ficha-materia) — entram DEPOIS de ProposicaoDetalheOut (referencia nomeada casa por
   ;; igualdade estrutural, mesma disciplina do manifesto de paineis/mesa).
   ["HistoricoTramitacaoItemOut" ficha/HistoricoTramitacaoItemOut]
   ["ApensacaoOut" ficha/ApensacaoOut]
   ["EmendaResumoOut" ficha/EmendaResumoOut]
   ["ParecerResumoOut" ficha/ParecerResumoOut]
   ["FichaMateriaOut" ficha/FichaMateriaOut]
   ;; Onda B Slice 5 (editor de parecer) — schema PROPRIO (nao reusa ParecerResumoOut, que e' o resumo
   ;; dentro da ficha da materia).
   ["ObjetoResumoOut" parecer/ObjetoResumoOut]
   ["ParecerEditorOut" parecer/ParecerEditorOut]
   ;; Onda B Slice 6 (expediente: documentos + protocolo geral) — schemas PROPRIOS.
   ["DocumentoOut" documento/DocumentoOut]
   ["DocumentoModeloOut" documento-modelo/DocumentoModeloOut]
   ["ListaModelosOut" documento-modelo/ListaModelosOut]
   ["ProtocoloGeralOut" protocolo-geral/ProtocoloGeralOut]
   ["LivroProtocoloOut" protocolo-geral/LivroProtocoloOut]
   ;; Onda B Slice 7 (pos-aprovacao: autografo + sancao/veto, F3.8a) — entram ANTES de PosAprovacaoOut (o
   ;; composto referencia os dois primeiros, mesma ordem-referencia-antes-do-composto de ficha-materia).
   ["AutografoOut" autografo/AutografoOut]
   ["TramitacaoExecutivaOut" tramitacao-executiva/TramitacaoExecutivaOut]
   ["PosAprovacaoOut" pos-aprovacao/PosAprovacaoOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-legislativo.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de legislativo gerados em" caminho "(" (count manifesto) "interfaces)")))
