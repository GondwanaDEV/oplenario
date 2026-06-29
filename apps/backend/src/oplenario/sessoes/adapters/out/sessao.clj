(ns oplenario.sessoes.adapters.out.sessao
  "Gate de SAIDA `models -> wire/out` da sessao (§22.10 adapters/out, ADR-0001 §3) — dividido por DIRECAO (sob
  adapters/out/). Chamado SO pelo diplomat/. Projeta a sessao do dominio (kebab, uuid/Instant) p/ a
  representacao externa (strings, JSON-serializavel) e FILTRA o que nao deve vazar: `lock-version` (token
  interno de concorrencia) e `ente-id` (o tenant ja e' o do ator). A defesa anti-vazamento de saida mora aqui.

  O contrato de forma e' `wire/out.SessaoOut` (a fonte do TS, Eixo 8); a projecao abaixo o satisfaz — e e'
  VALIDADA contra ele (drift de campo, ex.: modalidade NULL de linha legada, = bug de servidor -> 500, nunca
  resposta malformada que envenena o codegen do front)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn sessao->wire
  "Sessao de dominio -> SessaoOut (validada). nil-safe nos marcos opcionais; nunca inclui campos filtrados."
  [s]
  (when s
    (let [out {:id                        (->str (:id s))
     :sessao-legislativa-id     (->str (:sessao-legislativa-id s))
     :tipo-sessao               (:tipo-sessao s)
     :numero-sequencial         (:numero-sequencial s)
     :estado                    (:estado s)
     :modalidade                (:modalidade s)
     :delibera                  (:delibera s)
     :transmite-publica         (:transmite-publica s)
     :gera-ata-regimental       (:gera-ata-regimental s)
     :permite-voto-secreto      (:permite-voto-secreto s)
     :permite-modalidade-remota (:permite-modalidade-remota s)
     :agendada-para             (->str (:agendada-para s))
     :aberta-em                 (->str (:aberta-em s))
     :encerrada-em              (->str (:encerrada-em s))
     :motivo-nao-realizada      (:motivo-nao-realizada s)}]
      (when-not (m/validate wire/SessaoOut out)
        (throw (ex-info "projecao de sessao viola o contrato SessaoOut (bug de servidor)"
                        {:campos (keys (me/humanize (m/explain wire/SessaoOut out)))})))
      out)))

(defn recibo-agendamento->wire
  "Recibo de dominio {:id uuid :numero int} -> {:id string :numero-sequencial int} (resposta 201 do POST)."
  [{:keys [id numero]}]
  {:id (->str id) :numero-sequencial numero})
