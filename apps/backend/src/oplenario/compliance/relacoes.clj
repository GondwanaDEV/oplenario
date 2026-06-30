(ns oplenario.compliance.relacoes
  "Funcoes de RELACAO que o `compliance` e' dono (§22.5.3 / §22.7.5). O motor de DSL as alcanca POR NOME
  via o registry/injecao da F2 — nunca por import (§22.10). Recebem a `tx` do tenant (RLS isola; ente=1:1
  -> a Casa corrente; §4-bis: a relacao 'remessa_enviada' perde o arg `ente`, implicito na tx). Como as
  relacoes do `cadastros`, a query e' INLINE no proprio schema (compliance) — nao passa pelo `db/` (ADR-0001
  §3-bis: o db/ so e' importado pelo Repo-Component).

  Dono de `remessa_enviada(sistema, competencia)` [Remessa-tracking] — a COSTURA (§22.7.8) entre a
  obrigacao rastreada (T1 `remessa_mensal_sim`) e o artefato: so a remessa em 'aceita' a torna verdadeira,
  transitando a obrigacao pendente -> cumprida. F5.3a fecha o fato que o avaliar_seam_test deixou deferido."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.motor.api :as motor]))

(set! *warn-on-reflection* true)

(defn remessa-enviada?
  "A remessa do `sistema` (ex.: 'SIM') da `competencia` foi ACEITA pelo TCE no ente corrente? `competencia`
  e' o valor resolvido do tipo Competencia ({:ano :mes}); `motor/comp-chave` o normaliza p/ 'AAAA-MM' (mesma
  conversao que o motor usa no prazo; via a fachada, nao o runtime interno). So 'aceita' cumpre (rejeicao/
  submetida NAO) — §22.7.8. Usa o indice parcial idx_remessa_gerada_costura (estado='aceita') via `[:inline]`."
  [tx sistema competencia]
  (boolean
   (jdbc/execute-one! tx
     (sql/format {:select [1] :from [:compliance.remessa_gerada]
                  :where [:and [:= :sistema sistema] [:= :competencia (motor/comp-chave competencia)]
                          [:= :estado [:inline "aceita"]]]
                  :limit 1}))))

;; Registro das relacoes deste contexto (nome canonico -> fn). O host (sistema.clj) o funde no registry do
;; motor; o avaliador chama por nome (resolver-para). A assinatura tipada vive no catalogo do motor
;; (`remessa_enviada` [TEXTO COMPETENCIA] -> BOOLEANO, §4-bis) — a costura catalogo⋈registry exige aridade
;; de dominio 2 (a `tx` injetada e' a 3a; verificar-costura).
(def relacoes
  {"remessa_enviada" remessa-enviada?})
