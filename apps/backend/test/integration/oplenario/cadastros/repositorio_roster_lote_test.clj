(ns oplenario.cadastros.repositorio-roster-lote-test
  "INTEGRACAO (PG real) — `RepoCadastros/roster-da-casa-em-datas`: o LOTE de `roster-da-casa` para VARIAS
  datas de uma vez (Etapa 6 fatia 1 — o insumo da apuracao de assiduidade).

  A razao de esta leitura existir e' evitar o carry N+1 do leitor AGREGADO: apurar assiduidade de um
  periodo com centenas de sessoes NAO PODE reabrir `roster-da-casa` sessao a sessao. O predicado de 'quem
  tem mandato vigente numa data' e' o MESMO do singular (extraido para `mandato-vigente-lateral`/
  `cargo-mesa-lateral`, compartilhados) — e' esse compartilhamento (I3 do brief) que o `t1-lote-e-identico-
  ao-singular-data-a-data` abaixo prova: se os dois caminhos de codigo puderem divergir, a apuracao conta
  presenca sobre uma Casa diferente da que a chamada mostrou."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private hoje (LocalDate/of 2026 7 1))
(def ^:private d-30 (LocalDate/of 2026 6 1))
(def ^:private d-15 (LocalDate/of 2026 6 16))

(defn- casa! [ente]
  (let [leg (random-uuid)]
    (repo/criar-legislatura! *repo* ente
      {:id leg :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador! [ente nome & [nome-parlamentar]]
  (let [id (random-uuid)]
    (repo/criar-vereador! *repo* ente {:id id :ente-id ente :nome nome
                                       :nome-parlamentar nome-parlamentar :identidade-id nil})
    id))

(defn- mandato! [ente leg vereador-id inicio fim & [partido]]
  (let [id (random-uuid)]
    (repo/criar-mandato! *repo* ente {:id id :ente-id ente :vereador-id vereador-id :legislatura-id leg
                                      :estado "vigente" :partido (or partido "PX")
                                      :vigencia-inicio inicio :vigencia-fim fim})
    id))

;; ---------- T1: O TESTE-ANCORA — o lote e' IDENTICO ao singular, data a data ----------

(deftest t1-lote-e-identico-ao-singular-data-a-data
  ;; Sem este teste as duas leituras podem divergir em silencio (uma ganha um filtro que a outra nao
  ;; ganhou) e a apuracao de assiduidade passa a contradizer a tela da chamada — o mesmo risco que o T10
  ;; de `repositorio-roster-test` fecha para `roster-da-casa` vs. `membros-da-casa`.
  (let [ente (random-uuid)
        leg  (casa! ente)
        v1 (vereador! ente "Ana")
        v2 (vereador! ente "Bruno")
        v3 (vereador! ente "Carla")
        v4 (vereador! ente "Dario")   ; vai ficar licenciado
        v5 (vereador! ente "Elza")]   ; mandato termina antes de d-30
    (mandato! ente leg v1 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg v2 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg v3 d-15 nil)                                  ; empossado no MEIO do periodo
    (mandato! ente leg v4 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg v5 (LocalDate/of 2025 1 1) d-15)
    (repo/registrar-licenca! *repo* ente v4
      {:id (random-uuid) :ente-id ente :inicio (LocalDate/of 2026 6 20) :fim nil :motivo "Tratamento de saude"}
      (LocalDate/of 2026 6 20))
    ;; cargo na Mesa: exercita a 2a coluna projetada tambem no lote
    (let [mesa-id (random-uuid)]
      (repo/criar-comissao! *repo* ente {:id mesa-id :ente-id ente :nome "Mesa Diretora" :tipo "mesa"
                                         :legislatura-id leg :vigencia-inicio (LocalDate/of 2025 1 1)})
      (repo/criar-cargo! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                      :vereador-id v1 :cargo "presidente"
                                      :vigencia-inicio (LocalDate/of 2025 1 1)}))
    (let [datas [d-30 d-15 hoje]
          lote (repo/roster-da-casa-em-datas *repo* ente datas)]
      (is (= (set datas) (set (keys lote))) "toda data pedida aparece como chave")
      (doseq [data datas]
        (let [singular (set (repo/roster-da-casa *repo* ente data))
              do-lote  (set (get lote data))]
          (is (= singular do-lote)
              (str "data " data ": o lote tem de devolver o MESMO conjunto (mesmos ids, mesmos campos) "
                   "que o singular chamado data a data")))))))

;; ---------- T2: data sem NENHUM mandato vigente -> chave presente, vetor vazio ----------

(deftest t2-data-sem-mandato-vigente-tem-chave-presente-com-vetor-vazio
  ;; Chave AUSENTE seria lida a jusante como "nao perguntei por essa data"; o contrato e' "perguntei e a
  ;; resposta e' vazia" — a diferenca importa porque o denominador da apuracao soma sobre as CHAVES.
  (let [ente (random-uuid)
        _leg (casa! ente)
        antes-de-existir-a-casa (LocalDate/of 2020 1 1)]
    (let [lote (repo/roster-da-casa-em-datas *repo* ente [antes-de-existir-a-casa])]
      (is (= {antes-de-existir-a-casa []} lote)))))

;; ---------- T3: licenciado aparece, marcado, no lote (mesma revisao do singular) ----------

(deftest t3-licenciado-aparece-marcado-no-lote
  (let [ente (random-uuid)
        leg  (casa! ente)
        v (vereador! ente "Dario Licenciado")]
    (mandato! ente leg v (LocalDate/of 2025 1 1) nil)
    (repo/registrar-licenca! *repo* ente v
      {:id (random-uuid) :ente-id ente :inicio (LocalDate/of 2026 5 1) :fim nil :motivo "Tratamento de saude"}
      (LocalDate/of 2026 5 1))
    (let [lote (repo/roster-da-casa-em-datas *repo* ente [hoje])]
      (is (= "licenciado" (:estado-mandato (first (get lote hoje))))
          "o licenciado aparece no lote MARCADO, nao ausente e nao mascarado de 'vigente'"))))

;; ---------- T4: bordas inclusivas do stint ----------

(deftest t4-mandato-de-d-a-d-mais-10-aparece-nas-bordas-e-so-nelas
  (let [ente (random-uuid)
        leg  (casa! ente)
        v (vereador! ente "Fronteira")
        d      (LocalDate/of 2026 3 1)
        d+5    (.plusDays d 5)
        d+10   (.plusDays d 10)
        d-1    (.minusDays d 1)
        d+11   (.plusDays d 11)]
    (mandato! ente leg v d d+10)
    (let [lote (repo/roster-da-casa-em-datas *repo* ente [d-1 d d+5 d+10 d+11])
          tem? (fn [data] (contains? (set (map :vereador-id (get lote data))) v))]
      (is (not (tem? d-1))   "D-1: vespera do inicio, ainda nao exerce")
      (is (tem? d)           "D: primeiro dia do stint, ja exerce")
      (is (tem? d+5)         "D+5: meio do stint")
      (is (tem? d+10)        "D+10: ultimo dia do stint, ainda exerce (fim inclusivo)")
      (is (not (tem? d+11))  "D+11: um dia depois do fim, ja nao exerce"))))

;; ---------- T5: multi-tenant — RLS isola o lote tambem ----------

(deftest t5-lote-e-isolado-por-tenant
  (let [ente-a (random-uuid)
        ente-b (random-uuid)
        leg-a  (casa! ente-a)
        _leg-b (casa! ente-b)
        v (vereador! ente-a "Vereador da Casa A")]
    (mandato! ente-a leg-a v (LocalDate/of 2025 1 1) nil)
    (let [lote-b (repo/roster-da-casa-em-datas *repo* ente-b [hoje])]
      (is (= {hoje []} lote-b) "a Casa B nao ve' o vereador da Casa A"))))

;; ---------- T6: lista de datas vazia -> {} sem tocar o banco ----------

(deftest t6-lista-de-datas-vazia-devolve-mapa-vazio-sem-query
  (let [ente (random-uuid)
        chamou? (atom false)]
    (with-redefs [jdbc/execute! (fn [& _] (reset! chamou? true) [])]
      (let [lote (repo/roster-da-casa-em-datas *repo* ente [])]
        (is (= {} lote))
        (is (false? @chamou?) "lista vazia e' curto-circuito ANTES de qualquer round-trip ao banco")))))

;; ---------- T7: teto — acima de 400 datas distintas, lanca fail-closed ----------

(deftest t7-acima-do-teto-de-datas-lanca-fail-closed
  (let [ente (random-uuid)
        datas-demais (mapv #(.plusDays (LocalDate/of 2020 1 1) %) (range 401))]
    (is (= 401 (count (distinct datas-demais))) "sanidade: sao 401 datas DISTINTAS, acima do teto de 400")
    (let [erro (try (repo/roster-da-casa-em-datas *repo* ente datas-demais)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? erro) "estourar o teto lanca, nunca trunca em silencio")
      (is (= :limite/datas-excedido (:tipo (ex-data erro))))
      (is (= 401 (:medido (ex-data erro))) "o numero MEDIDO vai no corpo do erro")
      (is (= 400 (:teto (ex-data erro)))))))

;; ---------- T8: UMA query so' — provado por contagem de chamadas ao driver ----------

(deftest t8-o-lote-de-n-datas-e-uma-unica-query
  ;; Prova a garantia de I2 do brief ("nao ha loop de seam singular, ha uma leitura em lote"): sem isto, o
  ;; teste-ancora (T1) fica verde tanto para uma implementacao em UMA query quanto para uma que faz N
  ;; chamadas ao `roster-da-casa` internamente — os DOIS produzem o mesmo RESULTADO, so' este teste separa
  ;; os dois CAMINHOS.
  (let [ente (random-uuid)
        leg  (casa! ente)
        v (vereador! ente "Sozinho")
        chamadas (atom 0)
        original jdbc/execute!]
    (mandato! ente leg v (LocalDate/of 2025 1 1) nil)
    (with-redefs [jdbc/execute! (fn [tx q] (swap! chamadas inc) (original tx q))]
      (let [lote (repo/roster-da-casa-em-datas *repo* ente [d-30 d-15 hoje])]
        (is (= 3 (count lote)))
        (is (= 1 @chamadas) "N datas, UM round-trip ao banco — nao um loop de N chamadas")))))
