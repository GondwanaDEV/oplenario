(ns oplenario.legislativo.documento-adapters-in-test
  "UNIT (puro, sem DB) — o gate adapters/in do EXPEDIENTE (Onda B Slice 6): valida+coage+injeta os 3 corpos
  (gerar/editar/protocolar documento). Parecer nao tem arquivo unit dedicado a adapters/in (a cobertura dele
  mora nos testes de integracao do editor) — aqui cobrimos com deftest simples no MESMO estilo (validacao
  fail-closed -> :validacao/invalido; injecao de id/created-by/updated-by do ator, nunca do corpo)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.adapters.in.documento :as adapters]))

(defn- ator [] {:identidade-id (random-uuid) :ente-id (random-uuid)})

;; ---------- gerar-documento->dominio ----------

(deftest gerar-documento->dominio-corpo-minimo-valido
  (let [a (ator) modelo-id (random-uuid)
        m (adapters/gerar-documento->dominio a {"modelo-id" (str modelo-id) "assunto" "Convite"})]
    (is (some? (:id m)) "injeta id novo")
    (is (= modelo-id (:modelo-id m)))
    (is (= "Convite" (:assunto m)))
    (is (= {} (:dados m)) "dados ausente -> {}")
    (is (= (:identidade-id a) (:created-by m)))))

(deftest gerar-documento->dominio-com-dados
  (let [a (ator) modelo-id (random-uuid)]
    (is (= {"destinatario" "Prefeito"}
           (:dados (adapters/gerar-documento->dominio a
                     {"modelo-id" (str modelo-id) "assunto" "Convite"
                      "dados" {"destinatario" "Prefeito"}}))))))

(deftest gerar-documento->dominio-sem-assunto-invalido
  (let [a (ator) modelo-id (random-uuid)]
    (is (thrown-with-msg? Exception #"invalido"
          (adapters/gerar-documento->dominio a {"modelo-id" (str modelo-id)})))))

(deftest gerar-documento->dominio-modelo-id-nao-uuid-invalido
  (let [a (ator)]
    (is (thrown? Exception (adapters/gerar-documento->dominio a {"modelo-id" "nao-e-uuid" "assunto" "X"})))))

(deftest gerar-documento->dominio-corpo-nao-mapa-invalido
  (is (thrown? Exception (adapters/gerar-documento->dominio (ator) "nao e objeto"))))

(deftest gerar-documento->dominio-campo-extra-e-ignorado-nao-lanca
  ;; so-esperados filtra ANTES do Malli ver o mapa — campo desconhecido no corpo cru nao vaza pro dominio
  ;; nem quebra a validacao (o :closed true do wire barraria um campo que sobrevivesse ao filtro).
  (let [a (ator) modelo-id (random-uuid)
        m (adapters/gerar-documento->dominio a
            {"modelo-id" (str modelo-id) "assunto" "X" "campo-desconhecido" "hack"})]
    (is (not (contains? m :campo-desconhecido)))))

;; ---------- editar-documento->dominio ----------

(deftest editar-documento->dominio-so-corpo
  (let [a (ator) id (random-uuid)
        m (adapters/editar-documento->dominio a id {"lock-version" 0 "corpo" "Novo corpo."})]
    (is (= id (:id m)))
    (is (= 0 (:lock-version m)))
    (is (= "Novo corpo." (:corpo m)))
    (is (nil? (:assunto m)))
    (is (= (:identidade-id a) (:updated-by m)))))

(deftest editar-documento->dominio-sem-lock-version-invalido
  (let [a (ator) id (random-uuid)]
    (is (thrown? Exception (adapters/editar-documento->dominio a id {"corpo" "X"})))))

;; ---------- protocolar-documento->dominio ----------

(deftest protocolar-documento->dominio-basico
  (let [a (ator) id (random-uuid)
        m (adapters/protocolar-documento->dominio a id {"lock-version" 3})]
    (is (= id (:documento-id m)))
    (is (= 3 (:lock-version m)))
    (is (= (:identidade-id a) (:ator-id m)))
    (is (not (contains? m :ano)) "ano NAO e' resolvido aqui — o controller injeta via kernel/tempo")))

(deftest protocolar-documento->dominio-sem-lock-version-invalido
  (let [a (ator) id (random-uuid)]
    (is (thrown-with-msg? Exception #"invalido" (adapters/protocolar-documento->dominio a id {})))))
