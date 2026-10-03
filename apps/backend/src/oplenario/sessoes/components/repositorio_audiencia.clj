(ns oplenario.sessoes.components.repositorio-audiencia
  "Protocolo da PERSISTENCIA da AUDIENCIA PUBLICA (ADR-0021 Parte A): a audiencia 1:1 da sessao e as inscricoes de
  cidadao. Num ns proprio (como `repositorio-publicacao`): o `RepoSessoes` ja' e' grande e esta e' uma vertical
  separavel. O `RepoSessoesPg` implementa; o controller depende do Component, nunca do db/ direto. O AGENDAMENTO da
  audiencia nao e' daqui: `RepoSessoes/agendar-sessao!` grava a linha da audiencia na mesma tx da sessao.")

(defprotocol RepoAudiencia
  (audiencia-da-sessao [this ente-id sessao-id]
    "{:audiencia :inscricoes (na ordem) :ata-publicada} numa tx, ou nil (a sessao nao e' audiencia).")
  (atualizar-audiencia! [this ente-id sessao-id campos updated-by]
    "Tempo de fala / inscricoes abertas / local. Devolve a audiencia, ou nil.")
  (inscrever-cidadao! [this ente-id m]
    "Numa tx: le a sessao (`FOR SHARE`) e TRAVA a audiencia, confere se a inscricao cabe (`logic.audiencia/motivo-recusa-
     inscricao`, com `:pelo-portal?`) e grava (protocolo gapless + ordem max+1). Recusa = `:conflito/audiencia` (409);
     a sessao nao e' audiencia desta Casa = nil.")
  (transicionar-inscricao! [this ente-id m]
    "Numa tx: sessao `FOR SHARE` + audiencia travada + a inscricao (que tem de ser DESTA sessao — senao nil). `m` =
     {:sessao-id :inscricao-id :para :tempo-usado-segundos? :dono?}: `falando` (chamada: sessao aberta e ninguem
     falando), `falou` (encerramento, com o tempo usado), `ausente`, `desistiu` (`:dono?` = a fn que diz se a
     inscricao e' de quem pede; de outro = nil). Recusa = `:conflito/audiencia`.")
  (audiencias-publicas [this ente-id] "O portal: {:proximas :realizadas} (so' transmissao publica).")
  (inscricoes-da-identidade [this ente-id identidade-id] "A area da cidada: as inscricoes dela nesta Casa.")
  (buscar-inscricao-cidada [this ente-id id] "Uma inscricao (para conferir o dono), ou nil."))
