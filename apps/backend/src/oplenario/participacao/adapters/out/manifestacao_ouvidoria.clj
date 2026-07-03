(ns oplenario.participacao.adapters.out.manifestacao-ouvidoria
  "Gate de SAIDA `models -> wire/out` da manifestacao de ouvidoria (§22.10 adapters/out, ADR-0001) —
  chamado SO pelo diplomat/. Projeta a manifestacao de dominio (kebab) p/ a representacao externa (strings,
  JSON) e FILTRA o que nao deve vazar: o tenant (ente-id), o manifestante (PII, mesmo p/ o proprio dono —
  o id nao precisa vazar) e a flag `anonima` (interna a decisao de policy, nao contrato de saida). A
  projecao e' VALIDADA contra wire/out (drift de campo = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.manifestacao-ouvidoria :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Recibo de protocolo {:protocolo :recibo-em} -> ReciboOut (resposta 201). Sem PII, sem id interno."
  [r]
  (validar! wire/ReciboOut
            {:protocolo (:protocolo r)
             :recibo-em (->str (:recibo-em r))}
            "ReciboOut"))

(defn manifestacao->wire
  "Detalhe da manifestacao do proprio manifestante {:id :protocolo :tipo :assunto :descricao :estado
  :recibo-em :vence-em :dias-restantes} -> ManifestacaoOut. FILTRA tenant (ente-id) + manifestante (PII) +
  anonima (interna). vence-em/dias-restantes nulaveis (sem prazo ativo)."
  [m]
  (validar! wire/ManifestacaoOut
            {:id             (->str (:id m))
             :protocolo      (:protocolo m)
             :tipo           (:tipo m)
             :assunto        (:assunto m)
             :descricao      (:descricao m)
             :estado         (:estado m)
             :recibo-em      (->str (:recibo-em m))
             :vence-em       (->str (:vence-em m))
             :dias-restantes (:dias-restantes m)}
            "ManifestacaoOut"))
