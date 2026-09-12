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
  aceitavel; travar o barramento do sistema inteiro nao e'.

  TOLERANCIA A PAYLOAD MALFORMADO (frente 'relay-tolerante'): a tolerancia acima (HIGH-1) so' cobre UM
  formato de falha — `atualizar-estado!`/`atualizar-metadados!` continuam tendo `{:pre ...}` e EXPLODEM
  se o payload nao tiver as chaves que o contrato exige (medido: 27 `:pre` em 5 arquivos de
  transparencia/db/, e reproduzido de verdade com uma linha `proposicao.protocolada` payload `{:numero 7}`
  -> AssertionError em db.materia/inserir! -> poison do relay INTEIRO, 8 erros num namespace VIZINHO que so'
  teve a infelicidade de drenar depois). `projetar-evento!` (abaixo) e' a FRONTEIRA DE DESPACHO onde essa
  guarda mora — nao as 27 funcoes de db/: dentro de db/, o `:pre` continua sendo invariante legitima para
  quem chama em PROCESSO (controller, semente, teste de unidade); so' o dado que atravessa o relay
  COMPARTILHADO (fronteira de confianca) precisa de tolerancia. Ver `payload-malformado?` para a
  classificacao exata (o que e' 'forma do dado' vs. 'infra falhou') e o RATIONALE de nao usar o schema
  Malli do evento aqui (acoplaria este modulo a `legislativo.events.*`/`sessoes.events.*`, o que §22.10
  proibe — o mesmo motivo pelo qual `diplomat/consumers.clj` hardcoda os tipos como STRING em vez de
  importar o schema do produtor)."
  (:require [clojure.tools.logging :as log]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.transparencia.db.acompanhamento :as db-acompanhamento]
            [oplenario.transparencia.db.artefato-publicacao :as db-artefato]
            [oplenario.transparencia.db.materia :as db-materia]
            [oplenario.transparencia.db.norma :as db-norma]
            [oplenario.transparencia.db.parlamentar :as db-parlamentar]
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
  envelope NAO precisa disto — vem de uma coluna SQL nativa (outbox.ente_id), nunca do jsonb.

  Onda E fatia 2: `(some? (get m k))`, NAO so' `contains?` — uma chave OPCIONAL (:autor-id) que o producer
  emite via `some->` fica PRESENTE no payload com valor `nil` quando o autor nao e' vereador (jsonista nao
  strippa chave de valor null na serializacao); `contains?` sozinho veria a chave e chamaria
  `UUID/fromString` num `nil`, lancando NPE. `some?` trata 'chave ausente' e 'chave presente com nil' do
  mesmo jeito — intocada — que e' o comportamento correto pros dois (evento legado sem a chave E evento
  novo com autoria nao-parlamentar)."
  [payload chaves]
  (reduce (fn [m k] (cond-> m (some? (get m k)) (update k #(UUID/fromString %)))) payload chaves))

(defn- instant-tolerante
  "Parseia uma string ISO p/ Instant TOLERANDO ausencia/invalidez — nil ou string malformada vira nil, nunca
  lanca. Achado C-1 (revisao Task 2): `:ocorrido-em` foi ACRESCENTADO ao contrato de `voto.registrado` nominal
  DEPOIS que eventos ja estavam gravados no `shared.outbox` (deploy rolling, ou qualquer redrive de historico);
  esses eventos legados nao tem a chave. `Instant/parse` sem guarda contra nil/invalido lanca; `outbox/drenar-um!`
  chama o handler SEM try (kernel/outbox.clj) e o relay COMPARTILHADO (outbox_relay.clj) faz catch+retry
  ETERNO — a cabeca da fila trava PARA SEMPRE, bloqueando TODO evento de id maior de TODOS os modulos, nao so'
  desta projecao (mesmo racional do log/warn tolerante de proposicao.transicionou acima)."
  [s]
  (try
    (some-> s Instant/parse)
    (catch Exception _ nil)))

(defn- payload-malformado?
  "Classifica `t` como falha de FORMA DO PAYLOAD (o dado do evento nao bate o contrato) — o caso em que
  `projetar-evento!` deve LOGAR e DESCARTAR — versus falha de INFRAESTRUTURA (conexao caiu, deadlock,
  disco cheio, timeout) — o caso em que tem de PROPAGAR (capturar largo aqui trocaria uma indisponibilidade
  TRANSITORIA por perda SILENCIOSA de evento, pior que o defeito original).

  E' um WHITELIST FECHADO, de proposito, nao um blacklist ('tudo que nao e' SQLException'): um blacklist
  deixa passar qualquer classe de excecao NOVA e desconhecida como se fosse dado malformado, exatamente o
  erro que este seam existe para nao cometer. As quatro classes abaixo sao, medidamente, as UNICAS que o
  dispatch e as 27 `{:pre ...}` de transparencia/db/ lancam quando falta uma chave ou o tipo/formato esta
  errado:
   - AssertionError            — os `:pre` de db/materia,db/norma,db/artefato-publicacao,db/parlamentar
                                 (a causa medida do incidente: db.materia/inserir!, `(some? proposicao-id)`).
   - IllegalArgumentException  — `UUID/fromString` com string mal-formada (inclui a subclasse
                                 NumberFormatException, ex.: `:ano` que chega string nao-numerica).
   - NullPointerException      — `UUID/fromString` (ou `.toUpperCase`/`.trim` etc.) sobre um campo AUSENTE
                                 do payload que o dispatch le direto (antes de chegar em db/), ex.: o
                                 `(UUID/fromString (:proposicao-id payload))` do ramo 'proposicao.transicionou'.
   - ClassCastException        — um valor do TIPO errado no jsonb (nº onde se espera string, etc.).
  Mais o marcador explicito `:transparencia/payload-malformado?` em `ex-data` — usado pela checagem de
  `ente-id` ausente logo no topo de `projetar-evento!` (mesma familia de falha do `fan-out-notificacao!`
  vizinho: `shared.outbox.ente_id` e' NULLABLE, um evento supratenant/malformado nao pode propagar). E' um
  marcador INTENCIONAL, nao casamento de mensagem (`re-find` em `.getMessage`) — mensagem de excecao e'
  string de humano, muda sem aviso; `ex-data` e' contrato."
  [^Throwable t]
  (boolean
   (or (instance? AssertionError t)
       (instance? IllegalArgumentException t)
       (instance? NullPointerException t)
       (instance? ClassCastException t)
       (:transparencia/payload-malformado? (ex-data t)))))

(defn despachar!
  "O `case` de fato, SEM tolerancia — lanca em tipo sem branch (`case` sem default: 'No matching clause')
  OU em payload malformado ({:pre ...} de db/, UUID/Instant invalidos). PUBLICA (nao `defn-`) DE
  PROPOSITO — MESMO padrao de `paineis.components.repositorio/despachar!` (o precedente: F7 Slice 1,
  review security HIGH), que resolveu exatamente este problema antes: e' o alvo direto do drift-guard de
  teste (`todo-tipo-consumido-tem-branch-de-projecao`, transparencia/artefato_publicacao_test.clj), que
  precisa distinguir 'tipo sem branch' (bug de programador — deve ficar RUIDOSO, pego em CI antes de subir)
  de 'payload malformado em runtime' (dado externo — deve ser TOLERADO). `projetar-evento!` (abaixo) e'
  quem chama ESTA fn dentro de um try/catch p/ a tolerancia de runtime (`payload-malformado?`); se o catch
  estivesse AQUI, um 'No matching clause' de um tipo registrado sem branch seria silenciosamente engolido
  (nunca propagaria ao teste), mascarando o drift em vez de barra-lo.

  Escreve em transparencia.materia/norma/artefato_publicacao/voto_parlamentar/presenca_parlamentar/
  sessao_com_chamada. UM evento pode virar MAIS DE UM statement: `presenca.registrada` escreve DOIS
  (presenca + companheira), ambos na mesma tx do relay.
  `payload` ja chegou com chaves KEYWORD kebab (outbox/jsonb-> usa keyword-keys-object-mapper), casando 1:1 com
  o que os producers de legislativo/sessoes construiram (events/{proposicao,norma,artefato-publicacao,
  votacao,presenca}.clj) — EXCETO os campos :uuid e os de tempo (:publicado-em/:criado-em/:ocorrido-em), que
  chegam como string (ver `uuid-payload` e a re-parseacao Instant/parse; docstring de events/norma)."
  [tx ente-id tipo payload]
  (case tipo
    "proposicao.protocolada"
    (db-materia/inserir! tx (-> payload (uuid-payload [:proposicao-id :autor-id]) (assoc :ente-id ente-id)))

    "proposicao.editada"
    (let [m (-> payload (uuid-payload [:proposicao-id :autor-id]) (assoc :ente-id ente-id))]
      (or (db-materia/atualizar-metadados! tx m)
          (log/warn "transparencia: proposicao.editada sem materia projetada (protocolada ausente?)"
                    {:ente-id ente-id :proposicao-id (:proposicao-id m)})))

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
                                 (update :criado-em #(Instant/parse %))))

    ;; Onda E fatia 2 (perfil publico do vereador). SIGILO: o ramo 'secreta' de VotoRegistradoPayload e'
    ;; :closed e nao carrega :vereador-id — `when-let` sobre a PRESENCA da chave, nao sobre a string de
    ;; modalidade (defesa que nao depende do vocabulario de modalidade permanecer estavel).
    ;; C-1 (revisao Task 2): `instant-tolerante`, NAO `Instant/parse` cru — `:ocorrido-em` e' chave NOVA no
    ;; contrato; evento legado no shared.outbox (gravado antes desta mudanca) nao a tem. Sem projecao possivel
    ;; (a coluna e' NOT NULL — nao ha estado parcial honesto a gravar), loga e TOLERA em vez de lancar.
    "voto.registrado"
    (when-let [vid (:vereador-id payload)]
      (if-let [ocorrido-em (instant-tolerante (:ocorrido-em payload))]
        (db-parlamentar/registrar-voto! tx
          {:ente-id ente-id
           :votacao-id (UUID/fromString (:votacao-id payload))
           :vereador-id (UUID/fromString vid)
           :proposicao-id (some-> (:proposicao-id payload) UUID/fromString)
           :voto (:voto payload)
           :ocorrido-em ocorrido-em})
        (log/warn "transparencia: voto.registrado sem :ocorrido-em valido (evento legado?) — nao projetado"
                  {:ente-id ente-id :votacao-id (:votacao-id payload)})))

    ;; Carry I-5 fatia 5: DOIS statements na MESMA tx do relay — o estado por (sessao, vereador) e a
    ;; COMPANHEIRA `sessao_com_chamada` (uma linha por SESSAO, com a data civil do PRIMEIRO evento dela), que
    ;; e' o denominador que a fatia 6 vai recortar pela janela de exercicio do mandato. As duas commitam
    ;; juntas ou nenhuma: um erro de DB no segundo statement aborta a tx inteira, o relay faz rollback e o
    ;; evento re-drena — nao existe meia-projecao committada.
    ;;
    ;; O FUSO APARECE UMA VEZ SO', AQUI: `hoje-de` sobre o MESMO Instant ja' parseado (nao uma segunda
    ;; leitura de relogio, nao um `AT TIME ZONE` no SQL) — e' o que mata a classe de bug de meia-noite na
    ;; fronteira da janela. ESTE e' o SEGUNDO consumidor de `zona-civil-padrao` (o outro e' o host), e o
    ;; unico que a GRAVA: o valor derivado aqui vira `sessao_com_chamada.data`, coluna PERSISTIDA. Logo
    ;; promover o fuso a atributo do ente NAO basta — as linhas ja' projetadas continuam com a data civil de
    ;; America/Fortaleza e nao ha ferramenta de re-projecao no repo (carry escrito na docstring da constante,
    ;; corrigido na revisao da fatia 5: antes ele so' dizia que a constante e' global).
    ;;
    ;; TOLERANCIA (`instant-tolerante`, mesmo racional do C-1 de voto.registrado logo acima): ate' esta
    ;; fatia o ramo fazia `Instant/parse` CRU e LANCAVA num payload sem `:ocorrido-em` ou com instante
    ;; malformado — e um throw aqui trava a cabeca da fila do relay COMPARTILHADO por TODOS os modulos, para
    ;; sempre (achado HIGH do architect no F6c). O produtor real e' fail-closed (`RegistradaPayload` exige a
    ;; chave), entao o caminho vivo nao muda; quem chama `projetar-evento!` direto (teste, redrive de payload
    ;; legado escrito a mao) passa a ser TOLERADO em vez de envenenar o bus. RECORTE HONESTO: a tolerancia
    ;; cobre SO' o instante — `UUID/fromString` em `:sessao-id`/`:vereador-id` continua lancando com um id
    ;; malformado, exatamente como em todos os outros ramos deste `case`; nao foi alargado nesta fatia.
    "presenca.registrada"
    (if-let [ocorrido-em (instant-tolerante (:ocorrido-em payload))]
      (let [sessao-id (UUID/fromString (:sessao-id payload))]
        (db-parlamentar/registrar-presenca! tx
          {:ente-id ente-id
           :sessao-id sessao-id
           :vereador-id (UUID/fromString (:vereador-id payload))
           :tipo (:tipo payload)
           :modalidade (:modalidade payload)
           :ocorrido-em ocorrido-em})
        (db-parlamentar/registrar-sessao-com-chamada! tx
          {:ente-id ente-id
           :sessao-id sessao-id
           :data (tempo/hoje-de ocorrido-em tempo/zona-civil-padrao)}))
      (log/warn "transparencia: presenca.registrada sem :ocorrido-em valido — nao projetada"
                {:ente-id ente-id :sessao-id (:sessao-id payload)}))))

(defn projetar-evento!
  "Handler REGISTRADO no bus (§22.10 diplomat/consumers) — a fronteira de despacho ONDE MORA a guarda de
  tolerancia (frente 'relay-tolerante', ver docstring do ns). Seta o GUC de tenant (sem trocar de role — ver
  docstring do ns) e chama `despachar!` (o `case` de fato, sem tolerancia — ver a docstring dela p/ o
  formato da projecao) DENTRO de um try/catch: um evento pode falhar ANTES de chegar em db/ (ex.:
  `UUID/fromString` cru no ramo 'proposicao.transicionou') ou DENTRO (o `:pre` de db/). Se
  `payload-malformado?` reconhece a excecao, LOGA em :error (nunca :info — isto e' anomalia, nao rotina) com
  o suficiente para achar a linha exata (`:outbox-id`, `:idempotency-key`, `:tipo`, `:ente-id`, a razao) e
  DESCARTA — o handler devolve normalmente, a tx do relay COMMITA, o proximo `drenar-um!` pega o proximo id
  (sem head-of-line). Qualquer OUTRA excecao (SQLException do driver, timeout, o que for) PROPAGA sem
  disfarce — e' o unico jeito de nao trocar 'infra caiu' por 'evento sumiu em silencio'."
  [tx {:keys [tipo ente-id payload idempotency-key id]}]
  (try
    (when (nil? ente-id)
      (throw (ex-info "transparencia: evento sem ente-id (supratenant ou malformado) — nao projetavel"
                      {:transparencia/payload-malformado? true})))
    (tenancy/set-tenant! tx ente-id)
    (despachar! tx ente-id tipo payload)
    (catch Throwable t
      (if (payload-malformado? t)
        (log/error t "transparencia: evento descartado no relay compartilhado — payload malformado"
                   {:tipo tipo :ente-id ente-id :idempotency-key idempotency-key :outbox-id id
                    :razao (ex-message t)})
        (throw t)))))

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
        (let [{:keys [assunto corpo]} (logic-notif/renderizar materia para)
              destinatarios (db-acompanhamento/seguidores-ativos tx ente-id pid teto-fanout)]
          ;; SINAL OBSERVAVEL (frente 'truncamento-familia', sitio (b)): este e' um JOB, nao uma listagem —
          ;; nao ha campo `-total` a publicar (regra 1 da familia: o teto nunca vai ao cliente, e aqui nao ha
          ;; cliente nenhum, so' um consumer do bus). Quando a contagem bate no teto, MEDE o residuo real
          ;; (`contar-seguidores-ativos`, MESMO predicado de `seguidores-ativos` — regra 3) e LOGA: sem
          ;; cursor/paginacao nesta query (`ORDER BY seguidor_identidade_id LIMIT teto`, sempre os MESMOS N
          ;; primeiros), o residuo NAO entra em nenhuma rodada futura — nao e' um teto que dreno aos poucos,
          ;; e' um apagao permanente para quem ficou de fora. Sem este log o operador nao tem NENHUM jeito de
          ;; saber que uma materia populosa esta' deixando cidadaos sem notificacao.
          ;; achado MENOR da revisao adversarial: `(= (count destinatarios) teto-fanout)` e' o teto
          ;; ATINGIDO, nao EXCEDIDO — com EXATAMENTE teto seguidores ativos ninguem fica de fora, mas o
          ;; guard sozinho logaria um alarme falso ("o residuo NUNCA sera notificado" com :nao-notificados
          ;; 0). O `when` externo so' evita a query extra no caso comum (abaixo do teto); o `>` interno e'
          ;; quem decide se ha' de fato residuo antes de acusar.
          (when (= (count destinatarios) teto-fanout)
            (let [total (db-acompanhamento/contar-seguidores-ativos tx ente-id pid)]
              (when (> total teto-fanout)
                (log/warn "transparencia: fan-out de notificacao cortado pelo teto — o residuo NUNCA sera notificado (sem cursor nesta query)"
                          {:ente-id ente-id :proposicao-id pid :teto teto-fanout :seguidores-ativos total
                           :nao-notificados (max 0 (- total teto-fanout))}))))
          (doseq [dest destinatarios
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
  (listar-materias [this ente-id estados-excluidos]
    "Portal: {:materias :materias-total} — materias fora dos `estados-excluidos`, TRUNCADAS no teto de
     `listar-em-tramitacao` (200), mais `:materias-total` (SEM teto, `contar-em-tramitacao`) no MESMO
     predicado — a UNICA listagem publica de proposicoes (frente 'truncamento-familia', sitio (b)). Uma
     UNICA tx (mesma disciplina de `perfil-parlamentar` abaixo — READ COMMITTED, ver a nota la').")
  (buscar-norma [this ente-id norma-id] "Uma norma publicada por id, ou nil.")
  (norma-da-materia [this ente-id proposicao-id] "A norma publicada de uma materia, ou nil.")
  (listar-normas [this ente-id filtro]
    "Portal: {:normas :normas-total} — acervo as-enacted, com filtro opcional {:tipo :ano :numero} (ver
     db/norma/listar), TRUNCADO no teto (200) mais `:normas-total` (SEM teto, `db/norma/contar`) no MESMO
     filtro — frente 'truncamento-familia', sitio (c). Uma UNICA tx.")
  ;; F6c Slice 4b — artefato de publicacao oficial (PROJECAO; a rota publica de download resolve o ponteiro daqui)
  (artefato-mais-recente-da-norma [this ente-id norma-id]
    "Ponteiro do artefato de publicacao MAIS RECENTE de uma norma (objeto_store_ref + content_type + versao), ou nil.")
  ;; F6c Slice 2 — acompanhamento do cidadao (escritas autenticadas; consent-gated)
  (seguir! [this ente-id m] "UPSERT: cidadao segue a materia (re-seguir reativa). Devolve {:id :estado ...}.")
  (deixar-de-seguir! [this ente-id m] "Soft-cancel idempotente. Devolve {:id} se cancelou, ou nil (no-op).")
  (meus-acompanhamentos [this ente-id seguidor-identidade-id]
    "{:acompanhamentos :acompanhamentos-total} — materias que o cidadao segue (ativas, c/ cabecalho via
     LEFT JOIN; item cuja projecao ainda nao chegou vem com `:indisponivel true`, nunca omitido), TRUNCADAS
     no teto de `meus-da-materia` (200) mais `:acompanhamentos-total` (SEM teto, SEM join, `contar-meus`)
     no MESMO predicado — frente 'truncamento-familia', sitios (c)/(d). Uma UNICA tx.")
  ;; Onda E fatia 2 — perfil PUBLICO do vereador (leitura COMPOSTA numa UNICA tx, mesma disciplina de
  ;; legislativo/ficha-completa-da-proposicao). O QUE A TX DE FATO ENTREGA (correcao F3a da revisao Task 3
  ;; — a afirmacao anterior, "as leituras veem o MESMO snapshot MVCC, entao o numero-card nunca discorda da
  ;; lista", era FALSA): uma UNICA conexao e um UNICO contexto de tenant (o GUC app.ente_id setado uma vez).
  ;; NAO um round-trip so' — sao SEIS statements (mais BEGIN/SET LOCAL/COMMIT), e quem dimensionar latencia
  ;; da rota publica precisa contar assim. NAO um snapshot congelado: `transacao` -> kernel/tenancy/com-tenant* chama
  ;; `jdbc/with-transaction` SEM mapa de opcoes e o HikariConfig (kernel/components/datasource) nunca seta
  ;; transaction-isolation, entao o nivel efetivo e' READ COMMITTED — em que CADA statement toma um snapshot
  ;; NOVO. Com o relay committando projecoes entre os statements, uma divergencia card-vs-lista E' alcancavel;
  ;; e' uma janela ESTREITA que se auto-cura na proxima carga, nao uma garantia.
  ;; CARRY DELIBERADO: congelar o snapshot exigiria um `com-tenant-leitura*` no kernel com
  ;; `:isolation :repeatable-read :read-only true`. Fora do escopo desta fatia — o kernel e' COMPARTILHADO e
  ;; o mesmo overclaim existe em legislativo/components/repositorio (ficha-completa-da-proposicao, o
  ;; precedente citado); corrigir so' aqui criaria inconsistencia entre os dois.
  (perfil-parlamentar [this ente-id vereador-id janelas]
    "{:materias :materias-total :normas-de-autoria :votos :votos-total :presenca} do vereador no read-model
     publico (sem identidade). DUAS listas truncam e cada uma vem com o seu total: `:materias` no teto de
     `listar-por-autor` (200) e `:votos` no de `votos-do-vereador` (50) — sem `:materias-total`/`:votos-total`
     a borda nao sabe que truncou. Sao SEIS statements no caminho comum, nao cinco (achado C-4, revisao
     Task 4) — e CINCO quando `janelas` e' vazia: `resumo-presenca` curto-circuita e nao emite statement
     nenhum (ver a docstring dela)."))

(defrecord RepoTransparenciaPg [datasource]
  RepoTransparencia
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (buscar-materia [this ente-id pid] (transacao this ente-id #(db-materia/buscar % ente-id pid)))
  (listar-materias [this ente-id excl]
    (transacao this ente-id
      (fn [tx]
        {:materias       (db-materia/listar-em-tramitacao tx ente-id excl)
         :materias-total (db-materia/contar-em-tramitacao tx ente-id excl)})))
  (buscar-norma [this ente-id nid] (transacao this ente-id #(db-norma/buscar % ente-id nid)))
  (norma-da-materia [this ente-id pid] (transacao this ente-id #(db-norma/buscar-por-proposicao % ente-id pid)))
  (listar-normas [this ente-id filtro]
    (transacao this ente-id
      (fn [tx]
        {:normas       (db-norma/listar tx ente-id filtro)
         :normas-total (db-norma/contar tx ente-id filtro)})))
  (artefato-mais-recente-da-norma [this ente-id norma-id]
    (transacao this ente-id #(db-artefato/mais-recente-por-norma % ente-id norma-id)))
  (seguir! [this ente-id m] (transacao this ente-id #(db-acompanhamento/seguir! % (assoc m :ente-id ente-id))))
  (deixar-de-seguir! [this ente-id m] (transacao this ente-id #(db-acompanhamento/deixar-de-seguir! % (assoc m :ente-id ente-id))))
  (meus-acompanhamentos [this ente-id sid]
    (transacao this ente-id
      (fn [tx]
        {:acompanhamentos       (db-acompanhamento/meus-da-materia tx ente-id sid)
         :acompanhamentos-total (db-acompanhamento/contar-meus tx ente-id sid)})))
  (perfil-parlamentar [this ente-id vid janelas]
    (transacao this ente-id
      (fn [tx]
        {:materias          (db-materia/listar-por-autor tx ente-id vid)
         :materias-total    (db-materia/contar-por-autor tx ente-id vid)
         :normas-de-autoria (db-materia/contar-normas-por-autor tx ente-id vid)
         :votos             (db-parlamentar/votos-do-vereador tx ente-id vid nil)
         :votos-total       (db-parlamentar/contar-votos-do-vereador tx ente-id vid)
         :presenca          (db-parlamentar/resumo-presenca tx ente-id vid janelas)}))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoTransparenciaPg nil))
