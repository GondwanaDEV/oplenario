(ns oplenario.legislativo.diplomat.catalogo
  "As entradas do CATALOGO DE ACOES que sao do legislativo (ADR-0009). Cada uma e' a mesma acao de uma rota da tela —
  mesmo controller, mesmo wire/out — com descricao para o agente. As primeiras (§22.11 Eixo 2, 'migracao'): as
  consultas que um agente da Casa precisa antes de tudo: a situacao da materia e a tramitacao dela."
  (:require [oplenario.kernel.catalogo :as catalogo]
            [oplenario.legislativo.adapters.in.proposicao :as adapters-in-proposicao]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-out-proposicao]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.wire.out.proposicao :as wire]))

(set! *warn-on-reflection* true)

(def IdentificacaoMateria
  "Como o agente aponta a materia: pelo id, OU pela identificacao que uma pessoa usa (especie + numero + ano)."
  [:and
   [:map {:closed true :description "Informe proposicao-id, ou tipo + sequencial + ano."}
    [:proposicao-id {:optional true :description "Id da proposicao, se ja' for conhecido."} :uuid]
    [:tipo {:optional true :description "Especie da proposicao (ex.: projeto_lei para PL, requerimento para REQ)."}
     (into [:enum] (sort logic/tipos))]
    [:sequencial {:optional true :description "O numero da proposicao (o 12 de 'PL 12/2026')."} [:int {:min 1}]]
    [:ano {:optional true :description "O ano da proposicao (o 2026 de 'PL 12/2026')."} [:int {:min 1900 :max 2200}]]]
   [:fn {:error/message "informe proposicao-id, ou tipo + sequencial + ano"}
    (fn [m] (or (some? (:proposicao-id m)) (every? #(some? (get m %)) [:tipo :sequencial :ano])))]])

(defn- resolver-id
  "O id da materia no tenant do ator, ou nil."
  [repo-legislativo ente-id {:keys [proposicao-id tipo ano sequencial]}]
  (or proposicao-id
      (:id (repo/buscar-proposicao-por-numero repo-legislativo ente-id tipo ano sequencial))))

(def entradas
  [(catalogo/entrada
    {:nome "situacao_da_materia"
     :descricao (str "Consulta uma proposicao da Camara (projeto de lei, requerimento, indicacao, mocao...): numero, "
                     "ementa, autoria, em que pe' esta (estado da tramitacao) e o texto vigente. Use quando alguem "
                     "perguntar sobre uma materia especifica, como 'qual a situacao do PL 12/2026?'.")
     :classe :leitura
     :papeis #{"secretario" "vereador"}
     :entrada IdentificacaoMateria
     :saida wire/ProposicaoDetalheOut
     :rotas #{:legislativo/detalhe-proposicao}
     :executar (fn [{:keys [repo-legislativo]} ator m]
                 (let [ente-id (:ente-id ator)]
                   (when-let [id (resolver-id repo-legislativo ente-id m)]
                     (when-let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-legislativo ente-id id)]
                       (adapters-out-proposicao/detalhe->wire proposicao texto)))))})
   (catalogo/entrada
    {:nome "tramitacao_da_materia"
     :descricao (str "Mostra o historico de tramitacao de uma proposicao (cada movimentacao, de que estado para qual, "
                     "quando e quem recebeu) e os proximos atos que o rito da Casa permite a partir do estado atual. "
                     "Use para 'por onde passou o PL 12/2026?' ou 'o que falta para o requerimento 5/2026 andar?'.")
     :classe :leitura
     :papeis #{"secretario" "vereador"}
     :entrada IdentificacaoMateria
     :saida wire/TramitacaoOut
     :rotas #{:legislativo/tramitacao-proposicao}
     :executar (fn [{:keys [repo-legislativo nome-na-casa]} ator m]
                 (let [ente-id (:ente-id ator)]
                   (when-let [id (resolver-id repo-legislativo ente-id m)]
                     (when-let [t (controllers/buscar-tramitacao repo-legislativo (or nome-na-casa (constantly nil))
                                                                 ente-id id
                                                                 (:limite (adapters-in-proposicao/tramitacao-query->dominio {})))]
                       (adapters-out-proposicao/tramitacao->wire t)))))})])
