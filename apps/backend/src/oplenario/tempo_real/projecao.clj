(ns oplenario.tempo-real.projecao
  "wire/out do tempo real (§22.6 eixo G): projeta um evento de dominio em mensagens de canal {:canal :tipo
  :dados}. `:dados` = o payload do evento (ja e' a representacao publica do painel — os eventos de sessao nao
  carregam CPF nem voto secreto). Este e' o SEAM onde, quando os canais publico/dashboard chegarem (G3), entra
  a FILTRAGEM de campos sensiveis por canal. O cliente recomputa o cronometro a partir dos marcos."
  (:require [oplenario.tempo-real.canais :as canais]))

(defn projetar
  "Evento de dominio -> vetor de mensagens {:canal :ente-id :tipo :dados}. Uma mensagem por canal roteado
  (vazio p/ eventos nao-SSE). `:tipo` = o tipo do evento (o cliente discrimina o render). `:ente-id` viaja na
  mensagem (defesa-em-profundidade: a store deixa de ser tenant-cega) — MAS NAO e' a barreira de autorizacao:
  o endpoint SSE (G3) DEVE verificar a posse do tenant por consulta ao banco na ABERTURA da conexao (vide o
  checklist de authz em canais.clj). Aqui ainda nao ha filtragem por canal publico/dashboard (seam de G3)."
  [{:keys [tipo payload ente-id] :as evento}]
  (mapv (fn [canal] {:canal canal :ente-id ente-id :tipo tipo :dados payload})
        (canais/rotas-do-evento evento)))
