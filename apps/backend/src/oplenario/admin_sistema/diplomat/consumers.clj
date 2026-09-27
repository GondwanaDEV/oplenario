(ns oplenario.admin-sistema.diplomat.consumers
  "Inbound (ADR-0016): o handoff do provisionamento. Quando o 1o administrador da Casa entra pela primeira vez
  (`identidade.vinculo.primeiro_acesso` com o papel `admin_ente`), o registro passa a Casa de 'provisionar' a 'ativo'
  e sela na atuacao. O tipo e' STRING LITERAL (contrato de fiacao do bus, §22.10 — sem importar `identidade`).
  Tolerante: um payload inesperado vira log, nunca derruba o relay compartilhado."
  (:require [clojure.tools.logging :as log]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.kernel.outbox :as outbox]))

(def ^:private nome-consumidor "admin-sistema-registro")

(def tipo-primeiro-acesso "identidade.vinculo.primeiro_acesso")

(defn ativar-no-primeiro-acesso! [tx {:keys [ente-id payload]}]
  (try
    (when (and ente-id (some #{"admin_ente"} (:papeis payload)))
      (repo/ativar-casa-em-tx! tx ente-id {:primeiro-admin (str (:identidade-id payload))}))
    (catch Exception e
      (log/warn e "admin-sistema: primeiro acesso nao processado" {:ente-id ente-id})
      nil)))

(defn registrar [registro]
  (outbox/registrar registro nome-consumidor tipo-primeiro-acesso ativar-no-primeiro-acesso!))
