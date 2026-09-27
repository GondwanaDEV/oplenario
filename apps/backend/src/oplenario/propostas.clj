(ns oplenario.propostas
  "Host (§22.10): a PROPOSTA DE ATO (ADR-0012, docs/25 Eixo 4.2 B). O agente nunca executa um `ato`: a ferramenta cria
  uma proposta com a entrada exata e o que a pessoa vai ver; a pessoa confirma NA TELA — com o ritual da acao
  (assinatura em 2 toques) — e so' entao a MESMA entrada do catalogo roda, como ela, com papel e policy recalculados.

  Aqui: os seams do catalogo (`propor`, `marcar-terceiro`), a confirmacao/recusa e as rotas da tela. Estado so' anda
  por UPDATE condicional: a segunda confirmacao encontra `executando` e nao executa de novo."
  (:require [clojure.tools.logging :as log]
            [oplenario.catalogo :as catalogo]
            [oplenario.http :as http]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.catalogo :as kcat]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def prazo (Duration/ofHours 72))

(def mensagem-ao-agente
  (str "Proposta criada. Nada foi feito ainda: a pessoa revisa e confirma (ou recusa) na tela Propostas da "
       "plataforma. Diga isso a ela; nao diga que o ato foi feito."))

;; ---------- seams do catalogo (chamados pelo kernel na execucao do AGENTE) ----------

(defn propositor
  "O seam `:propor` (fn [ator e entrada apresentacao]) — grava a proposta com as leituras de terceiro que a execucao
  ja' fez (Eixo 4.5) e devolve o que a ferramenta diz ao agente (`kcat/PropostaOut`)."
  [repo relogio]
  (fn [ator e entrada {:keys [titulo texto]}]
    (let [via (:via ator)
          agora ^Instant (tempo/agora relogio)
          p (repo-ia/criar-proposta! repo {:ente-id (:ente-id ator) :execucao-id (:execucao-id via)
                                           :identidade-id (:identidade-id ator) :agente (:agente via)
                                           :ferramenta (:nome e) :entrada entrada :titulo titulo :texto texto
                                           :ritual (name (:ritual e))
                                           :contaminada-por (repo-ia/leituras-de-terceiro repo (:ente-id ator)
                                                                                          (:execucao-id via))
                                           :expira-em (java.sql.Timestamp/from (.plus agora prazo))})]
      {:proposta-id (str (:id p)) :titulo (:titulo p) :estado "aguardando_confirmacao"
       :mensagem mensagem-ao-agente})))

(defn marcador-de-terceiro
  "O seam `:marcar-terceiro` (fn [ator e marcas]) — as leituras de conteudo de terceiro da execucao."
  [repo]
  (fn [ator e marcas]
    (doseq [{:keys [origem referencia]} marcas]
      (repo-ia/registrar-leitura-de-terceiro! repo {:ente-id (:ente-id ator)
                                                     :execucao-id (get-in ator [:via :execucao-id])
                                                     :ferramenta (:nome e) :origem origem :referencia referencia}))))

;; ---------- a tela ----------

(defn- conflito! [tipo msg] (throw (ex-info msg {:tipo tipo})))

(defn- so-pessoa! [ator]
  (when (:via ator) (authz/negar! :agente-nao-confirma {:motivo "confirmar proposta e' da pessoa, na tela (Eixo 4.2 B)"})))

(defn- ->instant ^Instant [x] (if (instance? java.util.Date x) (.toInstant ^java.util.Date x) x))

(defn- vencida? [p ^Instant agora] (.isAfter agora (->instant (:expira-em p))))

(defn- da-pessoa
  "A proposta, se for DESTA pessoa; nil (-> 404) para a de outra pessoa, que nem se distingue da inexistente."
  [repo ator id]
  (when-let [p (repo-ia/proposta repo (:ente-id ator) id)]
    (when (= (:identidade-id p) (:identidade-id ator)) p)))

(defn- expirar-se-vencida [repo relogio ator p]
  (if (and (= "aguardando" (:estado p)) (vencida? p (tempo/agora relogio)))
    (or (repo-ia/mudar-estado-proposta! repo (:ente-id ator) (:id p) "aguardando" {:estado "expirada" :decidida? true})
        p)
    p))

(defn listar [{:keys [repo-integracao-ia relogio]} ator]
  (so-pessoa! ator)
  (repo-ia/propostas-da-pessoa repo-integracao-ia (:ente-id ator) (:identidade-id ator)
                               (java.sql.Timestamp/from (tempo/agora relogio))))

(defn ver
  "A proposta e, se ainda espera, o que seria feito AGORA (`:apresentacao-atual` — a data do texto e' a de hoje)."
  [{:keys [repo-integracao-ia relogio deps-catalogo]} ator id]
  (so-pessoa! ator)
  (when-let [p (some->> (da-pessoa repo-integracao-ia ator id) (expirar-se-vencida repo-integracao-ia relogio ator))]
    (let [e (get catalogo/por-nome (:ferramenta p))]
      (assoc p :apresentacao-atual
             (when (and e (= "aguardando" (:estado p)))
               (try ((:apresentar e) deps-catalogo ator (kcat/entrada-validada e (:entrada p)))
                    (catch clojure.lang.ExceptionInfo ex
                      (log/info "proposta nao se apresenta mais" (:ferramenta p) (:tipo (ex-data ex)))
                      nil)))))))

(defn- exige-aguardando! [p]
  (case (:estado p)
    "aguardando" nil
    "expirada" (conflito! :proposta/expirada "Esta proposta expirou. Peça de novo ao assistente, se ainda quiser.")
    (conflito! :proposta/decidida "Esta proposta já foi decidida.")))

(defn confirmar!
  "A pessoa confirma: toma a proposta (aguardando -> executando), executa a entrada guardada pela MESMA entrada do
  catalogo como ela, e fecha (`confirmada` + resultado). Falha devolve a `aguardando` com o erro e relanca."
  [{:keys [repo-integracao-ia relogio deps-catalogo]} ator id]
  (so-pessoa! ator)
  (when-let [p (some->> (da-pessoa repo-integracao-ia ator id) (expirar-se-vencida repo-integracao-ia relogio ator))]
    (exige-aguardando! p)
    (let [ente (:ente-id ator)
          mudar! (fn [de m] (repo-ia/mudar-estado-proposta! repo-integracao-ia ente id de m))
          e (or (get catalogo/por-nome (:ferramenta p))
                (conflito! :proposta/sem-acao "A ação desta proposta não existe mais."))]
      (when-not (mudar! "aguardando" {:estado "executando"})
        (conflito! :proposta/decidida "Esta proposta já foi decidida."))
      (let [saida (try (kcat/executar e deps-catalogo ator (:entrada p))
                       (catch Exception ex
                         (mudar! "executando" {:estado "aguardando" :erro "Não foi possível executar agora."})
                         (throw ex)))]
        (if (some? saida)
          (mudar! "executando" {:estado "confirmada" :resultado saida :decidida? true})
          (do (mudar! "executando" {:estado "aguardando" :erro "O que a proposta pede não existe mais."})
              (conflito! :proposta/sem-objeto "O que a proposta pede não existe mais (o modelo, a matéria…).")))))))

(defn recusar! [{:keys [repo-integracao-ia relogio]} ator id]
  (so-pessoa! ator)
  (when-let [p (some->> (da-pessoa repo-integracao-ia ator id) (expirar-se-vencida repo-integracao-ia relogio ator))]
    (exige-aguardando! p)
    (or (repo-ia/mudar-estado-proposta! repo-integracao-ia (:ente-id ator) id "aguardando"
                                        {:estado "recusada" :decidida? true})
        (conflito! :proposta/decidida "Esta proposta já foi decidida."))))

;; ---------- wire ----------

(defn- iso [x] (some-> x ->instant str))

(defn ->wire [p]
  (cond-> {:id (str (:id p)) :ferramenta (:ferramenta p) :agente (:agente p) :titulo (:titulo p) :texto (:texto p)
           :ritual (:ritual p) :estado (:estado p) :contaminada-por (vec (:contaminada-por p))
           :criada-em (iso (:criada-em p)) :expira-em (iso (:expira-em p)) :decidida-em (iso (:decidida-em p))
           :resultado (:resultado p) :erro (:erro p)}
    (contains? p :apresentacao-atual) (assoc :apresentacao-atual (:apresentacao-atual p))))

(defn- id-do-path [req]
  (or (parse-uuid (str (get-in req [:path-params :id])))
      (throw (ex-info "id invalido" {:tipo :validacao/invalido :campo :id}))))

(defn- com-conflito [f]
  (try (f)
       (catch clojure.lang.ExceptionInfo ex
         (if (= "proposta" (namespace (or (:tipo (ex-data ex)) :x/x)))
           (http/json-resposta 409 {:erro (ex-message ex) :causa (name (:tipo (ex-data ex)))})
           (throw ex)))))

(defn rotas
  "A tela das propostas: listar as que esperam a pessoa, ver uma, confirmar ou recusar. Sessao da pessoa (as rotas de
  tela nao aceitam a credencial do agente, ADR-0010); qualquer papel — a acao confere o dela ao executar."
  [{:keys [auth] :as deps}]
  #{["/propostas" :get [auth (fn [req] (http/json-resposta 200 {:itens (mapv ->wire (listar deps (:ator req)))}))]
     :route-name :propostas/listar]
    ["/propostas/:id" :get
     [auth (fn [req] (if-let [p (ver deps (:ator req) (id-do-path req))]
                       (http/json-resposta 200 (->wire p))
                       (http/json-resposta 404 {:erro "proposta não encontrada"})))]
     :route-name :propostas/ver]
    ["/propostas/:id/confirmacao" :post
     [auth it/corpo-json
      (fn [req] (com-conflito #(if-let [p (confirmar! deps (:ator req) (id-do-path req))]
                                 (http/json-resposta 200 (->wire p))
                                 (http/json-resposta 404 {:erro "proposta não encontrada"}))))]
     :route-name :propostas/confirmar]
    ["/propostas/:id/recusa" :post
     [auth it/corpo-json
      (fn [req] (com-conflito #(if-let [p (recusar! deps (:ator req) (id-do-path req))]
                                 (http/json-resposta 200 (->wire p))
                                 (http/json-resposta 404 {:erro "proposta não encontrada"}))))]
     :route-name :propostas/recusar]})
