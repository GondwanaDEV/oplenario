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

(defn- instante-ou-epoca
  "Ordenavel mesmo quando a vista nao traz o instante (linha de vista antiga/incompleta): sem instante = o mais
  antigo possivel, nunca NPE e nunca 'mais recente' por engano."
  ^java.time.Instant [^java.time.Instant i]
  (or i java.time.Instant/EPOCH))

(defn- escolher-sessao-viva
  "A sessao que o cockpit do vereador deve abrir entre as vivas (aberta/suspensa). `sli-sessoes` entrega o
  grupo aberto da MAIS ANTIGA para a mais nova (o dashboard da Mesa quer achar a sessao esquecida/travada);
  o cockpit quer o oposto — com duas em curso ao mesmo tempo, a de agora. Criterio, em ordem:
    1. 'aberta' antes de 'suspensa' (e' na aberta que se vota agora);
    2. `aberta-em` mais recente (quando a sessao comecou);
    3. `transicionou-em` mais recente;
    4. `sessao-id`, so' para a escolha ser deterministica em empate total.
  A vista nao guarda o TIPO da sessao (ordinaria/audiencia publica) nem o corpo do vereador, entao este criterio
  nao consegue preferir a ordinaria a uma audiencia que comecou depois — ver o relatorio da frente."
  [sessoes]
  (->> sessoes
       (filter #(contains? estados-sessao-viva (:estado-atual %)))
       (sort-by (fn [s] [(if (= "aberta" (:estado-atual s)) 0 1)
                         (- (.toEpochMilli (instante-ou-epoca (:aberta-em s))))
                         (- (.toEpochMilli (instante-ou-epoca (:transicionou-em s))))
                         (str (:sessao-id s))]))
       first))

(defn minha-sessao-atual->wire
  "{:sessoes [...] :sessoes-total N} (cru, do controller sli-sessoes — MESMO shape que `sli-sessoes->wire`
  consome, fatia 'truncamento-familia') -> MinhaSessaoAtualOut (validado). So' USA `:sessoes` — este
  endpoint devolve UMA sessao, entao `sessoes-total` (o par irmao que sinaliza corte de uma LISTA) nao se
  aplica aqui. So' entre as entradas cujo `estado-atual` seja realmente 'viva' (`estados-sessao-viva` —
  nunca uma 'agendada' futura), a escolhida por `escolher-sessao-viva` (a de agora, nao a mais antiga);
  nenhuma sessao viva -> {:sessao-id nil :situacao nil}."
  [{:keys [sessoes]}]
  (let [primeira (escolher-sessao-viva sessoes)
        out {:sessao-id (some-> primeira :sessao-id str)
             :situacao (some-> primeira :estado-atual situacao/derivar)}]
    (when-not (m/validate wire/MinhaSessaoAtualOut out)
      (throw (ex-info "projecao de minha-sessao-atual viola o contrato (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MinhaSessaoAtualOut out))})))
    out))
