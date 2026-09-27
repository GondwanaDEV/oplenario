(ns oplenario.sessoes.diplomat.catalogo
  "As entradas do CATALOGO DE ACOES que sao de sessoes (ADR-0009). A primeira: a pauta de uma sessao — por padrao a
  da sessao em curso ou da proxima, que e' como uma pessoa pergunta ('o que vai ser votado na proxima sessao?').
  So' sessao de transmissao PUBLICA: sessao secreta nunca vai a IA (§22.11, B1), e o agente e' a IA."
  (:require [oplenario.kernel.catalogo :as catalogo]
            [oplenario.sessoes.adapters.out.pauta :as adapters-out-pauta]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(def ^:private em-andamento-ou-futura #{"aberta" "suspensa" "agendada"})

(defn- publica? [s] (true? (:transmite-publica s)))

(defn- sessao-da-vez
  "A sessao em curso (aberta/suspensa) ou, nao havendo, a proxima agendada — entre as que o ator pode ver E que sao
  publicas. A listagem do controller ja' vem filtrada por visibilidade e ordenada (em curso primeiro, agendadas por
  data crescente)."
  [repo-sessoes ator]
  (:id (first (filter #(and (em-andamento-ou-futura (:estado %)) (publica? %))
                      (controllers/listar-sessoes repo-sessoes ator)))))

(defn- sessao-publica? [repo-sessoes ator sid]
  (publica? (repo/buscar-sessao repo-sessoes (:ente-id ator) sid)))

(def entradas
  [(catalogo/entrada
    {:nome "pauta_da_sessao"
     :descricao (str "Lista a pauta de uma sessao plenaria: os itens na ordem, com a proposicao de cada um (numero e "
                     "ementa) e o item em apreciacao agora, se houver. Sem sessao informada, usa a sessao em curso "
                     "ou, nao havendo, a proxima agendada — para 'o que vai ser votado na proxima sessao?'.")
     :classe :leitura
     :papeis #{"secretario" "vereador"}
     :entrada [:map {:closed true}
               [:sessao-id {:optional true :description "Id da sessao; ausente = a sessao em curso ou a proxima."}
                :uuid]]
     :saida wire/PautaOut
     :rotas #{:sessoes/pauta}
     :executar (fn [{:keys [repo-sessoes resumir-proposicoes]} ator {:keys [sessao-id]}]
                 (when-let [sid (if sessao-id
                                  (when (sessao-publica? repo-sessoes ator sessao-id) sessao-id)
                                  (sessao-da-vez repo-sessoes ator))]
                   (when-let [p (controllers/pauta-da-sessao repo-sessoes ator sid)]
                     (adapters-out-pauta/pauta->wire
                      p (controllers/resumos-da-pauta (or resumir-proposicoes (fn [_ _] {})) (:ente-id ator) p)))))})])
