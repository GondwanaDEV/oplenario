(ns oplenario.legislativo.adapters.out.ficha-materia
  "Gate de SAIDA `models -> wire/out` da ficha da materia (§22.10 adapters/out, ADR-0001, Onda B Slice 3).
  `ficha->wire` REUSA o cabecalho JA PROJETADO por `adapters.out.proposicao/detalhe->wire` (nao reprojeta
  campo-a-campo — mesma disciplina de nao duplicar logica ja fechada): recebe `proposicao-out` PRONTO (o
  diplomat e' quem chama os dois adapters e compoe — adapters/ NUNCA chama outro adapters/, ADR-0001 §3,
  `arquitetura_test`) e projeta as 4 listas cross-eixo. Dropa :contexto/:template-id/:ator-id da tramitacao
  (payload interno do motor / sem resolvedor ator->nome ainda) e os campos de armazenamento/internos de
  apensacao/emenda/parecer — nunca vaza atributo interno ao contrato externo. Validado contra o contrato
  (drift de campo = bug de servidor -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.ficha-materia :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn- tramitacao-item->wire [linha]
  {:de-estado (:de-estado linha) :para-estado (:para-estado linha) :gatilho (:gatilho linha)
   :ocorrido-em (->str (:ocorrido-em linha))})

(defn- apensacao->wire [linha]
  {:apensada-id (->str (:apensada-id linha)) :apensada-em (->str (:apensada-em linha))
   :motivo-apensacao (:motivo-apensacao linha)})

(defn- emenda-resumo->wire [linha]
  {:id (->str (:id linha)) :numero-local (:numero-local linha) :tipo-emenda (:tipo-emenda linha)
   :momento-apresentacao (:momento-apresentacao linha) :autor-tipo (:autor-tipo linha)
   :autor-texto (:autor-texto linha) :estado (:estado linha)})

(defn- parecer-resumo->wire [linha]
  {:id (->str (:id linha)) :comissao-id (->str (:comissao-id linha))
   :relator-id (->str (:relator-id linha)) :voto-relator (:voto-relator linha) :estado (:estado linha)})

(defn ficha->wire
  "`proposicao-out` = ProposicaoDetalheOut JA PROJETADO (o diplomat chamou `adapters.out.proposicao/
  detalhe->wire` antes — reuso, nao duplicacao); `ficha` = {:tramitacao :apensadas :emendas :pareceres}
  (dominio, kebab; :proposicao/:texto do `ficha` sao IGNORADOS aqui, ja' viraram `proposicao-out`) ->
  FichaMateriaOut. O caller (controller) ja gateou nil de :proposicao -> 404 na borda antes de chegar aqui."
  [proposicao-out {:keys [tramitacao apensadas emendas pareceres]}]
  (validado wire/FichaMateriaOut
            {:proposicao proposicao-out
             :tramitacao (mapv tramitacao-item->wire tramitacao)
             :apensadas (mapv apensacao->wire apensadas)
             :emendas (mapv emenda-resumo->wire emendas)
             :pareceres (mapv parecer-resumo->wire pareceres)}
            "ficha da materia"))
