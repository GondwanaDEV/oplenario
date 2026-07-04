(ns oplenario.paineis.controllers
  "Orquestracao (impura) do modulo paineis (§22.10 controllers, ADR-0001): coordena o Repo-Component.
  Trabalha SO em `models`/dados de dominio — NUNCA toca wire/adapters (o import-lint enforca); a traducao
  da borda fica no diplomat. Depende do Repo-Component, nunca do db/ direto."
  (:require [oplenario.paineis.components.repositorio :as repo]))

(set! *warn-on-reflection* true)

(defn o-que-vence
  "Read-model 'o que vence' (§16.11) p/ o tenant do `ator`. A authz GROSSA (papel) ja foi exigida na rota;
  o painel e' tenant-wide (sem recurso unico p/ camada fina — o escopo e' o proprio ente do ator, isolado
  por RLS). O teto do read e' server-side (no Repo)."
  [repo-paineis ator]
  (repo/o-que-vence repo-paineis (:ente-id ator)))
