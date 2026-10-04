(ns oplenario.kernel.arquivo
  "PURO (kernel): o que e' comum a QUALQUER modulo que recebe um arquivo do navegador (anexos de comunicado, anexos do
  atendimento ao cidadao). So' as duas limpezas da borda: o nome de exibicao seguro e o tipo declarado. A REGRA de quais
  tipos o modulo aceita, quantos e quando e' do modulo (`logic` dele) — nao daqui."
  (:require [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def ^:private teto-do-nome 200)
(def ^:private teto-da-extensao 20)

(defn- cortar-preservando-a-extensao
  "Corta `nome` em `teto-do-nome` caracteres; se tem uma extensao razoavel (ate' `teto-da-extensao`), ela sobrevive ao corte:
  o tipo do arquivo nao pode mudar por causa do tamanho do nome."
  [^String nome]
  (if (<= (count nome) teto-do-nome)
    nome
    (let [i (str/last-index-of nome ".")
          ext (when i (subs nome i))]
      (if (and ext (> i 0) (<= (count ext) (inc teto-da-extensao)))
        (str (subs nome 0 (- teto-do-nome (count ext))) ext)
        (subs nome 0 teto-do-nome)))))

(defn nome-de-arquivo
  "O nome que o navegador mandou, como nome de exibicao seguro: so' o ultimo segmento do caminho, sem aspas, sem caractere de
  controle (C0 e C1) nem de FORMATO/invisivel Unicode (U+202E, o inversor de direcao, faz um nome terminado em
  `fdp.exe` parecer terminado em `exe.pdf`; ZWSP, BOM...), aparado e com teto de 200 que preserva a extensao. Vazio -> \"anexo\"."
  [s]
  (let [base (last (str/split (str s) #"[/\\]"))
        limpo (-> (or base "")
                  (str/replace #"[\p{Cc}\p{Cf}\"]" "")
                  str/trim
                  cortar-preservando-a-extensao
                  str/trim)]
    (if (str/blank? limpo) "anexo" limpo)))

(defn tipo-de-midia
  "O content-type declarado da parte, se tem a forma `tipo/subtipo`; senao octet-stream (o navegador baixa)."
  [s]
  (let [t (some-> s (str/split #";") first str/trim str/lower-case)]
    (if (and t (re-matches #"[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}" t))
      t
      "application/octet-stream")))

(defn content-disposition
  "O cabecalho do download SEMPRE como arquivo (`attachment`, nunca `inline`: o navegador nao renderiza o conteudo de quem
  enviou), com o nome em ASCII (fallback) e em UTF-8 (RFC 5987). O nome ja' vem sem aspas nem controle
  (`nome-de-arquivo`)."
  [nome]
  (let [ascii (str/replace nome #"[^\x20-\x7E]" "_")
        utf8 (str/replace (URLEncoder/encode ^String nome StandardCharsets/UTF_8) "+" "%20")]
    (str "attachment; filename=\"" ascii "\"; filename*=UTF-8''" utf8)))
