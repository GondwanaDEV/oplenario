(ns oplenario.sessoes.adapters.out.presenca
  "Gate de SAIDA `models -> wire/out` da PRESENCA (§22.10 adapters/out, ADR-0001 §3) — eixo C. Projeta o recibo
  do registro de presenca p/ a borda. Validado contra o contrato wire/out (drift de campo = bug de servidor ->
  500, nunca resposta malformada que envenena o codegen do front, Eixo 8)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn recibo-presenca->wire
  "Recibo de dominio {:id uuid} -> PresencaReciboOut (validado, resposta 201)."
  [{:keys [id]}]
  (let [out {:id (some-> id str)}]
    (when-not (m/validate wire/PresencaReciboOut out)
      (throw (ex-info "recibo de presenca viola o contrato PresencaReciboOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/PresencaReciboOut out)))})))
    out))

(defn resumo-presenca->wire
  "Resumo cru (kebab, do db) -> PresencaResumoOut (validado)."
  [{:keys [media-percentual sessoes-consideradas membros-da-casa]}]
  (let [out {:media-percentual media-percentual :sessoes-consideradas sessoes-consideradas
             :membros-da-casa membros-da-casa}]
    (when-not (m/validate wire/PresencaResumoOut out)
      (throw (ex-info "resumo de presenca viola o contrato PresencaResumoOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/PresencaResumoOut out))})))
    out))

(defn- linha-chamada->wire
  "Uma LinhaChamada de dominio (`sessoes.controllers/linha-da-chamada`) -> LinhaChamadaOut. `estado` e'
  keyword no dominio (`logic/estados-chamada`, sem CHECK que o espelhe) -> string via `name`. Instantes
  (`desde`/`registrado-em`) -> ISO string ou nil."
  [{:keys [vereador-id nome nome-parlamentar partido cargo-mesa estado inconsistencia-cadastro
           desde fonte registrado-em justificativa]}]
  {:vereador-id (str vereador-id)
   :nome nome
   :nome-parlamentar nome-parlamentar
   :partido partido
   :cargo-mesa cargo-mesa
   :estado (name estado)
   :inconsistencia-cadastro (boolean inconsistencia-cadastro)
   :desde (some-> desde str)
   :fonte fonte
   :registrado-em (some-> registrado-em str)
   :justificativa (when justificativa
                    {:estado (:estado justificativa) :motivo (:motivo justificativa)})})

(defn chamada->wire
  "A CHAMADA de dominio (`sessoes.controllers/chamada-da-sessao`) -> ChamadaOut (validado). Instantes/data ->
  ISO string; `linhas` projetadas uma a uma; `quorum` ja chega no shape de ChamadaQuorumOut (contar-quorum)."
  [{:keys [sessao-id sessao-estado instante data-de-composicao composicao-resolvida-em
           sem-registro-de-presenca linhas quorum]}]
  (let [out {:sessao-id (str sessao-id)
             :sessao-estado sessao-estado
             :instante (str instante)
             :data-de-composicao (str data-de-composicao)
             :composicao-resolvida-em (str composicao-resolvida-em)
             :sem-registro-de-presenca (boolean sem-registro-de-presenca)
             :linhas (mapv linha-chamada->wire linhas)
             :quorum (select-keys quorum [:presentes-plenario :presentes-remoto :membros-da-casa])}]
    (when-not (m/validate wire/ChamadaOut out)
      (throw (ex-info "chamada viola o contrato ChamadaOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/ChamadaOut out))})))
    out))
