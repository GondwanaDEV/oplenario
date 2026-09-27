(ns oplenario.normas.components.repositorio
  "Repo-Component das normas de referencia (ADR-0011). Tudo na tx do tenant (`com-tenant*`): a RLS mostra as normas de
  referencia e as da Casa, e so' deixa escrever as da Casa."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.normas.db.norma :as db]))

(set! *warn-on-reflection* true)

(defprotocol RepoNormas
  (listar-normas [this ente-id])
  (importar-versao! [this ente-id norma versao dispositivos]
    "Acha ou cria a norma da Casa e grava a versao em conferencia com os dispositivos, numa tx. Lanca
    `:conflito/versao-em-conferencia` se a norma ja' tem uma versao esperando conferencia. Devolve o id da versao.")
  (versao [this ente-id versao-id] "A versao com a norma e os dispositivos, ou nil.")
  (decidir-versao! [this ente-id versao-id decisao pessoa]
    "publicar | descartar uma versao em conferencia; nil se ela ja' foi decidida ou nao existe."))

(defrecord RepoNormasPg [datasource]
  RepoNormas
  (listar-normas [_ ente-id] (tenancy/com-tenant* (:ds datasource) ente-id db/listar))
  (importar-versao! [_ ente-id norma v dispositivos]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (let [norma-id (or (:id (db/norma-por-identidade tx ente-id (:especie norma) (:numero norma)))
                           (db/inserir-norma! tx (assoc norma :ente-id ente-id)))]
          (when (db/tem-versao-em-conferencia? tx norma-id)
            (throw (ex-info "esta norma ja' tem uma versao esperando conferencia"
                            {:tipo :conflito/versao-em-conferencia :norma-id norma-id})))
          (db/inserir-versao! tx (assoc v :ente-id ente-id :norma-id norma-id) dispositivos)))))
  (versao [_ ente-id versao-id]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx] (some-> (db/versao tx versao-id) (assoc :dispositivos (db/dispositivos tx versao-id))))))
  (decidir-versao! [_ ente-id versao-id decisao pessoa]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/decidir! % versao-id decisao pessoa))))

(defn repositorio [] (map->RepoNormasPg {}))
