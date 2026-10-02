(ns oplenario.comunicacao.components.repositorio
  "Repo-Component dos comunicados internos (ADR-0020, ADR-0001 §3-bis). Tudo na tx do tenant (`com-tenant*`): a RLS
  isola a Casa. As leituras que gravam marca (a caixa grava `recebido`, o detalhe grava `lido`) gravam e releem na
  MESMA tx — a resposta ja' traz a hora que acabou de ficar registrada."
  (:require [oplenario.comunicacao.db.comunicado :as db]
            [oplenario.kernel.tenancy :as tenancy])
  (:import (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(defprotocol RepoComunicacao
  (enviar! [this ente-id comunicado destinos destinatarios]
    "O ato de enviar numa tx: numero + protocolo + comunicado + destinos + lista congelada. Devolve o comunicado
     hidratado (ver `comunicado`). Substituir um comunicado ja' substituido -> :conflito/ja-substituido.")
  (comunicado [this ente-id id]
    "O comunicado com `:destinos`, `:anexos`, `:substitui` {:id :protocolo}, `:substituido-por` {:id :protocolo} e
     `:n-destinatarios`; nil = inexistente nesta Casa.")
  (abrir! [this ente-id id identidade-id marcar?]
    "O comunicado (como `comunicado`) com `:destinatario` (a linha da pessoa na lista, ou nil) e `:minhas-marcas`. Com
     `marcar?` e a pessoa na lista, grava `lido` (e `recebido` se faltar) antes de reler. nil = inexistente.")
  (caixa! [this ente-id identidade-id marcar?]
    "A caixa da pessoa: `:itens` (os mais recentes, com o caminho, as marcas, quem substituiu e quantos anexos) e o
     `:resumo` ({:nao-lidos :pendentes}). Com `marcar?`, grava `recebido` dos itens entregues que ainda nao tinham.")
  (registrar-ciencia! [this ente-id id identidade-id]
    "Grava `ciente` (e `lido`/`recebido` se faltarem) e devolve as marcas da pessoa. Nao grava nada se a pessoa nao
     esta' na lista ou o comunicado nao pede ciencia (o chamador ja' conferiu; o banco tambem recusa).")
  (enviados [this ente-id remetente-id]
    "Os enviados pela pessoa (ou os da Casa, `remetente-id` nil), com as contagens, os destinos e quem substituiu.")
  (leitura [this ente-id id] "Uma linha por destinatario, com o caminho e a hora de cada marca.")
  (anexar! [this ente-id comunicado-id anexo limite]
    "Grava o anexo serializando os do mesmo comunicado; ja' com `limite` anexos -> :conflito/anexos-demais.")
  (anexo [this ente-id comunicado-id anexo-id] "O anexo do comunicado, ou nil."))

(defn- hidratar [tx ente-id c]
  (when c
    (let [id (:id c)]
      (assoc c
             :destinos (get (db/destinos-de tx ente-id [id]) id [])
             :anexos (db/anexos-de tx ente-id id)
             :substitui (when-let [s (:substitui-id c)]
                          {:id s :protocolo (get (db/protocolos tx ente-id [s]) s)})
             :substituido-por (get (db/substituido-por tx ente-id [id]) id)
             :n-destinatarios (db/contar-destinatarios tx ente-id id)))))

(defrecord RepoComunicacaoPg [datasource]
  RepoComunicacao
  (enviar! [_ ente-id c destinos destinatarios]
    (try
      (tenancy/com-tenant* (:ds datasource) ente-id
        (fn [tx]
          (let [id (db/inserir! tx (assoc c :ente-id ente-id) destinos destinatarios)]
            (hidratar tx ente-id (db/buscar tx ente-id id)))))
      (catch PSQLException e
        ;; 23505 de `uq_comunicado_substituido_uma_vez`: outro comunicado ja' substituiu este (o catch fica fora da tx)
        (if (and (= "23505" (.getSQLState e)) (re-find #"uq_comunicado_substituido_uma_vez" (str (.getMessage e))))
          (throw (ex-info "este comunicado ja' foi substituido" {:tipo :conflito/ja-substituido}))
          (throw e)))))
  (comunicado [_ ente-id id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(hidratar % ente-id (db/buscar % ente-id id))))
  (abrir! [_ ente-id id identidade-id marcar?]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (when-let [c (db/buscar tx ente-id id)]
          (let [d (when identidade-id (db/destinatario tx ente-id id identidade-id))]
            (when (and d marcar?) (db/marcar! tx ente-id identidade-id [id] "lido"))
            (assoc (hidratar tx ente-id c)
                   :destinatario d
                   :minhas-marcas (when d (get (db/marcas-da-pessoa tx ente-id identidade-id [id]) id {}))))))))
  (caixa! [_ ente-id identidade-id marcar?]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (let [itens (db/caixa tx ente-id identidade-id db/teto-da-lista)
              ids (mapv :id itens)
              _ (when marcar? (db/marcar! tx ente-id identidade-id ids "recebido"))
              marcas (db/marcas-da-pessoa tx ente-id identidade-id ids)
              subst (db/substituido-por tx ente-id ids)
              n-anexos (db/contar-anexos tx ente-id ids)]
          {:itens (mapv (fn [c] (assoc c :minhas-marcas (get marcas (:id c) {})
                                       :substituido-por (get subst (:id c))
                                       :n-anexos (get n-anexos (:id c) 0)))
                        itens)
           :resumo (db/resumo-da-caixa tx ente-id identidade-id)}))))
  (registrar-ciencia! [_ ente-id id identidade-id]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (db/marcar! tx ente-id identidade-id [id] "ciente")
        (get (db/marcas-da-pessoa tx ente-id identidade-id [id]) id {}))))
  (enviados [_ ente-id remetente-id]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (let [cs (db/enviados tx ente-id remetente-id db/teto-da-lista)
              ids (mapv :id cs)
              destinos (db/destinos-de tx ente-id ids)
              subst (db/substituido-por tx ente-id ids)]
          (mapv #(assoc % :destinos (get destinos (:id %) []) :substituido-por (get subst (:id %))) cs)))))
  (leitura [_ ente-id id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/leitura % ente-id id)))
  (anexar! [_ ente-id comunicado-id a limite]
    (tenancy/com-tenant* (:ds datasource) ente-id
      (fn [tx]
        (db/travar-anexos! tx ente-id comunicado-id)
        (when (>= (long (get (db/contar-anexos tx ente-id [comunicado-id]) comunicado-id 0)) (long limite))
          (throw (ex-info "o comunicado ja' tem o maximo de anexos" {:tipo :conflito/anexos-demais :limite limite})))
        (db/inserir-anexo! tx (assoc a :ente-id ente-id :comunicado-id comunicado-id)))))
  (anexo [_ ente-id comunicado-id anexo-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/anexo % ente-id comunicado-id anexo-id))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (map->RepoComunicacaoPg {}))
