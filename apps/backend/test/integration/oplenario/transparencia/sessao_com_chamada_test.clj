(ns oplenario.transparencia.sessao-com-chamada-test
  "INTEGRACAO (PG real) — carry I-5, fatia 5: a companheira INTRA-SCHEMA
  `transparencia.sessao_com_chamada` (mig 0067) e a manutencao dela pelo consumer de `presenca.registrada`.

  O QUE ESTA FATIA PROVA. O consumer passa de UM para DOIS statements na MESMA tx do relay: alem do UPSERT
  de `presenca_parlamentar` (estado atual por sessao+vereador), grava UMA linha por SESSAO com a DATA CIVIL
  do PRIMEIRO evento de presenca dela (`LEAST` no `ON CONFLICT`). Essa tabela e' o DENOMINADOR que a fatia 6
  vai recortar pela janela de exercicio do mandato — aqui ela sobe SEM LEITOR, de proposito, para a fatia
  ficar verde e bisectavel.

  O QUE ESTA FATIA **NAO** PROVA (limite honesto, escrito para nao ser re-descoberto):
  (a) o BACKFILL da migration nao e' exercitado por nenhum destes deftest — o fixture roda `migrar!` ANTES de
      qualquer linha existir, entao o `INSERT..SELECT..GROUP BY` da 0067 sempre encontra a tabela de presenca
      vazia. A verificacao do backfill e' MANUAL e esta documentada no commit da fatia (carry da decisao);
  (b) `resumo-presenca` continua lendo `presenca_parlamentar` e continua com o denominador do ENTE INTEIRO —
      o I-5 SEGUE ABERTO ate' a fatia 6.

  A semeadura e' pelo caminho do consumer (`projetar-evento!`), nunca INSERT direto no read-model, e dentro
  de `com-tenant*` — que faz `SET LOCAL ROLE oplenario_app` (NOBYPASSRLS) + `app.ente_id`, o mesmo regime do
  relay. Ou seja: os GRANTs e a policy da tabela nova estao no caminho critico de TODO deftest daqui."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.components.repositorio :as repo]
            [oplenario.transparencia.suporte-presenca :as sp])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

;; ---------- semeadura ----------

(defn- projetar!
  "Chama o consumer com o payload CRU, sem trava de vocabulario — so' os casos deliberadamente malformados
  usam esta porta (os demais entram por `presenca!`)."
  [ente payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (repo/projetar-evento! tx {:tipo "presenca.registrada" :ente-id ente :payload payload}))))

(defn- presenca!
  "Projeta `presenca.registrada` pelo caminho do consumer. `ocorrido-em` e' string ISO (como chega do jsonb
  do outbox). O payload passa por `suporte-presenca/validar-vocabulario!` — a trava UNICA dos ns de teste de
  presenca — ANTES de tocar o banco."
  ([ente sessao vereador ocorrido-em] (presenca! ente sessao vereador "entrada" ocorrido-em))
  ([ente sessao vereador tipo ocorrido-em]
   (projetar! ente (sp/validar-vocabulario!
                    {:sessao-id (str sessao) :vereador-id (str vereador) :tipo tipo
                     :modalidade "plenario" :fonte "manual_secretaria"
                     :ocorrido-em ocorrido-em}))))

;; ---------- leitura (sempre no regime de tenant: role oplenario_app + RLS) ----------

(defn- companheira
  "Linhas de `sessao_com_chamada` VISIVEIS ao tenant `ente` (role oplenario_app, RLS aplicada)."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (jdbc/execute! tx ["SELECT sessao_id, data FROM transparencia.sessao_com_chamada ORDER BY data, sessao_id"]
                     {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn- data-da-sessao [ente sessao]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:data (jdbc/execute-one! tx ["SELECT data FROM transparencia.sessao_com_chamada WHERE sessao_id = ?" sessao]
                                {:builder-fn rs/as-unqualified-kebab-maps})))))

(defn- contagens
  "{:sessoes-na-presenca N :linhas-na-companheira M} do tenant — o par que o invariante compara."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      {:sessoes-na-presenca
       (:c (jdbc/execute-one! tx ["SELECT count(DISTINCT sessao_id) c FROM transparencia.presenca_parlamentar"]
                              {:builder-fn rs/as-unqualified-kebab-maps}))
       :linhas-na-companheira
       (:c (jdbc/execute-one! tx ["SELECT count(*) c FROM transparencia.sessao_com_chamada"]
                              {:builder-fn rs/as-unqualified-kebab-maps}))})))

;; ---------- 1. a escrita nova existe ----------

(deftest projetar-presenca-cria-a-linha-da-sessao-na-companheira
  (testing "o consumer grava DOIS statements na mesma tx: a presenca e a linha da sessao"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z")
      (let [linhas (companheira ente)]
        (is (= 1 (count linhas)) "uma linha por SESSAO (nao por vereador)")
        (is (= sessao (:sessao-id (first linhas))))
        (is (= (LocalDate/parse "2026-05-18") (:data (first linhas)))
            "a data civil do evento, nao o timestamptz")))))

;; ---------- 2. uma linha por sessao, nao por vereador ----------

(deftest segunda-presenca-na-mesma-sessao-nao-duplica-a-companheira
  (testing "21 vereadores na mesma sessao = UMA linha (e' a reducao de cardinalidade que a fatia 6 gasta)"
    (let [ente (random-uuid) sessao (random-uuid)]
      (doseq [_ (range 5)]
        (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z"))
      (is (= 1 (count (companheira ente)))))))

;; ---------- 3. LEAST: a data e' a do PRIMEIRO evento, em qualquer ordem de projecao ----------

(deftest data-da-sessao-e-a-do-primeiro-evento-mesmo-projetado-fora-de-ordem
  (testing "redrive fora de ordem nao empurra a data da sessao para frente (ON CONFLICT ... LEAST)"
    (let [ente (random-uuid) sessao (random-uuid)]
      ;; o evento MAIS NOVO chega primeiro (ordem invertida — o caso do redrive)
      (presenca! ente sessao (random-uuid) "2026-05-20T17:00:00Z")
      (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao))
          "a data recuou para a do evento mais antigo")))
  (testing "e um evento POSTERIOR nunca sobrescreve a data ja' gravada"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z")
      (presenca! ente sessao (random-uuid) "2026-05-20T17:00:00Z")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao))))))

;; ---------- 4. o fuso e' aplicado UMA vez, em Clojure, na zona civil ----------

(deftest sessao-das-21h30-em-fortaleza-nao-cai-no-dia-seguinte
  (testing "sessao noturna: 00:30Z do dia 19 e' 21:30 do dia 18 em America/Fortaleza (UTC-3)"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-19T00:30:00Z")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao))
          "usar UTC aqui jogaria a sessao um dia adiante e moveria o denominador na borda do mandato"))))

;; ---------- 5. isolamento por tenant (a policy da tabela nova, no role oplenario_app) ----------

(deftest companheira-e-isolada-por-tenant-pela-rls
  (testing "o MESMO sessao_id em dois entes: cada tenant so' enxerga a propria linha"
    (let [a (random-uuid) b (random-uuid) sessao (random-uuid)]
      (presenca! a sessao (random-uuid) "2026-05-18T17:00:00Z")
      (presenca! b sessao (random-uuid) "2026-06-01T17:00:00Z")
      (let [la (companheira a) lb (companheira b)]
        (is (= 1 (count la)) "ente A ve so' a linha de A")
        (is (= 1 (count lb)) "ente B ve so' a linha de B")
        (is (= (LocalDate/parse "2026-05-18") (:data (first la))))
        (is (= (LocalDate/parse "2026-06-01") (:data (first lb))))))))

;; ---------- 6. tolerancia: o relay e' COMPARTILHADO ----------

(deftest consumer-continua-tolerante-e-nao-derruba-o-relay-compartilhado
  (testing "payload de presenca sem :ocorrido-em nao lanca — o relay e' unico e um throw trava o bus inteiro"
    (let [ente (random-uuid) sessao (random-uuid)]
      (is (nil? (projetar! ente {:sessao-id (str sessao) :vereador-id (str (random-uuid))
                                 :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"}))
          "sem instante nao ha data civil nem estado honesto a gravar: loga e tolera")
      (is (= {:sessoes-na-presenca 0 :linhas-na-companheira 0} (contagens ente))
          "e NENHUM dos dois statements foi executado (nao ha meia-projecao)")))
  (testing "o mesmo vale para um :ocorrido-em malformado"
    (let [ente (random-uuid)]
      (is (nil? (projetar! ente {:sessao-id (str (random-uuid)) :vereador-id (str (random-uuid))
                                 :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                 :ocorrido-em "ontem a' noite"})))
      (is (= {:sessoes-na-presenca 0 :linhas-na-companheira 0} (contagens ente))))))

;; ---------- 7. o invariante que a fatia 6 vai depender ----------

(deftest toda-sessao-em-presenca-parlamentar-tem-linha-na-companheira
  (testing "COUNT(DISTINCT sessao_id) da presenca == COUNT(*) da companheira, no mesmo tenant"
    (let [ente     (random-uuid)
          sessoes  (repeatedly 3 random-uuid)
          vereadores (repeatedly 4 random-uuid)]
      (doseq [[i s] (map-indexed vector sessoes)
              v vereadores]
        (presenca! ente s v (format "2026-05-%02dT17:00:00Z" (+ 10 i))))
      (let [{:keys [sessoes-na-presenca linhas-na-companheira]} (contagens ente)]
        (is (= 3 sessoes-na-presenca))
        (is (= 3 linhas-na-companheira))
        (is (= sessoes-na-presenca linhas-na-companheira)
            "sem esta igualdade o denominador da fatia 6 perderia sessoes em silencio")))))
