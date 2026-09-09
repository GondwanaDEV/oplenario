(ns oplenario.sessoes.adapters.out.tribuna
  "Gate de SAIDA `models -> wire/out` da TRIBUNA (§22.10 adapters/out, ADR-0001 §3) — eixo F. Projeta os recibos
  de inscricao/desistencia p/ a borda. Validado contra o contrato wire/out (drift de campo = bug de servidor ->
  500, nunca resposta malformada que envenena o codegen do front, Eixo 8).

  `orador-atual->wire`/`inscrito->wire` EXPOEM `lock-version` deliberadamente (ledger de prontidao Fase 8
  achado #2): `POST .../falas/:fala-id/encerrar` e `POST .../inscricoes/:insc-id/desistir` o exigem no corpo,
  e `GET .../tribuna` (a leitura que estas duas fns projetam) e' a UNICA leitura de onde um cliente aprende
  o valor corrente de uma fala/inscricao alheia — sem ele essas duas rotas sao impossiveis de montar so'
  pela API."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn recibo-inscricao->wire
  "Recibo de dominio {:id uuid :ordem int} -> InscricaoReciboOut (validado, resposta 201)."
  [{:keys [id ordem]}]
  (validado wire/InscricaoReciboOut {:id (->str id) :ordem ordem}
            "recibo de inscricao viola o contrato InscricaoReciboOut (bug de servidor)"))

(defn recibo-desistencia->wire
  "Recibo de dominio {:inscricao-id uuid :de string :para string} -> DesistenciaInscricaoOut (validado, 200)."
  [{:keys [inscricao-id de para]}]
  (validado wire/DesistenciaInscricaoOut {:inscricao-id (->str inscricao-id) :de de :para para}
            "recibo de desistencia viola o contrato DesistenciaInscricaoOut (bug de servidor)"))

(defn recibo-fala-iniciada->wire
  "Recibo de dominio {:id uuid} -> FalaReciboOut (validado, resposta 201). O `id` da fala vira `fala-id`."
  [{:keys [id]}]
  (validado wire/FalaReciboOut {:fala-id (->str id)}
            "recibo de inicio de fala viola o contrato FalaReciboOut (bug de servidor)"))

(defn recibo-cronometro->wire
  "Recibo de dominio {:id uuid} -> CronometroEventoReciboOut (validado, resposta 201). O `id` do evento gravado."
  [{:keys [id]}]
  (validado wire/CronometroEventoReciboOut {:id (->str id)}
            "recibo de evento de cronometro viola o contrato CronometroEventoReciboOut (bug de servidor)"))

(defn recibo-fala-encerrada->wire
  "Recibo de dominio {:id uuid :tempo-efetivamente-usado-segundos int} -> FalaEncerradaOut (validado, 200)."
  [{:keys [id tempo-efetivamente-usado-segundos]}]
  (validado wire/FalaEncerradaOut {:fala-id (->str id) :tempo-segundos tempo-efetivamente-usado-segundos}
            "recibo de encerramento de fala viola o contrato FalaEncerradaOut (bug de servidor)"))

(defn recibo-decisao-mesa->wire
  "Recibo de dominio {:id uuid} -> DecisaoMesaReciboOut (validado, resposta 201). O `id` da decisao gravada."
  [{:keys [id]}]
  (validado wire/DecisaoMesaReciboOut {:id (->str id)}
            "recibo de decisao da mesa viola o contrato DecisaoMesaReciboOut (bug de servidor)"))

;; ---------- Tribuna nominal — o ORADOR e a FILA (GET /sessoes/:id/tribuna) ----------

(defn- orador-atual->wire [{:keys [fala-id orador-id tipo-fala fase iniciou-em inscricao-id lock-version]}]
  {:fala-id (->str fala-id) :orador-id (->str orador-id) :tipo-fala tipo-fala :fase fase
   :iniciou-em (->str iniciou-em) :inscricao-id (->str inscricao-id) :lock-version lock-version})

(defn- marco->wire [{:keys [tipo ocorrido-em segundos-adicionais]}]
  {:tipo tipo :ocorrido-em (->str ocorrido-em) :segundos-adicionais segundos-adicionais})

(defn- inscrito->wire [{:keys [inscricao-id vereador-id origem-inscricao fase ordem lock-version]}]
  {:inscricao-id (->str inscricao-id) :vereador-id (->str vereador-id) :origem-inscricao origem-inscricao
   :fase fase :ordem ordem :lock-version lock-version})

(defn tribuna-sessao->wire
  "O estado de dominio (`sessoes.controllers/tribuna-da-sessao`) -> TribunaOut (validado). CAMPO A CAMPO
  (nunca `select-keys`/`dissoc` sobre um mapa maior) — mesma disciplina de `composicao-sessao->wire`: o
  default e' NAO expor, e o `:closed true` do contrato vira erro de servidor, nao vazamento silencioso,
  quando `logic`/`controllers` ganhar campo novo."
  [{:keys [sessao-id orador-atual marcos-cronometro inscritos]}]
  (validado wire/TribunaOut
            {:sessao-id (->str sessao-id)
             :orador-atual (some-> orador-atual orador-atual->wire)
             :marcos-cronometro (mapv marco->wire marcos-cronometro)
             :inscritos (mapv inscrito->wire inscritos)}
            "tribuna viola o contrato TribunaOut (bug de servidor)"))
