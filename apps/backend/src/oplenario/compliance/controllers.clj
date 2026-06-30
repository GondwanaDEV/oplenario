(ns oplenario.compliance.controllers
  "Orquestracao (impura) do modulo compliance (§22.10 controllers, ADR-0001): coordena o Repo-Component. O
  loop de runtime (materializa->avalia->monitora->audita) mora no Repo (F5.1-3); aqui ficam as operacoes
  da BORDA HTTP (F5.5): o read-model do painel (§16.11) + o ciclo da remessa (validar/submeter/registrar
  resposta). Trabalha SO em `models`/dados de dominio — NUNCA toca wire/adapters (o import-lint enforca);
  a traducao da borda fica no diplomat. Depende do Repo-Component, nunca do db/ direto. O `ator` (resolvido
  na borda) e' o sujeito de toda operacao (§22.5: sem ator = proibido)."
  (:require [oplenario.compliance.components.repositorio :as repo]))

(set! *warn-on-reflection* true)

(defn painel
  "Read-model do painel 'a Casa esta em dia com o TCE' (§16.11) p/ o tenant do `ator`. A authz GROSSA
  (papel) ja foi exigida na rota; o painel e' tenant-wide (sem recurso unico p/ camada fina — o escopo e'
  o proprio ente do ator, isolado por RLS). Os TETOS dos reads sao server-side (no Repo). Devolve o
  read-model {:resumo :em-aberto :remessas-recentes} (o diplomat projeta p/ wire)."
  [repo-compliance ator]
  (repo/painel repo-compliance (:ente-id ator) {}))
