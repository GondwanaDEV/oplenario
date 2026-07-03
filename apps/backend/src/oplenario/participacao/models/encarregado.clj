(ns oplenario.participacao.models.encarregado
  "Representacao INTERNA (dominio) do ENCARREGADO/DPO (§22.10 models/, ADR-0001) — Malli. CONFIG-like, UM por
  ente (contato PUBLICO exigido pela LGPD art. 41). MUTAVEL (nao append-only): `atualizado-por` = o servidor que
  definiu o contato (injetado do ator). O contato PUBLICO projetado ao cidadao (wire/out EncarregadoPublicoOut)
  carrega SO {nome, rotulo, email} — a defesa anti-vazamento de interno (ids, atualizado-por) mora no adapters/out."
  (:require [oplenario.kernel.malli :as km]))

(def Encarregado
  "Encarregado/DPO persistido (participacao.encarregado)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:nome [:string {:min 1}]]
   [:rotulo [:string {:min 1}]]
   [:email [:string {:min 1}]]
   [:atualizado-por :uuid]
   [:criado-em km/Instante]
   [:atualizado-em km/Instante]])
