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

(defn tramitacao-board
  "Read-model do board de tramitacao (§16.11, F7 Slice 2) p/ o tenant do `ator`. Mesma authz/escopo
  tenant-wide de `o-que-vence`."
  [repo-paineis ator]
  (repo/tramitacao-board repo-paineis (:ente-id ator)))

(defn sli-sessoes
  "SLI de janela de sessao (Inv.9, F7 E3) p/ o tenant do `ator`. Mesma authz/escopo tenant-wide."
  [repo-paineis ator]
  (repo/sli-sessoes repo-paineis (:ente-id ator)))

(defn dashboard-mesa
  "Rollups do dashboard da Mesa (F7, §16.11) p/ o tenant do `ator`. Mesma authz/escopo tenant-wide. Devolve
  so' os rollups do proprio paineis; a COMPOSICAO com o card de compliance acontece na borda (diplomat), que
  chama a fn injetada pelo host — o controller nunca cruza modulo (§22.10)."
  [repo-paineis ator]
  (repo/dashboard-mesa repo-paineis (:ente-id ator)))

(defn minhas-notificacoes
  "Inbox do PROPRIO `ator` (Onda E fatia 1). Diferente dos demais paineis deste modulo, o escopo NAO e'
  tenant-wide: e' (tenant, identidade do ator). A identidade vem SEMPRE do ator — nada no request escolhe
  'de quem' e' a inbox (anti-forja por construcao, mesmo contrato de /meu/painel do legislativo)."
  [repo-paineis ator]
  (repo/minhas-notificacoes repo-paineis (:ente-id ator) (:identidade-id ator)))

(defn marcar-lida
  "Marca uma notificacao do PROPRIO ator como lida (Onda E fatia 1). O destinatario e' SEMPRE o do ator —
  nunca do path/corpo (anti-forja). nil = inexistente OU de outro destinatario: a borda traduz os DOIS
  para 404, sem distingui-los (nao vaza existencia)."
  [repo-paineis ator id]
  (repo/marcar-notificacao-lida! repo-paineis (:ente-id ator)
                                 {:id id :destinatario-identidade-id (:identidade-id ator)}))
