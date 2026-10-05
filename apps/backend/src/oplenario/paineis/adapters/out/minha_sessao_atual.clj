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
  (aberta/suspensa); nenhuma sessao viva -> {:sessao-id nil :situacao nil} (nunca engana com uma agendada).

  Duas sessoes vivas ao mesmo tempo (docs/16, retriagem linha 12): a padrao e' a aberta mais recente, e a
  resposta lista todas para o cockpit oferecer a troca."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.paineis.logic.situacao :as situacao]
            [oplenario.paineis.wire.out.minha-sessao-atual :as wire])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def ^:private estados-sessao-viva
  "'aberta'/'suspensa' = a sessao ESTA acontecendo agora (ainda que pausada); 'agendada' NAO conta (ainda
  nao abriu) mesmo aparecendo no mesmo grupo nao-encerrado de `sli-sessoes`."
  #{"aberta" "suspensa"})

(defn- instante-ou-epoca
  "Ordenavel mesmo sem instante na vista: sem instante = o mais antigo possivel, nunca 'mais recente' por engano."
  ^Instant [^Instant i]
  (or i Instant/EPOCH))

(defn- ordenar-vivas
  "As sessoes vivas na ordem do cockpit — o OPOSTO do dashboard da Mesa (que quer a esquecida/travada primeiro):
    1. 'aberta' antes de 'suspensa' (e' na aberta que se vota agora);
    2. `aberta-em` mais recente;
    3. `transicionou-em` mais recente;
    4. `sessao-id`, so' para ser deterministico.
  A vista nao guarda o tipo da sessao, entao nenhuma ordem acerta sempre (uma audiencia aberta depois de uma
  ordinaria vem primeiro): por isso a resposta leva TODAS as vivas e o cockpit deixa o vereador trocar."
  [sessoes]
  (->> sessoes
       (filter #(contains? estados-sessao-viva (:estado-atual %)))
       (sort-by (fn [s] [(if (= "aberta" (:estado-atual s)) 0 1)
                         (- (.toEpochMilli (instante-ou-epoca (:aberta-em s))))
                         (- (.toEpochMilli (instante-ou-epoca (:transicionou-em s))))
                         (str (:sessao-id s))]))))

(defn minha-sessao-atual->wire
  "{:sessoes [...] :sessoes-total N} (cru, do controller sli-sessoes) -> MinhaSessaoAtualOut (validado). So' usa
  `:sessoes`. Entre as vivas (`estados-sessao-viva` — nunca uma 'agendada' futura), a padrao e' a primeira de
  `ordenar-vivas` (a de agora, nao a mais antiga); `sessoes-vivas` leva todas, na mesma ordem. Nenhuma viva ->
  {:sessao-id nil :situacao nil :sessoes-vivas []}."
  [{:keys [sessoes]}]
  (let [vivas (ordenar-vivas sessoes)
        primeira (first vivas)
        out {:sessao-id (some-> primeira :sessao-id str)
             :situacao (some-> primeira :estado-atual situacao/derivar)
             :sessoes-vivas (mapv (fn [s] {:sessao-id (str (:sessao-id s))
                                           :situacao (situacao/derivar (:estado-atual s))
                                           :aberta-em (some-> (:aberta-em s) str)})
                                  vivas)}]
    (when-not (m/validate wire/MinhaSessaoAtualOut out)
      (throw (ex-info "projecao de minha-sessao-atual viola o contrato (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MinhaSessaoAtualOut out))})))
    out))
