(ns oplenario.sessoes.adapters.out.presenca
  "Gate de SAIDA `models -> wire/out` da PRESENCA (§22.10 adapters/out, ADR-0001 §3) — eixo C. Projeta o recibo
  do registro de presenca p/ a borda. Validado contra o contrato wire/out (drift de campo = bug de servidor ->
  500, nunca resposta malformada que envenena o codegen do front, Eixo 8)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn recibo-presenca->wire
  "Recibo de dominio {:id uuid :ocorrido-em Instant :registrado-em Instant} -> PresencaReciboOut (validado,
  resposta 201). Instantes -> ISO string. Os dois carimbos sao OBRIGATORIOS no contrato: um recibo sem
  `registrado-em` deixaria o cliente sem como distinguir a hora do fato da hora da digitacao, e o Malli
  fecha essa porta aqui (nil -> 500 de bug de servidor, nunca 201 com o par pela metade)."
  [{:keys [id ocorrido-em registrado-em]}]
  (let [out {:id (some-> id str)
             :ocorrido-em (some-> ocorrido-em str)
             :registrado-em (some-> registrado-em str)}]
    (when-not (m/validate wire/PresencaReciboOut out)
      (throw (ex-info "recibo de presenca viola o contrato PresencaReciboOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/PresencaReciboOut out)))})))
    out))

(defn recibos-presenca-lote->wire
  "N recibos de dominio [{:id :ocorrido-em :registrado-em} ...] (Etapa 2c, POST .../presenca/lote) ->
  PresencaLoteReciboOut (validado, resposta 201). Reusa a projecao POR LINHA de `recibo-presenca->wire` (o
  lote nao inventa vocabulario de saida novo) e embrulha em `{:recibos [...]}`, na MESMA ordem recebida."
  [recibos]
  (let [out {:recibos (mapv recibo-presenca->wire recibos)}]
    (when-not (m/validate wire/PresencaLoteReciboOut out)
      (throw (ex-info "recibos de presenca em lote violam o contrato PresencaLoteReciboOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/PresencaLoteReciboOut out))})))
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

;; ---------- justificativa de ausencia (Etapa 2 da chamada) ----------

(defn- validar! [schema out nome]
  (when-not (m/validate schema out)
    (throw (ex-info (str nome " viola o contrato de saida (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn justificativa-aberta->wire
  "Recibo de dominio {:id :sessao-id :vereador-id :estado :lock-version} -> JustificativaAbertaOut (validado,
  resposta 201). NAO projeta `motivo`: o cliente acabou de envia-lo, e dado sensivel que nao precisa voltar
  nao volta."
  [{:keys [id sessao-id vereador-id estado lock-version]}]
  (validar! wire/JustificativaAbertaOut
            {:id (some-> id str)
             :sessao-id (some-> sessao-id str)
             :vereador-id (some-> vereador-id str)
             :estado estado
             :lock-version lock-version}
            "recibo de justificativa"))

(defn- linha-justificativa->wire [{:keys [id vereador-id estado motivo decidido-por decidido-em lock-version]}]
  {:id (str id)
   :vereador-id (str vereador-id)
   :estado estado
   :motivo motivo
   :decidido-por (some-> decidido-por str)
   :decidido-em (some-> decidido-em str)
   :lock-version lock-version})

(defn justificativas->wire
  "{:sessao-id :justificativas [...]} de dominio -> JustificativasOut (validado, resposta 200)."
  [{:keys [sessao-id justificativas]}]
  (validar! wire/JustificativasOut
            {:sessao-id (str sessao-id)
             :justificativas (mapv linha-justificativa->wire justificativas)}
            "lista de justificativas"))

(defn justificativa-decidida->wire
  "Recibo de dominio {:justificativa-id :de :para} -> JustificativaDecididaOut (validado, resposta 200)."
  [{:keys [justificativa-id de para]}]
  (validar! wire/JustificativaDecididaOut
            {:justificativa-id (some-> justificativa-id str) :de de :para para}
            "recibo de decisao de justificativa"))

(defn- linha-chamada->wire
  "Uma LinhaChamada de dominio (`sessoes.controllers/linha-da-chamada`) -> LinhaChamadaOut. `estado` e'
  keyword no dominio (`logic/estados-chamada`, sem CHECK que o espelhe) -> string via `name`. Instantes
  (`desde`/`registrado-em`) -> ISO string ou nil."
  [{:keys [vereador-id nome nome-parlamentar partido cargo-mesa estado inconsistencia-cadastro sem-assento
           desde fonte registrado-em justificativa]}]
  {:vereador-id (str vereador-id)
   :nome nome
   :nome-parlamentar nome-parlamentar
   :partido partido
   :cargo-mesa cargo-mesa
   :estado (name estado)
   :inconsistencia-cadastro (boolean inconsistencia-cadastro)
   :sem-assento (boolean sem-assento)
   :desde (some-> desde str)
   :fonte fonte
   :registrado-em (some-> registrado-em str)
   :justificativa (when justificativa
                    {:estado (:estado justificativa) :motivo (:motivo justificativa)
                     :decidido-em (some-> (:decidido-em justificativa) str)})})

;; ---------- §22.6 eixo C — o ATO da CHAMADA CONDUZIDA (Etapa 2d) ----------

(defn chamada-conduzida->wire
  "Um ato de dominio {:id :conduzida-por :membros-da-casa :ocorrido-em :registrado-em} -> ChamadaConduzidaOut
  (validado). MESMO shape usado no recibo de `POST /sessoes/:id/chamada` (201) e em cada item de
  `ChamadaOut.chamadas-conduzidas` — o ato nao inventa vocabulario de saida novo entre os dois lugares
  (precedente: `recibo-presenca->wire` reusado dentro de `recibos-presenca-lote->wire`)."
  [{:keys [id conduzida-por membros-da-casa ocorrido-em registrado-em]}]
  (validar! wire/ChamadaConduzidaOut
            {:id (some-> id str)
             :conduzida-por (some-> conduzida-por str)
             :membros-da-casa membros-da-casa
             :ocorrido-em (some-> ocorrido-em str)
             :registrado-em (some-> registrado-em str)}
            "recibo de chamada conduzida"))

(defn chamada->wire
  "A CHAMADA de dominio (`sessoes.controllers/chamada-da-sessao`) -> ChamadaOut (validado). Instantes/data ->
  ISO string; `linhas` projetadas uma a uma; `quorum` ja chega no shape de ChamadaQuorumOut (contar-quorum);
  `chamadas-conduzidas` (Etapa 2d) projetada item a item por `chamada-conduzida->wire`."
  [{:keys [sessao-id sessao-estado instante data-de-composicao composicao-resolvida-em
           sem-registro-de-presenca linhas quorum chamadas-conduzidas]}]
  (let [out {:sessao-id (str sessao-id)
             :sessao-estado sessao-estado
             :instante (str instante)
             :data-de-composicao (str data-de-composicao)
             :composicao-resolvida-em (str composicao-resolvida-em)
             :sem-registro-de-presenca (boolean sem-registro-de-presenca)
             :linhas (mapv linha-chamada->wire linhas)
             :quorum (select-keys quorum [:presentes-plenario :presentes-remoto :membros-da-casa
                                          :presencas-fora-do-roster])
             :chamadas-conduzidas (mapv chamada-conduzida->wire chamadas-conduzidas)}]
    (when-not (m/validate wire/ChamadaOut out)
      (throw (ex-info "chamada viola o contrato ChamadaOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/ChamadaOut out))})))
    out))
