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
