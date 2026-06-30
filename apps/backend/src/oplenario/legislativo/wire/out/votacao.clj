(ns oplenario.legislativo.wire.out.votacao
  "Representacao EXTERNA de SAIDA da votacao ao vivo (§22.10 wire/out, ADR-0001) — os recibos das acoes de
  dirigir a votacao, dos quais o Eixo 8 gera os tipos TS. Tudo serializavel a JSON (uuid -> string). NAO expoe
  `lock-version` nem campos internos — a defesa anti-vazamento mora no adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def AberturaOut
  "Recibo de POST .../votacoes (201): o id da votacao recem-aberta + o estado."
  [:map {:closed true}
   [:id :string]
   [:estado (km/enum-de logic/estados-votacao)]])

(def VotoOut
  "Recibo de POST .../votos (201): o id do voto registrado. NOMINAL e SECRETO compartilham a forma (sem
  identidade no recibo — sigilo §22.6)."
  [:map {:closed true}
   [:id :string]])

(def EncerramentoOut
  "Recibo de POST .../encerramento (200): o snapshot apurado (estado + resultado + totais + base). Totais/base
  ausentes na modalidade 'simbolica' (sem apuracao individual)."
  [:map {:closed true}
   [:id :string]
   [:estado (km/enum-de logic/estados-votacao)]
   [:resultado {:optional true} [:maybe [:enum "aprovada" "rejeitada"]]]
   [:total-sim {:optional true} [:maybe :int]]
   [:total-nao {:optional true} [:maybe :int]]
   [:total-abstencao {:optional true} [:maybe :int]]
   [:base-membros {:optional true} [:maybe :int]]])
