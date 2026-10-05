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
            [oplenario.legislativo.wire.out.meu-painel :as meu-painel]
            [oplenario.legislativo.wire.out.meus-votos :as meus-votos]
            [oplenario.legislativo.wire.out.norma :as norma]
            [oplenario.legislativo.wire.out.parecer :as parecer]
            [oplenario.legislativo.wire.out.pos-aprovacao :as pos-aprovacao]
            [oplenario.legislativo.wire.out.proposicao :as proposicao]
            [oplenario.legislativo.wire.out.protocolo-geral :as protocolo-geral]
            [oplenario.legislativo.wire.out.requerimento :as requerimento]
            [oplenario.legislativo.wire.out.nota-tecnica :as nota-tecnica]
            [oplenario.legislativo.wire.out.resumo :as resumo]
            [oplenario.legislativo.wire.out.tramitacao-executiva :as tramitacao-executiva]))

(def manifesto
  [["ProposicaoResumoOut" proposicao/ProposicaoResumoOut]
   ["ListaProposicoesOut" proposicao/ListaProposicoesOut]
   ["ProposicaoDetalheOut" proposicao/ProposicaoDetalheOut]
   ;; Onda B Slice 3 (ficha-materia) — entram DEPOIS de ProposicaoDetalheOut (referencia nomeada casa por
   ;; igualdade estrutural, mesma disciplina do manifesto de paineis/mesa).
   ;; fatia 2b: o recibo de carga de cada movimentacao — ANTES do item de historico que o referencia.
   ["RecebimentoOut" proposicao/RecebimentoOut]
   ["HistoricoTramitacaoItemOut" ficha/HistoricoTramitacaoItemOut]
   ["ApensacaoOut" ficha/ApensacaoOut]
   ["EmendaResumoOut" ficha/EmendaResumoOut]
   ["ParecerResumoOut" ficha/ParecerResumoOut]
   ["CoautorOut" ficha/CoautorOut]
   ;; faixa "Onde esta' a materia" — ANTES de FichaMateriaOut, que referencia o rito
   ["EtapaDoRitoOut" ficha/EtapaDoRitoOut]
   ["RitoDaMateriaOut" ficha/RitoDaMateriaOut]
   ["FichaMateriaOut" ficha/FichaMateriaOut]
   ;; Onda B Slice 5 (editor de parecer) — schema PROPRIO (nao reusa ParecerResumoOut, que e' o resumo
   ;; dentro da ficha da materia).
   ["ObjetoResumoOut" parecer/ObjetoResumoOut]
   ["ParecerEditorOut" parecer/ParecerEditorOut]
   ;; ADR-0019 fatia 2 (copiloto do relator). A citacao tem a forma de CitacaoCopilotoOut (B.7) e o codegen a nomeia
   ;; assim por igualdade estrutural — uma interface so' para "de onde veio" nos dois copilotos.
   ["AnaliseCopilotoOut" parecer/AnaliseCopilotoOut]
   ["CopilotoAnaliseOut" parecer/CopilotoAnaliseOut]
   ;; Onda B Slice 6 (expediente: documentos + protocolo geral) — schemas PROPRIOS.
   ["DocumentoOut" documento/DocumentoOut]
   ["DocumentoModeloOut" documento-modelo/DocumentoModeloOut]
   ["ListaModelosOut" documento-modelo/ListaModelosOut]
   ;; Fatia de escrita (aba "Modelos", CRUD de template) — GET/POST/PATCH /legislativo/documento-modelos(/:id).
   ["DocumentoModeloDetalheOut" documento-modelo/DocumentoModeloDetalheOut]
   ["ProtocoloGeralOut" protocolo-geral/ProtocoloGeralOut]
   ["LivroProtocoloOut" protocolo-geral/LivroProtocoloOut]
   ;; Onda B Slice 7 (pos-aprovacao: autografo + sancao/veto, F3.8a) — entram ANTES de PosAprovacaoOut (o
   ;; composto referencia os dois primeiros, mesma ordem-referencia-antes-do-composto de ficha-materia).
   ["AutografoOut" autografo/AutografoOut]
   ["TramitacaoExecutivaOut" tramitacao-executiva/TramitacaoExecutivaOut]
   ;; F3.8b — a norma promulgada (corpo de POST .../publicacao e campo `norma` de PosAprovacaoOut)
   ["NormaOut" norma/NormaOut]
   ["PosAprovacaoOut" pos-aprovacao/PosAprovacaoOut]
   ;; Onda C1 (borda /meu do vereador) — schemas PROPRIOS (proposicao/parecer resumos ENXUTOS, distintos
   ;; dos irmaos de ficha-materia/editor — so' os campos que o painel do vereador mostra).
   ["ProposicaoResumoMeuPainelOut" meu-painel/ProposicaoResumoMeuPainelOut]
   ["ParecerResumoMeuPainelOut" meu-painel/ParecerResumoMeuPainelOut]
   ["CienciaPendenteOut" meu-painel/CienciaPendenteOut]
   ["MeuPainelOut" meu-painel/MeuPainelOut]
   ["AcusarCienciaOut" meu-painel/AcusarCienciaOut]
   ;; "Minha atuacao" (GET /meu/votos) — os votos do proprio vereador, inclusive de sessao nao publica
   ["MeuVotoOut" meus-votos/MeuVotoOut]
   ["MeusVotosPorOpcaoOut" meus-votos/MeusVotosPorOpcaoOut]
   ["MeusVotosOut" meus-votos/MeusVotosOut]
   ;; Tela "tramitar a materia" (GET /legislativo/proposicoes/:id/tramitacao) — GatilhoPossivelOut ANTES de
   ;; TramitacaoOut (o composto o referencia por nome, mesma ordem-referencia-antes-do-composto de
   ;; ficha-materia/pos-aprovacao). O `:historico` de TramitacaoOut reusa HistoricoTramitacaoItemOut (ficha,
   ;; ja' acima) por igualdade estrutural. Substitui o tipo-espelho a mao de use-tramitacao.ts.
   ["GatilhoPossivelOut" proposicao/GatilhoPossivelOut]
   ["RecebimentoPendenteOut" proposicao/RecebimentoPendenteOut]
   ["TramitacaoOut" proposicao/TramitacaoOut]
   ;; Fatia 2b (recebimento assinado da tramitacao) — o recibo do POST e a fila de pendentes.
   ["RecebimentoReciboOut" proposicao/RecebimentoReciboOut]
   ["RecebimentoPendenteItemOut" proposicao/RecebimentoPendenteItemOut]
   ["RecebimentosPendentesOut" proposicao/RecebimentosPendentesOut]
   ;; Fatia 2a (o requerimento do vereador, borda /meu) — ModeloRequerimentoOut ANTES da lista que o referencia.
   ["ModeloRequerimentoOut" requerimento/ModeloRequerimentoOut]
   ["ModelosRequerimentoOut" requerimento/ModelosRequerimentoOut]
   ["PreviaRequerimentoOut" requerimento/PreviaRequerimentoOut]
   ["RequerimentoProtocoladoOut" requerimento/RequerimentoProtocoladoOut]
   ;; Fatia 2c (requerimento coletivo) — referencias antes dos compostos.
   ["ColegaOut" requerimento/ColegaOut]
   ["ColegasOut" requerimento/ColegasOut]
   ["SubscricaoOut" requerimento/SubscricaoOut]
   ["PropostaRequerimentoOut" requerimento/PropostaRequerimentoOut]
   ["PropostaResumoOut" requerimento/PropostaResumoOut]
   ["PropostasOut" requerimento/PropostasOut]
   ["ConviteSubscricaoOut" requerimento/ConviteSubscricaoOut]
   ["ConvitesSubscricaoOut" requerimento/ConvitesSubscricaoOut]
   ["RespostaSubscricaoOut" requerimento/RespostaSubscricaoOut]
   ["RequerimentoColetivoProtocoladoOut" requerimento/RequerimentoColetivoProtocoladoOut]
   ;; Faixa B / B.7 (copiloto do requerimento) — referencias antes do composto.
   ["PreenchimentoCopilotoOut" requerimento/PreenchimentoCopilotoOut]
   ["CitacaoCopilotoOut" requerimento/CitacaoCopilotoOut]
   ["JustificativaCopilotoOut" requerimento/JustificativaCopilotoOut]
   ["CopilotoRequerimentoOut" requerimento/CopilotoRequerimentoOut]
   ;; Faixa A / A.8 (resumo cidadao) — referencias antes dos compostos.
   ["RascunhoResumoPonteiroOut" resumo/RascunhoResumoPonteiroOut]
   ["ResumoVersaoOut" resumo/ResumoVersaoOut]
   ["ResumoAtualOut" resumo/ResumoAtualOut]
   ["ResumoProposicaoOut" resumo/ResumoProposicaoOut]
   ["CitacaoResumoOut" resumo/CitacaoResumoOut]
   ["IncertezaResumoOut" resumo/IncertezaResumoOut]
   ["RascunhoResumoOut" resumo/RascunhoResumoOut]
   ["ResumoReciboOut" resumo/ResumoReciboOut]
   ;; Faixa B / B.8 (nota tecnica de conferencia) — referencias antes dos compostos.
   ["CitacaoNotaOut" nota-tecnica/CitacaoNotaOut]
   ["NotaTecnicaResumoOut" nota-tecnica/NotaTecnicaResumoOut]
   ["NotasTecnicasOut" nota-tecnica/NotasTecnicasOut]
   ["NotaTecnicaOut" nota-tecnica/NotaTecnicaOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-legislativo.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de legislativo gerados em" caminho "(" (count manifesto) "interfaces)")))
