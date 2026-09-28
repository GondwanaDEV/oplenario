(ns oplenario.sessoes.adapters.out.livro-atas
  "Gate de SAIDA `models -> wire/out` do LIVRO DE ATAS (Onda E, `livro-atas`). O MESMO contrato serve a tela interna e
  o portal; `publico?` so' apaga o nome de quem publicou (servidor da Casa: fica na tela interna). Nunca atravessam
  `ente-id`, `publicada-por` (id), nem a proveniencia interna da IA (rascunho, modelo, prompt, proporcao alterada).
  Validado contra o wire (drift = bug de servidor -> 500, nunca resposta malformada)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn- sessao->wire [s]
  {:id (->str (or (:sessao-id s) (:id s))) :tipo-sessao (:tipo-sessao s) :numero-sequencial (:numero-sequencial s)
   :aberta-em (->str (:aberta-em s)) :encerrada-em (->str (:encerrada-em s)) :agendada-para (->str (:agendada-para s))})

(defn- leitura->wire [{:keys [modo registrada-em ata-versao]}]
  (when modo {:modo modo :registrada-em (->str registrada-em) :ata-versao ata-versao}))

(defn livro->wire
  "Linhas de `db/ata/livro` (ja' filtradas por visibilidade) -> LivroAtasOut."
  [linhas]
  (validado wire/LivroAtasOut
            {:atas (mapv (fn [l]
                           {:sessao (sessao->wire l) :versao (:versao l) :origem-redacao (:origem-redacao l)
                            :conteudo-sha256 (:conteudo-sha256 l) :publicada-em (->str (:publicada-em l))
                            :leitura (leitura->wire {:modo (:leitura-modo l)
                                                     :registrada-em (:leitura-registrada-em l)
                                                     :ata-versao (:leitura-ata-versao l)})})
                         linhas)}
            "livro de atas viola o contrato LivroAtasOut (bug de servidor)"))

(defn- versao->wire [publico? v]
  {:versao (:versao v) :origem-redacao (:origem-redacao v) :conteudo-sha256 (:conteudo-sha256 v)
   :motivo-retificacao (:motivo-retificacao v) :publicada-em (->str (:publicada-em v))
   :publicada-por-nome (when-not publico? (:publicada-por-nome v))})

(defn ata-do-livro->wire
  "{:sessao :ata (com texto) :vigente :versoes :leitura} -> AtaDoLivroOut. `publico?` = resposta do portal."
  [{:keys [sessao ata vigente versoes leitura]} publico?]
  (validado wire/AtaDoLivroOut
            {:sessao (sessao->wire sessao) :versao (versao->wire publico? ata) :texto (:texto ata)
             :vigente (boolean vigente) :versoes (mapv (partial versao->wire publico?) versoes)
             :leitura (some-> leitura leitura->wire)}
            "ata do livro viola o contrato AtaDoLivroOut (bug de servidor)"))
