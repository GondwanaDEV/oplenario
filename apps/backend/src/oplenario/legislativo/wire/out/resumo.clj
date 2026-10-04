(ns oplenario.legislativo.wire.out.resumo
  "Contrato de SAIDA do resumo cidadao de uma proposicao (Faixa A / A.8) na borda interna (papel 'secretario'). Malli
  fechado; fonte do tipo TS gerado (oplenario.codegen.gerar-legislativo).")

(def RascunhoResumoPonteiroOut
  "O que o core sabe do ultimo rascunho da IA: pronto (id na IA, proveniencia, sinais da Camada de Confianca) ou
  falhou (categoria do §22.3.5). `desatualizado` = o texto da proposicao mudou depois que a IA o leu (a IA ja' redige
  de novo sozinha)."
  [:map {:closed true}
   [:situacao [:enum "pronto" "falhou"]]
   [:rascunho-id [:maybe :string]]
   [:desatualizado :boolean]
   [:modelo-llm-id [:maybe :string]]
   [:prompt-versao [:maybe :string]]
   [:incerteza [:maybe [:enum "normal" "revisar_com_atencao"]]]
   [:n-citacoes [:maybe :int]]
   [:n-citacoes-conferidas [:maybe :int]]
   [:n-paragrafos-sem-fonte [:maybe :int]]
   [:categoria-erro [:maybe :string]]
   [:retentavel [:maybe :boolean]]
   [:ocorrido-em :string]])

(def ResumoVersaoOut
  "Uma versao publicada (metadados). `desatualizado` = ela descreve uma versao do texto que ja' nao e' a vigente."
  [:map {:closed true}
   [:versao :int]
   [:conteudo-sha256 :string]
   [:desatualizado :boolean]
   [:origem-redacao [:enum "gerada_automaticamente" "redigida_pela_casa"]]
   [:rascunho-id [:maybe :string]]
   [:modelo-llm-id [:maybe :string]]
   [:prompt-versao [:maybe :string]]
   [:publicado-por :string]
   [:publicado-em :string]])

(def ResumoAtualOut
  [:map {:closed true}
   [:versao ResumoVersaoOut]
   [:texto :string]])

(def ResumoProposicaoOut
  "GET /legislativo/proposicoes/:id/resumo — o rascunho da IA, a versao publicada e o historico."
  [:map {:closed true}
   [:proposicao-id :string]
   [:rascunho [:maybe RascunhoResumoPonteiroOut]]
   [:atual [:maybe ResumoAtualOut]]
   [:versoes [:sequential ResumoVersaoOut]]])

(def CitacaoResumoOut
  "Uma citacao do rascunho, com o resultado da conferencia objetiva (§22.11.8)."
  [:map {:closed true}
   [:fonte-id :string]
   [:trecho {:optional true} [:maybe :string]]
   [:status [:enum "conferida" "sem_trecho" "trecho_nao_encontrado" "fonte_nao_lida"]]
   [:rotulo {:optional true} [:maybe :string]]])

(def IncertezaResumoOut
  [:map {:closed true}
   [:nivel [:enum "normal" "revisar_com_atencao"]]
   [:motivos [:sequential :string]]])

(def RascunhoResumoOut
  "O rascunho para a revisao, lido da IA sob demanda. `texto` traz as marcas de citacao; `texto-limpo` vai para o
  editor. `execucao-ia` = o id da execucao NA IA (feature 8.4): so' ele permite o 'Reportar erro'; ausente, a tela
  nao oferece."
  [:map {:closed true}
   [:rascunho-id :string]
   [:texto :string]
   [:texto-limpo :string]
   [:incerteza IncertezaResumoOut]
   [:citacoes [:sequential CitacaoResumoOut]]
   [:paragrafos-sem-fonte [:sequential :int]]
   [:modelo-llm-id :string]
   [:prompt-versao :string]
   [:desatualizado :boolean]
   [:execucao-ia {:optional true} [:maybe :string]]])

(def ResumoReciboOut
  [:map {:closed true}
   [:versao :int]
   [:conteudo-sha256 :string]])
