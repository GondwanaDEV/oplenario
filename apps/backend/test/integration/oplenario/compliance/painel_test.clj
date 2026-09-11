(ns oplenario.compliance.painel-test
  "INTEGRACAO (PG real) — F5.5a: o read-model do painel 'a Casa esta em dia com o TCE' (§16.11 /
  paineis-mesa). Reads tenant-wide NOVOS (o db/ ate aqui so tinha leitura por-objeto/por-id): resumo de
  obrigacoes por estado (o placar 11·1·0), obrigacoes em aberto (o que vence), remessas recentes (o
  pipeline). + o metodo de composicao do Repo `painel` (uma tx do tenant). Sob FORCE RLS (mig 0009)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.db.obrigacao :as db-obr]
            [oplenario.compliance.db.remessa :as db-rem]
            [oplenario.config :as config]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(defn- nova-obrig [ente estado vence-em template]
  {:id (random-uuid) :ente-id ente :template-chave template :objeto-tipo "competencia"
   :objeto-id (random-uuid) :vence-em vence-em :prazo-fonte-ref nil :estado estado :cumprida-em nil})

(defn- nova-remessa [ente competencia versao]
  {:id (random-uuid) :ente-id ente :template-chave "remessa_mensal_sim" :sistema "SIM"
   :competencia competencia :versao versao :spec-layout-versao "fixture-sim-v0"
   :registry-versao-ref "registry-v1@2026-06-20" :hash "sha256:abc" :objeto-store-ref "remessas/x.bin"})

;; ---------- resumo-por-estado: COUNT(*) GROUP BY estado, escopado ao tenant ----------

(deftest resumo-conta-por-estado
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 7 31) "t-a"))
        (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 8 31) "t-b"))
        (db-obr/inserir! tx (nova-obrig ente "vencida"  (LocalDate/of 2099 1 31) "t-c"))
        (db-obr/inserir! tx (nova-obrig ente "cumprida" (LocalDate/of 2099 6 30) "t-d"))
        (let [m (into {} (map (juxt :estado :total)) (db-obr/resumo-por-estado tx ente))]
          (is (= 2 (get m "pendente")) "2 pendentes")
          (is (= 1 (get m "vencida"))  "1 vencida")
          (is (= 1 (get m "cumprida")) "1 cumprida")
          (is (nil? (get m "cancelada"))
              "estado sem linha NAO aparece no GROUP BY (o 0-fill mora no logic, nao no SQL)"))))))

;; ---------- listar-em-aberto: pendente+vencida, ordenadas por vencimento (o que vence) ----------

(deftest em-aberto-lista-pendente-e-vencida-por-vencimento
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 8 31) "t-a"))
        (db-obr/inserir! tx (nova-obrig ente "vencida"  (LocalDate/of 2099 1 31) "t-b"))
        (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 7 31) "t-c"))
        (db-obr/inserir! tx (nova-obrig ente "cumprida" (LocalDate/of 2099 6 30) "t-d"))   ; NAO entra
        (let [abertas (db-obr/listar-em-aberto tx ente 50)]
          (is (= 3 (count abertas)) "so as 3 em aberto (cumprida fora)")
          (is (every? #{"pendente" "vencida"} (map :estado abertas)) "so pendente/vencida")
          (is (= [(LocalDate/of 2099 1 31) (LocalDate/of 2099 7 31) (LocalDate/of 2099 8 31)]
                 (map :vence-em abertas))
              "ordenadas por vencimento asc (a mais urgente primeiro)"))))))

(deftest em-aberto-respeita-o-limite
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (dotimes [i 5] (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 1 (inc i)) (str "t-" i))))
        (is (= 2 (count (db-obr/listar-em-aberto tx ente 2))) "o limite (anti unbounded-read) corta o resultado")))))

;; ---------- contar-em-aberto: o TOTAL real, sem o teto — e' o que denuncia o corte silencioso ----------

(deftest contar-em-aberto-nao-e-truncado-pelo-limite-da-lista
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (dotimes [i 5] (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 1 (inc i)) (str "t-" i))))
        (is (= 2 (count (db-obr/listar-em-aberto tx ente 2)))
            "a lista, com teto 2, vem cortada")
        (is (= 5 (db-obr/contar-em-aberto tx ente))
            "o total, sem teto, continua o numero real — e' o que a lista sozinha nao consegue provar")))))

(deftest contar-em-aberto-usa-o-mesmo-predicado-da-lista
  ;; a asserção que mata a DERIVA: lista e total tem de nascer do MESMO filtro de estado. Insere um estado
  ;; de CADA fase (§16.11: pendente/cumprida/vencida/dispensada/cancelada) — se o total um dia passar a
  ;; usar um predicado copiado (em vez do compartilhado), esta asserção reprova no dia em que os dois
  ;; divergirem, nao anos depois.
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (db-obr/inserir! tx (nova-obrig ente "pendente"   (LocalDate/of 2099 1 1) "t-pendente"))
        (db-obr/inserir! tx (nova-obrig ente "vencida"    (LocalDate/of 2099 1 2) "t-vencida"))
        (db-obr/inserir! tx (nova-obrig ente "cumprida"   (LocalDate/of 2099 1 3) "t-cumprida"))
        (db-obr/inserir! tx (nova-obrig ente "dispensada" (LocalDate/of 2099 1 4) "t-dispensada"))
        (db-obr/inserir! tx (nova-obrig ente "cancelada"  (LocalDate/of 2099 1 5) "t-cancelada"))
        (is (= 2 (count (db-obr/listar-em-aberto tx ente 50))) "a lista: so pendente+vencida")
        (is (= 2 (db-obr/contar-em-aberto tx ente))
            "o total: o MESMO conjunto que a lista enxerga — a prova de que e' o mesmo predicado")))))

;; ---------- listar-recentes (remessas): escopo de tenant + limite ----------

(deftest remessas-recentes-escopo-e-limite
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid) outro (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (db-rem/inserir! tx (nova-remessa ente "2099-01" 1))
        (db-rem/inserir! tx (nova-remessa ente "2099-02" 1))
        (db-rem/inserir! tx (nova-remessa ente "2099-03" 1))))
    (tenancy/com-tenant* ds outro
      (fn [tx] (db-rem/inserir! tx (nova-remessa outro "2099-01" 1))))
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (is (= 3 (count (db-rem/listar-recentes tx ente 10))) "todas as 3 do tenant")
        (is (= 2 (count (db-rem/listar-recentes tx ente 2))) "o limite corta")
        (is (every? #(= ente (:ente-id %)) (db-rem/listar-recentes tx ente 10))
            "RLS + WHERE: nenhuma remessa de outro tenant vaza")))))

;; ---------- contar-recentes (remessas): o TOTAL real, sem o teto ----------

(deftest contar-recentes-nao-e-truncado-pelo-limite-da-lista
  (let [ds (:ds (:datasource *sys*)) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (dotimes [i 5] (db-rem/inserir! tx (nova-remessa ente (format "2099-%02d" (inc i)) 1)))
        (is (= 2 (count (db-rem/listar-recentes tx ente 2))) "a lista, com teto 2, vem cortada")
        (is (= 5 (db-rem/contar-recentes tx ente))
            "o total, sem teto — a remessa que NAO aparece na lista ainda e' contada")))))

;; ---------- Repo/painel: compoe os tres reads numa unica tx do tenant ----------

(deftest painel-compoe-os-tres-reads
  (let [{:keys [datasource repo-compliance]} *sys* ds (:ds datasource) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 7 31) "t-a"))
        (db-obr/inserir! tx (nova-obrig ente "vencida"  (LocalDate/of 2099 6 30) "t-b"))
        (db-obr/inserir! tx (nova-obrig ente "cumprida" (LocalDate/of 2099 6 30) "t-c"))
        (db-rem/inserir! tx (nova-remessa ente "2099-07" 1))))
    (let [p (repo-compliance/painel repo-compliance ente {})]
      (is (= {"pendente" 1 "vencida" 1 "cumprida" 1} (into {} (map (juxt :estado :total)) (:resumo p)))
          "o resumo (pares crus; o 0-fill e' do logic na borda)")
      (is (= 2 (count (:em-aberto p))) "2 obrigacoes em aberto (pendente+vencida)")
      (is (= 2 (:em-aberto-total p)) "o total bate com a lista quando nao ha corte")
      (is (= (+ (get (into {} (map (juxt :estado :total)) (:resumo p)) "pendente" 0)
                (get (into {} (map (juxt :estado :total)) (:resumo p)) "vencida" 0))
             (:em-aberto-total p))
          "COERENCIA DE SNAPSHOT: em-aberto-total bate com resumo.pendente+resumo.vencida — os dois reads enxergam o MESMO mundo dentro da tx unica do painel")
      (is (= 1 (count (:remessas-recentes p))) "1 remessa recente")
      (is (= 1 (:remessas-recentes-total p)) "o total de remessas bate com a lista quando nao ha corte"))))

(deftest painel-com-teto-injetado-trunca-lista-mas-total-continua-real
  ;; o teto de producao e' 100/50 (caro demais criar no teste); o Repo ja' aceita `opts` — este teste injeta
  ;; um teto pequeno para provar o comportamento de corte SEM pagar o custo de 101 linhas. O caminho de
  ;; PRODUCAO continua passando `{}` (controllers/painel) e cai no default {:limite-em-aberto 100
  ;; :limite-remessas 50} de `components/repositorio`.
  (let [{:keys [datasource repo-compliance]} *sys* ds (:ds datasource) ente (random-uuid)]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (dotimes [i 5] (db-obr/inserir! tx (nova-obrig ente "pendente" (LocalDate/of 2099 1 (inc i)) (str "t-" i))))
        (dotimes [i 5] (db-rem/inserir! tx (nova-remessa ente (format "2099-%02d" (inc i)) 1)))))
    (let [p (repo-compliance/painel repo-compliance ente {:limite-em-aberto 2 :limite-remessas 2})]
      (is (= 2 (count (:em-aberto p))) "a lista respeita o teto INJETADO")
      (is (= 5 (:em-aberto-total p)) "o total ignora o teto injetado — continua o numero real")
      (is (= 2 (count (:remessas-recentes p))) "idem para remessas")
      (is (= 5 (:remessas-recentes-total p)) "idem para o total de remessas"))))
