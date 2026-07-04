(ns oplenario.paineis.adapters.out.mesa
  "Gate de SAIDA `models -> wire/out` do dashboard da Mesa (§22.10 adapters/out, ADR-0001, F7) — chamado SO
  pelo diplomat/. Recebe (a) os ROLLUPS crus (linhas GROUP BY, kebab) que o Repo leu das tabelas do proprio
  paineis e (b) o card `compliance-tce` JA' PROJETADO (o `PainelOut` de compliance, cruzou a borda daquele
  modulo antes de chegar aqui — este ns nunca importa compliance, so' embute o mapa opaco). Deriva as
  contagens de leitura de negocio e valida contra wire/out.MesaOut (drift de campo = bug de servidor -> 500).

  NUNCA reprojeta compliance: `compliance-card` entra e sai verbatim sob :compliance-tce; a defesa
  anti-vazamento do que e' de compliance ja' rodou no adapters/out DAQUELE modulo."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.logic.situacao :as situacao]
            [oplenario.paineis.wire.out.mesa :as wire]))

(set! *warn-on-reflection* true)

(def ^:private lacunas-conhecidas
  "Facetas do dashboard institucional da Mesa (§16.11 item 11.4) ainda NAO materializadas como projecao no
  paineis — expostas honestamente p/ o FE rotular a vitrine sem sugerir cobertura inexistente (mesma
  disciplina dos [GAP] infra de F7 E3). Presenca agregada mora em `sessoes` (F4) e engajamento cidadao em
  `participacao`/`transparencia`; nenhum e' projetado aqui ainda (escopo diferido por default, §15)."
  ["presenca_agregada" "engajamento_cidadao"])

(defn- tramitacao->wire
  "Rollup 'proposicoes por status': linhas {:estado :n} (GROUP BY estado) -> {:total :por-estado}. `total` =
  soma das contagens (nao um COUNT separado — coerencia com o breakdown por construcao)."
  [rows]
  {:total (reduce + 0 (map :n rows))
   :por-estado (mapv (fn [r] {:estado (:estado r) :n (:n r)}) rows)})

(defn- pendencias->wire
  "Rollup 'o que vence': linhas {:estado :n} (GROUP BY estado; estados possiveis pendente/vencido/concluido)
  -> {:abertas :vencidas :pendentes}. So' as fases ABERTAS entram no manchete (concluido nao e' pendencia)."
  [rows]
  (let [by (into {} (map (juxt :estado :n) rows))
        pendentes (get by "pendente" 0)
        vencidas  (get by "vencido" 0)]
    {:pendentes pendentes
     :vencidas vencidas
     :abertas (+ pendentes vencidas)}))

(defn- sessoes->wire
  "Rollup do SLI: linhas {:estado-atual :n} (GROUP BY estado_atual) -> contagem POR SITUACAO derivada +
  manchetes. Duas transicoes distintas podem mapear p/ a mesma situacao (encerrada/arquivada -> realizada),
  por isso soma acumulando (fnil +). `em-curso` = so' 'em_curso' (sessao aberta agora; suspensa fica no
  breakdown, nao no manchete de 'acontecendo'); `nao-realizadas` = os no-shows (sinal de SLI do Inv.9)."
  [rows]
  (let [buckets (reduce (fn [m r] (update m (situacao/derivar (:estado-atual r)) (fnil + 0) (:n r)))
                        {} rows)]
    {:em-curso (get buckets "em_curso" 0)
     :nao-realizadas (get buckets "nao_realizada" 0)
     ;; ordem canonica estavel (situacao/ordenar) — nao a ordem incidental do reduce (review clojure MINOR).
     :por-situacao (mapv (fn [s] {:situacao s :n (get buckets s)}) (situacao/ordenar (keys buckets)))}))

(defn mesa->wire
  "Rollups internos + card de compliance (opaco) -> MesaOut (validada). `rollups` = {:tramitacao :pendencias
  :sessoes} (cada um uma seq de linhas GROUP BY, kebab); `compliance-card` = o PainelOut de compliance ja'
  projetado (mapa) — embutido verbatim."
  [rollups compliance-card]
  (let [out {:compliance-tce compliance-card
             :tramitacao (tramitacao->wire (:tramitacao rollups))
             :pendencias (pendencias->wire (:pendencias rollups))
             :sessoes    (sessoes->wire (:sessoes rollups))
             :lacunas    lacunas-conhecidas}]
    (when-not (m/validate wire/MesaOut out)
      (throw (ex-info "projecao do dashboard da Mesa viola o contrato MesaOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MesaOut out))})))
    out))
