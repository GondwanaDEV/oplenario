(ns oplenario.transparencia.controllers
  "Orquestracao IMPURA do portal (§22.10 controllers, ADR-0001) — coordena o Repo-Component. As rotas do
  Slice 1 (listar/ficha materia+legislacao) sao PUBLICAS (sem ator). As do Slice 2 (acompanhamento) sao
  AUTENTICADAS (cidadao): ente-id + seguidor vem do ATOR (nunca do corpo/path — anti-forge)."
  (:require [oplenario.kernel.ids :as ids]
            [oplenario.transparencia.components.repositorio :as repo]))

(defn listar-materias
  "Portal: materias em tramitacao (sem exclusao de estado nesta fatia — lista tudo, mais recente primeiro)."
  [repo-transparencia ente-id]
  (repo/listar-materias repo-transparencia ente-id #{}))

(defn ficha-materia
  "A ficha PUBLICA de uma materia — a materia + a norma publicada, se houver (liga 'proposicao -> lei').
  Devolve nil se a materia nao existe no portal (proposicao nunca protocolada, ou tenant errado)."
  [repo-transparencia ente-id proposicao-id]
  (when-let [m (repo/buscar-materia repo-transparencia ente-id proposicao-id)]
    (assoc m :norma (repo/norma-da-materia repo-transparencia ente-id proposicao-id))))

(defn listar-normas
  "Portal: legislacao PUBLICADA as-enacted (normas publicadas, mais recente primeiro)."
  [repo-transparencia ente-id]
  (repo/listar-normas repo-transparencia ente-id))

(defn buscar-norma
  "Uma norma publicada especifica, ou nil."
  [repo-transparencia ente-id norma-id]
  (repo/buscar-norma repo-transparencia ente-id norma-id))

;; ---------- Slice 2: acompanhamento do cidadao (autenticado; consent-gated) ----------

(defn seguir!
  "CIDADAO segue a `proposicao-id`. GUARD: so' se a materia existe no read-model (nao se segue UUID solto nem
  materia nao publicada) — ausente -> nil (borda -> 404). ente-id + seguidor INJETADOS do ator. UPSERT
  (re-seguir reativa). O ato de seguir E' o consentimento de ser notificado (§22.5). Devolve {:estado ...} ou nil."
  [repo-transparencia ator proposicao-id]
  (let [ente-id (:ente-id ator)]
    (when (repo/buscar-materia repo-transparencia ente-id proposicao-id)
      (repo/seguir! repo-transparencia ente-id
        {:id (ids/novo-id) :proposicao-id proposicao-id
         :seguidor-identidade-id (:identidade-id ator) :created-by (:identidade-id ator)}))))

(defn deixar-de-seguir!
  "CIDADAO deixa de seguir a `proposicao-id` (soft-cancel idempotente — retira o consentimento). ente-id +
  seguidor do ator. Sem guard de existencia da materia (deixar de seguir e' sempre seguro; no-op se nao seguia)."
  [repo-transparencia ator proposicao-id]
  (repo/deixar-de-seguir! repo-transparencia (:ente-id ator)
    {:proposicao-id proposicao-id :seguidor-identidade-id (:identidade-id ator)}))

(defn meus-acompanhamentos
  "'minhas materias acompanhadas' do cidadao autenticado — escopo pelo seguidor do ATOR (nunca ve as de
  outro; sem :id, sem policy fina necessaria — a query ja filtra por seguidor)."
  [repo-transparencia ator]
  (repo/meus-acompanhamentos repo-transparencia (:ente-id ator) (:identidade-id ator)))
