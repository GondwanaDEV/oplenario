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
