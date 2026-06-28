(ns oplenario.governanca.redator
  "Redação de MINIMIZAÇÃO (B2, 2ª camada): scrub de IDENTIFICADORES PRIVADOS de alta precisão sobre
  conteúdo JÁ liberado como público. NÃO mexe em nome público (vereador/autoridade é público no
  contexto legislativo). NER de PII amplo = refinamento deferido (content-aware, [GAP])."
  (:require [clojure.string :as str]))

;; Padrões determinísticos de alta precisão. CNPJ antes de CPF (mais longo) p/ não fragmentar.
(def ^:private padroes
  [[:cnpj  #"\b\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}\b"]
   [:cpf   #"\b\d{3}\.?\d{3}\.?\d{3}-?\d{2}\b"]
   [:email #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"]])

(defn redigir
  "Devolve {:texto <redigido> :redacoes {:cpf n ...}}. Cada identificador vira marcador tipado."
  [texto]
  (reduce
   (fn [acc [tipo re]]
     (let [n (count (re-seq re (:texto acc)))]
       {:texto (str/replace (:texto acc) re (str "[REDIGIDO:" (str/upper-case (name tipo)) "]"))
        :redacoes (cond-> (:redacoes acc) (pos? n) (assoc tipo n))}))
   {:texto texto :redacoes {}}
   padroes))
