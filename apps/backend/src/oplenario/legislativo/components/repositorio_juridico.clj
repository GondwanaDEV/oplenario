(ns oplenario.legislativo.components.repositorio-juridico
  "Protocolo da PERSISTENCIA do caminho da comissao e do parecer juridico da Casa (ADR-0019 fatia 1). Vive num ns
  proprio porque o `RepoLegislativo` ja' esta no limite do tamanho de metodo da JVM (um `defprotocol` maior nao
  compila: 'Method code too large'); o `RepoLegislativoPg` implementa os dois. Mesma regra do outro: o controller
  depende do Component, nunca do db/ direto.")

(defprotocol RepoJuridico
  (abrir-pareceres-de-comissao! [this ente-id m]
    "Encaminha a materia `(:proposicao-id m)` as comissoes de `(:comissoes m)` [{:comissao-id :relator-id}]: um parecer
     por comissao, numa tx (ja' aberto e nao terminal -> devolve o existente, :ja-existia true). nil = materia
     inexistente; lanca :sem-rito-de-parecer se a Casa nao configurou o rito de parecer.")
  (designar-relator-do-parecer! [this ente-id parecer-id relator-id por]
    "Define/troca o relator de um parecer nao terminal -> {:id :relator-id}; nil = inexistente ou terminal.")
  (criar-pedido-juridico! [this ente-id m] "Registra o pedido de parecer juridico; nil = materia inexistente.")
  (pedido-juridico [this ente-id id] "O pedido com o parecer corrente (com texto); nil = inexistente nesta Casa.")
  (pedidos-juridicos [this ente-id estado limite] "A fila, os mais antigos primeiro; parecer sem texto.")
  (cancelar-pedido-juridico! [this ente-id id por] "Cancela um pedido pendente -> pedido; nil = inexistente/nao pendente.")
  (salvar-parecer-juridico! [this ente-id pedido-id autor-id texto] "{:pedido} ou {:erro kw} (ver db/parecer-juridico).")
  (assinar-parecer-juridico! [this ente-id pedido-id assinante] "{:pedido} ou {:erro kw}; grava numero/ano e snapshot.")
  (substituir-parecer-juridico! [this ente-id pedido-id autor-id] "{:pedido} ou {:erro kw}.")
  (pareceres-juridicos-da-materia [this ente-id proposicao-id]
    "Ficha: {:pareceres [assinados, mais novo primeiro] :pedidos-abertos [...]}.")
  (pareceres-juridicos-publicos [this ente-id proposicao-id]
    "Portal: os vigentes, so' se a materia ja' foi deliberada."))
