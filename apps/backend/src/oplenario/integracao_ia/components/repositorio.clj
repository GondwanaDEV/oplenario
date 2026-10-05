(ns oplenario.integracao-ia.components.repositorio
  "Repo-Component da fronteira core <-> IA (ADR-0008). O FEED e' supratenant (tx sem tenant, como o relay do
  outbox); a CAIXA DE ENTRADA roda na tx do tenant do evento (`com-tenant*`), com o efeito injetado pelo host
  aplicado na MESMA tx do registro — dedup e efeito atomicos."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [oplenario.integracao-ia.db.chamada-agente :as chamada-agente]
            [oplenario.integracao-ia.db.eventos :as eventos]
            [oplenario.integracao-ia.db.interacao-assistente :as interacao]
            [oplenario.integracao-ia.db.orcamento :as orcamento]
            [oplenario.integracao-ia.db.proposta-ato :as proposta]
            [oplenario.integracao-ia.logic :as logic]
            [oplenario.kernel.tenancy :as tenancy]))

(set! *warn-on-reflection* true)

(defprotocol RepoIntegracaoIA
  (listar-eventos [this depois limite] "Feed: eventos com seq > depois (supratenant).")
  (receber-evento! [this evento efeito]
    "Caixa de entrada: registra `evento` (dominio) na tx do tenant e, se novo, chama (efeito tx ente-id evento)
    na mesma tx. Devolve {:aplicado boolean}.")
  (registrar-chamada-agente! [this chamada]
    "Audit (ADR-0010, Eixo 3.5): uma chamada de ferramenta de agente que escreve, com o desfecho, na tx do tenant.")
  (chamadas-da-execucao [this ente-id execucao-id] "As chamadas registradas de uma execucao, em ordem.")
  ;; ADR-0024: o historico auditavel da Clara
  (registrar-interacao-assistente! [this interacao]
    "Grava a interacao (a linha ja' com o hash, `logic/interacao`), na tx do tenant. Append-only.")
  (interacao-assistente [this ente-id id] "A interacao `id`, ou nil.")
  (conversa-da-pessoa? [this ente-id identidade-id conversa-id] "A conversa existe nesta Casa e e' desta pessoa?")
  (historico-assistente [this ente-id identidade-id antes limite]
    "As interacoes, a mais recente primeiro (resumo). `identidade-id` nil = a Casa inteira (so' o auditor).")
  (conversa-assistente [this ente-id conversa-id] "As interacoes de uma conversa, em ordem, com tudo o que foi guardado.")
  ;; B.6 / ADR-0012: a proposta de ato e as leituras de terceiro da execucao
  (criar-proposta! [this proposta] "Grava a proposta (estado aguardando); devolve-a.")
  (proposta [this ente-id id])
  (propostas-da-pessoa [this ente-id identidade-id agora] "As que esperam confirmacao, no prazo.")
  (propostas-da-execucao [this ente-id execucao-id])
  (mudar-estado-proposta! [this ente-id id de mudanca]
    "Condicional: so' se o estado atual for `de`; nil se outro chegou antes. `mudanca` = {:estado :resultado :erro :decidida?}.")
  (registrar-leitura-de-terceiro! [this leitura])
  (leituras-de-terceiro [this ente-id execucao-id])
  ;; B.9 / ADR-0014: o orcamento de IA da Casa
  (definir-orcamento! [this orcamento]
    "Grava a definicao ({:ente-id :mensal :teto-duro :moeda :definido-por}) e publica `OrcamentoIADefinido` no feed,
    na mesma tx. Devolve a definicao.")
  (orcamento-atual [this ente-id] "A definicao mais recente, ou nil.")
  (contar-propostas [this ente-id desde ate] "B.9: {estado n} das propostas criadas em [desde, ate)."))

(defrecord RepoIntegracaoIAPg [datasource]
  RepoIntegracaoIA
  (listar-eventos [_ depois limite]
    (jdbc/with-transaction [tx (:ds datasource) {:read-only true}]
      (eventos/listar-saida tx depois limite)))
  (receber-evento! [_ evento efeito]
    (tenancy/com-tenant* (:ds datasource) (:ente-id evento)
      (fn [tx]
        (if (eventos/registrar-entrada! tx evento)
          (do (efeito tx (:ente-id evento) evento) {:aplicado true})
          {:aplicado false}))))
  (registrar-chamada-agente! [_ chamada]
    (tenancy/com-tenant* (:ds datasource) (:ente-id chamada) #(chamada-agente/registrar! % chamada)))
  (chamadas-da-execucao [_ ente-id execucao-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(chamada-agente/da-execucao % ente-id execucao-id)))
  (registrar-interacao-assistente! [_ i]
    (tenancy/com-tenant* (:ds datasource) (:ente-id i) #(interacao/inserir! % i)))
  (interacao-assistente [_ ente-id id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(interacao/buscar % ente-id id)))
  (conversa-da-pessoa? [_ ente-id identidade-id conversa-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(interacao/conversa-da-pessoa? % ente-id identidade-id conversa-id)))
  (historico-assistente [_ ente-id identidade-id antes limite]
    (tenancy/com-tenant* (:ds datasource) ente-id #(interacao/listar % ente-id identidade-id antes limite)))
  (conversa-assistente [_ ente-id conversa-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(interacao/da-conversa % ente-id conversa-id)))
  (criar-proposta! [_ p]
    (tenancy/com-tenant* (:ds datasource) (:ente-id p) #(proposta/inserir! % p)))
  (proposta [_ ente-id id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(proposta/buscar % ente-id id)))
  (propostas-da-pessoa [_ ente-id identidade-id agora]
    (tenancy/com-tenant* (:ds datasource) ente-id #(proposta/da-pessoa % ente-id identidade-id agora)))
  (propostas-da-execucao [_ ente-id execucao-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(proposta/da-execucao % ente-id execucao-id)))
  (mudar-estado-proposta! [_ ente-id id de mudanca]
    (tenancy/com-tenant* (:ds datasource) ente-id #(proposta/mudar-estado! % ente-id id de mudanca)))
  (registrar-leitura-de-terceiro! [_ l]
    (tenancy/com-tenant* (:ds datasource) (:ente-id l) #(proposta/registrar-leitura-de-terceiro! % l)))
  (leituras-de-terceiro [_ ente-id execucao-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(proposta/leituras-de-terceiro % ente-id execucao-id)))
  (definir-orcamento! [_ o]
    (tenancy/com-tenant* (:ds datasource) (:ente-id o)
      (fn [tx]
        (let [d (orcamento/inserir! tx o)]
          (eventos/inserir-saida! tx (logic/evento-orcamento d))
          d))))
  (orcamento-atual [_ ente-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(orcamento/atual % ente-id)))
  (contar-propostas [_ ente-id desde ate]
    (tenancy/com-tenant* (:ds datasource) ente-id #(proposta/contar-por-estado % ente-id desde ate))))

(defn repositorio [] (map->RepoIntegracaoIAPg {}))

(defn- promover-ou-descartar
  "A parte PURA da promocao, na fronteira de despacho do consumidor: devolve o evento de integracao, ou nil se o
  tipo nao e' promovido, o sigilo barra, OU o evento e' malformado (log :warn nomeando a linha do outbox).

  `shared.outbox.ente_id` e' NULLABLE e o feed (`evento_saida.ente_id`) e' NOT NULL: sem a checagem, um evento
  supratenant/malformado virava `PSQLException` DENTRO da tx do relay COMPARTILHADO — poison + head-of-line de todo
  evento de id maior, de qualquer Casa. A checagem e' ANTES do INSERT (nunca um catch em volta dele): capturar
  uma SQLException dentro da tx a deixa abortada, e a falha de INFRA deve propagar (retry/dead-letter e' o carry
  'relay-observavel' de docs/16, nao esta frente). O catch Throwable cobre so' `logic/promover` (puro): qualquer
  excecao ali e' forma do dado, nao infra."
  [{:keys [id tipo ente-id payload]}]
  (if (nil? ente-id)
    (do (log/warn "integracao-ia: evento com ente-id ausente — descartado, nao promovido (dado malformado, rotina)"
                  {:id id :tipo tipo})
        nil)
    (try
      (logic/promover tipo ente-id payload)
      (catch Throwable e
        (log/warn e "integracao-ia: payload malformado — evento descartado, nao promovido (dado externo, rotina)"
                  {:id id :tipo tipo :ente-id ente-id})
        nil))))

(defn promover-em-tx!
  "Handler do relay (tx do outbox, supratenant): promove o evento de dominio a evento de integracao, se a lista
  de `logic/promocoes` o prever e o sigilo deixar. Idempotente pela chave. Evento malformado e' descartado com log
  (ver `promover-ou-descartar`), nunca relancado: o relay e' UM SO' para todas as Casas."
  [tx evento]
  (when-let [ev (promover-ou-descartar evento)]
    (eventos/inserir-saida! tx ev)))

;; ---------------------------------------------------------------------------------------------
;; ADR-0018 (Eixo 2): a IA da Casa SUSPENSA pausa — cota zero, pelo proprio orcamento da ADR-0014. Consumidores do relay
;; (tx do outbox, supratenant): setam o tenant na tx para a RLS do orcamento e publicam `OrcamentoIADefinido` no feed
;; na mesma tx. A suspensao grava 0/0 (o satelite fica "esgotada": nem o que a pessoa pede roda); a reativacao devolve
;; a definicao que valia antes — ou uma SEM VALOR, se antes a Casa so' media. Idempotentes pelo que a ultima diz.
;; ---------------------------------------------------------------------------------------------

(def definido-pela-suspensao "operacao:casa-suspensa")
(def definido-pela-reativacao "operacao:casa-reativada")

(defn- definir-em-tx! [tx o]
  (let [d (orcamento/inserir! tx o)]
    (eventos/inserir-saida! tx (logic/evento-orcamento d))
    d))

(defn pausar-por-suspensao-em-tx!
  "Handler de `admin_sistema.casa.suspensa`."
  [tx {:keys [ente-id]}]
  (when ente-id
    (tenancy/set-tenant! tx ente-id)
    (let [ultima (orcamento/ultima tx ente-id)]
      (when-not (= definido-pela-suspensao (:definido-por ultima))
        (definir-em-tx! tx {:ente-id ente-id :mensal 0M :teto-duro 0M :moeda (or (:moeda ultima) "USD")
                            :definido-por definido-pela-suspensao})))))

(defn retomar-apos-reativacao-em-tx!
  "Handler de `admin_sistema.casa.reativada`."
  [tx {:keys [ente-id]}]
  (when ente-id
    (tenancy/set-tenant! tx ente-id)
    (let [ultima (orcamento/ultima tx ente-id)]
      (when (= definido-pela-suspensao (:definido-por ultima))
        (let [antes (orcamento/ultima-exceto tx ente-id definido-pela-suspensao)]
          (definir-em-tx! tx {:ente-id ente-id :mensal (:mensal antes) :teto-duro (:teto-duro antes)
                              :moeda (or (:moeda antes) (:moeda ultima)) :definido-por definido-pela-reativacao}))))))
