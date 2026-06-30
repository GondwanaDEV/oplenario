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

(defn agendar-sessao
  "Agenda a sessao a partir do mapa de dominio `m` (ja decodificado+validado pelo adapters/in no diplomat). O
  Repo numera+resolve capabilities+insere atomico. Devolve o recibo de dominio {:id :numero}. A authz GROSSA
  (papel 'secretario') ja foi exigida na rota; a sessao nova nao tem recurso pre-existente p/ camada fina."
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
