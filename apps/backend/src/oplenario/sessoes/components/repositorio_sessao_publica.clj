(ns oplenario.sessoes.components.repositorio-sessao-publica
  "Protocolo da leitura das SESSOES PUBLICAS para o portal de votacoes (frente 'portal-votacoes-publicas'). Ns proprio
  como `repositorio-audiencia`; o `RepoSessoesPg` o implementa (extend-type). Quem decide que sessao e' publica e'
  `sessoes` (a mesma regra do livro de atas); os outros modulos recebem o resultado pelo host (§22.10).")

(defprotocol RepoSessaoPublica
  (sessoes-publicas [this ente-id]
    "Todas as sessoes publicas e nao secretas da Casa: [{:id :tipo-sessao :numero-sequencial :estado :agendada-para
     :aberta-em :encerrada-em}].")
  (sessao-publica [this ente-id sessao-id]
    "A sessao publica e nao secreta, ou nil (inexistente, de outra Casa, secreta ou fechada ao publico)."))
