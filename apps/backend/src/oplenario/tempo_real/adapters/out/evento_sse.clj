(ns oplenario.tempo-real.adapters.out.evento-sse
  "Gate de SAIDA `mensagem da CanalStore -> frame SSE` (§22.10 adapters/out, ADR-0001) — dividido por DIRECAO
  (sob adapters/out/). Chamado SO pelo diplomat/. Projeta a mensagem de canal {:tipo :dados :seq :ente-id} no
  frame SSE {:name :data :id} e VALIDA a forma logica contra wire/out.EventoSse (drift de campo, ex.: mensagem
  sem :tipo de um produtor novo, = bug de servidor -> 500, nunca frame malformado que envenena o cliente). A
  defesa anti-vazamento de SAIDA mora aqui: o `:ente-id` (defesa-em-profundidade da store) NAO vai no fio. O
  canal publico do portal cidadao (item 4 do checklist em canais.clj) — quando existir — retira aqui os campos
  internos (`:fonte`, `:ator-id`) antes de emitir; o canal plenario (autenticado) ja so carrega dados publicos."
  (:require [jsonista.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.tempo-real.wire.out.evento-sse :as wire]))

(set! *warn-on-reflection* true)

(def ^:private campos-proibidos
  "Campos que NUNCA podem ir no fio do SSE — defesa-em-profundidade (review seg MINOR-3) contra um produtor novo
  que inclua dado sensivel em :dados por engano. O canal plenario so carrega dado publico; um destes presente =
  bug de PRODUTOR -> 500 (nunca emite). A barreira estrutural de longo prazo e' tipar :dados por :tipo (Malli
  :multi).
  NOTA (§22.6, Slice 2): `:voto`/`:vereador-id` NAO entram aqui — sao CONTEXTUAIS (PUBLICOS no voto nominal: o
  placar mostra quem votou o que; SIGILOSOS no secreto). Um blocklist cego por NOME de campo nao distingue os
  dois casos e barraria o placar nominal legitimo. O sigilo do voto secreto mora no gate CIENTE DE CONTEXTO
  (projecao/dados-publicos, que faz whitelist do tick secreto e falha fechada em modalidade inesperada). Aqui
  ficam so campos universalmente nao-publicos."
  #{:cpf :token :senha :hash-senha :hash_senha})

(defn mensagem->frame
  "Mensagem de canal -> frame SSE {:name :data :id}. Barra campo sensivel em :dados (anti-vazamento) e VALIDA
  {:tipo :seq :dados} contra EventoSse; em violacao LANCA (bug de servidor). `:name` = tipo (event:), `:data` =
  dados em JSON (data:), `:id` = seq como string."
  [{:keys [tipo dados] msg-seq :seq}]
  (let [dados (or dados {})]
    (when-let [vazou (not-empty (filterv campos-proibidos (keys dados)))]
      (throw (ex-info "campo sensivel em dados SSE (bug de produtor)" {:campos vazou})))
    (let [evento {:tipo tipo :seq msg-seq :dados dados}]
      (when-not (m/validate wire/EventoSse evento)
        (throw (ex-info "evento SSE viola o contrato EventoSse (bug de servidor)"
                        {:campos (keys (me/humanize (m/explain wire/EventoSse evento)))})))
      {:name tipo
       :data (json/write-value-as-string dados)
       :id   (str msg-seq)})))
