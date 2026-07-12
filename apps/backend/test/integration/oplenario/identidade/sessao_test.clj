(ns oplenario.identidade.sessao-test
  "INTEGRACAO (PG real): `identidade.db.sessao` — a sessao OPACA de login (custodia BFF, Onda D Slice 2).
  Prova: criar+resolver round-trip; o banco guarda SO sha256(segredo) (nunca o cru); os DOIS prazos
  (teto absoluto `expira_em` e ociosidade `ocioso_ate`) fecham a sessao; resolver DESLIZA `ocioso_ate`;
  apagar e' idempotente; segredo desconhecido -> nil. Espelha a fixture de `identidade.db-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.identidade.db.sessao :as sessao]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.db-util :as comum]
            [oplenario.migracao :as migracao])
  (:import (java.security MessageDigest)
           (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- sha256
  "Recomputa sha256(s) de forma INDEPENDENTE do helper privado de db/sessao — a prova de que o banco guarda
  o hash correto tem que vir de fora da implementacao, nao reusar o mesmo codigo que ela usa internamente."
  ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn- linha-crua
  "Le a linha da tabela DIRETO (sem passar pelo db/sessao) — usado so' para inspecionar `sessao_hash`
  cru e provar que o segredo em si nunca chega ao banco."
  [segredo]
  (comum/linha->kebab
    (jdbc/execute-one! *ds*
      ["SELECT sessao_hash, identidade_id, ente_id, expira_em, ocioso_ate FROM identidade.sessao
        WHERE sessao_hash = ?" (sha256 segredo)])))

(deftest criar-e-resolver
  (let [iid (random-uuid) ente (random-uuid)
        seg (sessao/inserir! *ds* {:identidade-id iid :ente-id ente
                                    :expira-em (.plusSeconds (Instant/now) 3600)
                                    :ocioso-ate (.plusSeconds (Instant/now) 1800)})]
    (is (string? seg) "devolve o segredo cru como string")
    (is (= {:identidade-id iid :ente-id ente} (sessao/resolver! *ds* seg 1800))
        "resolve o segredo cru de volta a (identidade, ente)")))

(deftest hash-nao-guarda-o-cru
  (let [iid (random-uuid) ente (random-uuid)
        seg (sessao/inserir! *ds* {:identidade-id iid :ente-id ente
                                    :expira-em (.plusSeconds (Instant/now) 3600)
                                    :ocioso-ate (.plusSeconds (Instant/now) 1800)})
        crua (linha-crua seg)]
    (is (some? crua) "a linha existe (achavel pelo hash calculado fora da impl)")
    (is (not= (seq (.getBytes seg "UTF-8")) (seq (:sessao-hash crua)))
        "sessao_hash != os bytes do segredo cru")
    (is (= (seq (sha256 seg)) (seq (:sessao-hash crua)))
        "sessao_hash == sha256(segredo), recomputado de fora")))

(deftest expira-por-teto-absoluto
  (let [iid (random-uuid) ente (random-uuid)
        seg (sessao/inserir! *ds* {:identidade-id iid :ente-id ente
                                    :expira-em (.minusSeconds (Instant/now) 1)
                                    :ocioso-ate (.plusSeconds (Instant/now) 1800)})]
    (is (nil? (sessao/resolver! *ds* seg 1800)) "teto absoluto ja passado -> nil, mesmo com ociosidade futura")))

(deftest expira-por-ocioso
  (let [iid (random-uuid) ente (random-uuid)
        seg (sessao/inserir! *ds* {:identidade-id iid :ente-id ente
                                    :expira-em (.plusSeconds (Instant/now) 3600)
                                    :ocioso-ate (.minusSeconds (Instant/now) 1)})]
    (is (nil? (sessao/resolver! *ds* seg 1800)) "janela de ociosidade ja passada -> nil, mesmo com teto futuro")))

(deftest resolver-desliza-ocioso
  (let [iid (random-uuid) ente (random-uuid)
        ocioso-inicial (.plusSeconds (Instant/now) 5)
        seg (sessao/inserir! *ds* {:identidade-id iid :ente-id ente
                                    :expira-em (.plusSeconds (Instant/now) 3600)
                                    :ocioso-ate ocioso-inicial})]
    (is (= {:identidade-id iid :ente-id ente} (sessao/resolver! *ds* seg 3600))
        "resolve com sucesso (ainda dentro da janela curta-mas-futura)")
    (let [ocioso-apos (:ocioso-ate (linha-crua seg))]
      (is (.isAfter ocioso-apos ocioso-inicial)
          "ocioso_ate avancou (deslizou p/ now()+janela, bem alem do valor inicial curto)"))))

(deftest apagar-idempotente
  (let [iid (random-uuid) ente (random-uuid)
        seg (sessao/inserir! *ds* {:identidade-id iid :ente-id ente
                                    :expira-em (.plusSeconds (Instant/now) 3600)
                                    :ocioso-ate (.plusSeconds (Instant/now) 1800)})]
    (is (nil? (sessao/apagar! *ds* seg)) "primeiro apagar")
    (is (nil? (sessao/apagar! *ds* seg)) "segundo apagar (idempotente, nao lanca)")
    (is (nil? (sessao/resolver! *ds* seg 1800)) "apos apagar, resolver nao acha mais")))

(deftest segredo-desconhecido-nil
  (is (nil? (sessao/resolver! *ds* "segredo-que-nunca-existiu" 1800)) "hash desconhecido -> nil"))
