(ns oplenario.legislativo.documento-modelo-adapters-in-test
  "UNIT (puro, sem DB) — o gate adapters/in do MODELO de documento (Onda B Slice 6, fatia de escrita):
  valida+coage+injeta os 2 corpos (criar/atualizar modelo). Mesmo estilo de documento-adapters-in-test."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.adapters.in.documento-modelo :as adapters]))

(defn- ator [] {:identidade-id (random-uuid) :ente-id (random-uuid)})

;; ---------- criar-modelo->dominio ----------

(deftest criar-modelo->dominio-corpo-valido
  (let [a (ator)
        m (adapters/criar-modelo->dominio a
            {"chave" "oficio_padrao" "nome" "Oficio padrao" "tipo-documento" "oficio"
             "corpo-template" "Ao {{destinatario}}."})]
    (is (some? (:id m)) "injeta id novo")
    (is (= "oficio_padrao" (:chave m)))
    (is (= "Oficio padrao" (:nome m)))
    (is (= "oficio" (:tipo-documento m)))
    (is (= "Ao {{destinatario}}." (:corpo-template m)))
    (is (= (:identidade-id a) (:created-by m)))))

(deftest criar-modelo->dominio-sem-chave-invalido
  (let [a (ator)]
    (is (thrown-with-msg? Exception #"invalido"
          (adapters/criar-modelo->dominio a
            {"nome" "X" "tipo-documento" "oficio" "corpo-template" "Y"})))))

(deftest criar-modelo->dominio-tipo-documento-fora-do-vocabulario-invalido
  (let [a (ator)]
    (is (thrown? Exception
          (adapters/criar-modelo->dominio a
            {"chave" "x" "nome" "X" "tipo-documento" "nao-existe" "corpo-template" "Y"})))))

(deftest criar-modelo->dominio-corpo-template-vazio-invalido
  (let [a (ator)]
    (is (thrown? Exception
          (adapters/criar-modelo->dominio a
            {"chave" "x" "nome" "X" "tipo-documento" "oficio" "corpo-template" ""})))))

(deftest criar-modelo->dominio-corpo-nao-mapa-invalido
  (is (thrown? Exception (adapters/criar-modelo->dominio (ator) "nao e objeto"))))

(deftest criar-modelo->dominio-campo-extra-e-ignorado-nao-lanca
  (let [a (ator)
        m (adapters/criar-modelo->dominio a
            {"chave" "x" "nome" "X" "tipo-documento" "oficio" "corpo-template" "Y"
             "campo-desconhecido" "hack"})]
    (is (not (contains? m :campo-desconhecido)))))

;; ---------- atualizar-modelo->dominio ----------

(deftest atualizar-modelo->dominio-so-nome
  (let [a (ator) id (random-uuid)
        m (adapters/atualizar-modelo->dominio a id {"lock-version" 0 "nome" "Novo nome"})]
    (is (= id (:id m)))
    (is (= 0 (:lock-version m)))
    (is (= "Novo nome" (:nome m)))
    (is (nil? (:corpo-template m)))
    (is (nil? (:ativo m)))
    (is (= (:identidade-id a) (:updated-by m)))))

(deftest atualizar-modelo->dominio-desativar
  (let [a (ator) id (random-uuid)
        m (adapters/atualizar-modelo->dominio a id {"lock-version" 2 "ativo" false})]
    (is (false? (:ativo m)))
    (is (nil? (:nome m)))))

(deftest atualizar-modelo->dominio-sem-lock-version-invalido
  (let [a (ator) id (random-uuid)]
    (is (thrown-with-msg? Exception #"invalido"
          (adapters/atualizar-modelo->dominio a id {"nome" "X"})))))

(deftest atualizar-modelo->dominio-nao-aceita-chave-nem-tipo-documento
  ;; wire/in.AtualizarModelo e' :closed true e nem lista chave/tipo-documento entre os campos esperados —
  ;; so-esperados filtra ANTES do Malli ver o mapa, entao mandar esses campos nao lanca (sao simplesmente
  ;; ignorados), mas TAMBEM nao aparecem no dominio resultante (identidade do modelo e' imutavel nesta fatia).
  (let [a (ator) id (random-uuid)
        m (adapters/atualizar-modelo->dominio a id
            {"lock-version" 0 "chave" "outra_chave" "tipo-documento" "certidao"})]
    (is (not (contains? m :chave)))
    (is (not (contains? m :tipo-documento)))))
