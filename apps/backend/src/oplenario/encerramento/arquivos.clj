(ns oplenario.encerramento.arquivos
  "HOST (§22.10) — os BLOBS de uma Casa no object storage (ADR-0018, Eixo 4): o que a exportacao leva em `arquivos/`
  e o que o apagamento remove. Duas fontes, unidas:

  1. a CONVENCAO de chave `<pasta>/<ente-id>/...` (remessas, gravacao, folhas, publicacoes, …): as pastas de primeiro
     nivel sao DESCOBERTAS listando a raiz do bucket (nunca uma lista a mao), e dentro de cada uma lista-se
     `<pasta>/<ente-id>/`. `exportacoes/` fica fora da exportacao (seria a exportacao dentro dela mesma) e sai no fim
     do apagamento;
  2. as REFERENCIAS gravadas nas linhas da Casa: colunas de ponteiro (`*objeto_store_ref`, `*_uri`, `brasao_ref`) das
     tabelas exportadas, lidas na transacao do tenant (RLS). URL externa (`://`) nao e' blob nosso.

  A exportacao leva toda referencia que existe no store (a que falta vai ao manifesto como ausente). O apagamento so'
  remove referencia DENTRO da convencao (a chave tem o ente como segmento): uma chave fora dela nao prova que e' so'
  desta Casa, entao fica e e' relatada — nunca se apaga blob de outra Casa por engano."
  (:require [clojure.string :as str]
            [oplenario.encerramento.inventario :as inventario]
            [oplenario.kernel.components.objeto-store :as store])
  (:import (java.sql Connection)))

(set! *warn-on-reflection* true)

(def pasta-das-exportacoes "exportacoes/")

(defn prefixo-das-exportacoes [ente-id] (str pasta-das-exportacoes ente-id "/"))

(defn coluna-de-ponteiro?
  "PURA: a coluna guarda uma chave do object storage?"
  [nome]
  (boolean (re-find #"(^|_)objeto_store_ref$|_uri$|^brasao_ref$" nome)))

(defn da-convencao?
  "PURA: a chave segue `<pasta>/<ente-id>/...` desta Casa (o ente e' um segmento do caminho)?"
  [ente-id chave]
  (boolean (some #{(str ente-id)} (butlast (str/split chave #"/")))))

(defn referencias
  "Na transacao do tenant (`tx`, RLS): as chaves que as linhas da Casa apontam, em ordem. So' tabelas exportadas."
  [^Connection tx tabelas ente-id]
  (into (sorted-set)
        (for [t tabelas
              :let [cols (filter coluna-de-ponteiro? (map :coluna (inventario/colunas tx t)))]
              col cols
              :let [[pred n] (inventario/com-parametro (:predicado t))
                    sql (str "SELECT DISTINCT t." (inventario/ident col) "::text FROM " (inventario/tabela-sql t)
                             " AS t WHERE (" pred ") AND t." (inventario/ident col) " IS NOT NULL")]
              v (with-open [ps (.prepareStatement tx sql)]
                  (dotimes [i n] (.setObject ps (int (inc i)) ente-id))
                  (with-open [rs (.executeQuery ps)]
                    (loop [acc []] (if (.next rs) (recur (conj acc (.getString rs 1))) acc))))
              :when (and (not (str/blank? v)) (not (str/includes? v "://")))]
          v)))

(defn por-convencao
  "As chaves sob `<pasta>/<ente-id>/` em todas as pastas de primeiro nivel do bucket (descobertas), exceto as de
  `excluir` (prefixos de pasta, ex. #{\"exportacoes/\"})."
  [objeto-store ente-id excluir]
  (into (sorted-set)
        (for [pasta (store/listar objeto-store "" false)
              :when (and (str/ends-with? pasta "/") (not (contains? excluir pasta)))
              chave (store/listar objeto-store (str pasta ente-id "/") true)
              :when (not (str/ends-with? chave "/"))]
          chave)))
