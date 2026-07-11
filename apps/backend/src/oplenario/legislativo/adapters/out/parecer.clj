(ns oplenario.legislativo.adapters.out.parecer
  "Gate de SAIDA `models -> wire/out` do EDITOR de parecer (§22.10 adapters/out, ADR-0001, Onda B Slice 5).
  `editor->wire` projeta o resultado de `Repo/buscar-parecer-para-editor` p/ ParecerEditorOut, validado
  (mesmo padrao `validado` de adapters.out.ficha-materia/adapters.out.proposicao). Aqui mora o PARSER do
  texto-inline do parecer: `texto->relatorio-e-analise` (PURA, sem IO) faz o split das duas secoes markdown
  ('## Relatório' / '## Análise') — degrada SEM LANCAR se o conteudo nao casar o formato esperado (nil ou
  texto legado sem os headers), mesma disciplina fail-closed/nunca-500 do resto do FE view-model."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.parecer :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(def ^:private cabecalho-relatorio "## Relatório")
(def ^:private cabecalho-analise "## Análise")

(defn texto->relatorio-e-analise
  "texto-inline (string ou nil) -> {:relatorio :analise} (ambas string, nunca nil). Formato esperado:
  '## Relatório\\n\\n{relatorio}\\n\\n## Análise\\n\\n{analise}'. PURA — nunca lanca: (1) nil -> {\"\" \"\"};
  (2) so' o cabecalho de relatorio presente -> analise vazia; (3) sem NENHUM dos dois headers (texto
  legado/livre) -> relatorio = o texto inteiro (trim), analise vazia — degrada, nao rejeita."
  [texto]
  (cond
    (nil? texto)
    {:relatorio "" :analise ""}

    (str/includes? texto cabecalho-relatorio)
    (let [apos-relatorio (subs texto (+ (str/index-of texto cabecalho-relatorio) (count cabecalho-relatorio)))]
      (if (str/includes? apos-relatorio cabecalho-analise)
        (let [i (str/index-of apos-relatorio cabecalho-analise)]
          {:relatorio (str/trim (subs apos-relatorio 0 i))
           :analise (str/trim (subs apos-relatorio (+ i (count cabecalho-analise))))})
        {:relatorio (str/trim apos-relatorio) :analise ""}))

    :else
    {:relatorio (str/trim texto) :analise ""}))

(defn- objeto->wire [{:keys [id tipo ano sequencial urn-lex ementa]}]
  {:id (->str id) :tipo tipo :ano ano :sequencial sequencial :urn-lex urn-lex :ementa ementa})

(defn editor->wire
  "{:parecer :objeto :texto-rascunho :texto-vigente} (dominio, kebab, de buscar-parecer-para-editor) ->
  ParecerEditorOut. O texto-inline PREFERIDO e' o do rascunho (edicao em curso); na ausencia, o vigente;
  na ausencia de ambos, texto-estado='vazio' e relatorio/analise saem nil (o editor comeca em branco)."
  [{:keys [parecer objeto texto-rascunho texto-vigente]}]
  (let [fonte (or texto-rascunho texto-vigente)
        texto-estado (cond texto-rascunho "rascunho" texto-vigente "vigente" :else "vazio")
        {:keys [relatorio analise]} (when fonte (texto->relatorio-e-analise (:texto-inline fonte)))]
    (validado wire/ParecerEditorOut
              {:id (->str (:id parecer)) :objeto-tipo (:objeto-tipo parecer)
               :objeto-id (->str (:objeto-id parecer)) :comissao-id (->str (:comissao-id parecer))
               :relator-id (->str (:relator-id parecer)) :voto-relator (:voto-relator parecer)
               :estado (:estado parecer) :template-id (->str (:template-id parecer))
               :lock-version (:lock-version parecer) :criado-em (->str (:criado-em parecer))
               :objeto (when objeto (objeto->wire objeto))
               :relatorio relatorio :analise analise
               :texto-estado texto-estado
               :texto-numero-versao (:numero-versao fonte)}
              "editor de parecer")))
