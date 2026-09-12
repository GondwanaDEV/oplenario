(ns oplenario.identidade.adapters.out.meu-identidade
  "Gate de SAIDA `dominio -> wire/out` de GET /meu/identidade (§22.10 adapters/out, ADR-0001) — chamado SO
  pelo diplomat/. Validada contra wire/out (drift = bug de servidor -> 500), mesma disciplina de
  `transparencia/adapters/out/acompanhamento.clj`. `:closed true` no schema torna estruturalmente
  impossivel um `merge`/`select-keys` descuidado infiltrar `:cpf` aqui — reprova a validacao em vez de
  vazar em silencio."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.identidade.wire.out.meu-identidade :as wire]))

(set! *warn-on-reflection* true)

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn meu-identidade->wire
  "{:nome ... :papeis #{...}} -> MeuIdentidadeOut. `:papeis` chega como SET do ator (kernel/autorizacao) —
  vetorizado aqui pra JSON (um set nao tem ordem estavel de serializacao); ordenado (`sort`) pra a
  resposta ser deterministica entre chamadas (nunca depender da ordem de iteracao do hash-set)."
  [{:keys [nome papeis]}]
  (validar! wire/MeuIdentidadeOut {:nome nome :papeis (vec (sort papeis))} "MeuIdentidadeOut"))
