(ns oplenario.legislativo.components.repositorio-situacao
  "ADR-0019 fatia 3: a leitura em LOTE da situacao de parecer das materias, para a tela de publicar a pauta. Protocolo
  proprio (o `RepoLegislativo` esta' no limite do tamanho de metodo da JVM); o `RepoLegislativoPg` o implementa. O
  modulo `sessoes` nao importa este ns: recebe a funcao pronta do host (§22.10), como `resumos-de-proposicoes`.")

(defprotocol RepoSituacaoMateria
  (situacao-de-parecer-das-materias [this ente-id proposicao-ids]
    "{proposicao-id {:pareceres-emitidos n :pareceres-em-andamento n :pedidos-juridicos-pendentes n}} numa tx; materia
     que nao existe nesta Casa nao volta."))
