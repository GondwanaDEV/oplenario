(ns oplenario.paineis.components.repositorio
  "Component de PERSISTENCIA do paineis — banco disponibilizado como Stuart Sierra Component (ADR-0001
  §3-bis). O db/ de um modulo so' e' importado por ESTE component (regra do import-lint, arquitetura-test);
  logo TANTO a escrita de PROJECAO (chamada pelo consumer, dentro da tx do relay) QUANTO a LEITURA interna
  (chamada pelo controller, via com-tenant*) moram aqui.

  `projetar-evento!` e' funcao PLANA (nao um metodo do protocolo/record) — o consumer roda dentro da tx do
  relay, que ja' e' a `tx`; nao ha datasource a abrir (`com-tenant*` seria redundante e trocaria o role, o
  que quebraria o UPDATE seguinte do relay em shared.outbox — mesmo racional de transparencia/repositorio).
  So' seta o GUC app.ente_id (kernel.tenancy/set-tenant!) e despacha para db/pendencia (F7 Slice 1, 'o que
  vence') OU db/tramitacao (F7 Slice 2, board interno) conforme o tipo do evento.

  Os TIPOS de evento sao STRINGS LITERAIS, NAO imports de `participacao.events.*` — §22.10 proibe import
  cross-modulo; o nome do evento e' o CONTRATO DE FIACAO do bus, nao um tipo compartilhado (mesmo padrao de
  transparencia/diplomat/consumers).

  TOLERANCIA A GAP DE PROJECAO (mesmo racional de transparencia/db/materia/atualizar-estado!, review
  architect HIGH-1): fechamento/vencimento/prorrogacao NUNCA lancam quando a pendencia ainda nao foi
  projetada — devolvem nil, e este ns so' LOGA um warning. O relay e' UM SO, compartilhado por TODOS os
  modulos consumidores; um handler que lanca faz o MESMO evento ser reprocessado a cada tick para sempre,
  bloqueando HEAD-OF-LINE todo evento de id maior no bus inteiro (nao so' desta projecao).

  TOLERANCIA A PAYLOAD MALFORMADO (review security HIGH): `projetar-evento!` envolve `despachar!` (o `case`
  de fato, funcao separada — ver sua docstring) num try/catch — `UUID/fromString`/`LocalDate/parse`/
  `Instant/parse` lancam em string invalida ANTES de qualquer SQL rodar (avaliacao de argumento precede a
  chamada; nenhum efeito
  parcial no banco quando o parse falha), entao capturar aqui e' seguro (a tx do relay nao e' abortada — nao
  houve comando SQL nesta branch). Sem este guard, um payload malformado (ex.: um produtor futuro de
  `participacao` gravando 'vence-em' num formato errado — os schemas Malli de origem tipam a data como
  :string cru, nao um padrao ISO validado) lancaria IllegalArgumentException/DateTimeParseException DENTRO
  da tx compartilhada e envenenaria o relay do sistema INTEIRO exatamente como um `throw` de dominio — o
  guard estrutural de marcar-concluida!/marcar-vencida!/atualizar-vence-em! (WHERE condicional, nunca lanca)
  so' cobre 'nao encontrado', nao 'payload nao parseavel'."
  (:require [clojure.tools.logging :as log]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.paineis.components.notificacao :as porta]
            [oplenario.paineis.db.notificacao-caixa :as db-caixa]
            [oplenario.paineis.db.notificacao-entrega :as db-notificacao]
            [oplenario.paineis.db.pendencia :as db-pendencia]
            [oplenario.paineis.db.sli-sessao :as db-sli-sessao]
            [oplenario.paineis.db.tramitacao :as db-tramitacao])
  (:import (java.time Instant LocalDate)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def ^:private teto-o-que-vence 100)
;; teto POR GRUPO de estado (review database HIGH, F7 Slice 2) — ver docstring de db.tramitacao/listar-board.
(def ^:private teto-tramitacao-board-por-estado 50)
(def ^:private teto-sli-sessoes 200)

(defn- protocolar!
  "Aplica inserir! p/ uma das 4 especies de participacao — extrai o campo comum entre os 4 branches
  'protocolad[oa]' (review clojure MEDIUM: eram copy-paste identico exceto objeto-tipo/id-key)."
  [tx ente-id objeto-tipo id-str protocolo vence-em-str]
  (db-pendencia/inserir! tx {:ente-id ente-id :objeto-tipo objeto-tipo
                             :objeto-id (UUID/fromString id-str)
                             :protocolo protocolo :vence-em (LocalDate/parse vence-em-str)}))

(defn- fechar!
  "Aplica marcar-concluida! e loga se a pendencia ainda nao existia (redrive fora de ordem / backlog)."
  [tx ente-id objeto-tipo objeto-id-str]
  (or (db-pendencia/marcar-concluida! tx {:ente-id ente-id :objeto-tipo objeto-tipo
                                          :objeto-id (UUID/fromString objeto-id-str)})
      (log/warn "paineis: fechamento sem pendencia projetada (protocolo ausente?)"
                {:ente-id ente-id :objeto-tipo objeto-tipo :objeto-id objeto-id-str})))

(defn- transicionar-tramitacao!
  "Aplica atualizar-estado! do board e loga se a materia ainda nao existia (redrive fora de ordem / backlog
  — mesmo racional de fechar! e de transparencia/db/materia/atualizar-estado!). `ocorrido-em-str` (F7 carry):
  o instante REAL da transicao (do evento, nao 'agora') — ver docstring de db.tramitacao/atualizar-estado!."
  [tx ente-id proposicao-id-str estado ocorrido-em-str]
  (or (db-tramitacao/atualizar-estado! tx {:ente-id ente-id :proposicao-id (UUID/fromString proposicao-id-str)
                                           :estado estado :transicionou-em (Instant/parse ocorrido-em-str)})
      (log/warn "paineis: transicao sem materia projetada no board (protocolo ausente?)"
                {:ente-id ente-id :proposicao-id proposicao-id-str :estado estado})))

(def ^:private canal-email
  "O UNICO canal que o ledger de ENTREGA (notificacao_entrega) materializa. Onda E fatia 1: o mesmo evento
  `notificacao.requisitada` passou a carregar tambem `in_app` (inbox interna, projetada por
  `projetar-inbox!` numa tabela propria). Cada projetor trata APENAS o seu canal (spec §4.3); sem esta
  guarda o worker `entregar-pendentes!` tentaria enviar e-mail de uma notificacao que nunca teve endereco."
  "email")

(defn- registrar-intent-de-email!
  "Materializa o intent de entrega SO' quando o canal e' 'email'. Outro canal -> log/debug + nil (nunca
  lanca, nunca grava): nao e' erro, e' um evento endereçado a OUTRO projetor do mesmo modulo."
  [tx ente-id payload]
  (if (= canal-email (:canal payload))
    (db-notificacao/registrar-intent! tx {:ente-id ente-id
                                          :destinatario (:destinatario-identidade-id payload)
                                          :canal (:canal payload)
                                          :idempotency-key (:idempotency-key payload)
                                          :consent-base (:consent-base payload)
                                          :assunto (:assunto payload) :corpo (:corpo payload)
                                          :objeto-tipo (:objeto-tipo payload)
                                          :objeto-id (UUID/fromString (:objeto-id payload))})
    (do (log/debug "paineis: notificacao de outro canal ignorada pelo ledger de e-mail"
                   {:ente-id ente-id :canal (:canal payload)})
        nil)))

(defn despachar!
  "O `case` de fato, SEM tolerancia — lanca em tipo sem branch (`case` sem default: 'No matching clause') OU
  em payload malformado (UUID/LocalDate invalidos). PUBLICA (nao `defn-`) DE PROPOSITO: e' o alvo direto do
  drift-guard de teste (`todo-tipo-consumido-tem-branch-de-projecao`), que precisa distinguir 'tipo sem
  branch' (bug de programador — deve ficar RUIDOSO, pego em CI antes de subir) de 'payload malformado em
  runtime' (dado externo — deve ser TOLERADO). `projetar-evento!` (abaixo) e' quem envolve ESTA fn num
  try/catch p/ a tolerancia de runtime; se o catch estivesse AQUI, um 'No matching clause' de um tipo
  registrado sem branch seria silenciosamente engolido (nunca propagaria ao teste), mascarando o drift em
  vez de barra-lo (review security HIGH, cuidado ao aplicar o fix)."
  [tx ente-id tipo payload]
  (case tipo
    "participacao.pedido_esic.protocolado"
    (protocolar! tx ente-id "pedido_esic" (:pedido-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.recurso_esic.protocolado"
    (protocolar! tx ente-id "recurso_esic" (:recurso-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.solicitacao_titular.protocolada"
    (protocolar! tx ente-id "solicitacao_titular" (:solicitacao-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.manifestacao_ouvidoria.protocolada"
    (protocolar! tx ente-id "manifestacao_ouvidoria" (:manifestacao-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.pedido_esic.respondido"
    (fechar! tx ente-id "pedido_esic" (:pedido-id payload))

    "participacao.recurso_esic.decidido"
    (fechar! tx ente-id "recurso_esic" (:recurso-id payload))

    "participacao.solicitacao_titular.respondida"
    (fechar! tx ente-id "solicitacao_titular" (:solicitacao-id payload))

    "participacao.manifestacao_ouvidoria.respondida"
    (fechar! tx ente-id "manifestacao_ouvidoria" (:manifestacao-id payload))

    "participacao.manifestacao_ouvidoria.arquivada"
    (fechar! tx ente-id "manifestacao_ouvidoria" (:manifestacao-id payload))

    "participacao.prazo.vencido"
    (or (db-pendencia/marcar-vencida! tx {:ente-id ente-id :objeto-tipo (:objeto-tipo payload)
                                          :objeto-id (UUID/fromString (:objeto-id payload))})
        (log/warn "paineis: vencimento sem pendencia projetada" {:ente-id ente-id :payload payload}))

    "participacao.prazo.prorrogado"
    (or (db-pendencia/atualizar-vence-em! tx {:ente-id ente-id :objeto-tipo (:objeto-tipo payload)
                                              :objeto-id (UUID/fromString (:objeto-id payload))
                                              :vence-em (LocalDate/parse (:para-data payload))})
        (log/warn "paineis: prorrogacao sem pendencia projetada" {:ente-id ente-id :payload payload}))

    "proposicao.protocolada"
    (db-tramitacao/inserir! tx {:ente-id ente-id :proposicao-id (UUID/fromString (:proposicao-id payload))
                                :tipo (:tipo payload) :ano (:ano payload) :sequencial (:sequencial payload)
                                :urn-lex (:urn-lex payload) :ementa (:ementa payload)
                                :autor-tipo (:autor-tipo payload) :autor-texto (:autor-texto payload)
                                :estado (:estado payload)})

    "proposicao.transicionou"
    (transicionar-tramitacao! tx ente-id (:proposicao-id payload) (:para payload) (:ocorrido-em payload))

    ;; F7 E3: projeta o ciclo de vida da SESSAO plenaria (F4) na vista de SLI de janela de sessao (Inv.9). O
    ;; UPSERT e' idempotente + monotonico (ver db/sli-sessao/projetar-transicao!); a 1a transicao de uma
    ;; sessao INSERE, as seguintes atualizam a janela/estado. `:ocorrido-em` chega STRING ISO -> Instant.
    "sessao.transicionou"
    (db-sli-sessao/projetar-transicao! tx {:ente-id ente-id :sessao-id (UUID/fromString (:sessao-id payload))
                                           :para (:para payload) :ocorrido-em (Instant/parse (:ocorrido-em payload))})

    ;; F7 E3 (carry): o NASCIMENTO da sessao (`sessao.agendada`) materializa a linha ja' no agendamento, p/ o
    ;; SLI enxergar o no-show silencioso. `:agendada-para` opcional (some-> tolera nil). Mesmo UPSERT/gate.
    "sessao.agendada"
    (db-sli-sessao/projetar-agendamento! tx {:ente-id ente-id :sessao-id (UUID/fromString (:sessao-id payload))
                                             :agendada-para (some-> (:agendada-para payload) Instant/parse)
                                             :ocorrido-em (Instant/parse (:ocorrido-em payload))})

    ;; F7 E2: materializa o INTENT de entrega (estado 'pendente') a partir do fan-out de transparencia. NADA
    ;; e' enviado aqui (anti dual-write — ver db.notificacao-entrega); o worker (entregar-pendentes!) envia
    ;; depois. `objeto-id` chega STRING (jsonb) -> UUID; `destinatario` fica STRING (identidade-uuid como texto,
    ;; coluna `destinatario text`). Idempotencia da entrega pela chave DETERMINISTICA do payload (ON CONFLICT).
    ;; Onda E fatia 1: ROTEAMENTO POR CANAL — este branch cuida SO' de `email`. A inbox (`in_app`) e' um
    ;; SEGUNDO consumidor registrado (`paineis-inbox`, `projetar-inbox!`), com dedup independente.
    "notificacao.requisitada"
    (registrar-intent-de-email! tx ente-id payload)))

(defn projetar-evento!
  "Dispatch por tipo de evento -> a projecao de dominio, DENTRO da `tx` corrente (a do relay). Seta o GUC de
  tenant (sem trocar de role) e escreve em paineis.pendencia ou paineis.tramitacao. `payload` ja chegou com
  chaves KEYWORD kebab (outbox/jsonb-> usa keyword-keys-object-mapper) — EXCETO os campos :uuid e os de
  data (:vence-em/:para-data), que chegam como string (ver docstring de participacao/events/*). o ENTRY
  POINT REAL do consumer (§22.10 diplomat/consumers) — NUNCA lanca (review security HIGH — ver docstring do
  ns): envolve `despachar!` inteiro num try/catch, entao QUALQUER excecao de `despachar!` e' tolerada aqui
  (log + nil) — payload malformado (UUID/LocalDate invalidos) OU um tipo sem branch de dispatch (drift
  bus<->case), sem distincao (ambos sao igualmente inaceitaveis dentro do relay compartilhado em producao).
  A distincao entre as duas causas so' importa p/ o TESTE do drift-guard, que por isso chama `despachar!`
  DIRETO (nao este fn) — assim o drift ainda e' pego RUIDOSAMENTE em CI, antes de qualquer deploy chegar a
  rodar este caminho tolerante contra trafego real.

  CATCH Throwable, NAO Exception (review security HIGH, F7 Slice 2): as `:pre` de db/pendencia.clj e
  db/tramitacao.clj lancam `AssertionError` — um `Error`, IRMAO de `Exception` sob `Throwable`, NAO capturado
  por `(catch Exception ...)`. Um `:pre` falhando (payload com chave ausente/nil que uma validacao Malli
  upstream deveria ter barrado, mas §22.10 nao garante contrato compartilhado entre modulos) escaparia deste
  catch e envenenaria o relay exatamente como o caso que este ns existe p/ evitar. CARRY: o mesmo padrao de
  `:pre` (sem guarda de AssertionError) tambem existe em `transparencia.components.repositorio/
  projetar-evento!` — que ALEM DISSO nao tem NENHUM try/catch (nem de Exception): um alvo maior p/ correcao
  futura, fora do escopo deste modulo."
  [tx {:keys [tipo ente-id payload]}]
  (tenancy/set-tenant! tx ente-id)
  (try
    (despachar! tx ente-id tipo payload)
    (catch Throwable e
      (log/warn e "paineis: payload malformado ou falha de projecao — evento tolerado, nunca propaga p/ o relay compartilhado"
                {:tipo tipo :ente-id ente-id})
      nil)))

(def ^:private canal-in-app
  "O UNICO canal que a INBOX materializa (espelho de `canal-email`; spec §4.3)."
  "in_app")

(defn projetar-inbox!
  "Handler do SEGUNDO consumidor do modulo (`paineis-inbox`, mesma disciplina de
  `transparencia/fan-out-notificacao!`): projeta `notificacao.requisitada` de canal `in_app` na
  `paineis.notificacao_caixa`. Identidade de dedup SEPARADA da do projetor de entrega — cada um roda
  effectively-once por conta propria, entao a inbox nao depende da ordem nem do sucesso do ledger de e-mail.

  NUNCA lanca (o relay e' COMPARTILHADO por todos os modulos — um throw aqui trava a fila de todo mundo):
  try/catch Throwable envolve TUDO, INCLUSIVE `set-tenant!` (que lanca em ente-id nil, e a coluna
  shared.outbox.ente_id e' NULLABLE). `catch Throwable`, nao Exception: as `:pre` de db/ lancam
  AssertionError, que e' Error. Payload malformado (objeto-id nao-UUID) -> log + nil, evento drenado.

  Canal != in_app -> no-op silencioso (o evento e' de outro projetor, nao e' erro)."
  [tx {:keys [ente-id payload]}]
  (try
    (tenancy/set-tenant! tx ente-id)
    (when (= canal-in-app (:canal payload))
      (db-caixa/inserir! tx {:ente-id ente-id
                             :destinatario-identidade-id (UUID/fromString
                                                          (:destinatario-identidade-id payload))
                             ;; `categoria` e' OPCIONAL no contrato do evento (o produtor do cidadao nao a
                             ;; manda); um in-app sem categoria cai em "sistema" em vez de violar o NOT NULL.
                             :categoria (or (:categoria payload) "sistema")
                             :assunto (:assunto payload) :corpo (:corpo payload)
                             :objeto-tipo (:objeto-tipo payload)
                             :objeto-id (UUID/fromString (:objeto-id payload))
                             :idempotency-key (:idempotency-key payload)}))
    (catch Throwable e
      (log/warn e "paineis: projecao da inbox tolerada (payload malformado ou falha de escrita)"
                {:ente-id ente-id})
      nil)))

(defprotocol RepoPaineis
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant (com-tenant*) — leitura interna.")
  (o-que-vence [this ente-id opts]
    "Pendencias ABERTAS (pendente|vencido) do tenant, mais urgente primeiro + o TOTAL real (sem teto) —
     fatia 'GET /paineis/pendencias para de esconder prazo legal'. `opts` = {:limite} (TETO server-side,
     anti unbounded-read; o total IGNORA este teto de proposito, mesmo racional de
     compliance/components/repositorio/painel). Os dois reads (lista + total) rodam na MESMA tx e o total
     REUSA `db-pendencia/resumo` (o mesmo WHERE `estado IN ('pendente','vencido')` de `listar-abertas` —
     ver docstring de ambos em db/pendencia.clj), nunca uma contagem escrita a parte (evita uma 3a copia
     do predicado divergir em silencio). MESMA TX NAO E' MESMO SNAPSHOT — ver o CARRY DELIBERADO na
     docstring de `tramitacao-board` abaixo, identico aqui. Devolve {:pendencias [...] :pendencias-total N}.")
  (tramitacao-board [this ente-id]
    "O board de tramitacao do tenant, agrupado por estado, mais estagnadas primeiro DENTRO de cada grupo
     (fatia 'truncamento-familia'). `itens` corta no teto POR ESTADO (`teto-tramitacao-board-por-estado` —
     ver docstring de db.tramitacao/listar-board pro racional do corte ser por grupo, nao global) + o TOTAL
     real por estado (sem teto). O total REUSA `db-tramitacao/resumo` (MESMO predicado `WHERE ente_id = ?`
     da lista — o board nao filtra por estado, entao e' o mesmo WHERE inteiro, nao so' um prefixo dele;
     nunca uma 3a contagem escrita a parte). Devolve {:itens [...] :totais-por-estado [...]}.

     CARRY DELIBERADO (achado IMPORTANTE de revisao adversarial, mesmo overclaim ja documentado em
     transparencia/components/repositorio [perfil-parlamentar] e legislativo/components/repositorio
     [ficha-completa-da-proposicao]): os dois reads (lista + total) rodam na MESMA tx, mas NAO no MESMO
     SNAPSHOT MVCC — `com-tenant*` (kernel/tenancy) abre a tx sem `:isolation`, entao o nivel efetivo e'
     READ COMMITTED, em que CADA statement toma um snapshot novo. Se o relay commitar uma
     protocolada/transicao ENTRE os dois SELECTs, a lista e o total podem discordar por uma janela
     ESTREITA (ex.: o total ja conta uma proposicao que a lista, lida um instante antes, ainda nao viu) —
     auto-cura na proxima carga, nunca uma divergencia permanente. Corrigir exigiria um
     `com-tenant-leitura*` no kernel com `:isolation :repeatable-read :read-only true` — fora do escopo
     desta fatia (kernel COMPARTILHADO; consertar so' aqui criaria inconsistencia com os outros dois
     pares que tem o MESMO overclaim).")
  (sli-sessoes [this ente-id] "SLI de janela de sessao (Inv.9): sessoes do tenant, abertas primeiro, concluidas por recencia.")
  (dashboard-mesa [this ente-id]
    "Rollups do dashboard da Mesa (F7, §16.11 item 11.4): os TRES resumos agregados dos read-models do
    proprio paineis (tramitacao/pendencias/sessoes por estado), lidos numa UNICA tx do tenant. Devolve
    {:tramitacao [...] :pendencias [...] :sessoes [...]} (linhas GROUP BY cruas). NAO le' compliance — o card
    do TCE e' composto na borda (diplomat) via a fn injetada pelo host (inversao de dependencia, nunca
    reprojecao/JOIN cross-schema §22.10).")
  (entregar-pendentes! [this ente-id notificador]
    "WORKER de entrega (F7 E2): envia os intents 'pendente' do ledger pelo `notificador` (porta CanalNotificacao)
    e marca enviada/falha. Le' o lote numa tx; ENVIA fora de qualquer tx (efeito externo); marca cada intent
    numa tx CURTA propria (uma falha de envio nao desfaz as marcacoes das anteriores). Devolve {:processados n}.

    CONTRATO DE EXECUCAO — SINGLE-FLIGHT por ente (review database HIGH): a entrega e' AT-LEAST-ONCE por design
    (um crash pos-envio pre-marca reenvia; ledger.enviada e' best-effort). `listar-pendentes` NAO faz claim
    (sem FOR UPDATE SKIP LOCKED / estado 'enviando'), entao DUAS invocacoes CONCORRENTES p/ o MESMO ente leriam
    os mesmos intents e ENVIARIAM em duplicidade (so' o UPDATE final dedup, tarde demais). O worker DEVE rodar
    single-flight por ente — a mesma disciplina LIDER-UNICO que o relay do outbox ja' assume (sistema.clj: 'relay
    lider unico'); o agendador (carry infra) o invoca sob leader-election, nao concorrente. Escolhemos o design
    pendente-only (sem estado 'enviando') DE PROPOSITO: nao ha estado PRESO em crash (um crash deixa 'pendente',
    re-tentavel; um estado 'enviando' precisaria de um reaper/timeout = mais infra). REMEDIO de scale-out (se um
    dia N workers concorrentes forem necessarios): claim atomico `UPDATE ... SET estado='enviando' WHERE id IN
    (SELECT ... FOR UPDATE SKIP LOCKED LIMIT :teto) RETURNING *` + reaper de 'enviando' orfao. YAGNI ate' la'.
    Seu AGENDAMENTO (cron/loop + leader-election) e' carry infra — aqui a LOGICA de entrega, chamavel e testavel.")
  (minhas-notificacoes [this ente-id destinatario-identidade-id]
    "Inbox do PROPRIO ator (Onda E fatia 1): {:notificacoes [...] :nao-lidas n} numa UNICA tx do tenant
     (mesma disciplina de dashboard-mesa). O escopo por destinatario esta' no WHERE do SQL, junto do
     tenant — a authz fina desta rota NAO e' de papel, e' de posse.")
  (marcar-notificacao-lida! [this ente-id m]
    "Marca como lida a notificacao `(:id m)` do destinatario `(:destinatario-identidade-id m)` — guard de
     posse no MESMO WHERE do tenant. Idempotente; devolve {:id :lida-em} ou nil (inexistente/nao e' sua)."))

(defrecord RepoPaineisPg [datasource]
  RepoPaineis
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (o-que-vence [this ente-id {:keys [limite] :or {limite teto-o-que-vence}}]
    (transacao this ente-id
      (fn [tx]
        {:pendencias       (db-pendencia/listar-abertas tx ente-id limite)
         :pendencias-total (reduce + 0 (map :n (db-pendencia/resumo tx ente-id)))})))
  (tramitacao-board [this ente-id]
    (transacao this ente-id
      (fn [tx]
        {:itens             (db-tramitacao/listar-board tx ente-id teto-tramitacao-board-por-estado)
         :totais-por-estado (db-tramitacao/resumo tx ente-id)})))
  (sli-sessoes [this ente-id] (transacao this ente-id #(db-sli-sessao/listar-sli-sessoes % ente-id teto-sli-sessoes)))
  (dashboard-mesa [this ente-id]
    (transacao this ente-id
      (fn [tx]
        {:tramitacao (db-tramitacao/resumo tx ente-id)
         :pendencias (db-pendencia/resumo tx ente-id)
         :sessoes    (db-sli-sessao/resumo tx ente-id)})))
  (entregar-pendentes! [this ente-id notificador]
    (let [pendentes (transacao this ente-id #(db-notificacao/listar-pendentes % ente-id))]
      (doseq [intent pendentes]
        ;; envio EXTERNO fora da tx (nao-transacional; um throw viraria {:ok? false} na porta, nunca aqui);
        ;; a marcacao roda numa tx curta por intent — at-least-once (crash pos-envio pre-marca => reenvio).
        (let [res (porta/enviar! notificador intent)]
          (transacao this ente-id
            (fn [tx]
              (if (:ok? res)
                (db-notificacao/marcar-enviada! tx {:ente-id ente-id :id (:id intent)})
                (db-notificacao/marcar-falha! tx {:ente-id ente-id :id (:id intent)
                                                  :motivo (or (:motivo res) "falha de entrega sem motivo")}))))))
      {:processados (count pendentes)}))
  (minhas-notificacoes [this ente-id destinatario-identidade-id]
    (transacao this ente-id
      (fn [tx]
        {:notificacoes (db-caixa/listar-do-destinatario tx ente-id destinatario-identidade-id)
         :nao-lidas    (db-caixa/contar-nao-lidas tx ente-id destinatario-identidade-id)})))
  (marcar-notificacao-lida! [this ente-id m]
    (transacao this ente-id #(db-caixa/marcar-lida! % (assoc m :ente-id ente-id)))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoPaineisPg nil))
