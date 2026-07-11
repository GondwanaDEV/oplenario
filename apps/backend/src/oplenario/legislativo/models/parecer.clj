(ns oplenario.legislativo.models.parecer
  "Representacao INTERNA (dominio) do parecer_comissao — Malli (§22.10 models/, eixo F). Entidade propria
  com state machine PROPRIA governada pelo motor do eixo C (decisao (b) do workflow de reuso). `estado` e'
  :string (NAO enum): e' TEMPLATE-DRIVEN como proposicoes.estado — a maquina vive nas tabelas de template
  (config tenant), nao num enum em codigo. Ref polimorfica (objeto_tipo,objeto_id) p/ proposicao OU emenda
  (disc.2). `voto-relator` e' :string sem enum (vocabulario regimental ABERTO, §22.4.4)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def Parecer
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   ;; ref polimorfica: o objeto sobre o qual o parecer opina (proposicao OU emenda)
   [:objeto-tipo (enum-de logic/objetos-parecer)]
   [:objeto-id :uuid]
   [:comissao-id :uuid]
   [:relator-id {:optional true} [:maybe :uuid]]
   ;; voto do relator na entity principal — vocabulario regimental aberto (sem enum)
   [:voto-relator {:optional true} [:maybe :string]]
   ;; estado TEMPLATE-DRIVEN (a maquina e' o motor; sem enum fechado aqui, como proposicoes.estado)
   [:estado :string]
   [:template-id :uuid]
   ;; ponteiro p/ a versao de texto vigente do parecer (F3.6b); NULL ate la
   [:texto-vigente-versao-id {:optional true} [:maybe :uuid]]
   ;; concorrencia: exposto p/ o CAS de mudar-estado!/designar-relator!/transicionar-parecer!
   [:lock-version :int]
   ;; Onda B Slice 5: coluna ja existia na migration 0019, so' nao era lida por db/buscar ate agora.
   [:criado-em km/Instante]])
