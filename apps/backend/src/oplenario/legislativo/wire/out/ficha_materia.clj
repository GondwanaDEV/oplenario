(ns oplenario.legislativo.wire.out.ficha-materia
  "Representacao EXTERNA de SAIDA da ficha da materia (§22.10 wire/out, ADR-0001, Onda B Slice 3) — o
  contrato de GET /legislativo/proposicoes/:id/ficha (leitura interna, servidor). Namespace PROPRIO (nao
  incha wire/out/proposicao) porque agrega dado cross-eixo (tramitacao/apensacao/emenda/parecer). Reusa
  ProposicaoDetalheOut para o cabecalho (nao duplica os campos da proposicao). `estado`/`de-estado`/
  `para-estado`/`voto-relator` ficam :string (nunca enum): a maquina de tramitacao e' TEMPLATE-DRIVEN por
  camara (F3.3), mesmo racional de wire/out/proposicao; `tipo-emenda`/`momento-apresentacao` TAMBEM ficam
  :string aqui (o wire/out nao replica o enum fechado do model — mesma disciplina de nao reprojetar
  vocabulario fechado na borda externa, que fica livre a evoluir sem acoplar o contrato).

  Decisoes de escopo desta fatia (nao relitigar): `contexto` do historico de tramitacao NAO e' exposto
  (payload interno do motor); apensadas = so' NIVEL 1 (apensadas-ativas), nao a cadeia recursiva; sem
  ator-id na timeline (sem resolvedor ator->nome ainda, F2 cross-modulo)."
  (:require [oplenario.legislativo.wire.out.proposicao :as proposicao]))

(def HistoricoTramitacaoItemOut
  "Uma linha do historico de tramitacao — SEM :contexto/:template-id/:ator-id (decisao de escopo: payload
  interno do motor / uuid sem resolvedor de nome ainda)."
  [:map {:closed true}
   [:de-estado :string]
   [:para-estado :string]
   [:gatilho :string]
   [:ocorrido-em :string]
   ;; fatia 2b: o MESMO recibo de carga da rota irma (TramitacaoHistoricoItemOut — os dois mudam juntos)
   [:recebimento [:maybe proposicao/RecebimentoOut]]])

(def ApensacaoOut
  "Uma apensada ATIVA de nivel 1 (nao a cadeia recursiva — decisao de escopo desta fatia)."
  [:map {:closed true}
   [:apensada-id :string]
   [:apensada-em :string]
   [:motivo-apensacao {:optional true} [:maybe :string]]])

(def EmendaResumoOut
  "Uma linha da lista de emendas (resumo — nao o texto integral nem campos internos de armazenamento)."
  [:map {:closed true}
   [:id :string]
   [:numero-local :int]
   [:tipo-emenda :string]
   [:momento-apresentacao :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]])

(def ParecerResumoOut
  "Uma linha da lista de pareceres (resumo — sem template-id/texto-vigente-versao-id/lock-version)."
  [:map {:closed true}
   [:id :string]
   [:comissao-id :string]
   ;; Resolvido pelo HOST (`resolver-comissoes`, §22.5.3) — defeito #11 do ledger de prontidao, a aba
   ;; "Pareceres" mostrava um UUID por linha. nil quando o guard ref nao tem dono nesta Casa.
   [:comissao-nome {:optional true} [:maybe :string]]
   [:relator-id {:optional true} [:maybe :string]]
   [:voto-relator {:optional true} [:maybe :string]]
   [:estado :string]])

(def FichaMateriaOut
  "GET /legislativo/proposicoes/:id/ficha — o envelope agregado (Onda B Slice 3). `:proposicao` reusa
  ProposicaoDetalheOut (o controller ja' gateia nil -> 404 na borda antes de chegar aqui; a wire/out so'
  projeta ficha com proposicao presente). Tetos fixos (100 tramitacao / 50 demais) sao decisao do Repo
  (app-level) e o NUMERO nunca e' exposto aqui (regra 1 da familia 'truncamento-familia') — sem
  'carregar mais' nesta fatia.

  `<lista>-truncado` (booleano, um por lista — fatia 'truncamento-familia'): a rota irma
  GET /proposicoes/:id/tramitacao ja' sinaliza corte com `:historico-truncado` (mesma sonda teto+1); as
  4 listas daqui adotam a MESMA forma, uniformemente (uma lista sinalizando e a vizinha nao seria pior
  que nenhuma sinalizar — o cliente generalizaria a presenca do campo). NAO e' o par `<lista>-total`
  (a outra forma canonica do repo): aqui nao ha' contagem barata pre-existente pra' reusar, e a sonda
  teto+1 (ja embutida nos 4 db/ da ficha) resolve com ZERO query nova — 4 `count(*)` seriam uma 5a
  forma que a familia pede pra' evitar."
  [:map {:closed true}
   [:proposicao proposicao/ProposicaoDetalheOut]
   [:tramitacao [:sequential HistoricoTramitacaoItemOut]]
   [:tramitacao-truncado :boolean]
   [:apensadas [:sequential ApensacaoOut]]
   [:apensadas-truncado :boolean]
   [:emendas [:sequential EmendaResumoOut]]
   [:emendas-truncado :boolean]
   [:pareceres [:sequential ParecerResumoOut]]
   [:pareceres-truncado :boolean]])
