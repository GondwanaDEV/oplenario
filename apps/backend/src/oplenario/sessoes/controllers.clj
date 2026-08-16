(ns oplenario.sessoes.controllers
  "Orquestracao (impura) do modulo sessoes (§22.10 controllers, ADR-0001): coordena Repo-Component + policy.check
  (camada FINA, §22.5 eixo E). Trabalha SO em `models` (kebab) — NUNCA toca wire/adapters (o import-lint enforca);
  a traducao da borda fica no diplomat (que chama adapters/in|out). Depende do Repo-Component (e do ObjetoStore
  do kernel, p/ a ingestao de gravacao — kernel e' camada compartilhada, nao outro modulo), nunca do db/
  (§3-bis). O `ator` (resolvido na borda) e' o sujeito de toda operacao (§22.5: sem ator = proibido)."
  (:require [malli.core :as m]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.ids :as ids]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.components.renderizador-pdf :as renderizador-pdf]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.components.serializador-folha :as serializador-folha]
            [oplenario.sessoes.gerador-folha :as gerador-folha]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.folha :as mod-folha])
  (:import (java.security DigestInputStream MessageDigest)
           (java.time Instant)
           (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(defn- hex
  "byte-array -> string hex minuscula. `bit-and 0xff` desfaz a sign-extension do byte com sinal (sem ela,
  `(format \"%02x\" (byte -1))` daria \"ffffffffffffffff\" — hash malformado, dedup/integridade quebrados)."
  [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

;; ---------- §22.6 eixo C — a DATA DE REFERENCIA e o gate de ASSENTO ----------
;; Ficam no TOPO do ns porque servem a TRES eixos (presenca, justificativa, ato de chamada) e Clojure exige
;; definicao antes do uso. A ordem de leitura ainda e' a do eixo: quem procura a chamada acha o bloco §22.6
;; eixo C mais abaixo, que so' os CONSOME.

(defn- data-de-referencia
  "A DATA CIVIL que resolve QUEM compoe a Casa nesta sessao: `aberta-em` se a sessao ja abriu (a Casa que
  efetivamente se reuniu), senao `agendada-para` (a Casa PREVISTA, sessao ainda 'agendada'). Convertida no
  fuso `tempo/zona-civil-padrao` (NUNCA `LocalDate/now` — reabrir a chamada de uma sessao do mes passado
  mostraria a composicao de HOJE, nao a de entao).

  Sessao sem NENHUM dos dois marcos e' um caminho NORMAL da API, nao dado corrompido: `wire/in/AgendarSessao`
  declara `agendada-para` OPCIONAL e a coluna e' nullable (mig 0026) — POST /sessoes sem data cria a linha
  assim. Por isso o erro e' de CONFLITO DE ESTADO, com mensagem acionavel (`:conflito/sessao-sem-data` ->
  409 no diplomat), e nao um `:servidor/erro` que a borda traduziria em 500 'erro interno': a secretaria que
  agendou sem marcar a data precisa saber que e' isso que falta. O que continua proibido e' inventar uma
  data — uma composicao adivinhada vai parar em ata."
  [sessao]
  (if-let [instante (or (:aberta-em sessao) (:agendada-para sessao))]
    (tempo/hoje-de instante tempo/zona-civil-padrao)
    (throw (ex-info "sessao sem data marcada: sem data de referencia p/ a chamada"
                    {:tipo :conflito/sessao-sem-data :sessao-id (:id sessao)}))))

(defn- sem-assento-entre
  "Os `vereador-ids` que NAO compoem a Casa na `data` (seam `roster-da-casa`), com o INDICE de cada um na
  colecao recebida. UMA leitura do roster para N ids — o lote da chamada e' dezenas de nomes e nao pode virar
  dezenas de consultas cross-modulo."
  [roster-da-casa ente-id data vereador-ids]
  (let [com-assento (into #{} (map :vereador-id) (roster-da-casa ente-id data))]
    (vec (keep-indexed (fn [i v] (when-not (contains? com-assento v) {:indice i :vereador-id v}))
                       vereador-ids))))

(defn- exigir-assento-para-presenca!
  "Fail-closed nas ESCRITAS DE PRESENCA: todo `vereador-id` do request tem de compor a Casa na data de
  referencia da sessao. Valida o lote INTEIRO antes de qualquer INSERT (mesma disciplina do gate de estado).

  Sem isto, a coluna `presenca_evento.vereador_id` — que nao tem FK (forward-ref, §22.10) e que nenhum
  caminho validava — aceitava qualquer UUID, e a consequencia nao ficava no modulo:
  `relacoes/presenca/presentes-plenario`, a relacao que o MOTOR de DSL resolve por nome para decidir quorum
  de votacao, e' um COUNT sobre os ultimos eventos SEM join a mandato — a presenca forjada virava quorum e
  aprovava a materia. E `presenca.registrada` e' consumido por `transparencia` e projetado em
  `transparencia.presenca_parlamentar`, read-model PUBLICO: a linha atravessava para fora da Casa, numa
  tabela append-only sem GRANT de DELETE. O guard ja existia no modulo (`exigir-assento!` da justificativa,
  onde o dano era so' dado morto) e nao tinha sido aplicado onde o dano e' maior.

  Lanca `:conflito/sessao-nao-aceita-presenca` com `:motivo :sem-assento` — MESMA tag do gate de estado (a
  borda ja a traduz em 409) e nao 400: o corpo esta bem formado, o que nao bate e' a COMPOSICAO da Casa
  naquela data, que e' estado do sistema e se corrige em `cadastros`.

  TOCTOU conhecido e aceito: o roster e' lido FORA da tx da escrita (o seam e' cross-modulo e o Repo nao o
  alcanca), entao um mandato pode encerrar entre o check e o INSERT. E' a mesma janela de `exigir-assento!`;
  o efeito e' uma linha orfa, que a chamada ja publica como `sem-assento` — fail-loud, nao silencio."
  [roster-da-casa ente-id sessao vereador-ids]
  (let [data (data-de-referencia sessao)]
    (when-let [{:keys [indice vereador-id]} (first (sem-assento-entre roster-da-casa ente-id data vereador-ids))]
      (throw (ex-info (logic/mensagem-de-recusa-de-presenca :sem-assento sessao nil)
                      {:tipo :conflito/sessao-nao-aceita-presenca :motivo :sem-assento
                       :sessao-id (:id sessao) :data-de-composicao data
                       :vereador-id vereador-id :indice indice})))))

(defn buscar-sessao
  "Le a sessao `id` (UUID) do tenant do `ator`. Camada FINA: carrega o recurso e roda policy.check
  (pode-ver-sessao?) ANTES de devolver — quem nao consegue decidir NEGA (check! mapeia -> 403). Devolve a sessao
  de dominio (models) ou nil se nao existe (o diplomat traduz nil -> 404, e a sessao -> wire/out)."
  [repo-sessoes ator id]
  (when-let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) id)]
    (authz/check! ator :sessao/ver s logic/pode-ver-sessao?)
    s))

(defn transicionar-sessao
  "Mesa de conducao (§22.6 eixo A/G): move o estado da sessao `sessao-id` pela maquina. Carrega a sessao do
  tenant do `ator` (nil -> 404 via nil de retorno), roda pode-ver-sessao? (mesma Casa -> 403 fail-closed), e
  transiciona. O Repo compoe o ato + emite `sessao.transicionou` (que o canal SSE do plenario consome) na MESMA
  tx (atomicidade §22.9 E2). Maquina/CAS lanca `:conflito/transicao` (o diplomat mapeia 409). updated-by = o
  ator. Devolve {:sessao-id :de :para} ou nil (sessao inexistente)."
  [repo-sessoes ator {:keys [sessao-id para motivo lock-version]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/conduzir sessao logic/pode-ver-sessao?)
    (assoc (repo/transicionar-sessao! repo-sessoes (:ente-id ator)
             {:id sessao-id :para para :motivo motivo :lock-version lock-version
              :updated-by (:identidade-id ator)})
           :sessao-id sessao-id)))

(defn registrar-presenca
  "§22.6 eixo C: registra um evento de presenca (entrada/saida/retorno/mudanca_modalidade) numa sessao. Carrega
  a sessao do tenant do `ator` (nil -> 404 via nil de retorno), roda pode-ver-sessao? (mesma Casa -> 403
  fail-closed), e grava o evento append-only. A `fonte` e' FORCADA = 'manual_secretaria' no servidor: esta borda
  e' um registro HUMANO de um secretario autenticado, nunca confia em proveniencia do cliente — a fonte alimenta
  a precedencia de quorum (manual > painel > inferida), entao um cliente nao pode forjar 'painel_eletronico' p/
  ganhar desempate. O Repo compoe o ato + emite `presenca.registrada` (que alimenta o quorum ao vivo) na MESMA
  tx (atomicidade §22.9 E2). created-by = o ator.

  `agora` (o relogio do servidor, ja lido na borda) e' o TETO da hora declarada. Ele vem por parametro, e nao
  de `Instant/now` aqui dentro, pela mesma razao de sempre: relogio e' dependencia injetada (§22.6) e sem isso
  o teste do clamp seria uma aposta sobre o relogio do container. O GATE de estado + a janela NAO rodam neste
  ns: rodam dentro da tx do Repo (a leitura de authz e a escrita sao transacoes diferentes — checar aqui
  deixaria a janela em que a Mesa encerra a sessao entre uma e outra).

  Devolve {:id :ocorrido-em :registrado-em} ou nil (sessao inexistente). Recusa do gate lanca
  `:conflito/sessao-nao-aceita-presenca` (o diplomat mapeia 409)."
  [repo-sessoes roster-da-casa ator {:keys [sessao-id vereador-id tipo modalidade ocorrido-em]} agora]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/registrar-presenca sessao logic/pode-ver-sessao?)
    (exigir-assento-para-presenca! roster-da-casa (:ente-id ator) sessao [vereador-id])
    (repo/registrar-presenca! repo-sessoes (:ente-id ator)
      {:id (random-uuid) :sessao-id sessao-id :vereador-id vereador-id :tipo tipo :modalidade modalidade
       :fonte "manual_secretaria" :ocorrido-em ocorrido-em :agora agora
       :created-by (:identidade-id ator)})))

(defn registrar-presenca-lote
  "§22.6 eixo C, Etapa 2c: registra N eventos de presenca DE UMA VEZ (a chamada de uma camara e' UM ato de
  dezenas de nomes em minutos — POSTs sequenciais deixam meia chamada gravada quando a rede cai no meio).
  Carrega a sessao do tenant do `ator` (nil -> 404 via nil de retorno), roda pode-ver-sessao? (mesma Casa ->
  403 fail-closed), e grava o lote NUMA UNICA transacao no Repo (ou tudo entra, ou nada entra — o gate de
  estado e a janela da hora se aplicam a CADA linha; a primeira reprovada recusa o lote inteiro).

  `fonte` FORCADA = 'manual_secretaria' em CADA linha (mesma disciplina de `registrar-presenca`: e' um
  registro humano de um secretario autenticado). `id` (por linha) e `created-by` (o ator) sao gerados/
  injetados AQUI, nunca do cliente — mesmo contrato anti-forja do POST unitario.

  `agora` (o relogio do servidor, ja lido na borda) e' o TETO aplicado a CADA linha do lote; o GATE roda
  dentro da tx do Repo (nao aqui), sobre a sessao lida fresca la' dentro — nunca sobre esta leitura de authz.

  Devolve [{:id :ocorrido-em :registrado-em} ...] na ordem de `registros`, ou nil (sessao inexistente).
  Recusa do gate lanca `:conflito/sessao-nao-aceita-presenca` (o diplomat mapeia 409) — nenhuma linha grava."
  [repo-sessoes roster-da-casa ator {:keys [sessao-id registros]} agora]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/registrar-presenca sessao logic/pode-ver-sessao?)
    ;; UMA leitura do roster para o lote inteiro, e o lote INTEIRO validado antes de qualquer INSERT: a
    ;; borda de lote amplifica o buraco do `vereador-id` nao validado em ate' 200 linhas por request.
    (exigir-assento-para-presenca! roster-da-casa (:ente-id ator) sessao (mapv :vereador-id registros))
    (repo/registrar-presenca-lote! repo-sessoes (:ente-id ator)
      {:sessao-id sessao-id :agora agora
       :registros (mapv (fn [r] (assoc r :id (random-uuid) :fonte "manual_secretaria"
                                        :created-by (:identidade-id ator)))
                         registros)})))

(defn confirmar-minha-presenca
  "Onda C3 — autoatendimento: o vereador confirma a PROPRIA presenca pelo celular. `vereador-id` NUNCA vem
  do corpo (resolvido do ator via `resolver-vereador`, injetado pelo host — mesmo contrato anti-forja de
  `legislativo/controllers.clj/acusar-ciencia`). `fonte` e' SEMPRE 'autoatendimento' (nunca do cliente,
  mesma disciplina de `registrar-presenca` forcando 'manual_secretaria'). `tipo` e' SEMPRE 'entrada':
  reconfirmar nao corrompe nada (append-only; so' o ULTIMO evento por vereador conta, `esta-presente-em?`),
  entao um evento extra e' inofensivo — nao ha necessidade de checar 'ja presente' antes de inserir.
  `modalidade` fixa 'plenario' (V1 = Nivel 1, presenca remota e' so' manual pela Mesa, §22.6). Ator sem
  cadastro vinculado (`resolver-vereador` nil) -> nil (-> 404, mesmo contrato de /meu/ciencias). Sessao
  inexistente no tenant -> nil (-> 404). `instante` vem do RELOGIO do servidor (borda), nunca do cliente.

  O MESMO gate de estado da borda da Mesa vale aqui (ele mora na tx do Repo, entao nao ha como uma das duas
  portas escapar): deixar a self-service aberta seria o mesmo buraco por outra fechadura — um vereador
  'confirmando presenca' numa sessao encerrada mudaria o quorum de uma votacao ja realizada. Como `instante`
  E' o relogio do servidor, o clamp da hora e' trivialmente satisfeito nesta porta; o que morde e' o estado."
  [repo-sessoes roster-da-casa resolver-vereador ator sessao-id instante]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
      (authz/check! ator :sessao/confirmar-presenca sessao logic/pode-ver-sessao?)
      ;; O gate de assento vale AQUI TAMBEM, pelo mesmo motivo de `abrir-minha-justificativa`: ter cadastro
      ;; nao e' ter cadeira na data. `resolver-vereador` casa por identidade e continua resolvendo para um
      ;; ex-vereador — sem este gate ele "confirmaria presenca" e entraria no numerador de quorum do motor.
      (exigir-assento-para-presenca! roster-da-casa (:ente-id ator) sessao [vereador-id])
      (repo/registrar-presenca! repo-sessoes (:ente-id ator)
        {:id (random-uuid) :sessao-id sessao-id :vereador-id vereador-id :tipo "entrada" :modalidade "plenario"
         :fonte "autoatendimento" :ocorrido-em instante :agora instante
         :created-by (:identidade-id ator)}))))

(defn inscrever-orador
  "§22.6 eixo F (tribuna, intencao): inscreve um orador na fila da sessao. Carrega a sessao do tenant do `ator`
  (nil -> 404 via nil de retorno), roda pode-ver-sessao? (mesma Casa -> 403 fail-closed), e inscreve (a fila e'
  numerada server-side por sessao+fase). O Repo compoe o ato + emite `inscricao.registrada` (fila ao vivo) na
  MESMA tx. created-by = o ator. Devolve {:id :ordem} ou nil (sessao inexistente)."
  [repo-sessoes ator m]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) (:sessao-id m))]
    (authz/check! ator :sessao/inscrever-orador sessao logic/pode-ver-sessao?)
    (repo/inscrever! repo-sessoes (:ente-id ator)
      (assoc m :id (random-uuid) :created-by (:identidade-id ator)))))

(defn desistir-inscricao
  "§22.6 eixo F (tribuna, intencao): move uma inscricao para 'desistencia' (terminal). Carrega a sessao do tenant
  do `ator` (nil -> 404), roda pode-ver-sessao? (mesma Casa -> 403), e desiste (CAS por lock_version + maquina).
  O Repo compoe o ato + emite `inscricao.desistida` na MESMA tx. desistir!/CAS lanca `:conflito/inscricao`
  (ja-desistiu / lock-stale / inscricao inexistente; o diplomat mapeia 409). updated-by = o ator. Devolve
  {:de :para} (+ :inscricao-id) ou nil (sessao inexistente)."
  [repo-sessoes ator {:keys [sessao-id inscricao-id lock-version]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/desistir-inscricao sessao logic/pode-ver-sessao?)
    (assoc (repo/desistir! repo-sessoes (:ente-id ator)
             {:id inscricao-id :lock-version lock-version :updated-by (:identidade-id ator)})
           :inscricao-id inscricao-id)))

(defn iniciar-fala
  "§22.6 eixo F (tribuna, execucao): inicia uma fala numa sessao (intencao != execucao — a fala e' apartada da
  inscricao). Carrega a sessao do tenant do `ator` (nil -> 404), roda pode-ver-sessao? (mesma Casa -> 403
  fail-closed), e inicia (o Repo insere a fala + loga 'iniciada' + emite fala.iniciada na MESMA tx). created-by
  = o ator. Devolve {:id} ou nil (sessao inexistente)."
  [repo-sessoes ator m]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) (:sessao-id m))]
    (authz/check! ator :sessao/iniciar-fala sessao logic/pode-ver-sessao?)
    (repo/iniciar-fala! repo-sessoes (:ente-id ator)
      (assoc m :id (random-uuid) :created-by (:identidade-id ator)))))

(defn registrar-evento-cronometro
  "§22.6 eixo F (tribuna, execucao): registra um evento MANUAL do cronometro de uma fala (pausada/retomada/
  aparte/tempo-adicional). A authz mora no recurso sessao: carrega a sessao do tenant do `ator` (nil -> 404),
  roda pode-ver-sessao? (mesma Casa -> 403), e registra (append-only; o Repo emite fala.cronometro na MESMA tx).
  A fala vive no mesmo tenant (RLS isola). created-by = o ator. Devolve {:id} ou nil (sessao inexistente)."
  [repo-sessoes ator {:keys [sessao-id fala-id tipo ocorrido-em segundos-adicionais]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/registrar-evento-cronometro sessao logic/pode-ver-sessao?)
    ;; a authz roda no recurso SESSAO (path :id); a `fala-id` (outro path-param) tem de pertencer A ESTA sessao —
    ;; senao um secretario da Casa poderia cronometrar uma fala de OUTRA sessao da mesma Casa (confused-deputy,
    ;; review sec MAJOR). fala.sessao-id e' imutavel pos-criacao (nao ha TOCTOU). Mismatch/inexistente -> 404.
    (when (= sessao-id (:sessao-id (repo/buscar-fala repo-sessoes (:ente-id ator) fala-id)))
      (repo/registrar-evento-cronometro! repo-sessoes (:ente-id ator)
        (cond-> {:fala-id fala-id :tipo tipo :ocorrido-em ocorrido-em :created-by (:identidade-id ator)}
          segundos-adicionais (assoc :segundos-adicionais segundos-adicionais))))))

(defn encerrar-fala
  "§22.6 eixo F (tribuna, execucao): encerra uma fala — crava encerrou-em + COMPUTA o tempo efetivo dos eventos
  do cronometro (projecao). A authz mora no recurso sessao: carrega a sessao do tenant do `ator` (nil -> 404),
  roda pode-ver-sessao? (mesma Casa -> 403), e encerra (CAS por lock_version + guard encerrou_em IS NULL; o Repo
  emite fala.encerrada na MESMA tx). encerrar-fala! lanca `:conflito/fala` (ja-encerrada / lock-stale / fala
  inexistente; o diplomat mapeia 409). updated-by = o ator. Devolve {:id :tempo-efetivamente-usado-segundos} ou
  nil (sessao inexistente)."
  [repo-sessoes ator {:keys [sessao-id fala-id encerrou-em lock-version]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/encerrar-fala sessao logic/pode-ver-sessao?)
    ;; mesma guarda anti-confused-deputy do cronometro: a `fala-id` tem de pertencer A ESTA sessao (review sec
    ;; MAJOR). fala.sessao-id imutavel pos-criacao. Mismatch/inexistente -> nil de retorno -> 404 (nunca encerra
    ;; uma fala de outra sessao da mesma Casa).
    (when (= sessao-id (:sessao-id (repo/buscar-fala repo-sessoes (:ente-id ator) fala-id)))
      (repo/encerrar-fala! repo-sessoes (:ente-id ator)
        {:id fala-id :encerrou-em encerrou-em :lock-version lock-version :updated-by (:identidade-id ator)}))))

(defn registrar-decisao-mesa
  "§22.6 eixo F (tribuna): registra a DECISAO DA MESA sobre questao de ordem — ato regimental com efeito juridico
  que vai para a ata. APPEND-ONLY puro: sem CAS, sem evento (a decisao e' tomada uma vez; corrigir = nova
  decisao). Carrega a sessao do tenant do `ator` (nil -> 404), roda pode-ver-sessao? (mesma Casa -> 403
  fail-closed). Se `fala-id` veio no corpo, tem de pertencer A ESTA sessao (anti confused-deputy, mesma guarda do
  cronometro/encerrar: senao um secretario poderia atrelar a decisao a uma fala de OUTRA sessao da mesma Casa) —
  mismatch/inexistente -> nil -> 404. `presidente-id` e `created-by` sao INJETADOS do ator (um cliente nao forja
  quem decidiu). `id` gerado server-side (PK NOT NULL). Devolve {:id} ou nil (sessao inexistente / fala alheia).
  (Refinamento futuro: resolver o presidente REAL da Mesa via relacao é-presidente-da-mesa em vez do ator-operador
  — carry; hoje presidente-id = o operador autenticado que registrou o ato.)"
  [repo-sessoes ator {:keys [sessao-id fala-id] :as m}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/registrar-decisao-mesa sessao logic/pode-ver-sessao?)
    ;; fala.sessao-id e' imutavel pos-criacao (nao ha TOCTOU entre o buscar-fala e o insert) — mesma guarda do
    ;; cronometro/encerrar. fala-id ausente = decisao sem fala associada (regimentalmente valido).
    (when (or (nil? fala-id)
              (= sessao-id (:sessao-id (repo/buscar-fala repo-sessoes (:ente-id ator) fala-id))))
      (repo/registrar-decisao-mesa! repo-sessoes (:ente-id ator)
        (assoc m :id (random-uuid)
                 :presidente-id (:identidade-id ator)
                 :created-by (:identidade-id ator))))))

(defn registrar-incidente
  "§16.13: registra um INCIDENTE PROCESSUAL da sessao (pedido de vista, verificacao de votacao, urgencia, votacao
  em bloco) — ato regimental APPEND-ONLY p/ a ata + painel da mesa de conducao ao vivo (emite incidente.registrado
  no SSE, na mesma tx do Repo). Carrega a sessao do tenant do `ator` (nil -> 404), roda pode-ver-sessao? (mesma
  Casa -> 403 fail-closed). `created-by` INJETADO do ator. `objeto`/`requerente` sao forward-ref (sem checagem
  cross-module — mesma classe de carry de presenca/tribuna; o ator e' confiavel+auditado). `id` server-side.
  Devolve {:id} ou nil (sessao inexistente)."
  [repo-sessoes ator {:keys [sessao-id] :as m}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/registrar-incidente sessao logic/pode-ver-sessao?)
    (repo/registrar-incidente! repo-sessoes (:ente-id ator)
      (assoc m :id (random-uuid) :created-by (:identidade-id ator)))))

(defn pauta-da-sessao
  "Le a PAUTA VIVA da sessao `id` (UUID) p/ o `ator`. A authz mora no recurso sessao: carrega a sessao e roda
  policy.check (pode-ver-sessao?) ANTES de qualquer leitura de pauta — quem nao pode ver a sessao nao ve a
  pauta. Devolve {:sessao-id :itens [...]} (itens ativos em ordem) ou nil se a sessao nao existe (o diplomat
  traduz nil -> 404). Pauta opcional: sessao sem pauta criada -> itens vazios. Sao tres leituras de tenant em
  tx separadas (sessao, pauta, itens) — consistencia eventual entre snapshots e' aceitavel p/ um read-model de
  painel ao vivo."
  [repo-sessoes ator id]
  (when-let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) id)]
    (authz/check! ator :sessao/ver s logic/pode-ver-sessao?)
    (let [ente-id (:ente-id ator)
          pauta   (repo/buscar-pauta-por-sessao repo-sessoes ente-id id)
          itens   (when pauta (repo/listar-itens repo-sessoes ente-id (:id pauta)))]
      {:sessao-id id :itens (vec itens)})))

(defn adicionar-item-pauta
  "§22.6 eixo B (pauta viva): adiciona um item a pauta da sessao. Carrega a sessao do tenant do `ator` (nil ->
  404), roda pode-ver-sessao? (mesma Casa -> 403 fail-closed). O Repo faz get-or-create do container 1:1 +
  insere o item na MESMA tx (a pauta e' transparente — a borda adiciona item A SESSAO, nao a um container que o
  cliente cria a parte). `ordem` e' numerada server-side (max+1). created-by = o ator. Devolve {:id :ordem} ou
  nil (sessao inexistente)."
  [repo-sessoes ator m]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) (:sessao-id m))]
    (authz/check! ator :sessao/editar-pauta sessao logic/pode-ver-sessao?)
    (repo/adicionar-item-na-sessao! repo-sessoes (:ente-id ator)
      (assoc m :id (random-uuid) :created-by (:identidade-id ator)))))

(defn- item-desta-sessao
  "Resolve o item `item-id` GARANTINDO que pertence A pauta da sessao do path (anti confused-deputy: sem isto, um
  secretario da Casa reordenaria/removeria item de OUTRA sessao da mesma Casa via a URL desta — espelha o guard
  fala.sessao-id da tribuna). Devolve o item se a pauta existe E o item e' dela; senao nil (-> 404 no diplomat).
  pauta_item.pauta_sessao_id e' imutavel pos-criacao (sem TOCTOU entre este check e a mutacao)."
  [repo-sessoes ente-id sessao-id item-id]
  (when-let [pauta (repo/buscar-pauta-por-sessao repo-sessoes ente-id sessao-id)]
    (when-let [item (repo/buscar-item repo-sessoes ente-id item-id)]
      (when (= (:id pauta) (:pauta-sessao-id item)) item))))

(defn reordenar-item-pauta
  "§22.6 eixo B: move um item da pauta para `nova-ordem` (CAS por lock_version). Carrega a sessao (nil -> 404),
  pode-ver-sessao? (mesma Casa -> 403), e checa que o item e' DESTA sessao (item-desta-sessao; mismatch/inexistente
  -> nil -> 404). reordenar-item! lanca `:conflito/pauta` (lock-stale / item removido; o diplomat mapeia 409).
  updated-by = o ator. Devolve {:id :de :para} ou nil (sessao/pauta/item ausente)."
  [repo-sessoes ator {:keys [sessao-id item-id nova-ordem lock-version]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/editar-pauta sessao logic/pode-ver-sessao?)
    (when (item-desta-sessao repo-sessoes (:ente-id ator) sessao-id item-id)
      (repo/reordenar-item! repo-sessoes (:ente-id ator)
        {:id item-id :nova-ordem nova-ordem :lock-version lock-version :updated-by (:identidade-id ator)}))))

(defn remover-item-pauta
  "§22.6 eixo B: remocao SOFT (ativo=false, nunca DELETE — Inv.10) de um item da pauta, com CAS + LOG. Carrega a
  sessao (nil -> 404), pode-ver-sessao? (mesma Casa -> 403), e checa que o item e' DESTA sessao (anti
  confused-deputy -> 404). `tipo` ∈ {exclusao, retirada_pedido_autor} (validado na borda). remover-item! lanca
  `:conflito/pauta` (lock-stale / ja removido; o diplomat mapeia 409). updated-by = o ator. Devolve {:id :ativo}
  ou nil (sessao/pauta/item ausente)."
  [repo-sessoes ator {:keys [sessao-id item-id tipo justificativa lock-version]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/editar-pauta sessao logic/pode-ver-sessao?)
    (when (item-desta-sessao repo-sessoes (:ente-id ator) sessao-id item-id)
      (repo/remover-item! repo-sessoes (:ente-id ator)
        (cond-> {:id item-id :tipo tipo :lock-version lock-version :updated-by (:identidade-id ator)}
          justificativa (assoc :justificativa justificativa))))))

(defn agendar-sessao
  "Agenda a sessao a partir do mapa de dominio `m` (ja decodificado+validado pelo adapters/in no diplomat). O
  Repo numera+resolve capabilities+insere atomico + emite sessao.agendada (F7 E3) na mesma tx. Devolve o recibo
  de dominio {:id :numero :ocorrido-em} (o `:ocorrido-em` e' interno — o adapters/out expoe so' :id/:numero). A
  authz GROSSA (papel 'secretario') ja foi exigida na rota; a sessao nova nao tem recurso pre-existente p/ camada fina."
  [repo-sessoes ator m]
  (repo/agendar-sessao! repo-sessoes (:ente-id ator) m))

(defn ingerir-segmento
  "Ingesta um segmento de gravacao (§22.6 eixo D / §22.3.4): TRANSMITE o `body-stream` (container bruto) ao
  objeto_store computando o sha256 NO FLUXO (DigestInputStream — sem bufferizar heap), registra o segmento +
  emite `gravacao.segmento-captado` (atomico, no Repo). `meta` = a metadata validada pelo adapters/in. Se
  `meta` carrega `sessao-id` (link-at-ingest), a sessao tem de existir no tenant (nil -> 404 via nil de
  retorno) e ser da mesma Casa (pode-ver-sessao? -> 403 fail-closed). Sem sessao-id = Opcao A (vincula depois).
  Devolve o recibo {:id :audio-hash} ou nil (sessao-id informado mas inexistente -> 404). ente/autor vem do
  `ator`, nunca do cliente (§22.5). A chave do store = `gravacao/<ente>/<segmento>` (server-side)."
  [repo-sessoes objeto-store ator metadata body-stream]
  (let [ente-id   (:ente-id ator)
        sessao-id (:sessao-id metadata)
        sessao    (when sessao-id (repo/buscar-sessao repo-sessoes ente-id sessao-id))]
    (if (and sessao-id (nil? sessao))
      nil                          ; sessao-id informado mas inexistente no tenant -> 404 (diplomat traduz nil)
      (do
        ;; camada FINA: se vinculado, a sessao tem de ser da mesma Casa (-> 403 fail-closed) ANTES de gravar
        (when sessao (authz/check! ator :sessao/ver sessao logic/pode-ver-sessao?))
        (let [seg-id    (random-uuid)
              chave     (str "gravacao/" ente-id "/" seg-id)
              ;; sigilo §22.6 (review sec CRÍTICO): sessao SECRETA -> acesso-restrito SEMPRE true, NUNCA confia
              ;; no flag do cliente (que poderia mandar false e vazar o audio sigiloso ao pipeline de IA). Sem
              ;; vinculo (Opcao A), o flag vem do cliente — o RE-vinculo posterior recalcula (carry de workflow).
              restrito? (if (and sessao (= "secreta" (:tipo-sessao sessao)))
                          true
                          (boolean (:acesso-restrito metadata)))
              md        (MessageDigest/getInstance "SHA-256")
              din       (DigestInputStream. ^java.io.InputStream body-stream md)]
          (store/guardar-stream! objeto-store chave din "application/octet-stream")
          (let [hash-hex (hex (.digest md))]
            (repo/registrar-segmento! repo-sessoes ente-id
              (assoc metadata :id seg-id :container-bruto-uri chave :audio-hash hash-hex
                     :acesso-restrito restrito? :created-by (:identidade-id ator)))
            {:id seg-id :audio-hash hash-hex}))))))

(defn vincular-gravacao
  "Vincula (Opcao A pos-upload) um segmento ja ingerido a uma sessao. Carrega a SESSAO do tenant do `ator`
  (nil -> 404 via nil de retorno), roda pode-ver-sessao? (mesma Casa -> 403 fail-closed), e RE-deriva o sigilo:
  sessao SECRETA forca acesso-restrito=true no vinculo (o flag do cliente na ingestao Opcao A pode ter vindo
  false — mesmo guard de `ingerir-segmento`). O Repo vincula UMA-VEZ (CAS WHERE sessao_id IS NULL + lock_version);
  conflito/ja-vinculado/lock-stale -> lanca `:conflito/vinculo` (o diplomat mapeia 409). updated-by = o ator.
  Devolve o recibo {:id :sessao-id} ou nil (sessao inexistente)."
  [repo-sessoes ator {:keys [sessao-id segmento-id lock-version]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/ver sessao logic/pode-ver-sessao?)
    (repo/vincular-segmento! repo-sessoes (:ente-id ator)
      {:id segmento-id :sessao-id sessao-id :lock-version lock-version
       :updated-by (:identidade-id ator)
       :forcar-acesso-restrito (= "secreta" (:tipo-sessao sessao))})))

(defn listar-gravacoes
  "Read-model dos segmentos de gravacao da sessao `id` p/ o painel. A authz mora no recurso sessao: carrega a
  sessao e roda pode-ver-sessao? ANTES de listar. Devolve {:sessao-id :segmentos [...]} ou nil (sessao
  inexistente -> 404). So segmentos VINCULADOS aparecem (a query filtra por sessao_id)."
  [repo-sessoes ator id]
  (when-let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) id)]
    (authz/check! ator :sessao/ver s logic/pode-ver-sessao?)
    {:sessao-id id :segmentos (vec (repo/listar-segmentos-da-sessao repo-sessoes (:ente-id ator) id))}))

(defn resumo-presenca
  "Read-model da presenca agregada (F7/FE Onda A1), tenant-wide — sem recurso unico p/ camada fina (mesmo
  contrato de `compliance.controllers/painel`). `membros-da-casa` chega JA RESOLVIDO pelo caller (inversao de
  dependencia sobre cadastros; este ns nunca importa cadastros, §22.10). Devolve {:media-percentual
  :sessoes-consideradas :membros-da-casa}."
  [repo-sessoes ente-id membros-da-casa]
  (repo/resumo-presenca repo-sessoes ente-id membros-da-casa))

;; ---------- §22.6 eixo C — a CHAMADA (read-model, GET /sessoes/:id/chamada) ----------

(defn- linha-para-o-adapter
  "Uma linha da chamada pronta p/ o adapter: a linha DERIVADA por `logic/derivar-linhas-da-chamada`
  (identidade + estado + inconsistencia) MAIS o que a derivacao nao carrega — `cargo-mesa` (do roster; nil
  numa linha sem assento, porque cargo mora na Mesa e quem nao tem cadeira nao tem cargo), `desde`/`fonte`/
  `registrado-em` (do evento que a produziu) e o bloco `justificativa`.

  `decidido-em` entra no bloco (revisao): a chamada congela o INSTANTE da presenca, mas le' a justificativa
  no estado CORRENTE — deferir a falta depois e' o efeito desejado, so' que sem este campo a leitura de uma
  sessao ENCERRADA mudava de conteudo ao longo do tempo sem nenhum sinal de quando mudou. O juridico
  comparava a tela com a ata impressa, achava divergencia, e nao tinha na propria resposta como saber se a
  ata estava errada ou se a chamada mudou depois. O dado ja vinha de `listar-justificativas-da-sessao`;
  estava sendo descartado na projecao."
  [{:keys [linha roster-linha evento justificativa]}]
  (merge linha
         {:cargo-mesa (:cargo-mesa roster-linha)
          :desde (:ocorrido-em evento)
          :fonte (:fonte evento)
          :registrado-em (:registrado-em evento)
          :justificativa (when justificativa
                           (select-keys justificativa [:estado :motivo :decidido-em]))}))

;; ---------- §22.6 eixo C — a JUSTIFICATIVA DE AUSENCIA (Etapa 2 da chamada) ----------
;; NAO ha' gate de ESTADO DA SESSAO nestas escritas, e a ausencia e' deliberada — o oposto do que a fatia 2a
;; fez com a presenca. A justificativa e' um ATO ADMINISTRATIVO APARTADO (a propria mig 0029 a define como
;; "juizo posterior != fato observado"): o caso NORMAL e' o vereador registrar a doenca DEPOIS da sessao, e a
;; Mesa apreciar dias depois. Bloquear em sessao encerrada fecharia a porta justamente no momento em que ela
;; e' usada. O que ela nao pode fazer e' reescrever o passado, e nao faz: a chamada de uma sessao fechada
;; congela o INSTANTE da presenca (`instante-de-avaliacao`), mas le' o estado CORRENTE da justificativa —
;; deferir a falta depois e' exatamente o efeito desejado.

(defn- exigir-assento!
  "Fail-closed: `vereador-id` tem de compor a Casa na DATA DE REFERENCIA da sessao (o mesmo roster que a
  chamada cruza — seam `roster-da-casa`, injetado do host). Sem isto, a borda da Mesa aceitaria um uuid
  qualquer no corpo e gravaria uma justificativa que a chamada nunca exibe (a derivacao so' cruza justificativa
  com linha do roster ou com presenca observada): dado morto, silencioso, e que a Casa so' descobre em ata.

  Lanca `:conflito/justificativa` (409) e nao 400: o corpo esta bem formado — o que nao bate e' a COMPOSICAO
  da Casa naquela data, que e' estado do sistema, e a correcao e' em `cadastros`, nao no request."
  [roster-da-casa ente-id sessao vereador-id]
  (let [data (data-de-referencia sessao)]
    (when-not (some #(= vereador-id (:vereador-id %)) (roster-da-casa ente-id data))
      (throw (ex-info (logic/mensagem-de-recusa-de-justificativa :sem-assento)
                      {:tipo :conflito/justificativa :motivo :sem-assento
                       :sessao-id (:id sessao) :data-de-composicao data})))))

(defn- abrir-justificativa*
  "Corpo comum das duas portas de abertura (Mesa e self-service). O que as separa e' a PROCEDENCIA do
  `vereador-id` — do corpo numa, da identidade do ator na outra —, e essa diferenca fica INTEIRA na borda;
  daqui para baixo o ato e' o mesmo. Devolve {:id :sessao-id :vereador-id :estado :lock-version} ou nil
  (sessao inexistente -> 404)."
  [repo-sessoes roster-da-casa ator sessao-id vereador-id motivo]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/abrir-justificativa sessao logic/pode-ver-sessao?)
    (exigir-assento! roster-da-casa (:ente-id ator) sessao vereador-id)
    (merge {:sessao-id sessao-id :vereador-id vereador-id}
           (repo/criar-justificativa! repo-sessoes (:ente-id ator)
             {:id (random-uuid) :sessao-id sessao-id :vereador-id vereador-id :motivo motivo
              :created-by (:identidade-id ator)}))))

(defn abrir-justificativa
  "Porta da MESA (papel 'secretario'): protocola a justificativa de ausencia EM NOME de um vereador — o caso
  comum na camara real (o vereador liga e o servidor lanca). Como o `vereador-id` vem do CORPO, ele passa pelo
  gate de assento (`exigir-assento!`); a porta self-service nao aceita esse campo de jeito nenhum.
  Duplicata p/ o mesmo (sessao, vereador) -> `:conflito/justificativa` (409, e a existente fica intacta)."
  [repo-sessoes roster-da-casa ator {:keys [sessao-id vereador-id motivo]}]
  (abrir-justificativa* repo-sessoes roster-da-casa ator sessao-id vereador-id motivo))

(defn abrir-minha-justificativa
  "Porta SELF-SERVICE (papel 'vereador'): o vereador registra a PROPRIA ausencia. `vereador-id` NUNCA vem do
  corpo — sai da identidade do ator via `resolver-vereador` (seam injetado pelo host), mesmo contrato
  anti-forja de `confirmar-minha-presenca` e de `/meu/ciencias` do legislativo. Ator sem cadastro de vereador
  vinculado (`resolver-vereador` nil) -> nil (-> 404). O gate de assento tambem vale aqui: ter cadastro nao e'
  ter cadeira na data — um ex-vereador continua resolvendo por identidade."
  [repo-sessoes roster-da-casa resolver-vereador ator {:keys [sessao-id motivo]}]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (abrir-justificativa* repo-sessoes roster-da-casa ator sessao-id vereador-id motivo)))

(defn justificativas-da-sessao
  "Read-model das justificativas de ausencia da sessao p/ a Mesa (papel 'secretario' na borda). A authz mora
  no recurso sessao: carrega a sessao e roda pode-ver-sessao? ANTES de qualquer leitura. Devolve
  {:sessao-id :justificativas [...]} ou nil (sessao inexistente -> 404).

  NAO e' read-model publico e nao vira um: `motivo` pode ser dado pessoal SENSIVEL (saude). Duas leituras em
  tx separadas (sessao, justificativas) sao aceitaveis aqui — e' lista de conferencia, nao contagem de quorum
  (a chamada, que compoe numero, le' as tres fontes numa tx so')."
  [repo-sessoes ator sessao-id]
  (when-let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/ver s logic/pode-ver-sessao?)
    {:sessao-id sessao-id
     :justificativas (vec (repo/listar-justificativas repo-sessoes (:ente-id ator) sessao-id))}))

(defn decidir-justificativa
  "O ATO DA MESA: defere ou indefere a justificativa (estados terminais), com CAS por lock_version. Carrega a
  sessao (nil -> 404), roda pode-ver-sessao? (mesma Casa -> 403) e exige que a justificativa seja DESTA sessao
  (anti confused-deputy — sem isso a URL de uma sessao decidiria o ato de outra da mesma Casa; espelha o guard
  `fala.sessao-id` da tribuna e o `item-desta-sessao` da pauta). `justificativa_ausencia.sessao_id` e' imutavel
  pos-criacao, entao nao ha' TOCTOU entre este check e o UPDATE.

  IMPEDIMENTO — ninguem e' juiz em causa propria: se a identidade do ator resolve para o MESMO vereador da
  justificativa, a decisao e' NEGADA (403), mesmo que ele tenha o papel 'secretario'. O papel autoriza a
  CATEGORIA do ato (decidir faltas); nao autoriza decidir a propria. Um servidor que tambem e' vereador existe
  em camara pequena, e sem esta trava ele deferiria a propria ausencia com um PATCH.

  O IMPEDIMENTO FALHA FECHADO (revisao): quando `resolver-vereador` devolve nil E o ator carrega o papel
  'vereador', a decisao tambem e' NEGADA. `resolver-vereador` casa por `cadastros.vereador.identidade_id`, e
  esse vinculo e' um ato SEPARADO e `admin_ente`-gated (`PATCH /cadastros/vereadores/:id/identidade`) — NULL
  e' o estado PADRAO. Com o guard escrito como `(and meu (= meu ...))`, o `and` curto-circuitava e a negacao
  NUNCA disparava justamente na configuracao de dados mais comum: um membro da Casa com credencial de
  'secretario' e sem vinculo resolvivel deferia a propria falta, e a falta e' o insumo do art. 55 CF / LOM
  (perda de mandato) e do desconto de jeton. Impossibilidade de AVALIAR o impedimento tem de negar, nao
  permitir — e' a mesma disciplina fail-closed do resto do modulo (allowlist de estado, `exigir-assento!`).
  Quem for barrado por aqui destrava vinculando a identidade em `cadastros`, que e' o cadastro faltando.

  Lock stale / ja terminal / inexistente -> `:conflito/justificativa` (409). Devolve
  {:justificativa-id :de :para} ou nil (sessao/justificativa ausente, ou de outra sessao).

  SEM EVENTO DE DOMINIO, e por escolha: o payload deste ato carregaria o vereador e o estado, mas o outbox e'
  lido por um relay COMPARTILHADO e projetado por outros modulos (inclusive `transparencia`, PUBLICO). A
  chamada e' derivada NA LEITURA — o painel ao vivo ve a mudanca no proximo GET /chamada, sem que nada
  atravesse a fronteira de modulo. Se um dia um painel exigir push, o evento entra carregando
  {justificativa-id, sessao-id, vereador-id, estado} e NUNCA o `motivo`."
  [repo-sessoes resolver-vereador ator {:keys [sessao-id justificativa-id estado lock-version]}]
  (let [ente-id (:ente-id ator)]
    (when-let [sessao (repo/buscar-sessao repo-sessoes ente-id sessao-id)]
      (authz/check! ator :sessao/decidir-justificativa sessao logic/pode-ver-sessao?)
      (when-let [j (repo/buscar-justificativa repo-sessoes ente-id justificativa-id)]
        (when (= sessao-id (:sessao-id j))
          (let [meu-vereador-id (resolver-vereador ente-id (:identidade-id ator))]
            (when (or (= meu-vereador-id (:vereador-id j))
                      (and (nil? meu-vereador-id) (authz/tem-papel? ator "vereador")))
              (authz/negar! :juiz-em-causa-propria
                            {:acao :sessao/decidir-justificativa :justificativa-id justificativa-id
                             :ator (:identidade-id ator)
                             :vinculo-resolvido? (some? meu-vereador-id)})))
          (assoc (repo/decidir-justificativa! repo-sessoes ente-id
                   {:id justificativa-id :estado estado :lock-version lock-version
                    :decidido-por (:identidade-id ator)})
                 :justificativa-id justificativa-id))))))

(defn- projetar-chamada
  "PURO. A PROJECAO da chamada a partir dos insumos JA LIDOS (`:sessao :instante :presencas :justificativas
  :chamadas-conduzidas` — um unico snapshot do Repo) mais o `roster` da data. Nao toca no banco.

  Existe extraida (Etapa 5 fatia 1, revisao) porque DOIS chamadores precisam da MESMA projecao a partir de
  leituras diferentes: `chamada-da-sessao*` (que le' `repo/chamada-da-sessao`) e `folha-da-sessao` (que le'
  `repo/folha-da-sessao`, o mesmo mais a serie). A alternativa — a folha CHAMAR `chamada-da-sessao*` e
  depois voltar ao banco pelo resto — e' justamente o defeito que a revisao achou: cada chamada ao Repo e'
  uma tx nova, e o documento saia montado de snapshots diferentes. Aqui a projecao viaja; a leitura, nao."
  [{:keys [sessao instante presencas justificativas chamadas-conduzidas]} roster data agora]
  ;; A UNIAO (roster + presencas orfas) e a derivacao vem de `logic`, e nao montadas aqui: e' a MESMA funcao
  ;; que o Repo usa para congelar o denominador no ato de chamada conduzida. Duas montagens da uniao davam
  ;; dois denominadores para a mesma sessao (revisao MAJOR).
  (let [linhas (mapv linha-para-o-adapter
                     (logic/derivar-linhas-da-chamada roster presencas justificativas))]
    {:sessao-id (:id sessao)
     :sessao-estado (:estado sessao)
     :instante instante
     :data-de-composicao data
     :composicao-resolvida-em agora
     :sem-registro-de-presenca (empty? presencas)
     :linhas linhas
     :quorum (logic/contar-quorum linhas)
     ;; Etapa 2d: distingue "ninguem conduziu a chamada ainda" (vazio) de "a chamada aconteceu e a Casa toda
     ;; faltou" (nao-vazio + `:sem-registro-de-presenca` true) — o read-model de presenca_evento sozinho e'
     ;; cego a essa diferenca (os dois casos produzem zero linhas nele).
     :chamadas-conduzidas (vec chamadas-conduzidas)}))

(defn- chamada-da-sessao*
  "O CORPO da chamada, parametrizado pela POLITICA da camada fina — `chamada-da-sessao` (nominal) e
  `quorum-da-sessao` (magra) sao os dois chamadores. Parametrizar a politica existe por corretude: as duas
  leituras publicam o MESMO calculo para PUBLICOS diferentes, e a magra atravessa a fronteira da sessao
  SECRETA se herdar `pode-ver-sessao?` cru (ver `logic/pode-ver-quorum-da-sessao?`). Duplicar o calculo para
  variar a authz seria reabrir o defeito que as Etapas 1 e 2 mataram: duas aritmeticas da mesma Casa.

  A CHAMADA da sessao `sessao-id` (§22.6 eixo C): cruza o ROSTER da Casa (`roster-da-casa`, seam injetado do
  host sobre `cadastros` — este ns nunca importa cadastros, §22.10), a PRESENCA CORRENTE (ultimo evento por
  vereador ate' o `instante` resolvido) e as JUSTIFICATIVAS DE AUSENCIA (as duas ultimas numa UNICA tx do
  Repo, `repo/chamada-da-sessao` — evita o TOCTOU de le-las em tx separadas), deriva o estado de cada
  vereador (`logic/derivar-linha-chamada`) e conta o quorum (`logic/contar-quorum`) SOBRE as linhas ja
  derivadas — nunca um recalculo a parte, p/ a tela e a policy nunca contarem numeros diferentes.

  Camada FINA: a sessao vem da MESMA leitura que a presenca (`repo/chamada-da-sessao`, uma tx) — nao ha' um
  `buscar-sessao` antes. E' correcao de revisao, nao economia de round-trip: com a sessao lida numa tx
  ANTERIOR, a Mesa podia encerrar a sessao no meio do request e a chamada avaliaria 'agora' (estado stale
  'aberta') uma sessao ja fechada, incluindo um evento POSTERIOR ao encerramento numa chamada que vai para a
  ata. O `instante` e' resolvido DENTRO da tx, da sessao fresca (`logic/instante-de-avaliacao`); nil de
  retorno (sessao inexistente neste tenant) -> 404; `pode-ver-sessao?` roda sobre essa mesma sessao, ANTES
  de qualquer uso do dado lido (a tx ja e' escopada pelo ente do ator, RLS isola a Casa). A DATA DE
  REFERENCIA do roster sai dela tambem — nunca do cliente, nunca de 'hoje' implicito.

  AS LINHAS SAO A UNIAO, nao a intersecao: as do roster (com presenca/justificativa cruzadas por vereador) e
  as SEM ASSENTO — presencas cujo vereador o roster da data nao contem. Descartar as segundas era o defeito
  que a revisao achou: o quorum que o motor de votacao resolve por nome conta sobre `presenca_evento`
  sozinho, entao a tela e a policy passavam a contar conjuntos diferentes na MESMA votacao. Elas vao no fim
  da lista, ordenadas por id (o roster ja vem ordenado por nome+id).

  `sem-registro-de-presenca` = true quando a sessao nao tem NENHUM evento de presenca (a Casa inteira
  aparece `:ausente` porque ninguem registrou nada ainda, nao porque a Casa faltou). Devolve o mapa de
  dominio pronto p/ `adapters-out-presenca/chamada->wire`, ou nil (sessao inexistente -> 404 no diplomat)."
  [repo-sessoes roster-da-casa ator sessao-id relogio acao politica]
  (let [ente-id (:ente-id ator)
        agora   (tempo/agora relogio)]
    (when-let [{:keys [sessao] :as lido} (repo/chamada-da-sessao repo-sessoes ente-id sessao-id agora)]
      (authz/check! ator acao sessao politica)
      (let [data (data-de-referencia sessao)]
        (projetar-chamada lido (roster-da-casa ente-id data) data agora)))))

(defn chamada-da-sessao
  "A CHAMADA NOMINAL (`GET /sessoes/:id/chamada`, papel 'secretario' na borda) — o corpo inteiro vive em
  `chamada-da-sessao*`; aqui so' a POLITICA da camada fina: `pode-ver-sessao?` (mesma Casa). Deliberadamente
  NAO herda a clausula de sessao secreta de `pode-ver-quorum-da-sessao?`: quem chega aqui ja' passou pelo
  papel 'secretario' na borda, que e' justamente a excecao daquela clausula — apertar de novo tiraria da
  Mesa a chamada da sessao secreta, que ela conduz."
  [repo-sessoes roster-da-casa ator sessao-id relogio]
  (chamada-da-sessao* repo-sessoes roster-da-casa ator sessao-id relogio
                      :sessao/ver logic/pode-ver-sessao?))

(defn quorum-da-sessao
  "A leitura MAGRA de quorum (Etapa 4a): os NUMEROS da chamada, sem uma linha nominal sequer.

  E' literalmente `chamada-da-sessao` com um `select-keys` na saida — e essa e' a decisao, nao um atalho de
  implementacao. Escrever aqui uma consulta propria de contagem daria a MESMA sessao duas aritmeticas da
  composicao da Casa, que e' o defeito que a Etapa 1 (uniao roster+presencas orfas) e a Etapa 2 (denominador
  congelado do ato de chamada) gastaram uma revisao cada para matar. Reusando a funcao inteira, os dois
  numeros nao podem divergir: nao ha' um segundo lugar onde divergir. O custo (resolver o roster e derivar
  as linhas para depois descarta-las) e' o mesmo do GET da chamada e paga essa garantia.

  A AUTHZ, essa, NAO e' herdada — e' a unica coisa que a rota magra nao pode copiar da nominal. Ela roda
  `logic/pode-ver-quorum-da-sessao?` (mesma Casa E (transmissao publica OU papel 'secretario')), e nao
  `pode-ver-sessao?` cru: sem essa clausula a rota vira a porta dos fundos da sessao SECRETA que o SSE do
  painel recusa por politica explicita (achado MAJOR da revisao desta branch). Herda de graca o resto — o
  `instante` congelado de sessao encerrada e o 409 acionavel de sessao sem data marcada.

  CARRY (registrado aqui para nao se perder, revisao MEDIO/security): esta e' hoje a leitura mais CARA do
  modulo atras da MENOR barreira de authz — resolve o roster inteiro, a presenca corrente e as
  justificativas para descartar as linhas. Nao ha' rate-limit em lugar nenhum do backend e o interceptor
  global crava `Cache-Control: no-store`, entao nem proxy amortece. Um cliente escrito a mao (nao o telao,
  que dispara 1 request por carga + refetch debounced) pode la-la em laco. Mitigacao quando houver infra:
  1 req/s por (identidade, sessao) -> 429, ou cache de 1s por (ente, sessao) em Valkey.

  Devolve {:sessao-id :sessao-estado :instante :data-de-composicao :composicao-resolvida-em
  :sem-registro-de-presenca :quorum} ou nil (sessao inexistente -> 404 no diplomat)."
  [repo-sessoes roster-da-casa ator sessao-id relogio]
  (some-> (chamada-da-sessao* repo-sessoes roster-da-casa ator sessao-id relogio
                              :sessao/ver-quorum logic/pode-ver-quorum-da-sessao?)
          (select-keys [:sessao-id :sessao-estado :instante :data-de-composicao :composicao-resolvida-em
                        :sem-registro-de-presenca :quorum])))

;; ---------- §22.6 eixo C — o ATO da CHAMADA CONDUZIDA (Etapa 2d) ----------

(defn registrar-chamada-conduzida
  "Registra o ATO de que a chamada foi CONDUZIDA (Etapa 2d): distingue 'ninguem chamou ainda' de 'chamou, e a
  Casa toda estava ausente' — `presenca_evento` sozinho produz zero linhas nos dois casos. Carrega a sessao
  do tenant do `ator` (nil -> 404 via nil de retorno), roda pode-ver-sessao? (mesma Casa -> 403 fail-closed).
  `conduzida-por` = o ator (nunca do cliente — nao ha' corpo nesta rota). Este ns resolve o ROSTER na data de
  referencia (o seam e' cross-modulo e o Repo nao o alcanca) e o PASSA para o Repo; o DENOMINADOR CONGELADO
  e' computado LA DENTRO, na mesma tx e sobre a mesma uniao que a leitura publica (revisao: computa-lo aqui,
  roster-only, dava um numero que divergia da tela no caso do licenciado presente — e o registro e'
  append-only). `ocorrido-em` = o relogio do servidor (`agora`, ja' lido na borda), nunca do cliente — o
  clamp da hora e' trivialmente satisfeito (mesmo contrato de `confirmar-minha-presenca`); o que morde e' o
  ESTADO da sessao, o denominador zero e o teto de atos, todos checados DENTRO da tx do Repo.

  Devolve {:id :ocorrido-em :registrado-em :membros-da-casa :conduzida-por [:ja-registrado]} ou nil (sessao
  inexistente -> 404). `:ja-registrado` marca o REENVIO deduplicado (a borda responde 200, nao 201). Recusa
  lanca `:conflito/chamada` (o diplomat mapeia 409)."
  [repo-sessoes roster-da-casa ator sessao-id agora]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/conduzir-chamada sessao logic/pode-ver-sessao?)
    (let [data   (data-de-referencia sessao)
          roster (roster-da-casa (:ente-id ator) data)]
      (merge {:conduzida-por (:identidade-id ator)}
             (repo/registrar-chamada-conduzida! repo-sessoes (:ente-id ator)
               {:id (random-uuid) :sessao-id sessao-id :conduzida-por (:identidade-id ator)
                :roster roster :ocorrido-em agora :agora agora
                :created-by (:identidade-id ator)})))))

;; ---------- §22.6 eixo C — a FOLHA DA SESSAO (Etapa 5 fatia 1) ----------

(defn folha-da-sessao
  "O DOCUMENTO da folha de presenca da sessao `sessao-id` (Etapa 5 fatia 1, D1/D2/D6): a chamada da sessao
  FECHADA MAIS a SERIE completa de eventos por vereador. NAO ha' congelamento nesta fatia (a linha do banco,
  os dois hashes/refs, e' Fatia 4) — esta funcao devolve o DOCUMENTO puro, formato-agnostico.

  D1 (nao recalcula): reusa `projetar-chamada` (a MESMA projecao que serve `chamada-da-sessao` nominal) para
  `:linhas`/`:quorum`/`:chamadas-conduzidas` — nao ha' aqui uma segunda passada por
  `logic/derivar-linhas-da-chamada`/`logic/contar-quorum`. O gate de authz e' o MESMO da chamada NOMINAL
  (`logic/pode-ver-sessao?`, acao propria `:sessao/ver-folha` so' para o audit distinguir): a folha carrega
  MAIS dado sensivel que a chamada (o `motivo` de justificativa, abaixo), nunca o gate magro do quorum.

  D6 (fail-closed, allowlist): so' sessao em `logic/estados-sessao-fechada` tem folha — checado
  IMEDIATAMENTE apos a leitura (antes de qualquer segunda consulta), e por ALLOWLIST (o mesmo set que
  `instante-de-avaliacao` usa para congelar o instante), nunca pelo complemento dos estados 'abertos'
  (complemento aceitaria um estado NOVO e desconhecido — o caso em que ninguem pensou).

  UM SO' SNAPSHOT (correcao de revisao MAJOR): TODAS as leituras vem de `repo/folha-da-sessao`, UMA tx. A
  forma anterior encadeava quatro chamadas ao Repo (`chamada-da-sessao` + `buscar-sessao` +
  `serie-de-eventos-da-sessao` + `listar-justificativas`), e cada chamada ao Repo abre uma tx NOVA — quatro
  snapshots MVCC. A justificativa de ausencia e' um ato APARTADO, sem gate de estado de sessao (e' assim de
  proposito: a Mesa a decide dias depois), entao ela PODE ser decidida entre a 1a e a 4a leitura: a mesma
  justificativa saia `pendente` na linha derivada e `aprovada` no bloco cru, no MESMO documento. Um artefato
  que a Fatia 4 congela em PDF e assina nao pode divergir de si mesmo. Depois do retorno do Repo, este ns
  NAO volta ao banco: so' os dois seams de `cadastros` (roster e cabecalho), que sao outro modulo e outra
  tx por definicao de fronteira (§22.10) — e nenhum deles alimenta a aritmetica do quorum.

  A SERIE usa a MESMA janela que a chamada: `piso-da-janela-de-presenca` (o dia civil da sessao) como piso,
  o `:instante` (== `instante-de-avaliacao`) como teto — os dois resolvidos DENTRO da tx do Repo. `piso` nil
  (sessao sem `aberta-em`/`agendada-para` — improvavel numa sessao fechada, mas nao impossivel por CHECK)
  LANCA: sem piso nao ha' janela, e servir a serie sem filtro de piso vazaria eventos de fora da sessao
  para dentro do documento — o mesmo tipo de defeito que `instante-de-avaliacao` recusa a todo custo.

  `:justificativas` sai CRUA (com `motivo` — LGPD) da mesma leitura que derivou as linhas: as linhas de
  `chamada` derivaram o ESTADO a partir dela mas NAO carregam o texto do motivo (`LinhaChamada` nao o expoe).

  `dados-da-casa` (seam injetado do host sobre `cadastros`, irmao LITERAL de `roster-da-casa`) resolve o
  cabecalho (nome/legislatura) NA DATA de composicao da chamada — nunca 'hoje' fechado dentro do seam.

  Devolve o DOCUMENTO (`gerador-folha/renderizar`), ou nil (sessao inexistente neste ente -> 404, mesmo
  contrato de `chamada-da-sessao`). Sessao ainda aberta/suspensa/agendada lanca `:conflito/folha-sessao-aberta`."
  [repo-sessoes roster-da-casa dados-da-casa ator sessao-id relogio]
  (let [ente-id (:ente-id ator)
        agora   (tempo/agora relogio)]
    (when-let [{:keys [sessao] :as lido} (repo/folha-da-sessao repo-sessoes ente-id sessao-id agora)]
      (authz/check! ator :sessao/ver-folha sessao logic/pode-ver-sessao?)
      (when-not (contains? logic/estados-sessao-fechada (:estado sessao))
        (throw (ex-info "folha-da-sessao: so' sessao FECHADA tem folha"
                        {:tipo :conflito/folha-sessao-aberta :sessao-id sessao-id
                         :estado (:estado sessao)})))
      (when (nil? (:piso lido))
        (throw (ex-info "folha-da-sessao: sessao fechada sem piso de janela de presenca"
                        {:tipo :servidor/erro :sessao-id sessao-id})))
      (let [data    (data-de-referencia sessao)
            chamada (projetar-chamada lido (roster-da-casa ente-id data) data agora)]
        (gerador-folha/renderizar
         {:sessao {:id sessao-id :estado (:estado sessao)
                   :motivo-nao-realizada (:motivo-nao-realizada sessao)}
          :instante (:instante chamada)
          :cabecalho-da-casa (dados-da-casa ente-id (:data-de-composicao chamada))
          :linhas (:linhas chamada)
          :quorum (:quorum chamada)
          :serie (logic/agrupar-serie-por-vereador (:serie lido))
          :justificativas (:justificativas lido)
          :atos-de-chamada-conduzida (:chamadas-conduzidas chamada)})))))

;; ---------- §22.6 eixo C — O CONGELAMENTO DA FOLHA (Etapa 5 fatia 4, D2/D3/D4/D6/D7/D8/D9) ----------

(defn- sha256-hex
  "Hash hex SHA-256 do binario, prefixado 'sha256:' — integridade do congelamento (duplica conscientemente o
  helper de `legislativo/components/repositorio.clj`/`compliance/components/repositorio.clj`: 3 linhas,
  `sessoes` nao importa nenhum dos dois modulos, ADR-0001; consolidar num `kernel/hash` e' cleanup futuro,
  nao vale acoplar os modulos agora). Reusa `hex` (ja' definido no topo deste ns para os digests da gravacao)."
  [^bytes b]
  (str "sha256:" (hex (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn- store-ref-folha [ente-id sessao-id hash-conteudo extensao]
  (str "folhas/" ente-id "/" sessao-id "/" hash-conteudo "." extensao))

(defn- congelar-tentativa
  "UMA tentativa do laco de D7: le' o MAX (`repo/max-versao-da-folha`), renderiza HTML e PDF com a versao
  PREVISTA (`max+1`), hasheia os dois, e tenta o INSERT com essa versao EXPLICITA (`repo/inserir-folha!`).
  Devolve `{:row :html :pdf}` em sucesso; PROPAGA `PSQLException` em 23505 — o chamador (`congelar!`) decide
  re-tentar. `html`/`pdf` sao os mapas `{:bytes :content-type}` que os ports devolveram (o CALLER guarda os
  binarios DEPOIS do INSERT ter commitado, nunca aqui — mesma ordem ANCORA-PRIMEIRO do molde)."
  [repo-sessoes ente-id sessao-id doc serializador renderizador-pdf gerada-por agora]
  (let [versao        (inc (repo/max-versao-da-folha repo-sessoes ente-id sessao-id))
        html          (serializador-folha/serializar serializador doc versao)
        html-hash     (sha256-hex (:bytes html))
        pdf           (renderizador-pdf/renderizar renderizador-pdf (:bytes html) agora)
        pdf-hash      (sha256-hex (:bytes pdf))
        row (repo/inserir-folha! repo-sessoes ente-id
              {:id (ids/novo-id) :sessao-id sessao-id :versao versao :spec-versao (:spec-versao doc)
               :html-hash html-hash :html-content-type (:content-type html)
               :html-objeto-store-ref (store-ref-folha ente-id sessao-id html-hash "html")
               :pdf-hash pdf-hash :pdf-content-type (:content-type pdf)
               :pdf-objeto-store-ref (store-ref-folha ente-id sessao-id pdf-hash "pdf")
               :gerada-por gerada-por :gerada-em agora})]
    {:row row :html html :pdf pdf}))

(defn- congelar!
  "D7 — DESVIO CONSCIENTE DO MOLDE, escrito aqui para a proxima revisao NAO 'consertar' de volta para
  `INSERT ... SELECT MAX+1` (o padrao de `legislativo/gerar-artefato-publicacao!`/`compliance/gerar-
  remessa!`): a folha IMPRIME a propria versao no papel (`serializador-folha/serializar` na aridade-3), e
  por isso o NUMERO tem de ser conhecido ANTES da renderizacao — um INSERT atomico que decide a versao SO'
  depois de os bytes existirem seria circular aqui. Em vez disso: le' o MAX, renderiza com o numero
  PREVISTO, tenta o INSERT explicito; se colidir (23505 — duas Secretarias congelando a MESMA sessao no
  MESMO instante), RELE o max (ja' enxerga o commit concorrente) e REFAZ A RENDERIZACAO INTEIRA com o
  numero novo. O `UNIQUE (ente_id, sessao_id, versao)` (mig 0073) continua sendo o que garante a corretude
  sob corrida — so' muda QUEM PROPOE o numero. Retry UNICO (mesma disciplina de `inserir-com-retry!`/
  `inserir-artefato-com-retry!`): uma SEGUNDA colisao no mesmo pedido e' bug, nao corrida legitima.

  ANCORA-ANTES-DO-BLOB, e por que e' MAIS seguro aqui que nos moldes: a linha insere primeiro; os DOIS
  binarios vao ao `objeto-store` DEPOIS, so' se o INSERT commitou. Se o `objeto-store` falhar entre os dois
  `guardar!`, a linha aponta para um blob que nao existe — mas, ao contrario de `artefato_publicacao`/
  `remessa_gerada`, aqui o binario e' RE-DERIVAVEL byte a byte a partir da PROPRIA linha: a renderizacao e'
  deterministica (Fatias 2/3) e todo insumo (versao, `gerada_em`, o dado da sessao — imutavel depois de
  fechada) ja' esta' gravado. A linha nao e' so' uma ancora para triagem manual; e' a receita completa."
  [repo-sessoes ente-id sessao-id doc serializador renderizador-pdf objeto-store gerada-por agora]
  (let [tentar #(congelar-tentativa repo-sessoes ente-id sessao-id doc serializador renderizador-pdf
                                    gerada-por agora)
        {:keys [row html pdf]}
        (try (tentar)
             (catch PSQLException e
               (if (= "23505" (.getSQLState e)) (tentar) (throw e))))]
    (store/guardar! objeto-store (:html-objeto-store-ref row) (:bytes html) (:content-type html))
    (store/guardar! objeto-store (:pdf-objeto-store-ref row) (:bytes pdf) (:content-type pdf))
    row))

(defn gerar-folha!
  "Congela a folha de presenca da sessao FECHADA `sessao-id` (Etapa 5 fatia 4): renderiza o DOCUMENTO em HTML
  canonico + PDF, hasheia os dois, e insere UMA linha versionada em `sessoes.folha_sessao` (D3 — um trio
  html_*/pdf_* cada, NUNCA duas linhas). D4: NENHUM evento de dominio emitido (o molde da remessa, `compliance/
  events/remessa.clj`, e' so' comentario sem consumidor — abrir uma segunda ferida evento-sem-consumidor e'
  proibido); a linha do banco e' a ANCORA. `m` = `{:serializador :renderizador-pdf :objeto-store}`, os TRES
  ports injetados POR CHAMADA (nunca campos do record) — a mesma forma de `gerar-artefato-publicacao!`.

  1. CARREGA O DOCUMENTO reusando `folha-da-sessao` (acima, MESMA funcao, UMA SO' tx no Repo) — isto e' o
     que cumpre D6 (fail-closed: so' sessao em `logic/estados-sessao-fechada` chega aqui — `folha-da-sessao`
     JA' lanca `:conflito/folha-sessao-aberta` senao) e a AUTHZ (`logic/pode-ver-sessao?`, a MESMA politica
     da chamada nominal — nunca a magra do quorum, a folha carrega MAIS dado sensivel) SEM duplicar nenhuma
     das duas aqui. `nil` (sessao inexistente neste ente) propaga como `nil` (404 na borda).
  2. D8 — `m/validate FolhaDocumento` ANTES de renderizar: um artefato IMUTAVEL nao aceita schema como
     promessa (`gerador-folha/renderizar` nao valida no caminho de producao, so' em teste). Invalido lanca
     `:servidor/erro` com o `m/explain` na ex-data, fail-closed antes de qualquer byte ser produzido.
  3. D9 — DEDUP: uma folha da MESMA sessao gerada pelo MESMO ator ha' menos de
     `logic/janela-de-deduplicacao-de-chamada` (30s) devolve a folha EXISTENTE (`:ja-congelada true`) em vez
     de congelar outra — mesmo precedente de `registrar-chamada-conduzida!`/`:ja-registrado` (Etapa 2d): um
     duplo-clique nao pode poluir o acervo com versoes identicas numeradas.
  4. D7 — o CONGELAMENTO em si (`congelar!`, docstring la' tem o desvio de molde por extenso).

  Devolve a linha de `sessoes.folha_sessao` (com `:ja-congelada true` no caminho do dedup), ou `nil` (sessao
  inexistente neste ente -> 404 na borda, mesmo contrato de `folha-da-sessao`)."
  [repo-sessoes roster-da-casa dados-da-casa ator sessao-id relogio
   {:keys [serializador renderizador-pdf objeto-store]}]
  (when-not objeto-store
    (throw (ex-info "gerar-folha!: objeto-store ausente" {:sessao-id sessao-id})))
  (when-not serializador
    (throw (ex-info "gerar-folha!: serializador ausente" {:sessao-id sessao-id})))
  (when-not renderizador-pdf
    (throw (ex-info "gerar-folha!: renderizador-pdf ausente" {:sessao-id sessao-id})))
  (let [ente-id (:ente-id ator)
        agora   (tempo/agora relogio)
        doc     (folha-da-sessao repo-sessoes roster-da-casa dados-da-casa ator sessao-id relogio)]
    (when doc
      (when-not (m/validate mod-folha/FolhaDocumento doc)
        (throw (ex-info "gerar-folha!: documento nao bate FolhaDocumento — recusado ANTES de renderizar"
                        {:tipo :servidor/erro :sessao-id sessao-id
                         :explicacao (m/explain mod-folha/FolhaDocumento doc)})))
      (let [gerada-por (:identidade-id ator)
            desde      (.minus ^Instant agora logic/janela-de-deduplicacao-de-chamada)]
        (if-let [existente (repo/folha-recente-do-ator repo-sessoes ente-id sessao-id gerada-por desde)]
          (assoc existente :ja-congelada true)
          (congelar! repo-sessoes ente-id sessao-id doc serializador renderizador-pdf objeto-store
                     gerada-por agora))))))
