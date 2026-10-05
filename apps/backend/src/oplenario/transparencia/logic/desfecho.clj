(ns oplenario.transparencia.logic.desfecho
  "O desfecho da materia no portal (docs/16, retriagem linhas 18 e 30): os atos depois do plenario em palavras, para a
  linha do tempo publica ('Por onde a materia passou'). Puro. O backfill da migration 20261005000203 escreve os MESMOS
  rotulos em SQL para os atos anteriores ao deploy; `desfecho_da_materia_test` confere os dois lados."
  (:require [clojure.string :as str]))

(def ^:private nome-da-especie
  {"lei" "Lei" "lei_complementar" "Lei Complementar" "resolucao" "Resolução"
   "decreto_legislativo" "Decreto Legislativo" "emenda_lom" "Emenda à Lei Orgânica"})

(defn- numero-da-norma [{:keys [tipo-norma numero ano]}]
  (when (and numero ano)
    (str (get nome-da-especie tipo-norma "Norma") " nº " numero "/" ano)))

(defn rotulo
  "O ato `{:ato ...}` em palavras, ou nil para ato desconhecido (evento de uma versao futura: a linha nao e' projetada
  sem rotulo, em vez de inventar um)."
  [{:keys [ato redacao-final numero ano] :as a}]
  (case ato
    "aprovada" (if redacao-final "Redação final aprovada em plenário" "Aprovada em plenário")
    "rejeitada" (if redacao-final "Redação final rejeitada em plenário" "Rejeitada em plenário")
    "autografo_enviado" (if (and numero ano)
                          (str "Autógrafo nº " numero "/" ano " enviado ao Executivo")
                          "Autógrafo enviado ao Executivo")
    "sancionado" "Sancionada pelo Executivo"
    "sancao_tacita" "Sancionada sem resposta do Executivo no prazo (sanção tácita)"
    "vetado" "Vetada pelo Executivo"
    "veto_mantido" "Veto mantido pela Câmara"
    "veto_derrubado" "Veto derrubado pela Câmara"
    "promulgada" (if-let [n (numero-da-norma a)] (str "Promulgação: " n) "Promulgação")
    "publicada" (if-let [n (numero-da-norma a)] (str "Publicação: " n) "Publicação")
    nil))

(defn chave
  "A chave de idempotencia da linha do tempo para o ato (nunca vai a tela). Prefixo `ato:` para nunca colidir com uma
  etapa do rito da Casa (texto livre)."
  [{:keys [ato redacao-final]}]
  (when-not (str/blank? ato)
    (str "ato:" ato (when redacao-final ":redacao_final"))))
