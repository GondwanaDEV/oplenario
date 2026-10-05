(ns oplenario.legislativo.components.repositorio-votacao-ia
  "Protocolo da LEITURA das votacoes encerradas de uma sessao para o contexto da IA (ata com o resultado das votacoes,
  ADR-0008). Ns proprio porque o `RepoLegislativo` ja' esta' no limite do tamanho de metodo da JVM (mesmo motivo do
  `RepoJuridico`); o `RepoLegislativoPg` o implementa. O modulo `integracao_ia` nao importa este ns: o host entrega a
  funcao pronta (§22.10).")

(defprotocol RepoVotacaoIA
  (votacoes-da-sessao-para-ia [this ente-id sessao-id]
    "As votacoes ENCERRADAS da sessao na forma do contexto da IA — [{:id :objeto :modalidade :quorum-tipo
     :votos-necessarios :base-membros :resultado :total-sim :total-nao :total-abstencao :encerrada-em}], na ordem em
     que encerraram. SO' o resultado e os totais: voto por vereador nao entra (nem na nominal). Quem chama decide o
     sigilo da sessao (secreta nunca chega aqui). Acima do teto, lanca `:integracao-ia/votacoes-demais`."))
