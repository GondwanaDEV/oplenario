(ns oplenario.legislativo.adapters.out.votacao
  "Gate de SAIDA `models -> wire/out` da votacao ao vivo (§22.10 adapters/out, ADR-0001 §3). Chamado SO pelo
  diplomat/. Projeta os recibos do dominio (kebab, uuid) p/ a representacao externa (strings, JSON) e FILTRA
  internos. EXCECAO deliberada: `abertura->wire` EXPOE `lock-version` (ledger de prontidao Fase 8 achado #2) —
  nao ha' rota GET de detalhe da votacao, entao o recibo de abertura e' a UNICA fonte do token de CAS que
  `POST .../encerramento` exige; esconde-lo tornaria essa segunda chamada impossivel de montar so' pela API.
  As projecoes sao VALIDADAS contra os contratos wire/out (drift de campo = bug de servidor -> 500, nunca
  resposta malformada que envenena o codegen do front, Eixo 8)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.votacao :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn abertura->wire
  "Recibo de abrir-votacao! {:id :lock-version} -> AberturaOut. Estado e' sempre 'aberta' (a votacao nasce
  aberta). `lock-version` viaja (excecao consciente, ver docstring do ns) — sem ele o cliente nao tem como
  montar `POST .../encerramento` (que o exige no corpo)."
  [{:keys [id lock-version]}]
  (validado wire/AberturaOut {:id (->str id) :estado "aberta" :lock-version lock-version} "abertura de votacao"))

(defn voto->wire
  "Recibo de registrar-voto!/registrar-voto-secreto! {:id} -> VotoOut (so o id; sem identidade — sigilo §22.6)."
  [{:keys [id]}]
  (validado wire/VotoOut {:id (->str id)} "voto"))

(defn encerramento->wire
  "Snapshot de encerrar-votacao! (kebab: :sim/:nao/:abstencao) -> EncerramentoOut (total-sim/...). nil-safe nos
  totais (modalidade 'simbolica' nao apura individual)."
  [{:keys [id estado resultado sim nao abstencao base-membros]}]
  (validado wire/EncerramentoOut
            {:id (->str id) :estado estado :resultado resultado
             :total-sim sim :total-nao nao :total-abstencao abstencao :base-membros base-membros}
            "encerramento de votacao"))

(defn objeto->wire
  "{:objeto-tipo :proposicao} (controllers/detalhe-votacao) -> ObjetoVotacaoOut (fatia 'demo-tres-consertos'
  #2). `:proposicao` (quando presente) e' o registro CHEIO de `repo/buscar-proposicao` — SELECT-KEYS aqui e'
  a defesa: so' tipo/ano/sequencial/ementa atravessam pro cliente, nunca autor/estado/atributos-especificos/
  etc que a leitura completa carrega e este recurso nao precisa expor."
  [{:keys [objeto-tipo proposicao]}]
  (validado wire/ObjetoVotacaoOut
            {:objeto-tipo objeto-tipo
             :proposicao (when proposicao (select-keys proposicao [:tipo :ano :sequencial :ementa]))}
            "objeto de votacao"))

(defn- proposicao-resumo [p] (when p (select-keys p [:tipo :ano :sequencial :ementa])))

(defn votacao-aberta->wire
  "{:votacao-id :modalidade :objeto-tipo :proposicao :votos|:votos-registrados} (controllers/votacao-aberta)
  -> VotacaoAbertaOut (fatia 'demo-tres-consertos' #2b). `:votos` (lista {vereador-id voto}) SO' atravessa
  quando `:modalidade` e' EXATAMENTE 'nominal' — o schema `:multi` (`:dispatch :modalidade`) torna
  estruturalmente impossivel uma votacao 'secreta'/'simbolica' carregar essa chave: um `merge` descuidado
  que tentasse incluir `:votos` no ramo errado reprova a validacao (500), nunca vaza voto individual em
  silencio (sigilo §22.6, mesma fronteira de `voto->wire`/`VotoRegistradoPayload`)."
  [{:keys [votacao-id modalidade objeto-tipo objeto-id proposicao votos votos-registrados]}]
  (validado wire/VotacaoAbertaOut
            (if (= "nominal" modalidade)
              {:votacao-id (->str votacao-id) :modalidade modalidade :objeto-tipo objeto-tipo
               :objeto-id (->str objeto-id) :proposicao (proposicao-resumo proposicao)
               :votos (mapv (fn [v] {:vereador-id (->str (:vereador-id v)) :voto (:voto v)}) votos)}
              {:votacao-id (->str votacao-id) :modalidade modalidade :objeto-tipo objeto-tipo
               :objeto-id (->str objeto-id) :proposicao (proposicao-resumo proposicao)
               :votos-registrados votos-registrados})
            "votacao aberta"))
