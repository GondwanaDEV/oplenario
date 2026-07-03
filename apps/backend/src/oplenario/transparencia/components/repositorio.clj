(ns oplenario.transparencia.components.repositorio
  "Component de PERSISTENCIA do transparencia — banco disponibilizado como Stuart Sierra Component (ADR-0001
  §3-bis). O db/ de um modulo so' e' importado por ESTE component (regra do import-lint, arquitetura-test);
  logo TANTO a escrita de PROJECAO (chamada pelo consumer, dentro da tx do relay) QUANTO a LEITURA publica
  (chamada pelo controller, via com-tenant*) moram aqui.

  `projetar-evento!` e' funcao PLANA (nao um metodo do protocolo/record) — o consumer roda dentro da tx do
  relay, que ja' e' a `tx`; nao ha datasource a abrir (`com-tenant*` seria redundante e trocaria o role, o que
  quebraria o UPDATE seguinte do relay em shared.outbox — ver docstring de consumer.clj). So' seta o GUC
  app.ente_id (kernel.tenancy/set-tenant!) e despacha para db/materia ou db/norma.

  O protocolo RepoTransparencia (record, `using` :datasource) serve SO a LEITURA do portal — as rotas
  publicas abrem `com-tenant*` com o `ente-id` resolvido de `resolver-ente-publico` (RLS isola).

  TOLERANCIA A GAP DE PROJECAO (review architect HIGH-1): `atualizar-estado!` NAO lanca quando a materia
  ainda nao foi projetada — devolve nil, e este ns so' LOGA um warning (nunca lanca daqui). O relay e' UM SO,
  compartilhado por TODOS os modulos consumidores; um handler que lanca faz o MESMO evento ser reprocessado
  a cada tick para sempre, bloqueando HEAD-OF-LINE todo evento de id maior no bus inteiro (nao so' desta
  projecao). Perder uma atualizacao de estado numa projecao (sem verdade propria, re-derivavel) e' um preco
  aceitavel; travar o barramento do sistema inteiro nao e'."
  (:require [clojure.tools.logging :as log]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.transparencia.db.acompanhamento :as db-acompanhamento]
            [oplenario.transparencia.db.materia :as db-materia]
            [oplenario.transparencia.db.norma :as db-norma])
  (:import (java.time Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- uuid-payload
  "Coage `chaves` de `payload` de STRING p/ java.util.UUID. NECESSARIO: o outbox serializa o payload em
  jsonb (jsonista) — um java.util.UUID no evento vira string JSON na ida e volta STRING na leitura (jsonb->
  nao tem modulo UUID); um valor string bindado contra uma coluna `uuid` do Postgres lanca (driver nao
  cast implicito: 'column is of type uuid but expression is of type character varying'). `ente-id` do
  envelope NAO precisa disto — vem de uma coluna SQL nativa (outbox.ente_id), nunca do jsonb."
  [payload chaves]
  (reduce (fn [m k] (cond-> m (contains? m k) (update k #(UUID/fromString %)))) payload chaves))

(defn projetar-evento!
  "Dispatch por tipo de evento -> a projecao de dominio, DENTRO da `tx` corrente (a do relay). Seta o GUC de
  tenant (sem trocar de role — ver docstring do ns) e escreve em transparencia.materia/norma. `payload` ja
  chegou com chaves KEYWORD kebab (outbox/jsonb-> usa keyword-keys-object-mapper), casando 1:1 com o que os
  producers de legislativo construiram (events/{proposicao,norma}.clj) — EXCETO os campos :uuid, que chegam
  como string (ver docstring de `uuid-payload`)."
  [tx {:keys [tipo ente-id payload]}]
  (tenancy/set-tenant! tx ente-id)
  (case tipo
    "proposicao.protocolada"
    (db-materia/inserir! tx (-> payload (uuid-payload [:proposicao-id]) (assoc :ente-id ente-id)))

    "proposicao.transicionou"
    (let [pid (UUID/fromString (:proposicao-id payload))]
      (or (db-materia/atualizar-estado! tx {:ente-id ente-id :proposicao-id pid :estado (:para payload)})
          (log/warn "transparencia: proposicao.transicionou sem materia projetada (protocolada ausente?)"
                    {:ente-id ente-id :proposicao-id pid :para (:para payload)})))

    "norma.publicada"
    (db-norma/inserir! tx (-> payload
                              (uuid-payload [:norma-id :proposicao-id])
                              (assoc :ente-id ente-id)
                              (update :publicado-em #(Instant/parse %))))))

(defprotocol RepoTransparencia
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant (com-tenant*) — leitura publica.")
  (buscar-materia [this ente-id proposicao-id] "Ficha PUBLICA de uma materia, ou nil.")
  (listar-materias [this ente-id estados-excluidos] "Portal: materias fora dos `estados-excluidos`.")
  (buscar-norma [this ente-id norma-id] "Uma norma publicada por id, ou nil.")
  (norma-da-materia [this ente-id proposicao-id] "A norma publicada de uma materia, ou nil.")
  (listar-normas [this ente-id] "Portal: legislacao PUBLICADA as-enacted (nao 'consolidada' — ver db/norma).")
  ;; F6c Slice 2 — acompanhamento do cidadao (escritas autenticadas; consent-gated)
  (seguir! [this ente-id m] "UPSERT: cidadao segue a materia (re-seguir reativa). Devolve {:id :estado ...}.")
  (deixar-de-seguir! [this ente-id m] "Soft-cancel idempotente. Devolve {:id} se cancelou, ou nil (no-op).")
  (meus-acompanhamentos [this ente-id seguidor-identidade-id] "Materias que o cidadao segue (ativas, c/ cabecalho)."))

(defrecord RepoTransparenciaPg [datasource]
  RepoTransparencia
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (buscar-materia [this ente-id pid] (transacao this ente-id #(db-materia/buscar % ente-id pid)))
  (listar-materias [this ente-id excl] (transacao this ente-id #(db-materia/listar-em-tramitacao % ente-id excl)))
  (buscar-norma [this ente-id nid] (transacao this ente-id #(db-norma/buscar % ente-id nid)))
  (norma-da-materia [this ente-id pid] (transacao this ente-id #(db-norma/buscar-por-proposicao % ente-id pid)))
  (listar-normas [this ente-id] (transacao this ente-id #(db-norma/listar-publicadas % ente-id)))
  (seguir! [this ente-id m] (transacao this ente-id #(db-acompanhamento/seguir! % (assoc m :ente-id ente-id))))
  (deixar-de-seguir! [this ente-id m] (transacao this ente-id #(db-acompanhamento/deixar-de-seguir! % (assoc m :ente-id ente-id))))
  (meus-acompanhamentos [this ente-id sid] (transacao this ente-id #(db-acompanhamento/meus-da-materia % ente-id sid))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoTransparenciaPg nil))
