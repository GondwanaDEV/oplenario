(ns oplenario.legislativo.wire.in.proposicao
  "Representacao EXTERNA de ENTRADA da proposicao (§22.10 wire/in, ADR-0001, Onda B Slice 2) — os corpos de
  POST/PATCH. `:closed true` recusa campo extra; tenant/autor NAO vem do corpo (vem do ator resolvido na
  auth); o `id` (PATCH) vem do path. Enums saem de legislativo.logic (fonte unica; espelham os CHECK das
  migrations 20260620000013/20260620000015)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

;; Limites de tamanho: mesmo rigor dos filtros de GET nesta borda de escrita (o body-cap global de 256KiB
;; nao substitui bound por campo). autor-id/destinatario-id sao UUID em string (36 chars); o adapter ainda
;; os converte e rejeita shape invalido. `texto` nao ganha :max aqui — o limite REAL e' 32KB em BYTES,
;; imposto por logic/decidir-armazenamento no Repo (spec §6), nao em caracteres.
(def ^:private campos-metadados-opcionais
  "Campos de metadados opcionais compartilhados por criar/editar (mesmos limites; evita drift)."
  [[:autor-tipo {:optional true} [:maybe (km/enum-de logic/autor-tipos)]]
   [:autor-id {:optional true} [:maybe [:string {:max 36}]]]
   [:autor-texto {:optional true} [:maybe [:string {:max 300}]]]
   [:objeto-indicacao {:optional true} [:maybe [:string {:max 500}]]]
   [:destinatario-id {:optional true} [:maybe [:string {:max 36}]]]
   [:destinatario-texto {:optional true} [:maybe [:string {:max 300}]]]
   [:tipo-requerimento {:optional true} [:maybe [:string {:max 200}]]]
   [:categoria-mocao {:optional true} [:maybe [:string {:max 200}]]]
   [:texto {:optional true} [:maybe :string]]])

(def CriarProposicao
  "Corpo de POST /legislativo/proposicoes. `texto` e' OPCIONAL (corpo integral markdown, inline <=32KB —
  overflow p/ objeto_store fica de carry, spec §6)."
  (into [:map {:closed true}
         [:tipo (km/enum-de logic/tipos)]
         [:ano [:int {:min 1900 :max 2200}]]
         [:ementa [:string {:max 2000}]]]
        campos-metadados-opcionais))

(def EditarProposicao
  "Corpo de PATCH /legislativo/proposicoes/:id. PATCH parcial: so' os campos presentes mudam.
  `lock-version` e' obrigatorio (CAS). Se `texto` presente, promove uma nova versao (origem 'edicao')."
  (into [:map {:closed true}
         [:lock-version :int]
         [:ementa {:optional true} [:maybe [:string {:max 2000}]]]]
        campos-metadados-opcionais))
