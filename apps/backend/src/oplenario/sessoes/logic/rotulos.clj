(ns oplenario.sessoes.logic.rotulos
  "PURO: o nome em palavras do que a pessoa le' sobre uma sessao — o tipo da sessao, o nome da sessao
  ('sessão ordinária nº 3'), o titulo com a data e o cargo na Mesa. Mora fora de `sessoes.logic` de
  proposito: a folha de presenca (`serializador-folha`) precisa destes rotulos e NAO importa `sessoes.logic`
  (D1 por construcao: nenhuma aritmetica de quorum ao alcance de quem serializa). Sem I/O, sem relogio."
  (:require [clojure.string :as str])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter DecimalStyle)))

(set! *warn-on-reflection* true)

(def rotulo-do-tipo
  "O nome legivel de cada tipo de sessao (o que a pessoa le: 'sessão ordinária nº 3', 'audiência pública nº 1')."
  {"ordinaria" "ordinária" "extraordinaria" "extraordinária" "solene" "solene" "secreta" "secreta" "especial" "especial"
   "audiencia_publica" "audiência pública"})

(defn nome-da-sessao
  "'sessão ordinária nº 3' / 'audiência pública nº 1' (minusculo, para entrar no meio da frase). Tipo
  desconhecido passa como veio; sem numero, sai sem o 'nº'."
  [{:keys [tipo-sessao numero-sequencial]}]
  (str (when-not (= "audiencia_publica" tipo-sessao) "sessão ")
       (get rotulo-do-tipo tipo-sessao (some-> tipo-sessao (str/replace #"_+" " ")))
       (when numero-sequencial (str " nº " numero-sequencial))))

(def ^:private formatador-data
  ;; DecimalStyle explicito: o titulo entra no HTML congelado e hasheado da folha — o Locale da JVM nao pode
  ;; entrar nos bytes (a mesma disciplina de `serializador-folha`).
  (-> (DateTimeFormatter/ofPattern "dd/MM/yyyy")
      (.withDecimalStyle DecimalStyle/STANDARD)))

(defn data-curta
  "LocalDate -> 'dd/MM/yyyy'; nil -> nil."
  [^LocalDate data]
  (when data (.format ^DateTimeFormatter formatador-data data)))

(defn- capitalizar [^String s]
  (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn titulo-da-sessao
  "'Sessão ordinária nº 3 de 01/10/2026' — o nome da sessao com a data civil `data` (LocalDate, a data de
  referencia da sessao). Sem data, so' o nome."
  [sessao ^LocalDate data]
  (str (capitalizar (nome-da-sessao sessao))
       (when data (str " de " (data-curta data)))))

(defn sessao-de
  "O texto NEUTRO para quando o documento nao traz o titulo (folha montada antes do campo existir): 'Sessão de
  dd/MM/yyyy'. Nunca o prefixo do UUID."
  [^LocalDate data]
  (if data (str "Sessão de " (data-curta data)) "Sessão"))

(def ^:private cargos-da-mesa
  {"presidente" "Presidência"
   "vice" "Vice-presidência"
   "vice_presidente" "Vice-presidência"
   "secretario" "Secretaria"})

(defn rotulo-do-cargo-na-mesa
  "O cargo na Mesa em palavras. O cadastro (`cadastros.comissao_cargo.cargo`, texto aberto) pode guardar a CHAVE
  ('1_secretario', 'vice'); quem le' a folha le' o nome do cargo ('1ª Secretaria', 'Vice-presidência'), sem supor
  o genero de quem o ocupa. Texto ja' escrito por extenso ('2ª Secretária da Mesa') passa como esta'; chave
  desconhecida troca `_` por espaco. Espelho de `rotuloDoCargoNaMesa` (frontend, `lib/perfil-vereador-vista.ts`,
  PR #137): mesma tabela, mesma regra. nil/branco -> nil."
  [cargo]
  (let [chave (some-> cargo str/trim)]
    (when-not (str/blank? chave)
      (let [k (str/lower-case chave)]
        (or (get cargos-da-mesa k)
            (when-let [[_ n tipo] (re-matches #"^(\d+)[_ºo°.\s-]*(vice[_\s-]?presidente|secretario|secretário)$" k)]
              (str n "ª " (if (str/starts-with? tipo "vice") "Vice-presidência" "Secretaria")))
            (capitalizar (str/trim (str/replace chave #"_+" " "))))))))
