(ns oplenario.paineis.adapters.out.minha-sessao-atual
  "Gate de SAIDA `models -> wire/out` de GET /meu/sessao-atual (§22.10 adapters/out, ADR-0001, Onda C3). A
  `situacao` e' DERIVADA (mesma `paineis.logic.situacao/derivar` que `adapters/out/sli-sessao` usa — fonte
  unica do rotulo de negocio, review clojure MEDIUM daquele slice) a partir do `estado-atual` cru da
  entrada VIVA de `sli-sessoes`.

  review MAJOR (revisao final de branch): `sli-sessoes` (F7 E3, `db/sli_sessao.clj`) agrupa por
  `encerrada_em IS NULL` (verdadeiro TANTO p/ 'aberta' QUANTO p/ 'agendada'/'suspensa') e ordena esse grupo
  por `transicionou_em ASC` (proposito: o dashboard da Mesa quer ver a sessao aberta HA MAIS TEMPO primeiro,
  p/ sinalizar sessao travada). Tomar cegamente a PRIMEIRA entrada dessa ordem (como este ns fazia antes)
  pode devolver uma sessao 'agendada' (futura, ainda fechada) na frente de uma 'aberta' (a REALMENTE viva)
  se a agendada foi criada com `transicionou_em` mais antigo — exatamente o caso que este endpoint existe
  p/ resolver (achar a sessao viva do cockpit do celular). Filtra ANTES p/ so' os estados 'vivos de fato'
  (aberta/suspensa); nenhuma sessao viva -> {:sessao-id nil :situacao nil} (nunca engana com uma agendada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.logic.situacao :as situacao]
            [oplenario.paineis.wire.out.minha-sessao-atual :as wire]))

(set! *warn-on-reflection* true)

(def ^:private estados-sessao-viva
  "'aberta'/'suspensa' = a sessao ESTA acontecendo agora (ainda que pausada); 'agendada' NAO conta (ainda
  nao abriu) mesmo aparecendo no mesmo grupo nao-encerrado de `sli-sessoes`."
  #{"aberta" "suspensa"})

(defn minha-sessao-atual->wire
  "Sequencia de sessoes (cru, do controller sli-sessoes — MESMO shape que `sli-sessoes->wire` consome, uma
  seq PLANA, nao um mapa {:sessoes ...}) -> MinhaSessaoAtualOut (validado). So' a PRIMEIRA entrada cujo
  `estado-atual` seja realmente 'viva' (`estados-sessao-viva` — nunca uma 'agendada' futura); nenhuma
  sessao viva -> {:sessao-id nil :situacao nil}."
  [sessoes]
  (let [primeira (first (filter #(contains? estados-sessao-viva (:estado-atual %)) sessoes))
        out {:sessao-id (some-> primeira :sessao-id str)
             :situacao (some-> primeira :estado-atual situacao/derivar)}]
    (when-not (m/validate wire/MinhaSessaoAtualOut out)
      (throw (ex-info "projecao de minha-sessao-atual viola o contrato (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MinhaSessaoAtualOut out))})))
    out))
