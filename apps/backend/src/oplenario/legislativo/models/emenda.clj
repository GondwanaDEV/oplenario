(ns oplenario.legislativo.models.emenda
  "Representacao INTERNA (dominio) da emenda — Malli (§22.10 models/, eixo D). Entidade propria (nao um
  tipo de proposicao). Ciclo de vida em ENUM SIMPLES (`estado`) — universal entre camaras, por isso enum
  fechado aqui (ao contrario do `estado` :string da proposicao, cuja maquina e' template-driven). Texto
  hibrido inline/URI sem versionamento. Os vocabularios vem de legislativo.logic (fonte unica; os CHECK
  da migration 0017 espelham)."
  (:require [oplenario.legislativo.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def Emenda
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:proposicao-mae-id :uuid]
   [:numero-local :int]
   [:tipo-emenda (enum-de logic/tipos-emenda)]
   [:momento-apresentacao (enum-de logic/momentos-apresentacao)]
   [:escopo-textual {:optional true} [:maybe :string]]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :uuid]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado (enum-de logic/estados-emenda)]
   [:formato :string]
   ;; XOR no banco: exatamente um de inline/uri. Aqui ambos opcionais (o caller resolve via decidir-armazenamento).
   [:texto-inline {:optional true} [:maybe :string]]
   [:conteudo-uri {:optional true} [:maybe :string]]
   [:hash-conteudo {:optional true} [:maybe :string]]
   ;; ciclo bidirecional com o texto-mae (preenchido na aprovacao)
   [:versao-texto-resultante-id {:optional true} [:maybe :uuid]]
   ;; concorrencia: exposto p/ o CAS de mudar-estado!/aprovar!
   [:lock-version :int]])
