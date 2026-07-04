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
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.transparencia.db.acompanhamento :as db-acompanhamento]
            [oplenario.transparencia.db.artefato-publicacao :as db-artefato]
            [oplenario.transparencia.db.materia :as db-materia]
            [oplenario.transparencia.db.norma :as db-norma]
            [oplenario.transparencia.events.notificacao :as ev-notif]
            [oplenario.transparencia.logic.notificacao :as logic-notif])
  (:import (java.time Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def ^:private teto-fanout
  "Teto de seguidores notificados POR transicao (anti unbounded — uma materia MUITO seguida nao pode explodir
  a tx do relay COMPARTILHADO com N emissoes). TRADEOFF (review database MEDIUM): e' uma ordem de grandeza
  acima dos tetos de LEITURA do modulo (100/200/500) DE PROPOSITO — cobertura de ENTREGA (cada seguidor que
  consentiu deve ser notificado) pesa mais que uma listagem de UI; um teto baixo dropa-silenciosamente cidadaos
  que consentiram, pior que uma tx um pouco mais longa. O custo (ate' 5000 INSERTs sequenciais num-por-um na tx
  do relay) e' REAL mas BOUNDADO: o caso tipico e' <10 seguidores, e o relay usa SKIP LOCKED (outros eventos
  nao ficam bloqueados — so' este evento-gatilho demora). CARRY (o fix estrutural): (a) paginacao por cursor
  (seguidor_identidade_id, retomavel entre transicoes) elimina o teto e a tx longa; (b) `EventBus/emitir!`
  aceitar >1 evento (INSERT multi-linha) corta os N round-trips — ambos YAGNI ate' uma materia real exceder."
  5000)

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
  tenant (sem trocar de role — ver docstring do ns) e escreve em transparencia.materia/norma/artefato_publicacao.
  `payload` ja chegou com chaves KEYWORD kebab (outbox/jsonb-> usa keyword-keys-object-mapper), casando 1:1 com
  o que os producers de legislativo construiram (events/{proposicao,norma,artefato-publicacao}.clj) — EXCETO os
  campos :uuid e os de tempo (:publicado-em/:criado-em), que chegam como string (ver `uuid-payload` e a
  re-parseacao Instant/parse; docstring de events/norma)."
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
                              (update :publicado-em #(Instant/parse %))))

    "artefato.publicacao.gerado"
    (db-artefato/inserir! tx (-> payload
                                 (uuid-payload [:norma-id :artefato-id])
                                 (assoc :ente-id ente-id)
                                 (update :criado-em #(Instant/parse %))))))

(defn fan-out-notificacao!
  "Consumer do FAN-OUT (F7 E2) — SEGUNDO consumidor de `proposicao.transicionou` (o 1o, projetar-evento!,
  atualiza materia.estado; ESTE notifica os seguidores). Consumidores independentes = dedup independente por
  (consumidor, key), cada um na tx do relay. Numa transicao de materia acompanhada, EMITE um
  `notificacao.requisitada` POR seguidor ATIVO — event-chaining no MESMO outbox/tx (§22.9 E2: as linhas novas
  commitam com o dedup e o relay as drena nas iteracoes seguintes de `drenar!`); `paineis` materializa a
  entrega duravel. RENDERIZA aqui (transparencia tem a materia same-schema; paineis e' entrega burra §22.10).
  Usa `payload.para` (o novo estado, autoritativo do evento) — NAO materia.estado — logo INDEPENDE da ORDEM
  entre este consumer e projetar-evento! na mesma tx.

  NUNCA lanca (relay COMPARTILHADO — mesmo racional de projetar-evento!): try/catch Throwable envolve TUDO,
  INCLUSIVE `set-tenant!` (review clojure/security MEDIUM: `set-tenant!` lanca em ente-id nil — e a coluna
  shared.outbox.ente_id e' NULLABLE, um evento supratenant/malformado propagaria a excecao ao relay se ficasse
  fora do try; aqui um ente nil e' TOLERADO = log + nil, o evento e' drenado sem envenenar o bus de todos os
  modulos). TOLERANTE a gap: sem materia projetada (redrive fora de ordem — mas um seguidor so' existe se a
  materia foi exibida) ou sem seguidores -> nil (nada a emitir).

  SEMANTICA DE FALHA (review database/security LOW/MEDIUM): os payloads por-seguidor sao UNIFORMES (so' variam
  destinatario/chave, ambos derivados de UUIDs) e validados por Malli `:closed` ANTES de emitir, entao uma
  falha APP-LEVEL no meio do `doseq` e' praticamente impossivel; uma falha de DRIVER/DB no INSERT do outbox
  aborta a tx INTEIRA (o `UPDATE processed_at` seguinte do relay falha na tx abortada -> rollback total ->
  o evento-gatilho re-drena, re-emitindo TODOS os seguidores — nunca um subconjunto commitado). Ou seja: nao
  ha caminho realista de fan-out PARCIAL-e-commitado; e' tudo-ou-nada por transicao (a 1a emissao so' ocorre
  apos materia+seguidores lidos com sucesso, e o commit e' atomico com o processed_at do evento-gatilho)."
  [tx {:keys [ente-id payload]}]
  (try
    (tenancy/set-tenant! tx ente-id)
    (let [pid  (UUID/fromString (:proposicao-id payload))
          para (:para payload)
          tid  (:transicao-id payload)]
      (when-let [materia (db-materia/buscar tx ente-id pid)]
        (let [{:keys [assunto corpo]} (logic-notif/renderizar materia para)]
          (doseq [dest (db-acompanhamento/seguidores-ativos tx ente-id pid teto-fanout)
                  :let [dest-str (str dest)]]
            (eventos/emitir! (outbox/bus) tx
              (ev-notif/requisitada
               ente-id
               {:destinatario-identidade-id dest-str
                :canal "email"
                :consent-base "acompanhamento"
                :idempotency-key (logic-notif/chave-idempotencia tid dest-str)
                :assunto assunto
                :corpo corpo
                :objeto-tipo "proposicao"
                :objeto-id (str pid)}))))))
    (catch Throwable e
      (log/warn e "transparencia: fan-out de notificacao tolerado (payload malformado ou falha de leitura)"
                {:ente-id ente-id})
      nil)))

(defprotocol RepoTransparencia
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant (com-tenant*) — leitura publica.")
  (buscar-materia [this ente-id proposicao-id] "Ficha PUBLICA de uma materia, ou nil.")
  (listar-materias [this ente-id estados-excluidos] "Portal: materias fora dos `estados-excluidos`.")
  (buscar-norma [this ente-id norma-id] "Uma norma publicada por id, ou nil.")
  (norma-da-materia [this ente-id proposicao-id] "A norma publicada de uma materia, ou nil.")
  (listar-normas [this ente-id filtro] "Portal: acervo as-enacted, com filtro opcional {:tipo :ano :numero} (ver db/norma/listar).")
  ;; F6c Slice 4b — artefato de publicacao oficial (PROJECAO; a rota publica de download resolve o ponteiro daqui)
  (artefato-mais-recente-da-norma [this ente-id norma-id]
    "Ponteiro do artefato de publicacao MAIS RECENTE de uma norma (objeto_store_ref + content_type + versao), ou nil.")
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
  (listar-normas [this ente-id filtro] (transacao this ente-id #(db-norma/listar % ente-id filtro)))
  (artefato-mais-recente-da-norma [this ente-id norma-id]
    (transacao this ente-id #(db-artefato/mais-recente-por-norma % ente-id norma-id)))
  (seguir! [this ente-id m] (transacao this ente-id #(db-acompanhamento/seguir! % (assoc m :ente-id ente-id))))
  (deixar-de-seguir! [this ente-id m] (transacao this ente-id #(db-acompanhamento/deixar-de-seguir! % (assoc m :ente-id ente-id))))
  (meus-acompanhamentos [this ente-id sid] (transacao this ente-id #(db-acompanhamento/meus-da-materia % ente-id sid))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoTransparenciaPg nil))
