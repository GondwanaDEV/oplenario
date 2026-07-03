(ns oplenario.participacao.controllers
  "Orquestracao (impura) do modulo participacao (§22.10 controllers, ADR-0001): coordena o Repo-Component.
  Trabalha SO em `models`/dados de dominio — NUNCA toca wire/adapters (o import-lint enforca); a traducao da
  borda fica no diplomat. Depende do Repo-Component, nunca do db/ direto. O relogio e' INJETADO (kernel/tempo)
  — determinismo em teste; nunca LocalDate/now direto. O `solicitante`/`created-by` sao INJETADOS do ATOR
  (§22.5: sem ator = proibido), NUNCA do corpo do cliente (anti-forge)."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.ids :as ids]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.participacao.components.repositorio :as repo]
            [oplenario.participacao.logic :as logic])
  (:import (java.time ZoneId)))

(set! *warn-on-reflection* true)

(def zona-civil
  "Fuso civil p/ o calculo do prazo LAI (prazos legais correm por fuso, nao UTC). V1 = America/Fortaleza
  (beachhead NE); fuso por-ente e' refino futuro (carry). Consistente entre protocolar e acompanhar."
  (ZoneId/of "America/Fortaleza"))

(def ^:private prazo-fonte-lai
  "Proveniencia do prazo (citacao legal). [GAP] de conteudo: corridos-vs-uteis nao cravado (V1 = corridos)."
  "LAI 12.527/2011 art. 11 §1º (20 dias; corridos-vs-uteis [GAP])")

(def ^:private prazo-fonte-recurso
  "Proveniencia do prazo do RECURSO. [GAP] de conteudo: a autoridade superior/CGU tem prazo PROPRIO na LAI, mas
  o numero exato nao esta cravado nesta fatia (ver logic/dias-recurso-esic) — default documentado, nao lei."
  "LAI 12.527/2011 (recurso; prazo da autoridade superior [GAP] — default documentado)")

(def ^:private prazo-fonte-titular
  "Proveniencia do prazo LGPD (citacao legal). [GAP] de conteudo: a LGPD nao cravou um numero unico p/ todos os
  direitos do titular (o art. 19 fixa 15 dias so p/ confirmacao/acesso); CONTADOR SEPARADO do e-SIC — ver
  logic/dias-titular. Default documentado, nao lei."
  "LGPD 13.709/2018 art. 18/19 (prazo do titular [GAP] — default documentado; contador SEPARADO do e-SIC)")

(defn- em-conflito!
  "Sinaliza CONFLITO DE CICLO (a borda mapeia p/ 409): a operacao e' incompativel com o estado atual do
  agregado (pedido ja terminal, recurso ja decidido, pedido nao-recorrivel). Distinto de nil (ausente -> 404)
  e de authz/negar! (403). Espelha o :conflito/remessa de compliance."
  [razao info]
  (throw (ex-info razao (assoc info :tipo :conflito/participacao))))

(defn protocolar-pedido
  "Protocola um pedido e-SIC do `ator` (cidadao). Computa o recibo (Instant = marco do relogio) e o vencimento
  (LAI 20 dias corridos) do relogio INJETADO. UMA tx no Repo (sequencial+pedido+prazo+evento). solicitante e
  created-by INJETADOS do ator, NUNCA do corpo. Devolve {:id :protocolo :recibo-em}."
  [repo-participacao relogio ator {:keys [assunto descricao]}]
  (let [agora   (tempo/agora relogio)
        ;; UMA leitura do relogio por ato: recibo (Instant) e prazo (LocalDate) ancoram no MESMO instante.
        ;; Derivar `hoje` de `agora` (nao chamar tempo/hoje, que releria o relogio) evita o straddle de
        ;; meia-noite — recibo num dia civil e vence-em/ano derivados de outro (prazo LAI off-by-one).
        hoje    (tempo/hoje-de agora zona-civil)
        ano     (.getYear hoje)
        vence   (logic/vence-em hoje)
        sujeito (:identidade-id ator)]
    (repo/protocolar-pedido! repo-participacao (:ente-id ator)
      {:id (ids/novo-id) :ano ano :assunto assunto :descricao descricao
       :solicitante-identidade-id sujeito :recibo-em agora :vence-em vence
       :prazo-id (ids/novo-id) :base-dias logic/dias-lai-esic :prazo-fonte-ref prazo-fonte-lai
       :created-by sujeito})))

(defn- dias-restantes-do-prazo
  "dias-restantes do prazo contra o `hoje` do relogio, ou nil quando nao ha prazo ativo (read-derivation pura)."
  [relogio prazo]
  (when prazo (logic/dias-restantes (:vence-em prazo) (tempo/hoje relogio zona-civil))))

(defn acompanhar-por-protocolo
  "Andamento PUBLICO de um pedido por protocolo, no tenant `ente-id` (resolvido do path na borda; a RLS isola).
  Le pedido+prazo in-schema (1 tx) e computa dias-restantes. Devolve {:protocolo :estado :dias-restantes} ou
  nil (protocolo inexistente no tenant). NAO devolve PII — o diplomat projeta pelo adapters/out publico."
  [repo-participacao ente-id relogio protocolo]
  (when-let [{:keys [pedido prazo]} (repo/acompanhar-por-protocolo repo-participacao ente-id protocolo)]
    {:protocolo      (:protocolo pedido)
     :estado         (:estado pedido)
     :dias-restantes (dias-restantes-do-prazo relogio prazo)}))

(defn meu-pedido
  "Detalhe do pedido `id` para o proprio SOLICITANTE (rota autenticada). Policy FINA (camada in-domain,
  §22.5 eixo E): so o DONO le — ator != solicitante -> authz/negar! (403). Devolve o mapa de detalhe (pedido
  + prazo + dias-restantes) ou nil (inexistente -> 404). A RLS ja escopa por tenant; esta e' a checagem de
  propriedade DENTRO do tenant."
  [repo-participacao ator relogio id]
  (when-let [{:keys [pedido prazo]} (repo/pedido-com-prazo repo-participacao (:ente-id ator) id)]
    (when (not= (:solicitante-identidade-id pedido) (:identidade-id ator))
      (authz/negar! :nao-e-solicitante {:pedido-id id :ator (:identidade-id ator)}))
    (assoc pedido
           :vence-em       (:vence-em prazo)
           :dias-restantes (dias-restantes-do-prazo relogio prazo))))

(defn meus-pedidos
  "'Meus pedidos' do `ator` (lista por solicitante). Read-model do cidadao autenticado (sem rota em Slice 1;
  o diplomat a expoe quando a tela pedir). ATENCAO: devolve linhas CRUAS (incl. ente-id + solicitante = PII) —
  NAO exponha na borda sem um adapters/out que filtre/projete (como pedido->wire faz), senao vaza tenant+PII."
  [repo-participacao ator]
  (repo/pedidos-do-solicitante repo-participacao (:ente-id ator) (:identidade-id ator)))

;; ========================= SLICE 2: responder / recorrer / decidir =========================

(defn responder-pedido!
  "SERVIDOR responde o pedido `id` (papel exigido na rota). UMA tx no Repo: CAS pedido->respondido + resposta
  append-only + cumpre o prazo do PEDIDO + emit. respondido-por INJETADO do ator (nunca do corpo). Devolve
  {:respondida-em} (a borda projeta), ou nil (pedido inexistente -> 404); se o pedido AINDA existe mas ja e'
  terminal (CAS falhou), :conflito/participacao (-> 409)."
  [repo-participacao relogio ator id {:keys [corpo]}]
  (let [ente-id (:ente-id ator)
        agora   (tempo/agora relogio)]
    (or (repo/responder-pedido! repo-participacao ente-id
          {:pedido-id id :resposta-id (ids/novo-id) :corpo corpo
           :respondido-por (:identidade-id ator) :respondida-em agora})
        (when (repo/buscar-pedido repo-participacao ente-id id)
          (em-conflito! "pedido ja respondido/indeferido (nao ha o que responder)" {:pedido-id id})))))

(defn interpor-recurso!
  "CIDADAO interpoe recurso ao pedido `id` (rota SO-auth, sem papel — LAI: qualquer solicitante recorre). Policy
  FINA (in-domain, §22.5 eixo E): (a) pedido inexistente no tenant -> nil (404); (b) ator != solicitante do
  pedido -> authz/negar! (403); (c) pedido NAO-recorrivel (ainda nao respondido/indeferido) -> :conflito (409).
  So entao UMA tx no Repo (recurso + prazo PROPRIO + emit). O relogio do recurso e' INDEPENDENTE do pedido:
  recibo/vence derivam de UMA leitura do relogio no ato da interposicao. instancia=1 (1a instancia; multiplas =
  refino futuro). Devolve {:id :protocolo :recibo-em}."
  [repo-participacao relogio ator id {:keys [motivo]}]
  (let [ente-id (:ente-id ator)]
    (when-let [pedido (repo/buscar-pedido repo-participacao ente-id id)]
      (when (not= (:solicitante-identidade-id pedido) (:identidade-id ator))
        (authz/negar! :nao-e-solicitante {:pedido-id id :ator (:identidade-id ator)}))
      (when-not (logic/pedido-admite-recurso? (:estado pedido))
        (em-conflito! "pedido nao admite recurso (ainda nao respondido/indeferido)"
                      {:pedido-id id :estado (:estado pedido)}))
      (let [agora   (tempo/agora relogio)
            hoje    (tempo/hoje-de agora zona-civil)
            ano     (.getYear hoje)
            vence   (logic/vence-em-recurso hoje)
            sujeito (:identidade-id ator)]
        (repo/interpor-recurso! repo-participacao ente-id
          {:recurso-id (ids/novo-id) :pedido-id id :ano ano :instancia 1 :motivo motivo
           :recibo-em agora :vence-em vence :prazo-id (ids/novo-id) :base-dias logic/dias-recurso-esic
           :prazo-fonte-ref prazo-fonte-recurso :created-by sujeito})))))

(defn decidir-recurso!
  "SERVIDOR decide o recurso `id` (papel exigido na rota). UMA tx: CAS recurso->decidido (+ decidido_em) +
  resposta append-only(recurso) + cumpre o prazo do RECURSO + emit. respondido-por INJETADO do ator. Devolve
  {:decidido-em}, ou nil (recurso inexistente -> 404); recurso ja decidido -> :conflito/participacao (409)."
  [repo-participacao relogio ator id {:keys [corpo]}]
  (let [ente-id (:ente-id ator)
        agora   (tempo/agora relogio)]
    (or (repo/decidir-recurso! repo-participacao ente-id
          {:recurso-id id :resposta-id (ids/novo-id) :corpo corpo
           :respondido-por (:identidade-id ator) :respondida-em agora :decidido-em agora})
        (when (repo/buscar-recurso repo-participacao ente-id id)
          (em-conflito! "recurso ja decidido" {:recurso-id id})))))

;; ========================= SLICE 4: LGPD — solicitacao do titular + Encarregado/DPO =========================

(defn solicitar-titular!
  "TITULAR autenticado solicita o exercicio de um direito LGPD (rota SO-auth, sem papel — qualquer titular pede
  sobre os PROPRIOS dados). Computa o recibo (Instant = marco do relogio LGPD) e o vencimento (dias-titular,
  CONTADOR SEPARADO do e-SIC) do relogio INJETADO. UMA tx no Repo (sequencial+solicitacao+prazo+evento). titular e
  created-by INJETADOS do ator, NUNCA do corpo. `detalhe` opcional. Devolve {:id :protocolo :recibo-em}."
  [repo-participacao relogio ator {:keys [tipo detalhe]}]
  (let [agora   (tempo/agora relogio)
        hoje    (tempo/hoje-de agora zona-civil)
        ano     (.getYear hoje)
        vence   (logic/vence-em-titular hoje)   ; CONTADOR SEPARADO (dias-titular, nao a LAI 20)
        sujeito (:identidade-id ator)]
    (repo/solicitar-titular! repo-participacao (:ente-id ator)
      {:id (ids/novo-id) :ano ano :tipo tipo :detalhe detalhe
       :titular-identidade-id sujeito :recibo-em agora :vence-em vence
       :prazo-id (ids/novo-id) :base-dias logic/dias-titular :prazo-fonte-ref prazo-fonte-titular
       :created-by sujeito})))

(defn minha-solicitacao
  "Detalhe da solicitacao `id` para o proprio TITULAR (rota autenticada). Policy FINA (§22.5 eixo E): so o DONO
  le — ator != titular -> authz/negar! (403). Devolve o mapa de detalhe (solicitacao + prazo + dias-restantes) ou
  nil (inexistente -> 404). A RLS ja escopa por tenant; esta e' a checagem de propriedade DENTRO do tenant."
  [repo-participacao ator relogio id]
  (when-let [{:keys [solicitacao prazo]} (repo/solicitacao-titular-com-prazo repo-participacao (:ente-id ator) id)]
    (when (not= (:titular-identidade-id solicitacao) (:identidade-id ator))
      (authz/negar! :nao-e-titular {:solicitacao-id id :ator (:identidade-id ator)}))
    (assoc solicitacao
           :vence-em       (:vence-em prazo)
           :dias-restantes (dias-restantes-do-prazo relogio prazo))))

(defn responder-solicitacao!
  "SERVIDOR/Encarregado responde a solicitacao `id` (papel exigido na rota). UMA tx no Repo: CAS solicitacao->
  respondida + resposta append-only + cumpre o prazo do TITULAR + emit. respondido-por INJETADO do ator (nunca do
  corpo). Devolve {:respondida-em} (a borda projeta), ou nil (solicitacao inexistente -> 404); se ainda existe mas
  ja e' terminal (CAS falhou), :conflito/participacao (-> 409). (CARRY: o cumprimento AUTOMATIZADO de
  revogar_consentimento via identidade e' guard futuro do host — em V1 o DPO processa manual, sem cross-modulo.)"
  [repo-participacao relogio ator id {:keys [corpo]}]
  (let [ente-id (:ente-id ator)
        agora   (tempo/agora relogio)]
    (or (repo/responder-solicitacao! repo-participacao ente-id
          {:solicitacao-id id :resposta-id (ids/novo-id) :corpo corpo
           :respondido-por (:identidade-id ator) :respondida-em agora})
        (when (repo/buscar-solicitacao-titular repo-participacao ente-id id)
          (em-conflito! "solicitacao do titular ja respondida/indeferida (nao ha o que responder)"
                        {:solicitacao-id id})))))

(defn definir-encarregado!
  "SERVIDOR define/atualiza o contato PUBLICO do Encarregado/DPO (papel exigido na rota). UPSERT (1 por ente):
  a 1a vez cria; as seguintes atualizam a MESMA linha. atualizado-por INJETADO do ator. Devolve o mapa da linha."
  [repo-participacao ator {:keys [nome rotulo email]}]
  (repo/definir-encarregado! repo-participacao (:ente-id ator)
    {:id (ids/novo-id) :nome nome :rotulo rotulo :email email :atualizado-por (:identidade-id ator)}))

(defn encarregado-publico
  "Contato PUBLICO do Encarregado/DPO do tenant `ente-id` (resolvido do path na borda; a RLS isola). LGPD art. 41
  §1º: o contato do Encarregado e' de divulgacao publica. Devolve o mapa da linha ou nil (ente sem DPO definido ->
  404). NAO devolve interno — o diplomat projeta pelo adapters/out publico (so nome/rotulo/email)."
  [repo-participacao ente-id]
  (repo/buscar-encarregado repo-participacao ente-id))
