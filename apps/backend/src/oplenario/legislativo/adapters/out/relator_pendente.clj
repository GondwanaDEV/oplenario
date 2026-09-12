(ns oplenario.legislativo.adapters.out.relator-pendente
  "Gate de SAIDA `models -> wire/out` da fila de relatores pendentes (§22.10 adapters/out, ADR-0001,
  FE Onda A1)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.relator-pendente :as wire]))

(set! *warn-on-reflection* true)

(defn- item->wire [{:keys [id proposicao-id tipo ano sequencial urn-lex ementa criado-em indisponivel]}]
  {:id (str id) :proposicao-id (str proposicao-id) :tipo tipo :ano ano :sequencial sequencial
   :urn-lex urn-lex :ementa ementa :criado-em (str criado-em)
   ;; `(boolean ...)` aqui e' o MESMO precedente de `transparencia.adapters.out.acompanhamento/minha->wire`
   ;; pro campo homonimo `:indisponivel` (o db/ so' produz true/false, nunca nil — ver docstring de
   ;; `parecer/relatores-pendentes`). Diferente do `:truncado` abaixo, que fica VERBATIM.
   :indisponivel (boolean indisponivel)})

(defn relatores-pendentes->wire
  "{:itens :truncado} (cru, kebab, do Repo — frente 'truncamento-familia') -> RelatoresPendentesOut
  (validado). `:truncado` vem PRONTO do Repo (sonda teto+1 na MESMA tx da lista) e e' projetado VERBATIM
  (nunca `(boolean x)` — mesmo racional CRITICO de `adapters.out.ficha-materia`/`adapters.out.meu-painel`:
  ausente tem de reprovar no schema, nao virar `false` fingindo fila completa)."
  [{:keys [itens truncado]}]
  (let [out {:itens (mapv item->wire itens) :truncado truncado}]
    (when-not (m/validate wire/RelatoresPendentesOut out)
      (throw (ex-info "fila de relatores pendentes viola o contrato RelatoresPendentesOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/RelatoresPendentesOut out))})))
    out))
