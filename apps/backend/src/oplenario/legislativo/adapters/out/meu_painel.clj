(ns oplenario.legislativo.adapters.out.meu-painel
  "Gate de SAIDA `models -> wire/out` do painel do vereador (§22.10 adapters/out, ADR-0001, Onda C1)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.meu-painel :as wire]))

(set! *warn-on-reflection* true)

(defn- proposicao->wire [{:keys [id tipo ano sequencial urn-lex ementa estado atualizado-em]}]
  {:id (str id) :tipo tipo :ano ano :sequencial sequencial :urn-lex urn-lex :ementa ementa :estado estado
   :atualizado-em (str atualizado-em)})

(defn- parecer->wire [{:keys [id objeto-tipo objeto-id comissao-id estado voto-relator criado-em]}]
  {:id (str id) :objeto-tipo objeto-tipo :objeto-id (str objeto-id) :comissao-id (str comissao-id)
   :estado estado :voto-relator voto-relator :criado-em (str criado-em)})

(defn- ciencia->wire [{:keys [parecer-id proposicao-id tipo ano sequencial urn-lex ementa]}]
  {:parecer-id (str parecer-id) :proposicao-id (str proposicao-id) :tipo tipo :ano ano
   :sequencial sequencial :urn-lex urn-lex :ementa ementa})

(defn meu-painel->wire
  "{:vereador-id :proposicoes :pareceres :ciencias} (cru, kebab, do controller) -> MeuPainelOut (validado)."
  [{:keys [vereador-id proposicoes pareceres ciencias]}]
  (let [out {:vereador-id (some-> vereador-id str)
             :proposicoes (mapv proposicao->wire proposicoes)
             :pareceres (mapv parecer->wire pareceres)
             :ciencias (mapv ciencia->wire ciencias)}]
    (when-not (m/validate wire/MeuPainelOut out)
      (throw (ex-info "painel do vereador viola o contrato MeuPainelOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MeuPainelOut out))})))
    out))

(defn acusar-ciencia->wire
  "{:id :ciente-em} (cru, do db) -> AcusarCienciaOut (validado)."
  [{:keys [id ciente-em]}]
  (let [out {:id (str id) :ciente-em (str ciente-em)}]
    (when-not (m/validate wire/AcusarCienciaOut out)
      (throw (ex-info "recibo de ciencia viola o contrato AcusarCienciaOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/AcusarCienciaOut out))})))
    out))
