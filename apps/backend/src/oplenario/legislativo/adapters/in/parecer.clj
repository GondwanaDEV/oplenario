(ns oplenario.legislativo.adapters.in.parecer
  "Gate de ENTRADA `wire/in -> models` do EDITOR de parecer (§22.10 adapters/in, ADR-0001, Onda B Slice 5).
  Chamado SO pelo diplomat/. Reusa `adapters-in/id-param->uuid` de adapters.in.votacao (nao duplica —
  mesma fn serve p/ path-param de qualquer modulo/rota, ja' generica). Valida (fail-closed -> 400) e COAGE
  o corpo JSON p/ o dominio; INJETA o que nao vem do corpo (`id`/`created-by`/`updated-by` do ator, nunca
  do cliente, §22.5)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.parecer :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword ja internada)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so os nomes-de-campo humanizados (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(def ^:private campos-rascunho ["relatorio" "analise"])
(def ^:private campos-emitir ["voto-relator" "lock-version"])

(defn salvar-rascunho->dominio
  "Corpo (wire/in.SalvarRascunhoParecer) + `ator` + `parecer-id` (path, ja' UUID) -> mapa de dominio p/
  Repo/nova-versao-parecer!. Monta o texto-inline no MESMO formato de duas secoes que o adapters/out faz o
  parse inverso (`## Relatório\\n\\n{relatorio}\\n\\n## Análise\\n\\n{analise}`). `relatorio`/`analise`
  ausentes -> \"\" (o form sempre manda as duas secoes; ausencia e' tratada como vazia, nao erro)."
  [ator parecer-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-rascunho)]
    (validar! wire/SalvarRascunhoParecer m "corpo de salvar rascunho de parecer invalido")
    (let [relatorio (or (:relatorio m) "")
          analise (or (:analise m) "")]
      {:id (random-uuid) :parecer-id parecer-id
       :texto-inline (str "## Relatório\n\n" relatorio "\n\n## Análise\n\n" analise)
       :origem-versao "redacao" :formato "markdown" :created-by (:identidade-id ator)})))

(defn emitir->dominio
  "Corpo (wire/in.EmitirParecer) + `ator` + `parecer-id` + `template-id` (o controller busca o parecer p/
  extrair) + `agora` (LocalDate, JA' RESOLVIDO pelo caller via kernel/tempo — review MEDIUM fe-11-parecer:
  este adapters/in e' traducao PURA wire->dominio, nao le relogio; o diplomat injeta `agora`, mesma
  disciplina de `oplenario.participacao.controllers` que le o Relogio na borda, nao no meio do parse) ->
  mapa de dominio p/ Repo/emitir-parecer!. `voto-relator` obrigatorio e NAO-BRANCO (o Malli `:min 1` so'
  barra string vazia — espacos-em-branco viram :validacao/invalido aqui, defesa extra). `lock-version`
  (review HIGH fe-11-parecer) e' o valor que o CLIENTE viu no editor — a CAS real acontece no Repo, antes
  de qualquer escrita."
  [ator parecer-id template-id agora wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-emitir)]
    (validar! wire/EmitirParecer m "corpo de emitir parecer invalido")
    (when (str/blank? (:voto-relator m))
      (invalido! "voto-relator obrigatorio (nao-branco)" {:campos [:voto-relator]}))
    {:parecer-id parecer-id :template-id template-id :gatilho "emitir" :voto-relator (:voto-relator m)
     :lock-version (:lock-version m)
     ;; `alegado` (fatia 4): o canal do CLIENTE no `amb` do guard, FIXADO vazio aqui. Nenhum campo do
     ;; corpo de emissao alimenta a avaliacao do rito — o que o relator manda e' voto e texto, e os dois
     ;; sao ESCRITA auditada, nao premissa de guard. O nome declara a procedencia para o dia em que
     ;; alguem quiser abrir o campo; ate' la, `{}` e' a afirmacao de que nao ha' nada alegado.
     :updated-by (:identidade-id ator) :agora agora :alegado {}}))
