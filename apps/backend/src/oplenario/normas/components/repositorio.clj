(ns oplenario.normas.components.repositorio
  "Repo-Component das normas de referencia (ADR-0011). Tudo na tx do tenant (`com-tenant*`): a RLS mostra as normas de
  referencia e as da Casa, e so' deixa escrever as da Casa."
  (:require [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.normas.db.norma :as db]
            [oplenario.normas.events.norma :as ev]))

(set! *warn-on-reflection* true)

(defprotocol RepoNormas
  (listar-normas [this ente-id])
  (importar-versao! [this ente-id norma versao dispositivos]
    "Acha ou cria a norma da Casa e grava a versao em conferencia com os dispositivos, numa tx. Lanca
    `:conflito/versao-em-conferencia` se a norma ja' tem uma versao esperando conferencia. Devolve o id da versao.")
  (versao [this ente-id versao-id] "A versao com a norma e os dispositivos, ou nil.")
  (decidir-versao! [this ente-id versao-id decisao pessoa]
    "publicar | descartar uma versao em conferencia; nil se ela ja' foi decidida ou nao existe. Publicar emite
    `norma.versao-vigente` na mesma tx.")
  (dispositivos-vigentes [this ente-id versao-id]
    "Fronteira com a IA (B.4b): a versao e os dispositivos SO' se ela e' a vigente e e' desta Casa; senao nil.")
  (ler-dispositivo [this ente-id norma-id especie endereco]
    "B.5: o dispositivo (com os descendentes) da versao VIGENTE da norma — pelo id, ou pela especie para as unicas da
    Casa (LOM, Regimento). Vazio se nao houver.")
  (hidratar-dispositivos [this ente-id pares] "B.5: dos pares [versao-id endereco] da IA, so' os ainda vigentes.")
  (buscar-dispositivos-literal [this ente-id consulta limite] "B.5, R-IA-1: busca pelas palavras exatas."))

(defrecord RepoNormasPg [datasource bus]
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
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (let [v (db/decidir! tx versao-id decisao pessoa)]
          ;; B.4b: a versao que passa a valer vai ao indice da IA (evento na MESMA tx do ato)
          (when (and v bus (= "vigente" (:estado v)))
            (eventos/emitir! bus tx (ev/vigente ente-id {:norma-id (:norma-id v) :versao-id (:id v)
                                                         :especie (get-in v [:norma :especie])})))
          v))))
  (dispositivos-vigentes [_ ente-id versao-id]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (when-let [v (db/versao tx versao-id)]
          (when (and (= "vigente" (:estado v)) (= ente-id (get-in v [:norma :ente-id])))
            (assoc v :dispositivos (db/dispositivos tx versao-id)))))))
  (ler-dispositivo [_ ente-id norma-id especie endereco]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (if-let [nid (or norma-id (when especie (db/norma-da-casa-por-especie tx ente-id especie)))]
          (db/ler-vigente tx nid endereco)
          []))))
  (hidratar-dispositivos [_ ente-id pares]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/vigentes-por-endereco % pares)))
  (buscar-dispositivos-literal [_ ente-id consulta limite]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/buscar-literal % consulta limite))))

(defn repositorio [] (map->RepoNormasPg {}))
