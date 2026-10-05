(ns oplenario.transparencia.adapters.out.movimentacao
  "Gate de SAIDA `models -> wire/out` da linha do tempo da materia (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. Projeta so' as colunas publicas (instante, etapa, abertura, se e' votacao) e valida contra o wire
  `:closed`: drift de campo = bug de servidor -> 500, nunca vazamento. A chave da etapa entra so' para decidir
  `votacao` e nao sai."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.logic.desfecho :as desfecho]
            [oplenario.transparencia.wire.out.movimentacao :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- item [m]
  {:ocorrido-em (->str (:ocorrido-em m)) :etapa (:etapa m) :abertura (boolean (:inicial m))
   :votacao (desfecho/votacao? (:etapa-chave m))})

(defn movimentacoes->wire
  "{:movimentacoes :total :completo? :desde} (dominio) -> MovimentacoesOut. SEM `(or ... 0)` no total: ausente e'
  bug de servidor e tem de reprovar no schema `:closed`, nao virar `0` silencioso."
  [{:keys [movimentacoes total completo? desde]}]
  (validar! wire/MovimentacoesOut
            {:movimentacoes (mapv item movimentacoes)
             :movimentacoes-total total
             :historico-completo (boolean completo?)
             :historico-desde (->str desde)}
            "MovimentacoesOut"))
