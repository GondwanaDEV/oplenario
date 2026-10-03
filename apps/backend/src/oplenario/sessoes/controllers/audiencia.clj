(ns oplenario.sessoes.controllers.audiencia
  "Orquestracao da AUDIENCIA PUBLICA (ADR-0021 Parte A). Trabalha so' em models; depende do Repo-Component e dos SEAMS
  do host (§22.10 — sessoes nunca importa cadastros, legislativo nem identidade), num mapa `deps`:
    :repo-sessoes         o Repo (RepoSessoes + RepoAudiencia)
    :comissoes-vigentes   (fn [ente-id] -> [{:id :nome}])       a comissao que promove tem de ser vigente (agendar)
    :nomes-de-comissoes   (fn [ente-id ids] -> {id nome})        o nome da comissao nas telas
    :rotular-proposicoes  (fn [ente-id ids] -> {id {:rotulo :ementa}}) a materia em debate
    :nome-da-identidade   (fn [identidade-id] -> nome|nil)       o nome de quem se inscreve pelo gov.br
    :relogio              kernel/tempo (o ano do protocolo)
  A authz GROSSA (papel) fica na rota; a FINA (mesma Casa) roda aqui, com a sessao carregada."
  (:require [clojure.tools.logging :as log]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.components.repositorio-audiencia :as repo-aud]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.logic.audiencia :as logic-aud]))

(set! *warn-on-reflection* true)

(defn- recusa! [msg motivo] (throw (ex-info msg {:tipo :conflito/audiencia :motivo motivo})))

;; ---------- os seams (enriquecimento: falham para nil, nunca derrubam a leitura) ----------

(defn- nome-da-comissao [{:keys [nomes-de-comissoes]} ente-id comissao-id]
  (try (get (nomes-de-comissoes ente-id [comissao-id]) comissao-id)
       (catch Exception e (log/warn e "nome da comissao indisponivel; a audiencia segue sem ele") nil)))

(defn- nomes-das-comissoes [{:keys [nomes-de-comissoes]} ente-id ids]
  (if (empty? ids)
    {}
    (try (nomes-de-comissoes ente-id (vec (distinct ids)))
         (catch Exception e (log/warn e "nomes das comissoes indisponiveis") {}))))

(defn- proposicao-da-audiencia [{:keys [rotular-proposicoes]} ente-id proposicao-id]
  (when proposicao-id
    (try (when-let [r (get (rotular-proposicoes ente-id #{proposicao-id}) proposicao-id)]
           (assoc r :id proposicao-id))
         (catch Exception e (log/warn e "rotulo da proposicao indisponivel; a audiencia segue sem ele") nil))))

;; ---------- agendar (o bloco `audiencia` de POST /sessoes) ----------

(defn agendar-sessao
  "POST /sessoes. A sessao comum segue o caminho de sempre. A AUDIENCIA PUBLICA confere, antes de gravar, que a
  comissao que promove e' VIGENTE nesta Casa e que a proposicao em debate (se informada) existe aqui — os dois por
  seam do host. Recusa = `:conflito/audiencia-invalida` (a borda responde 422 com a frase)."
  [{:keys [repo-sessoes comissoes-vigentes rotular-proposicoes]} ator m]
  (when-let [a (:audiencia m)]
    (let [ente-id (:ente-id ator)]
      (when-not (some #(= (:comissao-id a) (:id %)) (comissoes-vigentes ente-id))
        (throw (ex-info "a comissao informada nao e' uma comissao vigente desta Casa"
                        {:tipo :conflito/audiencia-invalida :campo :comissao-id})))
      (when-let [pid (:proposicao-id a)]
        (when-not (contains? (rotular-proposicoes ente-id #{pid}) pid)
          (throw (ex-info "a proposicao informada nao existe nesta Casa"
                          {:tipo :conflito/audiencia-invalida :campo :proposicao-id}))))))
  (controllers/agendar-sessao repo-sessoes ator m))

;; ---------- a Mesa (interno) ----------

(defn- sessao-autorizada [repo-sessoes ator sessao-id]
  (when-let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (authz/check! ator :sessao/ver s logic/pode-ver-sessao?)
    s))

(defn audiencia-da-sessao
  "GET /sessoes/:id/audiencia: a sessao, a audiencia, a comissao (nome), a proposicao (rotulo, ementa) e as inscricoes
  na ordem. nil = sessao inexistente ou que nao e' audiencia (404)."
  [{:keys [repo-sessoes] :as deps} ator sessao-id]
  (when-let [s (sessao-autorizada repo-sessoes ator sessao-id)]
    (when-let [{:keys [audiencia inscricoes]} (repo-aud/audiencia-da-sessao repo-sessoes (:ente-id ator) sessao-id)]
      {:sessao s :audiencia audiencia :inscricoes inscricoes
       :comissao-nome (nome-da-comissao deps (:ente-id ator) (:comissao-id audiencia))
       :proposicao (proposicao-da-audiencia deps (:ente-id ator) (:proposicao-id audiencia))})))

(defn atualizar-audiencia!
  "PATCH /sessoes/:id/audiencia. Devolve a audiencia como `audiencia-da-sessao`, ou nil (404)."
  [{:keys [repo-sessoes] :as deps} ator sessao-id campos]
  (when-let [_ (sessao-autorizada repo-sessoes ator sessao-id)]
    (when (repo-aud/atualizar-audiencia! repo-sessoes (:ente-id ator) sessao-id campos (:identidade-id ator))
      (audiencia-da-sessao deps ator sessao-id))))

(defn- ano-de [{:keys [relogio]}]
  (logic/ano-civil (tempo/agora (or relogio (tempo/relogio-sistema)))))

(defn inscrever-presencial!
  "POST /sessoes/:id/audiencia/inscricoes: a Mesa inscreve quem esta' presente (sem identidade; o nome digitado).
  Recusa (sessao que nao aceita inscricao, audiencia acabada) = `:conflito/audiencia` (409). nil = 404."
  [{:keys [repo-sessoes] :as deps} ator sessao-id m]
  (when (sessao-autorizada repo-sessoes ator sessao-id)
    (repo-aud/inscrever-cidadao! repo-sessoes (:ente-id ator)
                                 (assoc m :sessao-id sessao-id :ano (ano-de deps) :origem "presencial_secretaria"
                                          :identidade-id nil :registrada-por (:identidade-id ator)
                                          :pelo-portal? false))))

(defn conduzir-inscricao!
  "As tres escritas da Mesa sobre uma inscricao: `para` = `falando` (chamada), `falou` (encerramento, com
  `:tempo-usado-segundos`) ou `ausente`. nil = sessao/inscricao inexistente (ou de outra sessao) -> 404."
  [{:keys [repo-sessoes]} ator sessao-id inscricao-id para extra]
  (when (sessao-autorizada repo-sessoes ator sessao-id)
    (repo-aud/transicionar-inscricao! repo-sessoes (:ente-id ator)
                                      (merge extra {:sessao-id sessao-id :inscricao-id inscricao-id :para para}))))

;; ---------- o portal (anonimo) ----------

(defn- resumo [comissoes linha]
  {:sessao-id (:id linha) :tema (:tema linha) :comissao-nome (get comissoes (:comissao-id linha))
   :agendada-para (:agendada-para linha) :estado (:estado linha) :local (:local linha)
   :finalidade (:finalidade linha)})

(defn audiencias-publicas
  "GET /portal/casa/:ente/audiencias: {:proximas :realizadas} em `ResumoAudiencia` (so' transmissao publica)."
  [{:keys [repo-sessoes] :as deps} ente-id]
  (let [{:keys [proximas realizadas]} (repo-aud/audiencias-publicas repo-sessoes ente-id)
        comissoes (nomes-das-comissoes deps ente-id (map :comissao-id (concat proximas realizadas)))]
    {:proximas (mapv #(resumo comissoes %) proximas)
     :realizadas (mapv #(resumo comissoes %) realizadas)}))

(defn audiencia-publica
  "GET /portal/casa/:ente/audiencias/:sessao-id. nil (404) = inexistente, nao e' audiencia ou nao e' publica. As
  inscricoes abertas sao as EFETIVAS (a flag da Mesa e o estado da sessao); `falaram` so' depois de encerrada; quem
  desistiu ou nao falou nunca aparece."
  [{:keys [repo-sessoes] :as deps} ente-id sessao-id]
  (let [s (repo/buscar-sessao repo-sessoes ente-id sessao-id)]
    (when (and s (logic-aud/audiencia? s) (true? (:transmite-publica s)))
      (when-let [{:keys [audiencia inscricoes ata-publicada]}
                 (repo-aud/audiencia-da-sessao repo-sessoes ente-id sessao-id)]
        {:sessao s :audiencia audiencia
         :comissao-nome (nome-da-comissao deps ente-id (:comissao-id audiencia))
         :proposicao (proposicao-da-audiencia deps ente-id (:proposicao-id audiencia))
         :inscricoes-abertas (logic-aud/inscricoes-abertas-efetivas? s audiencia)
         :inscritos (logic-aud/inscritos-publicos inscricoes)
         :ata-publicada (boolean ata-publicada)
         :falaram (logic-aud/falaram s inscricoes)}))))

;; ---------- a cidada (autenticada) ----------

(defn inscrever-pelo-portal!
  "POST /portal/audiencias/:sessao-id/inscricoes. A sessao tem de ser da Casa de quem pede (a RLS do ator: de outra
  Casa = 404) e audiencia PUBLICA; o NOME vem da identidade (gov.br), nunca do corpo. Ja' inscrito ou inscricoes
  fechadas = `:conflito/audiencia` (409). Devolve a inscricao gravada (protocolo, ordem, criado-em)."
  [{:keys [repo-sessoes nome-da-identidade] :as deps} ator sessao-id m]
  (when (nil? (:identidade-id ator)) (authz/negar! :sem-identidade {:acao :audiencia/inscrever}))
  (let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) sessao-id)]
    (when (and s (logic-aud/audiencia? s) (true? (:transmite-publica s)))
      (authz/check! ator :audiencia/inscrever s logic/pode-ver-sessao?)
      (let [nome (some-> (nome-da-identidade (:identidade-id ator)) not-empty)]
        (when-not nome (recusa! "nao foi possivel ler o seu nome da identidade gov.br" :sem-nome))
        (repo-aud/inscrever-cidadao! repo-sessoes (:ente-id ator)
                                     (assoc m :sessao-id sessao-id :ano (ano-de deps) :origem "portal_govbr"
                                              :identidade-id (:identidade-id ator) :nome nome :registrada-por nil
                                              :pelo-portal? true))))))

(defn minhas-inscricoes
  "GET /portal/minhas-inscricoes: as inscricoes de quem pede, nesta Casa, com o nome da comissao."
  [{:keys [repo-sessoes] :as deps} ator]
  (when (nil? (:identidade-id ator)) (authz/negar! :sem-identidade {:acao :audiencia/minhas-inscricoes}))
  (let [linhas (repo-aud/inscricoes-da-identidade repo-sessoes (:ente-id ator) (:identidade-id ator))
        comissoes (nomes-das-comissoes deps (:ente-id ator) (map :comissao-id linhas))]
    (mapv #(assoc % :comissao-nome (get comissoes (:comissao-id %))) linhas)))

(defn desistir!
  "POST /portal/minhas-inscricoes/:id/desistencia: `inscrita -> desistiu`, SO' de quem se inscreveu (de outra pessoa =
  nil -> 404, sem dizer que a inscricao existe). Devolve a linha como em `minhas-inscricoes`."
  [{:keys [repo-sessoes] :as deps} ator inscricao-id]
  (when (nil? (:identidade-id ator)) (authz/negar! :sem-identidade {:acao :audiencia/desistir}))
  (let [ente-id (:ente-id ator)
        dono? #(= (:identidade-id ator) (:identidade-id %))]
    (when-let [i (repo-aud/buscar-inscricao-cidada repo-sessoes ente-id inscricao-id)]
      (when (dono? i)
        (when (repo-aud/transicionar-inscricao! repo-sessoes ente-id {:sessao-id (:sessao-id i) :inscricao-id inscricao-id
                                                                      :para "desistiu" :dono? dono?})
          (some #(when (= inscricao-id (:id %)) %) (minhas-inscricoes deps ator)))))))
