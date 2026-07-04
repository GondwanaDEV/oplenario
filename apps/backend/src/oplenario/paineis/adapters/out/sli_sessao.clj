(ns oplenario.paineis.adapters.out.sli-sessao
  "Gate de SAIDA `models -> wire/out` do SLI de janela de sessao (§22.10 adapters/out, ADR-0001, F7 E3) —
  chamado SO pelo diplomat/. Projeta o read-model do dominio (linhas de sli_sessao, kebab) p/ a representacao
  externa (strings) e DERIVA os campos de leitura de negocio (`situacao`, `duracao-segundos`); o tenant
  (ente-id) nunca vaza (nem esta' nas colunas lidas). Validada contra wire/out.SliSessoesOut (drift de campo =
  bug de servidor -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.wire.out.sli-sessao :as wire])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- situacao
  "Rotulo de leitura de negocio do SLI, DERIVADO puramente do estado atual da maquina de sessao (`sessoes`):
  o painel fala 'em curso / concluida / nao realizada', nao os estados internos. 'arquivada' e' pos-encerrada
  -> conta como realizada. Estado desconhecido passa cru (defensivo — a fonte pode ganhar estados novos)."
  [estado-atual]
  (case estado-atual
    "aberta"        "em_curso"
    "suspensa"      "suspensa"
    ("encerrada" "arquivada") "realizada"
    "nao_realizada" "nao_realizada"
    "agendada"      "agendada"
    estado-atual))

(defn- duracao-segundos
  "Janela FECHADA em segundos (aberta_em -> encerrada_em) — so' quando a sessao ja' abriu E encerrou; nil se
  ainda em curso (a duracao viva e' derivacao de leitura no cliente, contra 'agora', p/ nao carimbar tempo de
  processamento no servidor). Guard contra janela invertida (encerrada_em < aberta_em): negativo -> nil.
  Esse caso e' ESTRUTURALMENTE INALCANCAVEL hoje (review clojure MEDIUM): o CAS de lock_version serializa as
  transicoes da sessao, e o `now()` (congelado no inicio da tx) do fechamento e' sempre >= o da abertura ja'
  commitada. O `nil` e' fail-soft DELIBERADO na borda publica (nunca devolver duracao negativa) — NAO se loga
  aqui p/ preservar a pureza desta fn (se um dia o caso 'impossivel' precisar de alarme, instrumentar em
  `sessao->wire`, que tem `sessao-id`/contexto; ou uma metrica do pilar 2 do Inv.7, infra-gated [GAP])."
  [^Instant aberta-em ^Instant encerrada-em]
  (when (and aberta-em encerrada-em)
    (let [s (.toSeconds (Duration/between aberta-em encerrada-em))]
      (when (nat-int? s) s))))

(defn- sessao->wire [s]
  {:sessao-id (->str (:sessao-id s))
   :estado-atual (:estado-atual s)
   :situacao (situacao (:estado-atual s))
   :aberta-em (->str (:aberta-em s))
   :encerrada-em (->str (:encerrada-em s))
   :duracao-segundos (duracao-segundos (:aberta-em s) (:encerrada-em s))})

(defn sli-sessoes->wire
  "Read-model do SLI (sequencia de linhas) -> SliSessoesOut (validada)."
  [sessoes]
  (let [out {:sessoes (mapv sessao->wire sessoes)}]
    (when-not (m/validate wire/SliSessoesOut out)
      (throw (ex-info "projecao do SLI de sessao viola o contrato SliSessoesOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/SliSessoesOut out))})))
    out))
