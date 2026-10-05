(ns oplenario.legislativo.components.repositorio-votacao-publica
  "Protocolo da LEITURA das votacoes encerradas para o portal do cidadao (frente 'portal-votacoes-publicas'). Ns
  proprio porque o `RepoLegislativo` ja' esta' no limite do tamanho de metodo da JVM (mesmo motivo do
  `RepoJuridico`); o `RepoLegislativoPg` o implementa. O modulo `transparencia` nao importa este ns: recebe a funcao
  pronta do host (§22.10).")

(defprotocol RepoVotacaoPublica
  (votacoes-encerradas-das-sessoes [this ente-id sessao-ids limite deslocamento]
    "{:votacoes :total} das votacoes ENCERRADAS das sessoes dadas, a mais recente primeiro, paginadas; `:total` e'
     do mesmo predicado, sem pagina. Quem chama decide QUAIS sessoes sao publicas.")
  (votacao-encerrada [this ente-id votacao-id]
    "A votacao ENCERRADA (com sessao) e, se nominal, seus votos {:vereador-id :voto}; nil = inexistente, aberta,
     anulada ou sem sessao. Quem chama confere que a sessao e' publica."))
