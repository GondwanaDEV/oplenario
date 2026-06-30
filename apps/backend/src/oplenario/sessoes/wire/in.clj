(ns oplenario.sessoes.wire.in
  "Representacao EXTERNA de ENTRADA da sessao (§22.10 wire/in, ADR-0001) — o contrato do corpo de request, em
  tipos JSON (strings). O `adapters/in` valida contra isto e coage p/ o dominio. `:closed true` recusa campos
  extra (defesa de borda); o autor/tenant NAO vem do corpo (vem do `ator` resolvido na auth)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def AgendarSessao
  "Corpo de POST /sessoes. `sessao-legislativa-id` = uuid (string); `agendada-para` = ISO-8601 (string).
  capabilities-override fica fora da V1 da borda (o tipo resolve os defaults; override entra quando pedido)."
  [:map {:closed true}
   [:sessao-legislativa-id :string]
   [:tipo-sessao (km/enum-de logic/tipos-sessao)]
   [:modalidade {:optional true} [:maybe (km/enum-de logic/modalidades-sessao)]]
   [:agendada-para {:optional true} [:maybe :string]]])

(def RegistrarPresenca
  "Corpo de POST /sessoes/:id/presenca (§22.6 eixo C). `vereador-id` = uuid (string); `ocorrido-em` = instante
  de DOMINIO (ISO-8601 string) em que o evento ocorreu — OBRIGATORIO (como `iniciou-em` da gravacao: o instante
  de dominio e' dado, nao conveniencia de servidor; o efetivado_em=now() do db e' o instante de auditoria).
  NAO carrega `fonte` (forcada = manual_secretaria no servidor: registro humano autenticado, integridade de
  proveniencia) nem `sessao-id`/autor/tenant (vem do path/ator). `:closed true` recusa campos extra."
  [:map {:closed true}
   [:vereador-id :string]
   [:tipo (km/enum-de logic/tipos-evento-presenca)]
   [:modalidade (km/enum-de logic/modalidades-presenca)]
   [:ocorrido-em :string]])

(def InscreverOrador
  "Corpo de POST /sessoes/:id/inscricoes (§22.6 eixo F, tribuna camada de intencao). `vereador-id` = uuid
  (string); `origem-inscricao` discrimina o caminho (app/secretaria/pedido/autoria) — dado descritivo da fila,
  validado contra o enum (NAO forcado: sem implicacao de precedencia, diferente da `fonte` de presenca);
  `fase` reusa as fases-pauta (a tribuna e' subordinada a fase); `proposicao-ref-id` opcional (uuid). NAO carrega
  autor/tenant (vem do ator) nem `ordem` (numerada server-side). `:closed true` recusa campos extra."
  [:map {:closed true}
   [:vereador-id :string]
   [:origem-inscricao (km/enum-de logic/origens-inscricao)]
   [:fase (km/enum-de logic/fases-pauta)]
   [:proposicao-ref-id {:optional true} [:maybe :string]]])
