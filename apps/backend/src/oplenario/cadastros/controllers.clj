(ns oplenario.cadastros.controllers
  "Orquestracao (fina) do modulo cadastros (§22.10 controllers, ADR-0001): a diplomat/borda HTTP (Task 5)
  depende DESTE ns, nunca do protocolo `RepoCadastros` direto. Fatia de leitura de vereador (Task 4) — a
  agregacao ja mora no Repo (Task 2: `listar-vereadores`/`ficha-vereador` rodam numa UNICA tx). Sem logica
  aqui: nao le `hoje`/`data` (a borda resolve e passa `data` pronta)."
  (:require [oplenario.cadastros.components.repositorio :as repo]))

(set! *warn-on-reflection* true)

(defn listar-vereadores
  "Vereadores da Casa `ente-id` com mandato+cargo-na-Mesa vigentes em `data` — pass-through do Repo."
  [repo-cadastros ente-id data]
  (repo/listar-vereadores repo-cadastros ente-id data))

(defn ficha-vereador
  "Ficha composta {:vereador :mandato :legislatura :comissoes} do vereador `id`, ou nil se nao existe —
  pass-through do Repo (a agregacao ja roda numa unica tx la')."
  [repo-cadastros ente-id id data]
  (repo/ficha-vereador repo-cadastros ente-id id data))

(defn criar-vereador
  "INSERT de vereador (identidade_id NULL, efetivado_em=now()). Devolve {:id} com o id gerado no adapters/in."
  [repo-cadastros ente-id m]
  (repo/criar-vereador! repo-cadastros ente-id m)
  {:id (:id m)})

(defn editar-vereador
  "UPDATE parcial de nome/nome-parlamentar — devolve o update-count (o diplomat mapeia 0 -> 404)."
  [repo-cadastros ente-id id campos]
  (repo/atualizar-vereador! repo-cadastros ente-id id campos))

(defn registrar-mandato
  "Pass-through: {:id} | nil (404) | throws :conflito/mandato-sobreposto (409)."
  [repo-cadastros ente-id m]
  (repo/registrar-mandato! repo-cadastros ente-id m))

(defn registrar-licenca
  "Pass-through: {:id} | nil (404) | throws :conflito/sem-mandato-vigente (409)."
  [repo-cadastros ente-id vereador-id l data]
  (repo/registrar-licenca! repo-cadastros ente-id vereador-id l data))

(defn legislatura-vigente
  "A legislatura vigente da Casa (p/ o seletor do form de mandato), ou nil."
  [repo-cadastros ente-id]
  (repo/legislatura-vigente repo-cadastros ente-id))
