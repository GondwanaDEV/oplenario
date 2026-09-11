(ns oplenario.legislativo.models.proposicao
  "Representacao INTERNA (dominio) da proposicao — Malli (§22.10 models/). STI HIBRIDO (eixo A): tronco
  comum + atributos quentes por tipo (opcionais) + atributos_especificos jsonb (so heterogeneo). O
  vocabulario de especies vem de legislativo.logic/tipos (fonte unica; o CHECK do schema 0013 espelha).
  `estado` e' :string (NAO enum fechado) — a maquina fina de tramitacao e' template-driven por camara
  (F3.3), nao um enum cravado. Datas/carimbos seguem kernel/db-tipos. Um arquivo enquanto cabe."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def Proposicao
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   ;; identidade canonica (eixo H) — imutavel apos protocolo (trigger no banco)
   [:tipo (into [:enum] (sort logic/tipos))]
   [:ano :int]
   [:sequencial :int]                                     ; bigint -> long; :int casa
   [:urn-lex :string]
   ;; tronco comum
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :uuid]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   ;; o RITO sob o qual a materia corre (mig 0076) — o elo que a engine do eixo C sempre pediu como
   ;; argumento e a proposicao nunca teve onde guardar. NULLABLE, ao contrario de Parecer/:template-id:
   ;; existem materias protocoladas ANTES desta coluna, e nao ha' rito conhecido para atribuir
   ;; retroativamente. `nil` significa exatamente "esta materia nao tramita" — o estado de hoje.
   [:template-id {:optional true} [:maybe :uuid]]
   [:lock-version :int]
   [:atualizado-em km/Instante]
   ;; atributos quentes por tipo
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :uuid]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   ;; sidecar heterogeneo (PDL e subtipos) + ponteiro do texto vigente (F3.2)
   [:atributos-especificos {:optional true} [:maybe [:map-of :keyword :any]]]
   [:texto-vigente-versao-id {:optional true} [:maybe :uuid]]])
