(ns oplenario.legislativo.wire.in.votacao
  "Representacao EXTERNA de ENTRADA da votacao ao vivo (§22.10 wire/in, ADR-0001) — o contrato dos corpos de
  request (tipos JSON: strings). O `adapters/in` valida contra isto e coage p/ o dominio. `:closed true` recusa
  campos extra (defesa de borda); o tenant/autor NAO vem do corpo (vem do `ator` resolvido na auth), o sessao-id
  vem da URL e o votacao-id vem do path. Os enums saem de legislativo.logic (fonte unica; espelham o CHECK 0021)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def AbrirVotacao
  "Corpo de POST /sessoes/:id/votacoes. O objeto e' POLIMORFICO (objeto-tipo,objeto-id sobre a materia); o
  pauta-item-id e' contexto temporal opcional. O sessao-id vem do path (:id), nao do corpo."
  [:map {:closed true}
   [:objeto-tipo (km/enum-de logic/objetos-votacao)]
   [:objeto-id :string]
   [:modalidade (km/enum-de logic/modalidades-votacao)]
   [:quorum-tipo (km/enum-de logic/quoruns)]
   [:pauta-item-id {:optional true} [:maybe :string]]])

(def RegistrarVoto
  "Corpo de POST /sessoes/:id/votacoes/:votacao-id/votos. `voto` sempre presente; `vereador-id` so faz sentido na
  modalidade NOMINAL — na SECRETA o controller o descarta (sigilo §22.6). A modalidade nao vem do corpo: e' a da
  votacao carregada (o controller dispatcha)."
  [:map {:closed true}
   [:voto (km/enum-de logic/tipos-voto)]
   [:vereador-id {:optional true} [:maybe :string]]])

(def MeuVoto
  "Corpo de POST /sessoes/:id/votacoes/:votacao-id/meu-voto (Onda C3). SO' `voto` — `vereador-id` NAO existe
  neste contrato (nem opcional): e' resolvido do ator no controller, anti-forja por construcao — a mesma
  disciplina estrutural do sigilo em `votos_secretos` (§22.6), so' que aqui o campo simplesmente nao existe
  na FORMA do contrato, em vez de ser descartado depois de chegar."
  [:map {:closed true}
   [:voto (km/enum-de logic/tipos-voto)]])

(def EncerrarVotacao
  "Corpo de POST /sessoes/:id/votacoes/:votacao-id/encerramento. `lock-version` p/ o CAS; `resultado` so na
  modalidade 'simbolica' (aclamacao sem apuracao individual). `base-membros` (denominador do quorum p/ as
  maiorias absoluta/qualificada) NAO existe mais neste contrato (sec MEDIUM-1): e' resolvido SERVER-SIDE da
  composicao real da Casa no controller. Um valor forjado no corpo e' DESCARTADO na borda (`so-esperados` do
  adapters/in nao o copia, mesma disciplina das rotas irmas) e, ainda que passasse, o controller o
  SOBRESCREVE — dupla defesa, anti-forja por construcao (mesmo espirito do `vereador-id` que nao existe em MeuVoto)."
  [:map {:closed true}
   [:lock-version :int]
   [:resultado {:optional true} [:maybe [:enum "aprovada" "rejeitada"]]]])
