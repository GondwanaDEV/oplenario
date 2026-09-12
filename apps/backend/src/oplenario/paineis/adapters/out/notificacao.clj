(ns oplenario.paineis.adapters.out.notificacao
  "Gate de SAIDA `models -> wire/out` da INBOX (§22.10 adapters/out, ADR-0001) — chamado SO pelo diplomat.
  Projeta o read-model (kebab, do db) p/ a representacao externa (strings) e FILTRA `ente-id` e
  `destinatario-identidade-id`, que nunca vazam. Validada contra o wire (drift de campo = bug de servidor
  -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.notificacao :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- notificacao->wire [n]
  {:id (->str (:id n)) :categoria (:categoria n) :assunto (:assunto n) :corpo (:corpo n)
   :objeto-tipo (:objeto-tipo n) :objeto-id (->str (:objeto-id n))
   :criado-em (->str (:criado-em n)) :lida-em (->str (:lida-em n))})

(defn minhas-notificacoes->wire
  "{:notificacoes [...] :nao-lidas n :notificacoes-total n} -> MinhasNotificacoesOut (validada).
  `notificacoes-total` sai VERBATIM (`(int ...)`, nunca `(or ... 0)`) — fatia 'truncamento-familia': um
  total AUSENTE e' bug de servidor e tem de virar 500 pela validacao Malli abaixo, nunca um zero
  silencioso que a UI leria como 'sem corte' (o mesmo cuidado que `nao-lidas`, historicamente, nao tinha)."
  [{:keys [notificacoes nao-lidas notificacoes-total]}]
  (let [out {:notificacoes (mapv notificacao->wire notificacoes) :nao-lidas (int (or nao-lidas 0))
             :notificacoes-total (int notificacoes-total)}]
    (when-not (m/validate wire/MinhasNotificacoesOut out)
      (throw (ex-info "projecao da inbox viola o contrato MinhasNotificacoesOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MinhasNotificacoesOut out))})))
    out))

(defn marcar-lida->wire
  "Recibo de marcacao de leitura -> MarcarLidaOut (validada). Task 7."
  [recibo]
  (let [out {:id (->str (:id recibo)) :lida-em (->str (:lida-em recibo))}]
    (when-not (m/validate wire/MarcarLidaOut out)
      (throw (ex-info "recibo de leitura viola o contrato MarcarLidaOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MarcarLidaOut out))})))
    out))
