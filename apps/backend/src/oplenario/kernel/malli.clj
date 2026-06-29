(ns oplenario.kernel.malli
  "Specs-base reusaveis (Malli) que os schema/ dos modulos compoem: identidade de tenant (Inv.1),
  referencia polimorfica (objeto_tipo,objeto_id) (§22.9 Eixo 2 — integridade em camadas) e temporais.
  Sao dados de schema (nao chamam malli.core); o kernel nao importa modulo (§22.10)."
  (:import (java.time Instant)))

(def EnteId
  "Identificador de tenant — sempre UUID (Inv.1: ente_id em toda tabela tenant)."
  :uuid)

(def Instante
  "Coordenada temporal absoluta (java.time.Instant)."
  [:fn {:error/message "deve ser java.time.Instant"} #(instance? Instant %)])

(defn enum-de
  "[:enum ...] a partir de um conjunto de valores, em ordem estavel (dado de schema; nao chama malli.core).
  Reusavel pelos wire/ e models/ dos modulos (os enums espelham os vocabularios de logic/ + CHECK das migrations)."
  [valores]
  (into [:enum] (sort valores)))

(def Polimorfico
  "Referencia polimorfica (objeto_tipo, objeto_id) — §22.9 Eixo 2; integridade em camadas
  (guard de servico + indice por objeto_tipo + CHECK XOR so nas relacoes quentes)."
  [:map
   [:objeto-tipo :string]
   [:objeto-id :uuid]])
