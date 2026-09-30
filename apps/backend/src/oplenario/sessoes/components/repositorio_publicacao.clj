(ns oplenario.sessoes.components.repositorio-publicacao
  "Protocolo da PERSISTENCIA de 'publicar a pauta' (ADR-0019 fatia 3, Eixo 7): a regra da Casa, a leitura da tela de
  publicar, o ATO de publicar e a pauta oficial do portal. Num ns proprio (como o `RepoJuridico` do legislativo): o
  `RepoSessoes` ja' e' grande, e esta fatia e' uma vertical separavel. O `RepoSessoesPg` implementa os dois; o
  controller depende do Component, nunca do db/ direto.")

(defprotocol RepoPublicacaoPauta
  (regra-da-pauta [this ente-id] "A linha da regra da Casa (quem publica, antecedencia), ou nil = o padrao.")
  (definir-regra-da-pauta! [this ente-id m]
    "Grava a regra da Casa {:quem-publica :antecedencia-minima-horas :atualizada-por}. Devolve a linha.")
  (publicacao-da-pauta [this ente-id sessao-id]
    "As leituras da tela de publicar, numa tx: {:regra (linha ou nil) :itens (vivos, em ordem) :versoes (todas)}. A
     sessao e' do chamador (ja' autorizada).")
  (publicar-pauta! [this ente-id m]
    "O ATO: numa tx, trava as publicacoes da sessao, rele a pauta viva e as versoes, confere o que o chamador viu
     (`:proposicoes-conferidas`, o conjunto de materias sobre o qual os avisos foram calculados) e congela a versao
     nova — `publicacao_inicial` na primeira, `republicacao` (com justificativa) nas outras. `:avisos-de` (fn [itens]
     -> avisos) roda sobre os itens DESTA tx. Recusa com `:conflito/publicacao-pauta` e `:motivo` :pauta-vazia |
     :sem-alteracao | :pauta-mudou | :justificativa-obrigatoria. Devolve a versao gravada (hidratada).")
  (pautas-publicas [this ente-id] "O portal: as sessoes publicas com a situacao da pauta oficial (ver db/pauta).")
  (pauta-oficial [this ente-id sessao-id]
    "O portal: {:vigente (a ultima versao publica, com snapshot) :versoes (as publicas, metadados)} numa tx."))
