(ns oplenario.transparencia.logic.notificacao
  "Logica PURA do fan-out de notificacao (F7 E2) — sem I/O, unit-testavel. Renderiza o conteudo (assunto/corpo)
  a partir da materia (info PUBLICA) + a transicao, e deriva a chave de idempotencia DETERMINISTICA do ledger.
  Separada de components/repositorio (que le' db e emite) p/ testar a renderizacao/chave sem PG."
  (:require [clojure.string :as str]))

(defn chave-idempotencia
  "Chave DETERMINISTICA da entrega no ledger de `paineis` (UNIQUE ente_id, idempotency_key, mig 0004). Deriva
  de (transicao-id, destinatario): uma entrega logica por (transicao, seguidor). Um redrive/backfill FUTURO
  que re-execute o fan-out gera a MESMA chave -> a insercao no ledger e' no-op (nunca duplica a entrega).
  NAO usa a idempotency-key ALEATORIA do envelope (essa dedup o consumer, nao a entrega logica)."
  [transicao-id destinatario-identidade-id]
  (str "transicao:" transicao-id ":dest:" destinatario-identidade-id))

(defn- identificador-materia
  "Identificador humano da materia: '<TIPO> <sequencial>/<ano>' (ex.: 'PL 12/2026'). tipo em maiuscula."
  [{:keys [tipo sequencial ano]}]
  (str (str/upper-case (str tipo)) " " sequencial "/" ano))

(defn renderizar
  "Renderiza {:assunto :corpo} da notificacao de transicao — info PUBLICA (ementa + novo estado). `materia` =
  {:tipo :ano :sequencial :ementa ...}; `para` = o novo estado da tramitacao (do evento, autoritativo). Texto
  factual, sem PII (o destinatario nao aparece no corpo — a entrega e' 1:1)."
  [materia para]
  (let [id-materia (identificador-materia materia)]
    {:assunto (str "Movimentacao: " id-materia)
     :corpo   (str "A materia " id-materia " que voce acompanha teve movimentacao.\n\n"
                   "Ementa: " (:ementa materia) "\n"
                   "Nova fase: " para "\n\n"
                   "Acompanhe a tramitacao completa no portal da Camara.")}))
