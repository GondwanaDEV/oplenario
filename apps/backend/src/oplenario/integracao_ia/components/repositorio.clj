(ns oplenario.integracao-ia.components.repositorio
  "Repo-Component da fronteira core <-> IA (ADR-0008). O FEED e' supratenant (tx sem tenant, como o relay do
  outbox); a CAIXA DE ENTRADA roda na tx do tenant do evento (`com-tenant*`), com o efeito injetado pelo host
  aplicado na MESMA tx do registro — dedup e efeito atomicos."
  (:require [next.jdbc :as jdbc]
            [oplenario.integracao-ia.db.chamada-agente :as chamada-agente]
            [oplenario.integracao-ia.db.eventos :as eventos]
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
  ;; B.6 / ADR-0012: a proposta de ato e as leituras de terceiro da execucao
  (criar-proposta! [this proposta] "Grava a proposta (estado aguardando); devolve-a.")
  (proposta [this ente-id id])
  (propostas-da-pessoa [this ente-id identidade-id agora] "As que esperam confirmacao, no prazo.")
  (propostas-da-execucao [this ente-id execucao-id])
  (mudar-estado-proposta! [this ente-id id de mudanca]
    "Condicional: so' se o estado atual for `de`; nil se outro chegou antes. `mudanca` = {:estado :resultado :erro :decidida?}.")
  (registrar-leitura-de-terceiro! [this leitura])
  (leituras-de-terceiro [this ente-id execucao-id]))

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
    (tenancy/com-tenant* (:ds datasource) ente-id #(proposta/leituras-de-terceiro % ente-id execucao-id))))

(defn repositorio [] (map->RepoIntegracaoIAPg {}))

(defn promover-em-tx!
  "Handler do relay (tx do outbox, supratenant): promove o evento de dominio a evento de integracao, se a lista
  de `logic/promocoes` o prever e o sigilo deixar. Idempotente pela chave."
  [tx {:keys [tipo ente-id payload]}]
  (when-let [ev (logic/promover tipo ente-id payload)]
    (eventos/inserir-saida! tx ev)))
