(ns oplenario.kernel.arquivo
  "PURO (kernel): o que e' comum a QUALQUER modulo que recebe um arquivo do navegador (anexos de comunicado, anexos do
  atendimento ao cidadao). So' as duas limpezas da borda: o nome de exibicao seguro e o tipo declarado. A REGRA de quais
  tipos o modulo aceita, quantos e quando e' do modulo (`logic` dele) — nao daqui."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn nome-de-arquivo
  "O nome que o navegador mandou, como nome de exibicao seguro: so' o ultimo segmento do caminho, sem caractere de
  controle nem aspas, aparado e com teto. Vazio -> \"anexo\"."
  [s]
  (let [base (last (str/split (str s) #"[/\\]"))
        limpo (-> (or base "")
                  (str/replace #"[\p{Cntrl}\"]" "")
                  str/trim)
        limpo (if (> (count limpo) 200) (subs limpo 0 200) limpo)]
    (if (str/blank? limpo) "anexo" limpo)))

(defn tipo-de-midia
  "O content-type declarado da parte, se tem a forma `tipo/subtipo`; senao octet-stream (o navegador baixa)."
  [s]
  (let [t (some-> s (str/split #";") first str/trim str/lower-case)]
    (if (and t (re-matches #"[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}" t))
      t
      "application/octet-stream")))
