(ns oplenario.transparencia.acervo-test
  "INTEGRACAO (PG real) — F6c Slice 3: NAVEGACAO do acervo de legislacao as-enacted (§16.5). Prova, contra o
  banco sob FORCE RLS (mig 0044), a filtragem de `transparencia.norma` por especie/ano/numero — todos
  OPCIONAIS e combinaveis — via db/norma/listar, a query que finalmente USA o indice idx_norma_tipo_numero
  (aspiracional no Slice 1). Sem filtro: mais recentes por publicado_em (compat Slice 1). As normas sao
  inseridas DIRETO no read-model (o fluxo de projecao ja' e' provado em portal_test; aqui so' importa a
  LEITURA filtrada). Cada teste usa um ente proprio (RLS isola) — mesmo racional de portal_test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.db.norma :as db-norma])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(defn- inserir!
  "Insere uma norma publicada direto no read-model (norma-id/proposicao-id proprios — a UNIQUE por proposicao
  exige uma proposicao distinta por norma). Devolve o norma-id (explicito via `:norma-id` ou aleatorio)."
  [ente {:keys [tipo-norma numero ano publicado-em norma-id]}]
  (let [nid (or norma-id (random-uuid))]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (db-norma/inserir! tx {:ente-id ente :norma-id nid :proposicao-id (random-uuid)
                               :tipo-norma tipo-norma :numero numero :ano ano
                               :urn (str "urn:lex:br;ce;fortaleza:" tipo-norma ":" ano ";" numero)
                               :ementa (str tipo-norma " " numero "/" ano)
                               :publicado-em publicado-em
                               :veiculo-publicacao "Diario Oficial do Municipio"})))
    nid))

(defn- listar [ente filtro]
  (tenancy/com-tenant* *ds* ente (fn [tx] (db-norma/listar tx ente filtro))))

(defn- chaves
  "Reduz cada norma a [tipo-norma ano numero] p/ asserir conjunto+ORDEM sem ruido."
  [normas]
  (mapv (juxt :tipo-norma :ano :numero) normas))

(defn- em! [s] (Instant/parse s))

;; ---------- filtro por especie ----------

(deftest filtra-por-especie-ordena-ano-e-numero-desc
  (let [ente (random-uuid)]
    (inserir! ente {:tipo-norma "lei" :numero 10 :ano 2026 :publicado-em (em! "2026-01-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "lei" :numero 2  :ano 2026 :publicado-em (em! "2026-02-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "lei" :numero 5  :ano 2025 :publicado-em (em! "2025-06-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "resolucao" :numero 3 :ano 2026 :publicado-em (em! "2026-03-01T00:00:00Z")})
    (is (= [["lei" 2026 10] ["lei" 2026 2] ["lei" 2025 5]]
           (chaves (listar ente {:tipo "lei"})))
        "so' as leis, por (ano desc, numero desc) — a ordem estavel do acervo (idx_norma_tipo_numero)")))

;; ---------- filtro por especie + ano ----------

(deftest filtra-por-especie-e-ano
  (let [ente (random-uuid)]
    (inserir! ente {:tipo-norma "lei" :numero 10 :ano 2026 :publicado-em (em! "2026-01-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "lei" :numero 2  :ano 2026 :publicado-em (em! "2026-02-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "lei" :numero 5  :ano 2025 :publicado-em (em! "2025-06-01T00:00:00Z")})
    (is (= [["lei" 2026 10] ["lei" 2026 2]] (chaves (listar ente {:tipo "lei" :ano 2026})))
        "so' as leis de 2026")))

;; ---------- known-item lookup: 'Lei 123/2026' ----------

(deftest known-item-lookup-especie-ano-numero
  (let [ente (random-uuid)]
    (inserir! ente {:tipo-norma "lei" :numero 123 :ano 2026 :publicado-em (em! "2026-01-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "lei" :numero 45  :ano 2026 :publicado-em (em! "2026-02-01T00:00:00Z")})
    (is (= [["lei" 2026 123]] (chaves (listar ente {:tipo "lei" :ano 2026 :numero 123})))
        "Lei 123/2026 — o known-item resolve pela mesma listagem filtrada")
    (is (empty? (listar ente {:tipo "lei" :ano 2026 :numero 999})) "numero inexistente -> vazio")))

;; ---------- sem filtro = compat Slice 1 (mais recentes por publicado_em) ----------

(deftest sem-filtro-mais-recentes-por-publicado
  (let [ente (random-uuid)]
    (inserir! ente {:tipo-norma "lei" :numero 1 :ano 2025 :publicado-em (em! "2025-01-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "resolucao" :numero 9 :ano 2026 :publicado-em (em! "2026-05-01T00:00:00Z")})
    (inserir! ente {:tipo-norma "lei" :numero 7 :ano 2026 :publicado-em (em! "2026-03-01T00:00:00Z")})
    (is (= [["resolucao" 2026 9] ["lei" 2026 7] ["lei" 2025 1]] (chaves (listar ente {})))
        "sem filtro: por publicado_em desc — preserva o contrato do Slice 1")))

;; ---------- especie desconhecida -> vazio (tolerante, nunca erro) ----------

(deftest especie-desconhecida-vazio-tolerante
  (let [ente (random-uuid)]
    (inserir! ente {:tipo-norma "lei" :numero 1 :ano 2026 :publicado-em (em! "2026-01-01T00:00:00Z")})
    (is (empty? (listar ente {:tipo "decreto_legislativo"}))
        "especie valida mas sem normas -> lista vazia (tolerante), nao excecao")))

;; ---------- desempate estavel (review db+clojure MAJOR/MINOR): ordem determinista entre cargas ----------

(def ^:private nid-baixo (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001"))
(def ^:private nid-alto  (java.util.UUID/fromString "ffffffff-ffff-ffff-ffff-ffffffffffff"))

(deftest empate-desempata-por-norma-id-determinista
  ;; sem filtro: publicado_em EMPATADO (lote/mesma data) — sem tiebreaker a ordem entre empatadas nao e'
  ;; garantida entre execucoes (plano/heap), e uma norma podia 'sumir/trocar' na borda do LIMIT. Com o
  ;; desempate por norma_id a ordem e' estavel — dado legal nao pode piscar. UUIDs FIXOS: o Postgres ordena
  ;; UUID como bytes UNSIGNED (ff.. > 00..01), diferente do UUID.compareTo SIGNED do Java — por isso o
  ;; esperado e' cravado na ordem do banco, nao computado por (sort ...) em Clojure.
  (let [ente (random-uuid)
        pub  (em! "2026-01-01T00:00:00Z")]
    (inserir! ente {:tipo-norma "lei" :numero 1 :ano 2026 :publicado-em pub :norma-id nid-baixo})
    (inserir! ente {:tipo-norma "resolucao" :numero 1 :ano 2026 :publicado-em pub :norma-id nid-alto})
    (is (= [nid-alto nid-baixo] (mapv :norma-id (listar ente {})))
        "publicado_em empatado -> desempata por norma_id DESC (bytes unsigned, estavel)")))

;; ---------- RLS: o acervo e' por ente ----------

(deftest rls-isola-o-acervo-por-ente
  (let [ente-a (random-uuid) ente-b (random-uuid)]
    (inserir! ente-a {:tipo-norma "lei" :numero 1 :ano 2026 :publicado-em (em! "2026-01-01T00:00:00Z")})
    (is (empty? (listar ente-b {:tipo "lei"})) "outro ente nao ve o acervo alheio (RLS)")))
