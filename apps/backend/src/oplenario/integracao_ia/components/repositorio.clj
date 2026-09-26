(ns oplenario.integracao-ia.components.repositorio
  "Repo-Component da fronteira core <-> IA (ADR-0008). O FEED e' supratenant (tx sem tenant, como o relay do
  outbox); a CAIXA DE ENTRADA roda na tx do tenant do evento (`com-tenant*`), com o efeito injetado pelo host
  aplicado na MESMA tx do registro — dedup e efeito atomicos."
  (:require [next.jdbc :as jdbc]
            [oplenario.integracao-ia.db.eventos :as eventos]
            [oplenario.integracao-ia.logic :as logic]
            [oplenario.kernel.tenancy :as tenancy]))

(set! *warn-on-reflection* true)

(defprotocol RepoIntegracaoIA
  (listar-eventos [this depois limite] "Feed: eventos com seq > depois (supratenant).")
  (receber-evento! [this evento efeito]
    "Caixa de entrada: registra `evento` (dominio) na tx do tenant e, se novo, chama (efeito tx ente-id evento)
    na mesma tx. Devolve {:aplicado boolean}."))

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
          {:aplicado false})))))

(defn repositorio [] (map->RepoIntegracaoIAPg {}))

(defn promover-em-tx!
  "Handler do relay (tx do outbox, supratenant): promove o evento de dominio a evento de integracao, se a lista
  de `logic/promocoes` o prever e o sigilo deixar. Idempotente pela chave."
  [tx {:keys [tipo ente-id payload]}]
  (when-let [ev (logic/promover tipo ente-id payload)]
    (eventos/inserir-saida! tx ev)))
