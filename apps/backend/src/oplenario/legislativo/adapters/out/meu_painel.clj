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
  "{:vereador-id :proposicoes :proposicoes-truncado :pareceres :pareceres-truncado :ciencias
  :ciencias-truncado} (cru, kebab, do controller) -> MeuPainelOut (validado).

  Os 3 `-truncado` (frente 'truncamento-familia') vem PRONTOS do Repo (a sonda teto+1 ja' rodou na
  MESMA tx da lista) — este adapter so' projeta, VERBATIM, nunca `(boolean x)` (mesmo racional do fix
  CRITICO de `adapters.out.ficha-materia/ficha->wire`): o Repo real so' produz `true`/`false`, e a UNICA
  forma de uma destas 3 chaves chegar aqui `nil` e' um PRODUTOR incompleto (fixture esquecida) — que TEM
  de reprovar no `m/validate` abaixo (:boolean fechado), nao virar `false` silencioso fingindo painel
  completo."
  [{:keys [vereador-id proposicoes proposicoes-truncado pareceres pareceres-truncado ciencias
           ciencias-truncado]}]
  (let [out {:vereador-id (some-> vereador-id str)
             :proposicoes (mapv proposicao->wire proposicoes)
             :proposicoes-truncado proposicoes-truncado
             :pareceres (mapv parecer->wire pareceres)
             :pareceres-truncado pareceres-truncado
             :ciencias (mapv ciencia->wire ciencias)
             :ciencias-truncado ciencias-truncado}]
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
