(ns oplenario.legislativo.wire.in.pos-aprovacao
  "Representacao EXTERNA de ENTRADA do POS-APROVACAO — autografo + tramitacao no Executivo (§22.10 wire/in,
  ADR-0001, Onda B Slice 7, F3.8a). `:closed true` recusa campo extra; tenant/autor/id NUNCA vem do corpo
  (vem do ator resolvido na auth, §22.5); `proposicao-id`/`autografo-id`/`tramitacao-id` vem do PATH, nunca
  do corpo. `lock-version` OBRIGATORIO nas 2 escritas que mutam `tramitacao_executiva` (mesmo contrato de
  wire/in/documento.EditarDocumento/ProtocolarDocumento — CAS real). `resultado`/`veto-tipo` ficam ENUM
  FECHADO aqui (ao contrario de wire/in/documento — o vocabulario de resposta do Executivo/apreciacao do
  veto e' FIXO em legislativo.logic, nao config do tenant como tipo-documento)."
  (:require [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def ^:private razoes-max 4000)

(def GerarAutografo
  "Corpo de POST /legislativo/proposicoes/:id/autografo — 'Gerar autografo e enviar ao Executivo'. Body
  MINIMO: `proposicao-id` vem do path; `ano`/`destinatario-texto`/`texto-versao-id` sao RESOLVIDOS pelo
  controller/diplomat (nunca do cliente, §22.5/§22.10 — mesmo racional de tipo-documento/corpo-template em
  wire/in/documento, resolvidos do MODELO, nao do corpo). `prazo-resposta-em` OPCIONAL (instante ISO-8601):
  o rito exato do prazo de sancao/veto e' `[GAP]` regimental (mesma nota da migration 20260620000022) — se
  o cliente ja souber o prazo legal, manda; senao fica nil (sem contagem regressiva ainda)."
  [:map {:closed true}
   [:prazo-resposta-em {:optional true} [:maybe :string]]])

(def RegistrarRespostaExecutivo
  "Corpo de POST /legislativo/autografos/:id/resposta — 'Registrar retorno' do Executivo. `resultado`
  fechado (logic/estados-resposta-executivo: sancionado|sancao_tacita|vetado). `veto-tipo`/`veto-razoes` so
  fazem sentido p/ 'vetado' (o db/tramitacao-executiva.clj tambem guarda isso — a forma do corpo so' espelha
  o vocabulario, a REGRA de coerencia mora no dominio). `lock-version` OBRIGATORIO (CAS real, mesmo contrato
  de wire/in/documento.ProtocolarDocumento)."
  [:map {:closed true}
   [:lock-version :int]
   [:resultado (enum-de logic/estados-resposta-executivo)]
   [:veto-tipo {:optional true} [:maybe (enum-de logic/tipos-veto)]]
   [:veto-razoes {:optional true} [:maybe [:string {:max razoes-max}]]]])

(def ApreciarVeto
  "Corpo de POST /legislativo/tramitacoes-executivas/:id/apreciacao — carimba a apreciacao do veto pela
  camara (a votacao REAL e' aberta/encerrada via POST /sessoes/:id/votacoes*, ja' existente, §5 doc-mestre
  'nao construir DSLs/subsistemas distintos' — sem rota nova aqui). `resultado` fechado
  (logic/estados-apreciacao-veto: veto_mantido|veto_derrubado). `veto-votacao-id` chega como string e o
  adapters/in coage p/ UUID. `lock-version` OBRIGATORIO (CAS real).

  CORRIGIDO (T2 grupo B, ledger Fase 10): esta docstring afirmava que `veto-votacao-id` era
  forward-ref, `sem FK declarativa no dominio`. O BANCO DESMENTE — ha' FK real `(ente_id, veto_votacao_id) ->
  legislativo.votacoes` (migration 0022). A sonda mandou um UUID que nao existia e levou 500 cru de
  violacao de FK. Hoje o Repo-Component traduz 23503 -> 400; o teste
  `veto-votacao-id-tem-FK-de-verdade-contra-votacoes` ancora o fato para a docstring nao voltar a mentir."
  [:map {:closed true}
   [:lock-version :int]
   [:resultado (enum-de logic/estados-apreciacao-veto)]
   [:veto-votacao-id [:string {:min 1 :max 36}]]])
