(ns oplenario.legislativo.documento-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis) no EXPEDIENTE (Onda B Slice 6) —
  `protocolar-documento!` compoe protocolo-geral/protocolar! (numera gapless) + documento/emitir! (rascunho
  -> emitido, vinculado ao protocolo) NUMA UNICA tx (mesmo racional de emitir-parecer!/protocolar!). Tambem
  cobre `buscar-modelo` (ja' existente no protocol, F3.9b) e `buscar-documento`/`gerar-documento!` pela
  borda do Repo (nao so' do db/ direto, como documento-db-test ja' cobre)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- criar-modelo! [ente]
  (:id (repo/criar-modelo! *repo* ente
         {:id (random-uuid) :chave (str "oficio_" (random-uuid)) :nome "Oficio [FIXTURE]"
          :tipo-documento "oficio" :corpo-template "Ao {{destinatario}}."})))

(defn- gerar-documento! [ente mid]
  (:id (repo/gerar-documento! *repo* ente
         {:id (random-uuid) :modelo-id mid :tipo-documento "oficio" :assunto "Convite [FIXTURE]"
          :corpo-template "Ao {{destinatario}}." :dados {"destinatario" "Prefeito"}
          :created-by (random-uuid)})))

;; ========================= buscar-modelo (ja' existente no protocol, F3.9b) =========================

(deftest buscar-modelo-basico
  (let [ente (random-uuid) mid (criar-modelo! ente)
        m (repo/buscar-modelo *repo* ente mid)]
    (is (some? m))
    (is (= mid (:id m)))
    (is (= "oficio" (:tipo-documento m)))))

(deftest buscar-modelo-inexistente-e-nil
  (is (nil? (repo/buscar-modelo *repo* (random-uuid) (random-uuid)))))

;; ========================= protocolar-documento! — o CTA composto =========================

(deftest protocolar-documento-numera-e-emite-atomico
  (let [ente (random-uuid) mid (criar-modelo! ente) did (gerar-documento! ente mid)
        ator-id (random-uuid)
        antes (repo/buscar-documento *repo* ente did)
        r (repo/protocolar-documento! *repo* ente
            {:documento-id did :ano 2026 :ator-id ator-id :lock-version (:lock-version antes)})]
    (is (= did (:documento-id r)))
    (is (= 1 (:protocolo-numero r)) "primeiro protocolo do ano 2026 neste ente")
    (is (= 2026 (:protocolo-ano r)))
    (is (some? (:protocolo-id r)))
    (let [depois (repo/buscar-documento *repo* ente did)]
      (is (= "emitido" (:estado depois)) "emitiu (congela o conteudo)")
      (is (= (:protocolo-id r) (:protocolo-geral-id depois)) "vinculado ao protocolo recem-criado")
      (is (some? (:emitido-em depois)))
      (is (= ator-id (:emitido-por depois))))
    (let [protocolo (repo/buscar-protocolo *repo* ente (:protocolo-id r))]
      (is (= "documento" (:objeto-tipo protocolo)))
      (is (= did (:objeto-id protocolo)))
      (is (= "expedido" (:sentido protocolo)))
      (is (= "Convite [FIXTURE]" (:assunto protocolo)) "assunto vem do documento, nao inventado"))))

(deftest protocolar-documento-numera-gapless-entre-documentos
  (let [ente (random-uuid) mid (criar-modelo! ente)
        did-a (gerar-documento! ente mid) did-b (gerar-documento! ente mid)
        a (repo/buscar-documento *repo* ente did-a) b (repo/buscar-documento *repo* ente did-b)
        ra (repo/protocolar-documento! *repo* ente
             {:documento-id did-a :ano 2026 :ator-id (random-uuid) :lock-version (:lock-version a)})
        rb (repo/protocolar-documento! *repo* ente
             {:documento-id did-b :ano 2026 :ator-id (random-uuid) :lock-version (:lock-version b)})]
    (is (= [1 2] [(:protocolo-numero ra) (:protocolo-numero rb)]))))

(deftest protocolar-documento-inexistente-lanca-sem-numerar
  (let [ente (random-uuid) id-fantasma (random-uuid)]
    (is (thrown-with-msg? Exception #"inexistente"
          (repo/protocolar-documento! *repo* ente
            {:documento-id id-fantasma :ano 2026 :ator-id (random-uuid) :lock-version 0})))
    (is (empty? (repo/protocolos-do-objeto *repo* ente "documento" id-fantasma)) "nada foi numerado")))

(deftest protocolar-documento-lock-version-desatualizado-lanca-e-rollback-nao-deixa-buraco
  (let [ente (random-uuid) mid (criar-modelo! ente) did (gerar-documento! ente mid)]
    (is (thrown? Exception
          (repo/protocolar-documento! *repo* ente
            {:documento-id did :ano 2026 :ator-id (random-uuid) :lock-version 999})))
    (is (= "rascunho" (:estado (repo/buscar-documento *repo* ente did))) "nao emitiu (CAS barrou)")
    ;; o proximo protocolo (sucesso) deste ente/ano ainda comeca em 1 — o rollback nao deixou buraco no
    ;; numerador gapless (kernel/sequencial e' transacional).
    (let [outro-did (gerar-documento! ente mid)
          outro (repo/buscar-documento *repo* ente outro-did)
          r (repo/protocolar-documento! *repo* ente
              {:documento-id outro-did :ano 2026 :ator-id (random-uuid) :lock-version (:lock-version outro)})]
      (is (= 1 (:protocolo-numero r)) "gapless: a tentativa falha nao consumiu numero"))))

(deftest protocolar-documento-ja-emitido-lanca-mesmo-guard-de-emitir
  (let [ente (random-uuid) mid (criar-modelo! ente) did (gerar-documento! ente mid)
        antes (repo/buscar-documento *repo* ente did)]
    (repo/protocolar-documento! *repo* ente
      {:documento-id did :ano 2026 :ator-id (random-uuid) :lock-version (:lock-version antes)})
    (let [depois (repo/buscar-documento *repo* ente did)]
      (is (thrown-with-msg? Exception #"rascunho"
            (repo/protocolar-documento! *repo* ente
              {:documento-id did :ano 2026 :ator-id (random-uuid) :lock-version (:lock-version depois)}))))))
