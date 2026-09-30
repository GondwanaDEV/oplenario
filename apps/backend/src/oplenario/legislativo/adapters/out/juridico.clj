(ns oplenario.legislativo.adapters.out.juridico
  "Gate de SAIDA `dominio -> wire/out` do caminho da comissao e do parecer juridico (ADR-0019 fatia 1). Projeta campo a
  campo e valida contra o contrato fechado — o id de quem assinou e de quem pediu nao saem (o nome vem do host)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.wire.out.juridico :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [schema out]
  (when-not (m/validate schema out)
    (throw (ex-info "parecer juridico viola o contrato (bug de servidor)"
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- ->str [x] (some-> x str))

(defn- assinatura [p]
  (when (= "assinado" (:estado p))
    {:nome (:assinatura-nome p) :oab (:assinatura-oab p) :qualificacao (:assinatura-qualificacao p)
     :em (->str (:assinado-em p))}))

(defn- parecer [p com-texto?]
  (cond-> {:id (->str (:id p)) :numero (:numero p) :ano (:ano p) :estado (:estado p)
           :conclusao (:conclusao p) :assinatura (assinatura p)
           :substitui-id (->str (:substitui-id p)) :substituido (boolean (:substituido p))}
    ;; o texto so' sai quando o dominio o trouxe: o rascunho de outrem chega SEM ele (controller/ocultar-rascunho)
    (and com-texto? (contains? p :relatorio)) (assoc :relatorio (:relatorio p) :fundamentacao (:fundamentacao p))))

(defn- materia [p]
  (when (:proposicao-id p)
    {:id (->str (:proposicao-id p))
     :ref (logic/numero-exibicao {:tipo (:materia-tipo p) :ano (:materia-ano p) :sequencial (:materia-sequencial p)})
     :ementa (or (:materia-ementa p) "")}))

(defn- pedido [p com-texto?]
  {:id (->str (:id p)) :proposicao (materia p) :assunto (:assunto p) :prazo (->str (:prazo p))
   :estado (:estado p) :pedido-por (:pedido-por-nome p) :em-nome-de (:em-nome-de p) :origem (:origem p)
   :criado-em (->str (:criado-em p)) :parecer (some-> (:parecer p) (parecer com-texto?))})

(defn pedido->wire
  "Um pedido com o parecer corrente e o texto (detalhe)."
  [p]
  (validado wire/PedidoJuridicoOut (pedido p true)))

(defn pedidos->wire
  "A fila: sem o texto do parecer."
  [ps]
  (validado wire/PedidosJuridicosOut {:pedidos (mapv #(pedido % false) ps)}))

(defn da-materia->wire
  "Ficha da materia: os assinados (com texto) e os pedidos ainda abertos."
  [{:keys [pareceres pedidos-abertos]}]
  (validado wire/PareceresDaMateriaOut
            {:pareceres (mapv #(assoc (parecer % true) :pedido-id (->str (:pedido-id %))) pareceres)
             :pedidos-abertos (mapv (fn [p] {:id (->str (:id p)) :assunto (:assunto p) :prazo (->str (:prazo p))
                                             :criado-em (->str (:criado-em p))})
                                    pedidos-abertos)}))

(defn publicos->wire
  "Portal: so' o vigente, com a assinatura."
  [pareceres]
  (validado wire/PareceresPublicosOut
            {:pareceres (mapv (fn [p] {:numero (:numero p) :ano (:ano p) :conclusao (:conclusao p)
                                       :relatorio (:relatorio p) :fundamentacao (:fundamentacao p)
                                       :assinatura (assinatura p)})
                              pareceres)}))

(defn comissoes->wire [cs]
  (validado wire/ComissoesOut {:comissoes (mapv (fn [c] {:id (->str (:id c)) :nome (:nome c)}) cs)}))

(defn pareceres-abertos->wire [ps]
  (validado wire/PareceresAbertosOut
            {:pareceres (mapv (fn [p] {:id (->str (:id p)) :comissao-id (->str (:comissao-id p))
                                       :comissao-nome (:comissao-nome p) :relator-id (->str (:relator-id p))
                                       :relator-nome (:relator-nome p) :estado (:estado p)
                                       :ja-existia (boolean (:ja-existia p))})
                              ps)}))

(defn relator->wire [{:keys [id relator-id relator-nome]}]
  (validado wire/RelatorDesignadoOut {:id (->str id) :relator-id (->str relator-id) :relator-nome relator-nome}))
