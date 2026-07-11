(ns oplenario.legislativo.pos-aprovacao-adapters-in-test
  "UNIT (puro, sem DB) — o gate adapters/in do POS-APROVACAO (Onda B Slice 7, F3.8a): valida+coage+injeta
  os 3 corpos (gerar autografo/registrar resposta/apreciar veto). Mesmo estilo de
  documento-adapters-in-test (validacao fail-closed -> :validacao/invalido; injecao de id/created-by/
  updated-by do ator, nunca do corpo)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.adapters.in.pos-aprovacao :as adapters]))

(defn- ator [] {:identidade-id (random-uuid) :ente-id (random-uuid)})

;; ---------- gerar-autografo->dominio ----------

(deftest gerar-autografo->dominio-corpo-vazio-valido
  (let [a (ator) pid (random-uuid)
        m (adapters/gerar-autografo->dominio a pid {})]
    (is (some? (:id m)) "injeta id novo do autografo")
    (is (= pid (:proposicao-id m)))
    (is (nil? (:prazo-resposta-em m)) "ausente -> nil (rito de prazo e' [GAP] regimental)")
    (is (= (:identidade-id a) (:created-by m)))
    (is (not (contains? m :ano)) "ano NAO e' resolvido aqui — o controller injeta via kernel/tempo")
    (is (not (contains? m :destinatario-texto)) "destinatario-texto NAO e' resolvido aqui — o controller")))

(deftest gerar-autografo->dominio-com-prazo-resposta-em
  (let [a (ator) pid (random-uuid)
        m (adapters/gerar-autografo->dominio a pid {"prazo-resposta-em" "2026-08-01T00:00:00Z"})]
    (is (= (java.time.Instant/parse "2026-08-01T00:00:00Z") (:prazo-resposta-em m)))))

(deftest gerar-autografo->dominio-prazo-invalido-lanca
  (let [a (ator) pid (random-uuid)]
    (is (thrown? Exception (adapters/gerar-autografo->dominio a pid {"prazo-resposta-em" "nao-e-data"})))))

(deftest gerar-autografo->dominio-corpo-nao-mapa-invalido
  (is (thrown? Exception (adapters/gerar-autografo->dominio (ator) (random-uuid) "nao e objeto"))))

(deftest gerar-autografo->dominio-campo-extra-e-ignorado-nao-lanca
  (let [a (ator) pid (random-uuid)
        m (adapters/gerar-autografo->dominio a pid {"campo-desconhecido" "hack"})]
    (is (not (contains? m :campo-desconhecido)))))

;; ---------- registrar-resposta->dominio ----------

(deftest registrar-resposta->dominio-sancionado
  (let [a (ator)
        m (adapters/registrar-resposta->dominio a {"lock-version" 0 "resultado" "sancionado"})]
    (is (= 0 (:lock-version m)))
    (is (= "sancionado" (:resultado m)))
    (is (nil? (:veto-tipo m)))
    (is (= (:identidade-id a) (:updated-by m)))
    (is (not (contains? m :id)) "id NAO e' resolvido aqui — o controller resolve a tramitacao pelo autografo")))

(deftest registrar-resposta->dominio-vetado-com-tipo-e-razoes
  (let [a (ator)
        m (adapters/registrar-resposta->dominio a
            {"lock-version" 1 "resultado" "vetado" "veto-tipo" "total" "veto-razoes" "Inconstitucional"})]
    (is (= "vetado" (:resultado m)))
    (is (= "total" (:veto-tipo m)))
    (is (= "Inconstitucional" (:veto-razoes m)))))

(deftest registrar-resposta->dominio-sem-lock-version-invalido
  (let [a (ator)]
    (is (thrown-with-msg? Exception #"invalido"
          (adapters/registrar-resposta->dominio a {"resultado" "sancionado"})))))

(deftest registrar-resposta->dominio-resultado-fora-do-vocabulario-invalido
  (let [a (ator)]
    (is (thrown? Exception
          (adapters/registrar-resposta->dominio a {"lock-version" 0 "resultado" "engavetado"})))))

(deftest registrar-resposta->dominio-veto-tipo-fora-do-vocabulario-invalido
  (let [a (ator)]
    (is (thrown? Exception
          (adapters/registrar-resposta->dominio a
            {"lock-version" 0 "resultado" "vetado" "veto-tipo" "meio"})))))

(deftest registrar-resposta->dominio-vetado-sem-veto-tipo-invalido
  ;; coerencia resultado<->veto-tipo — espelha na borda o guard de db/tramitacao-executiva.clj (que lanca
  ;; SEM :tipo; sem este guard aqui a excecao cairia no fallback 500 em vez de 400 fail-closed).
  (let [a (ator)]
    (is (thrown-with-msg? Exception #"veto-tipo"
          (adapters/registrar-resposta->dominio a {"lock-version" 0 "resultado" "vetado"})))))

;; ---------- apreciar-veto->dominio ----------

(deftest apreciar-veto->dominio-basico
  (let [a (ator) vid (random-uuid)
        m (adapters/apreciar-veto->dominio a
            {"lock-version" 1 "resultado" "veto_derrubado" "veto-votacao-id" (str vid)})]
    (is (= 1 (:lock-version m)))
    (is (= "veto_derrubado" (:resultado m)))
    (is (= vid (:veto-votacao-id m)))
    (is (= (:identidade-id a) (:updated-by m)))
    (is (not (contains? m :id)) "id NAO e' resolvido aqui — e' o path direto (tramitacao-executiva-id)")))

(deftest apreciar-veto->dominio-sem-veto-votacao-id-invalido
  (let [a (ator)]
    (is (thrown-with-msg? Exception #"invalido"
          (adapters/apreciar-veto->dominio a {"lock-version" 1 "resultado" "veto_mantido"})))))

(deftest apreciar-veto->dominio-resultado-fora-do-vocabulario-invalido
  (let [a (ator) vid (random-uuid)]
    (is (thrown? Exception
          (adapters/apreciar-veto->dominio a
            {"lock-version" 1 "resultado" "vetado" "veto-votacao-id" (str vid)})))))

(deftest apreciar-veto->dominio-veto-votacao-id-nao-uuid-invalido
  (let [a (ator)]
    (is (thrown? Exception
          (adapters/apreciar-veto->dominio a
            {"lock-version" 1 "resultado" "veto_mantido" "veto-votacao-id" "nao-e-uuid"})))))
