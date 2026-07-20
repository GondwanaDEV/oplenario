(ns oplenario.paineis.adapters.in.notificacao
  "Gate de ENTRADA da borda de escrita da inbox (§22.10 adapters/in, ADR-0001, Onda E fatia 1). O unico
  dado que vem do cliente e' o `:id` do path — o destinatario NUNCA (vem do ator).

  DIFERENCA DELIBERADA em relacao a `legislativo/adapters/in/votacao/id-param->uuid` (que lanca
  :validacao/invalido -> 400): aqui um id malformado devolve `nil` e a borda traduz p/ 404, igual ao id
  inexistente e ao id de outro destinatario. Motivo: a spec §4.5 exige que os tres casos sejam
  INDISTINGUIVEIS — um 400 so' para o malformado revelaria que o formato do id e' checado antes da posse,
  dando ao atacante um oraculo de forma. Nenhum caminho legitimo do FE manda id malformado."
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn id-param->uuid
  "Path-param (string) -> UUID, ou nil se nao parseia (a borda traduz p/ 404 — ver docstring do ns)."
  [s]
  (try (UUID/fromString s) (catch Exception _ nil)))
