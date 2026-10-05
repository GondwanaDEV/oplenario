(ns oplenario.legislativo.components.repositorio-meus-votos
  "Protocolo da LEITURA dos votos do proprio vereador (tela 'Minha atuacao'). Ns proprio porque o `RepoLegislativo` ja'
  esta' no limite do tamanho de metodo da JVM (mesmo motivo do `RepoVotacaoPublica`); o `RepoLegislativoPg` o implementa.")

(defprotocol RepoMeusVotos
  (meus-votos [this ente-id vereador-id]
    "{:votos [...] :total n :sim n :nao n :abstencao n} dos votos NOMINAIS do vereador na Casa, lidos de
     `legislativo.votos` (a fonte, nao o read-model publico), a lista no teto do modulo, mais recentes primeiro. Inclui
     o voto de sessao secreta ou fechada ao publico — quem chama garante que `vereador-id` e' o do ATOR. Voto de votacao
     secreta nao existe aqui (sigilo no schema). Cada voto traz `:sessao-id` para o controller marcar o que o portal
     nao publica."))
