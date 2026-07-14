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
