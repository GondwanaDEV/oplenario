(ns oplenario.auditoria.components.repositorio
  "Repo-Component da trilha de auditoria (ADR-0017). Toda operacao numa tx de tenant (`com-tenant*`, RLS)."
  (:require [oplenario.auditoria.db.registro :as db]
            [oplenario.kernel.tenancy :as tenancy]))

(set! *warn-on-reflection* true)

(defprotocol RepoAuditoria
  (registrar! [this registro]
    "Acrescenta o registro a corrente da Casa (selado). Quando ele abre um dia novo, grava o selo do dia que fechou,
    anula os IPs de mais de 6 meses e garante as particoes dos proximos meses — na mesma tx. Devolve
    {:registro :dia-fechado}.")
  (trilha [this ente-id filtro limite] "{:registros :total} — uma pagina, mais recente primeiro.")
  (total [this ente-id] "Quantos registros a corrente da Casa tem.")
  (verificar [this ente-id] "{:integra :total :cabeca :quebra-em} — a corrente inteira conferida.")
  (selos-do-dia [this ente-id n] "Os ultimos `n` selos do dia da Casa."))

(defrecord RepoAuditoriaPg [datasource]
  RepoAuditoria
  (registrar! [_ registro]
    (tenancy/com-tenant* (:ds datasource) (:ente-id registro)
      (fn [tx]
        (let [{:keys [dia-fechado] :as res} (db/gravar! tx registro)]
          (when dia-fechado
            (db/registrar-selo-do-dia! tx (:ente-id registro) dia-fechado)
            (db/anular-ips-antigos! tx (:ente-id registro))
            (db/garantir-particoes! tx))
          res))))
  (trilha [_ ente-id filtro limite]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/listar % ente-id filtro limite)))
  (total [_ ente-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/total-da-casa % ente-id)))
  (verificar [_ ente-id]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/verificar % ente-id)))
  (selos-do-dia [_ ente-id n]
    (tenancy/com-tenant* (:ds datasource) ente-id #(db/selos-diarios % ente-id n))))

(defn repositorio [] (map->RepoAuditoriaPg {}))
