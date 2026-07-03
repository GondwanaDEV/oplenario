(ns oplenario.participacao.adapters.out.encarregado
  "Gate de SAIDA `models -> wire/out` do ENCARREGADO/DPO (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. Projeta o CONTATO PUBLICO {nome, rotulo, email} e FILTRA todo interno: id, ente-id, atualizado-por,
  timestamps — NENHUM chega ao wire. A rota e' PUBLICA (sem ator); a defesa anti-vazamento mora AQUI. Validada
  contra wire/out EncarregadoPublicoOut (drift de campo = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.encarregado :as wire]))

(set! *warn-on-reflection* true)

(defn publico->wire
  "Contato de dominio {:nome :rotulo :email ...} -> EncarregadoPublicoOut (validada). So o contato publico da
  LGPD (nome, rotulo, email); NADA de interno (id, ente-id, atualizado-por, timestamps)."
  [e]
  (let [out {:nome   (:nome e)
             :rotulo (:rotulo e)
             :email  (:email e)}]
    (when-not (m/validate wire/EncarregadoPublicoOut out)
      (throw (ex-info "projecao do encarregado viola o contrato EncarregadoPublicoOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/EncarregadoPublicoOut out))})))
    out))
