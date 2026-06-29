(ns oplenario.sessoes.controllers
  "Orquestracao (impura) do modulo sessoes (§22.10 controllers, ADR-0001): coordena Repo-Component + policy.check
  (camada FINA, §22.5 eixo E). Trabalha SO em `models` (kebab) — NUNCA toca wire/adapters (o import-lint enforca);
  a traducao da borda fica no diplomat (que chama adapters/in|out). Depende do Repo-Component, nunca do db/
  (§3-bis). O `ator` (resolvido na borda) e' o sujeito de toda operacao (§22.5: sem ator = proibido)."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.sessoes.components.repositorio :as repo]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(defn buscar-sessao
  "Le a sessao `id` (UUID) do tenant do `ator`. Camada FINA: carrega o recurso e roda policy.check
  (pode-ver-sessao?) ANTES de devolver — quem nao consegue decidir NEGA (check! mapeia -> 403). Devolve a sessao
  de dominio (models) ou nil se nao existe (o diplomat traduz nil -> 404, e a sessao -> wire/out)."
  [repo-sessoes ator id]
  (when-let [s (repo/buscar-sessao repo-sessoes (:ente-id ator) id)]
    (authz/check! ator :sessao/ver s logic/pode-ver-sessao?)
    s))

(defn agendar-sessao
  "Agenda a sessao a partir do mapa de dominio `m` (ja decodificado+validado pelo adapters/in no diplomat). O
  Repo numera+resolve capabilities+insere atomico. Devolve o recibo de dominio {:id :numero}. A authz GROSSA
  (papel 'secretario') ja foi exigida na rota; a sessao nova nao tem recurso pre-existente p/ camada fina."
  [repo-sessoes ator m]
  (repo/agendar-sessao! repo-sessoes (:ente-id ator) m))
