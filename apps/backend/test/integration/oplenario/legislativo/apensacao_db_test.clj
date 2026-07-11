(ns oplenario.legislativo.apensacao-db-test
  "INTEGRACAO (PG real): eixo E — apensacao (§22.4). Prova: associacao COM HISTORICO
  (legislativo.proposicao_apensacao), desapensacao = UPDATE em `desapensada_em` (NAO DELETE — a linha
  persiste como fato historico), unicidade da apensacao ATIVA (uma proposicao apensada a no maximo UM
  principal por vez; re-apensacao apos desapensar e' livre), nao-apensa-a-si-mesma, imutabilidade
  PARCIAL nivel (c) (§22.4.3 disc.4: so `desapensada_em`/motivos correlatos sao mutaveis, uma vez; o
  resto e' fato congelado), e a CADEIA genuina via traversal recursivo (CTE) cycle-safe."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.apensacao :as ap]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.models.apensacao :as mod]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- apensar! [tx ente principal apensada & [extra]]
  (ap/apensar! tx (merge {:id (random-uuid) :ente-id ente :principal-id principal :apensada-id apensada
                          :motivo-apensacao "Mesma materia (conexao)"} extra)))

(deftest apensar-e-buscar-conforma
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) q (protocolar! tx ente)
              {aid :id} (apensar! tx ente p q)
              r (ap/buscar tx ente aid)]
          (is (= p (:principal-id r)) "principal preservado")
          (is (= q (:apensada-id r)) "apensada preservada")
          (is (some? (:apensada-em r)) "apensada_em preenchida (fato)")
          (is (nil? (:desapensada-em r)) "ativa: sem desapensada_em")
          (is (= "Mesma materia (conexao)" (:motivo-apensacao r)) "motivo da apensacao gravado")
          (is (m/validate mod/Apensacao r) "apensacao bate o model interno"))))))

(deftest apensada-ativa-e-unica
  ;; a UNIQUE parcial (ente_id,apensada_id WHERE desapensada_em IS NULL) aborta a tx -> com-tenant*/caso.
  (let [ente (random-uuid) ids (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) p2 (protocolar! tx ente) q (protocolar! tx ente)]
          (reset! ids {:p p :p2 p2 :q q})
          (apensar! tx ente p q))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (apensar! tx ente (:p2 @ids) (:q @ids)))))
        "a MESMA proposicao nao pode estar apensada a dois principais ativos (UNIQUE parcial)")))

(deftest nao-apensa-a-si-mesma
  (let [ente (random-uuid) pid (atom nil)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (reset! pid (protocolar! tx ente))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente (fn [tx] (apensar! tx ente @pid @pid))))
        "principal = apensada e' barrado pelo CHECK")))

(deftest desapensar-e-update-nao-delete-e-permite-reapensar
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) p2 (protocolar! tx ente) q (protocolar! tx ente)
              {aid :id} (apensar! tx ente p q)]
          (ap/desapensar! tx {:id aid :ente-id ente :motivo-desapensacao "Materia distinta"
                              :updated-by nil :lock-version 0})
          (let [r (ap/buscar tx ente aid)]
            (is (some? r) "linha PERSISTE (UPDATE, nao DELETE)")
            (is (some? (:desapensada-em r)) "desapensada_em preenchida")
            (is (= "Materia distinta" (:motivo-desapensacao r)) "motivo da desapensacao gravado"))
          ;; q (ja desapensada) pode ser apensada a OUTRO principal — a linha historica nao bloqueia
          (let [{aid2 :id} (apensar! tx ente p2 q)]
            (is (some? aid2) "re-apensacao a outro principal apos desapensar")
            (is (nil? (:desapensada-em (ap/buscar tx ente aid2))) "nova apensacao nasce ativa")))))))

(deftest desapensada-e-congelada
  (let [ente (random-uuid) aid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) q (protocolar! tx ente)
              {a :id} (apensar! tx ente p q)]
          (reset! aid a)
          (ap/desapensar! tx {:id a :ente-id ente :motivo-desapensacao "X" :updated-by nil :lock-version 0}))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (ap/desapensar! tx {:id @aid :ente-id ente :motivo-desapensacao "de novo"
                                                :updated-by nil :lock-version 1}))))
        "fato ja desapensado e' congelado (imutabilidade nivel c) — nao re-desapensa")))

(deftest campos-fixos-sao-imutaveis
  ;; nivel (c): so desapensada_em/motivos sao mutaveis; mudar principal/apensada (fato) e' barrado pelo
  ;; trigger. Mudanca de principal e' DOIS atos (desapensar + apensar), nunca um UPDATE in-place.
  (let [ente (random-uuid) aid (atom nil) outra (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) q (protocolar! tx ente) r (protocolar! tx ente)
              {a :id} (apensar! tx ente p q)]
          (reset! aid a) (reset! outra r))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx]
                     (jdbc/execute-one! tx
                       ["UPDATE legislativo.proposicao_apensacao SET principal_id = ? WHERE ente_id = ? AND id = ?"
                        @outra ente @aid]))))
        "mudar principal_id (campo fixo) e' barrado pelo trigger nivel (c)")))

(deftest cadeia-recursiva-e-cycle-safe
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) q (protocolar! tx ente) r (protocolar! tx ente)]
          ;; cadeia genuina: p<-q (n1), q<-r (n2)  => cadeia(p) = {q nivel1, r nivel2}
          (apensar! tx ente p q)
          (apensar! tx ente q r)
          (let [cad (ap/cadeia tx ente p)]
            (is (= 2 (count cad)) "cadeia segue 2 niveis")
            (is (= #{q r} (set (map :apensada-id cad))) "q (n1) e r (n2) na cadeia")
            (is (= 1 (:nivel (first (filter #(= q (:apensada-id %)) cad)))) "q no nivel 1")
            (is (= 2 (:nivel (first (filter #(= r (:apensada-id %)) cad)))) "r no nivel 2"))
          ;; ciclo: r<-p fecha p->q->r->p; a UNIQUE ativa permite (apensada p usada uma vez), mas o
          ;; traversal e' cycle-safe (array de caminho visitado) e NAO entra em loop infinito.
          (apensar! tx ente r p)
          (let [cad (ap/cadeia tx ente p)]
            ;; exato: caminho base [p,q] bloqueia o retorno r->p => so q(n1) e r(n2). A assercao frouxa
            ;; (<=3) passaria mesmo com o cycle-guard quebrado (devolveria a 3a linha r->p).
            (is (= 2 (count cad)) "ciclo cycle-safe: q(n1) e r(n2); r->p bloqueado pelo caminho [p,q]")))))))

(deftest efetivado-em-nao-volta-a-null
  ;; review F3.5 DB-M2: efetivado_em e' transicao ONE-WAY. Voltar a NULL esconderia a linha sob a policy
  ;; RLS (soft-delete sem trilha, Inv.10) — o trigger nivel (c) barra timestamp->NULL.
  (let [ente (random-uuid) aid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) q (protocolar! tx ente)
              {a :id} (apensar! tx ente p q)]
          (reset! aid a))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx]
                     (jdbc/execute-one! tx
                       ["UPDATE legislativo.proposicao_apensacao SET efetivado_em = NULL WHERE ente_id = ? AND id = ?"
                        ente @aid]))))
        "efetivado_em nao regride a NULL (anti soft-delete via RLS)")))

(deftest apensadas-ativas-com-limite-traz-as-mais-recentes-nao-as-mais-antigas
  ;; review MAJOR fe-9-ficha-materia (repositorio.clj + db/apensacao.clj): o teto anterior era um `take`
  ;; em memoria sobre o ASC — preservava as apensacoes MAIS ANTIGAS, descartava as MAIS RECENTES. Prova: 60
  ;; apensadas ativas do MESMO principal com `apensada_em` EXPLICITO e distinto (insert direto — `apensar!`
  ;; so' aceita o now() da tx, que empataria as 60 linhas no MESMO instante); com limite=50 a MAIS RECENTE
  ;; (i=59) sobrevive, a MAIS ANTIGA (i=0) e' descartada, ordem cronologica ASC preservada.
  (let [ente (random-uuid) principal (atom nil)
        base (java.time.Instant/parse "2026-01-01T00:00:00Z")
        apensada-em (fn [i] (.plusSeconds base i))]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (reset! principal (protocolar! tx ente))
        (dotimes [i 60]
          (let [ap-id (protocolar! tx ente)]
            (jdbc/execute-one! tx
              ["INSERT INTO legislativo.proposicao_apensacao
                (ente_id, id, principal_id, apensada_id, apensada_em, motivo_apensacao, efetivado_em)
                VALUES (?, ?, ?, ?, ?, ?, now())"
               ente (random-uuid) @principal ap-id (apensada-em i) "materia conexa"])))))
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [todas (ap/apensadas-ativas tx ente @principal)
              limitadas (ap/apensadas-ativas tx ente @principal 50)]
          (is (= 60 (count todas)) "sem limite: todas as apensadas")
          (is (= 50 (count limitadas)) "com limite: o SQL aplica o teto")
          (is (= (apensada-em 59) (:apensada-em (last limitadas))) "a MAIS RECENTE (i=59) sobrevive ao corte")
          (is (not-any? #(= (apensada-em 0) (:apensada-em %)) limitadas)
              "a MAIS ANTIGA (i=0) foi descartada — o corte preserva o recente, nao o antigo")
          (is (= (map apensada-em (range 10 60)) (map :apensada-em limitadas))
              "as 50 mais recentes (i=10..59), em ordem cronologica ASC"))))))

(deftest desapensar-cas
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [p (protocolar! tx ente) q (protocolar! tx ente)
              {a :id} (apensar! tx ente p q)]
          (is (thrown? Exception
                       (ap/desapensar! tx {:id a :ente-id ente :motivo-desapensacao "X"
                                           :updated-by nil :lock-version 7}))
              "lock_version desatualizado e' rejeitado (CAS honesto)"))))))
