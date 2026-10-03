(ns oplenario.legislativo.components.repositorio-contas
  "Protocolo da PERSISTENCIA do julgamento das contas (ADR-0021 Parte B). Ns proprio pelo mesmo motivo do
  `RepoJuridico` (o `RepoLegislativo` esta' no limite do tamanho de metodo da JVM); o `RepoLegislativoPg` implementa os
  tres. A prestacao HIDRATADA traz `:documentos`, `:proposicao` ({:id :tipo :ano :sequencial :estado}, so' governo) e
  `:votacao` ({:id :total-sim :total-nao :total-abstencao :base-membros}, so' julgada por votacao).")

(defprotocol RepoContas
  (registrar-prestacao! [this ente-id m]
    "Registra a prestacao numa tx. Governo: protocola o PDL (`(:pdl m)`, os campos de `protocolar!`) ANTES e grava o
     prazo de julgamento congelado (parametro da Casa ou padrao). Mesmo (tipo, exercicio) ja' registrado ->
     :conflito/prestacao-duplicada. Devolve a prestacao hidratada.")
  (prestacao [this ente-id id] "A prestacao hidratada, ou nil.")
  (prestacao-da-proposicao [this ente-id proposicao-id] "A prestacao hidratada cujo PDL e' a proposicao, ou nil.")
  (prestacoes [this ente-id] "As prestacoes da Casa, hidratadas, as mais recentes primeiro.")
  (atualizar-prestacao! [this ente-id id m por] "PATCH de processo/situacao no TCE -> hidratada, ou nil.")
  (notificar-prestacao! [this ente-id id m]
    "Registra a notificacao {:notificado-em :meio :por} e congela o prazo de defesa. {:prestacao} ou {:erro kw}
     (:nao-encontrada :mesa :ja-notificada :julgada).")
  (anexar-documento-de-contas! [this ente-id prestacao-id doc]
    "Grava a linha do documento (o blob ja' esta' no object storage); `tipo` defesa marca a defesa juntada (a primeira).
     nil = prestacao inexistente.")
  (documento-de-contas [this ente-id prestacao-id doc-id] "O documento da prestacao, ou nil.")
  (parametros-de-contas [this ente-id] "A linha da Casa ou nil (padrao).")
  (salvar-parametros-de-contas! [this ente-id m] "UPSERT -> a linha gravada."))
