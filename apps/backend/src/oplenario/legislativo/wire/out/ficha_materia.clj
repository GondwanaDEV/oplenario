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
   [:ocorrido-em :string]])

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
   [:relator-id {:optional true} [:maybe :string]]
   [:voto-relator {:optional true} [:maybe :string]]
   [:estado :string]])

(def FichaMateriaOut
  "GET /legislativo/proposicoes/:id/ficha — o envelope agregado (Onda B Slice 3). `:proposicao` reusa
  ProposicaoDetalheOut (o controller ja' gateia nil -> 404 na borda antes de chegar aqui; a wire/out so'
  projeta ficha com proposicao presente). Tetos fixos (100 tramitacao / 50 demais) sao decisao do Repo
  (app-level), nao expostos aqui como metadado de paginacao — sem 'carregar mais' nesta fatia."
  [:map {:closed true}
   [:proposicao proposicao/ProposicaoDetalheOut]
   [:tramitacao [:sequential HistoricoTramitacaoItemOut]]
   [:apensadas [:sequential ApensacaoOut]]
   [:emendas [:sequential EmendaResumoOut]]
   [:pareceres [:sequential ParecerResumoOut]]])
