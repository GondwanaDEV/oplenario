(ns oplenario.legislativo.components.repositorio-nota-juridica
  "Protocolo da PERSISTENCIA da fatia 2a da ADR-0019: a nota tecnica da IA como rascunho do parecer juridico (Eixo 5) e o
  parametro por Casa que antecipa o parecer ao portal (Eixo 4). Ns proprio pelo mesmo motivo de `repositorio-juridico`: o
  `RepoLegislativo` esta' no limite do tamanho de metodo da JVM. O `RepoLegislativoPg` implementa este tambem.")

(defprotocol RepoNotaJuridica
  (usar-nota-como-rascunho! [this ente-id nota-id advogado-id]
    "Numa tx: abre/reaproveita o pedido de parecer da materia da nota, cria o rascunho com o texto da nota sem as marcas
     de citacao (origem registrada) e marca a nota como aproveitada. {:pedido} ou {:erro :nao-encontrado |
     :nota-decidida | :ja-ha-rascunho}.")
  (parametros-parecer-juridico [this ente-id] "{:publicar-ao-assinar bool} da Casa (sem linha: false).")
  (salvar-parametros-parecer-juridico! [this ente-id m]
    "Grava `{:publicar-ao-assinar bool :por identidade-id}` da Casa; devolve os parametros."))
