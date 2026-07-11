(ns oplenario.sessoes.controllers
  "Orquestracao (impura) do modulo sessoes (§22.10 controllers, ADR-0001): coordena Repo-Component + policy.check
  (camada FINA, §22.5 eixo E). Trabalha SO em `models` (kebab) — NUNCA toca wire/adapters (o import-lint enforca);
  a traducao da borda fica no diplomat (que chama adapters/in|out). Depende do Repo-Component (e do ObjetoStore
  do kernel, p/ a ingestao de gravacao — kernel e' camada compartilhada, nao outro modulo), nunca do db/
  (§3-bis). O `ator` (resolvido na borda) e' o sujeito de toda operacao (§22.5: sem ator = proibido)."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.logic :as logic])
  (:import (java.security DigestInputStream MessageDigest)))

(set! *warn-on-reflection* true)

(defn- hex
  "byte-array -> string hex minuscula. `bit-and 0xff` desfaz a sign-extension do byte com sinal (sem ela,
  `(format \"%02x\" (byte -1))` daria \"ffffffffffffffff\" — hash malformado, dedup/integridade quebrados)."
  [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

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
  tx (atomicidade §22.9 E2). created-by = o ator. Devolve {:id} ou nil (sessao inexistente)."
  [repo-sessoes ator {:keys [sessao-id vereador-id tipo modalidade ocorrido-em]}]
  (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/registrar-presenca sessao logic/pode-ver-sessao?)
    (let [id (random-uuid)]
      (repo/registrar-presenca! repo-sessoes (:ente-id ator)
        {:id id :sessao-id sessao-id :vereador-id vereador-id :tipo tipo :modalidade modalidade
         :fonte "manual_secretaria" :ocorrido-em ocorrido-em :created-by (:identidade-id ator)})
      {:id id})))

(defn confirmar-minha-presenca
  "Onda C3 — autoatendimento: o vereador confirma a PROPRIA presenca pelo celular. `vereador-id` NUNCA vem
  do corpo (resolvido do ator via `resolver-vereador`, injetado pelo host — mesmo contrato anti-forja de
  `legislativo/controllers.clj/acusar-ciencia`). `fonte` e' SEMPRE 'autoatendimento' (nunca do cliente,
  mesma disciplina de `registrar-presenca` forcando 'manual_secretaria'). `tipo` e' SEMPRE 'entrada':
  reconfirmar nao corrompe nada (append-only; so' o ULTIMO evento por vereador conta, `esta-presente-em?`),
  entao um evento extra e' inofensivo — nao ha necessidade de checar 'ja presente' antes de inserir.
  `modalidade` fixa 'plenario' (V1 = Nivel 1, presenca remota e' so' manual pela Mesa, §22.6). Ator sem
  cadastro vinculado (`resolver-vereador` nil) -> nil (-> 404, mesmo contrato de /meu/ciencias). Sessao
  inexistente no tenant -> nil (-> 404). `instante` vem do RELOGIO do servidor (borda), nunca do cliente."
  [repo-sessoes resolver-vereador ator sessao-id instante]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when-let [sessao (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
      (authz/check! ator :sessao/confirmar-presenca sessao logic/pode-ver-sessao?)
      (let [id (random-uuid)]
        (repo/registrar-presenca! repo-sessoes (:ente-id ator)
          {:id id :sessao-id sessao-id :vereador-id vereador-id :tipo "entrada" :modalidade "plenario"
           :fonte "autoatendimento" :ocorrido-em instante :created-by (:identidade-id ator)})
        {:id id}))))

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
