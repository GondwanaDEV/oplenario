(ns oplenario.legislativo.wire.in.proposicao
  "Representacao EXTERNA de ENTRADA da proposicao (§22.10 wire/in, ADR-0001, Onda B Slice 2) — os corpos de
  POST/PATCH. `:closed true` recusa campo extra; tenant/autor NAO vem do corpo (vem do ator resolvido na
  auth); o `id` (PATCH) vem do path. Enums saem de legislativo.logic (fonte unica; espelham os CHECK das
  migrations 20260620000013/20260620000015)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def CriarProposicao
  "Corpo de POST /legislativo/proposicoes. `texto` e' OPCIONAL (corpo integral markdown, inline <=32KB —
  overflow p/ objeto_store fica de carry, spec §6)."
  [:map {:closed true}
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe (km/enum-de logic/autor-tipos)]]
   [:autor-id {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   [:texto {:optional true} [:maybe :string]]])

(def EditarProposicao
  "Corpo de PATCH /legislativo/proposicoes/:id. PATCH parcial: so' os campos presentes mudam.
  `lock-version` e' obrigatorio (CAS). Se `texto` presente, promove uma nova versao (origem 'edicao')."
  [:map {:closed true}
   [:lock-version :int]
   [:ementa {:optional true} [:maybe :string]]
   [:autor-tipo {:optional true} [:maybe (km/enum-de logic/autor-tipos)]]
   [:autor-id {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   [:texto {:optional true} [:maybe :string]]])
