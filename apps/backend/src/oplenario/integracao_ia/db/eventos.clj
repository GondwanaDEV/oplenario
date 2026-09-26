(ns oplenario.integracao-ia.db.eventos
  "Persistencia da fronteira core <-> IA (mig 0085, ADR-0008). `evento_saida` e' o FEED supratenant (lido pelo
  satelite via rota de servico, sem RLS, como `shared.outbox`); `evento_entrada` e' a caixa de entrada (dedup +
  auditoria), gravada na tx do tenant do evento junto com o efeito."
  (:require [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(defn- jsonb [m]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-value-as-string m))))

(defn- jsonb-> [^PGobject o]
  (when o (json/read-value (.getValue o) json/keyword-keys-object-mapper)))

(defn inserir-saida!
  "Publica um evento de integracao no feed. Idempotente pela `chave` (promover de novo nao duplica). Devolve o
  `seq` do evento novo, ou nil quando ja existia."
  [tx {:keys [ente-id tipo versao chave payload]}]
  (some-> (jdbc/execute-one! tx
            ["INSERT INTO integracao_ia.evento_saida (ente_id, tipo, versao, chave, payload)
              VALUES (?, ?, ?, ?, ?) ON CONFLICT (chave) DO NOTHING RETURNING seq"
             ente-id tipo versao chave (jsonb payload)])
          :evento_saida/seq))

(defn listar-saida
  "Os eventos com `seq` > `depois`, em ordem, no maximo `limite`."
  [tx depois limite]
  (->> (jdbc/execute! tx ["SELECT seq, ente_id, tipo, versao, chave, payload, criado_em
                           FROM integracao_ia.evento_saida WHERE seq > ? ORDER BY seq LIMIT ?"
                          depois limite])
       (mapv (fn [r] (-> (comum/linha->kebab r) (update :payload jsonb->))))))

(defn registrar-entrada!
  "Registra um evento recebido da IA. true = novo (aplicar o efeito); false = a `chave` ja' chegou antes."
  [tx {:keys [ente-id tipo versao chave correlation-id bruto]}]
  (some? (jdbc/execute-one! tx
           ["INSERT INTO integracao_ia.evento_entrada (ente_id, tipo, versao, chave, correlation_id, payload)
             VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (chave) DO NOTHING RETURNING id"
            ente-id tipo versao chave correlation-id (jsonb bruto)])))
