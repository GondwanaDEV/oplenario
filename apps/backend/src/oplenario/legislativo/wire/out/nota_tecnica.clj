(ns oplenario.legislativo.wire.out.nota-tecnica
  "Contrato de SAIDA da nota tecnica de conferencia (Faixa B / B.8, ADR-0013) na borda interna (papel 'secretario').
  Malli fechado; fonte do tipo TS gerado (oplenario.codegen.gerar-legislativo). Quem decidiu nao sai como id de pessoa.")

(def estados [:enum "pendente" "aproveitada" "descartada"])

(def CitacaoNotaOut
  "Uma citacao do rascunho, com o resultado da conferencia objetiva feita na execucao (§22.11.8)."
  [:map {:closed true}
   [:fonte-id :string]
   [:trecho [:maybe :string]]
   [:status [:enum "conferida" "sem_trecho" "trecho_nao_encontrado" "fonte_nao_lida"]]
   [:rotulo [:maybe :string]]])

(def NotaTecnicaResumoOut
  "Uma linha da fila: a materia conferida e em que pe' esta' a nota."
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:tipo :string]
   [:sequencial :int]
   [:ano :int]
   [:ementa :string]
   [:estado estados]
   [:incerteza [:enum "normal" "revisar_com_atencao"]]
   [:criada-em :string]
   [:decidida-em [:maybe :string]]])

(def NotasTecnicasOut
  "GET /legislativo/notas-tecnicas — a fila da secretaria."
  [:map {:closed true} [:itens [:sequential NotaTecnicaResumoOut]]])

(def NotaTecnicaOut
  "GET /legislativo/notas-tecnicas/:id — o rascunho inteiro para a revisao. `texto` traz as marcas de citacao (a tela
  mostra cada uma ao lado do paragrafo); `texto-limpo` vai para o editor; `texto-final` e' o que a secretaria
  aproveitou."
  [:map {:closed true}
   [:id :string]
   [:proposicao-id :string]
   [:tipo :string]
   [:sequencial :int]
   [:ano :int]
   [:ementa :string]
   [:estado estados]
   [:agente :string]
   [:texto :string]
   [:texto-limpo :string]
   [:citacoes [:sequential CitacaoNotaOut]]
   [:paragrafos-sem-fonte [:sequential :int]]
   [:incerteza [:enum "normal" "revisar_com_atencao"]]
   [:motivos-incerteza [:sequential :string]]
   [:modelo-llm-id :string]
   [:texto-final [:maybe :string]]
   [:criada-em :string]
   [:decidida-em [:maybe :string]]])
