(ns oplenario.sessoes.assiduidade-test
  "UNIT (sem Postgres) — Etapa 6 fatia 2: a subquery em LOTE (`ultimos-eventos-por-sessao-e-vereador-q`), o
  teto do periodo (`validar-periodo-assiduidade!`) e o coracao PURO da apuracao (`apurar-assiduidade`).

  A FIXTURE E' O TESTE (mesma disciplina da revisao da Fatia 1): cada `deftest` abaixo exercita um ramo que
  uma fixture pobre deixaria verde por acidente — vereador empossado no meio do periodo, licenciado, presenca
  sem assento, sessao nao_realizada, sessao secreta, e as 4 classificacoes de falta separadas."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant LocalDate)))

;; ---------- a subquery em LOTE — estrutura (o dado real e' pinado na integracao) ----------

(deftest ultimos-eventos-por-sessao-e-vereador-q-reusa-a-ordem-canonica
  (let [s1 (random-uuid) s2 (random-uuid)
        i1 (Instant/parse "2026-06-20T10:00:00Z") i2 (Instant/parse "2026-06-21T10:00:00Z")
        ente (random-uuid)
        sem (logic/ultimos-eventos-por-sessao-e-vereador-q {:sessoes-e-instantes [[s1 i1] [s2 i2]]})
        com (logic/ultimos-eventos-por-sessao-e-vereador-q {:sessoes-e-instantes [[s1 i1] [s2 i2]] :ente-id ente})]
    (testing "DISTINCT ON particiona por (sessao, vereador) — os DOIS antepostos a ordem-ultimo-evento LITERAL"
      (is (= [[:presenca_evento.sessao_id :vereador_id] :presenca_evento.sessao_id :vereador_id :tipo :modalidade]
             (:select-distinct-on sem))))
    (testing "order-by = os DOIS particionadores + ordem-ultimo-evento, NAO redigitada"
      (is (= (into [[:presenca_evento.sessao_id :asc] [:vereador_id :asc]] logic/ordem-ultimo-evento)
             (:order-by sem)))
      (is (= (:order-by sem) (:order-by com)) "ente-id NUNCA muda a ordem — so' aperta o WHERE"))
    (testing "ente-id opcional so' aperta o WHERE (defense-in-depth sob a mesma RLS)"
      (is (= [:and] (:where sem)))
      (is (= [:and [:= :presenca_evento.ente_id ente]] (:where com))))
    (testing "projecao explicita substitui o default (mesmo contrato do singular)"
      (is (= [[:presenca_evento.sessao_id :vereador_id] :presenca_evento.sessao_id :vereador_id :fonte]
             (:select-distinct-on
              (logic/ultimos-eventos-por-sessao-e-vereador-q
               {:sessoes-e-instantes [[s1 i1]] :projecao [:presenca_evento.sessao_id :vereador_id :fonte]})))))))

;; ---------- o teto do periodo ----------

(deftest validar-periodo-assiduidade-rejeita-de-posterior-a-ate
  (let [hoje (LocalDate/of 2026 6 20)
        erro (try (logic/validar-periodo-assiduidade! hoje (.minusDays hoje 1)) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro))
    (is (= :validacao/invalido (:tipo (ex-data erro))))))

(deftest validar-periodo-assiduidade-366-dias-passa-367-estoura
  (let [ate (LocalDate/of 2026 6 20)]
    (is (nil? (logic/validar-periodo-assiduidade! (.minusDays ate 365) ate))
        "366 dias (365+1, inclusivo) = o periodo maximo, PASSA")
    (let [de (.minusDays ate 366)
          erro (try (logic/validar-periodo-assiduidade! de ate) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? erro) "367 dias estoura")
      (is (= :limite/periodo-excedido (:tipo (ex-data erro))))
      (is (= 367 (:medido (ex-data erro))))
      (is (= 366 (:teto (ex-data erro)))))))

;; ---------- fixtures puros de `apurar-assiduidade` ----------

(defn- sessao-fechada
  [{:keys [id numero tipo estado data-de-referencia transmite-publica]
    :or {numero 1 tipo "ordinaria" estado "encerrada" transmite-publica true}}]
  {:id id :numero numero :tipo tipo :estado estado :data-de-referencia data-de-referencia
   :transmite-publica transmite-publica})

(defn- roster-linha
  [vid nome & [{:keys [nome-parlamentar partido estado-mandato cargo-mesa]
                :or {partido "PX" estado-mandato "vigente"}}]]
  {:vereador-id vid :nome nome :nome-parlamentar nome-parlamentar :partido partido
   :estado-mandato estado-mandato :cargo-mesa cargo-mesa})

(defn- presenca [vid tipo modalidade] {:vereador-id vid :tipo tipo :modalidade modalidade})
(defn- justificativa [vid estado] {:vereador-id vid :estado estado})

(defn- por-vereador-map [resultado] (into {} (map (juxt :vereador-id identity)) (:por-vereador resultado)))
(defn- detalhe-por-vereador [resultado sid]
  (into {} (comp (filter #(= sid (:sessao-id %))) (map (juxt :vereador-id :estado))) (:detalhe resultado)))

;; ---------- PUROS obrigatorios do brief ----------

(deftest as-quatro-classificacoes-saem-separadas-e-pendente-nunca-vira-ausente
  (let [s1 (random-uuid) ana (random-uuid) bruno (random-uuid) carla (random-uuid) dede (random-uuid)
        d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        roster {d [(roster-linha ana "Ana") (roster-linha bruno "Bruno") (roster-linha carla "Carla")
                   (roster-linha dede "Dede")]}
        presencas {s1 [(presenca ana "entrada" "plenario")]}
        justs {s1 [(justificativa bruno "aprovada") (justificativa carla "pendente")]}
        r (logic/apurar-assiduidade sessoes roster presencas justs)
        por-ver (por-vereador-map r)
        detalhe (detalhe-por-vereador r s1)]
    (is (= :presente-plenario (detalhe ana)))
    (is (= :ausente-justificado (detalhe bruno)))
    (is (= :ausente-justificativa-pendente (detalhe carla)))
    (is (= :ausente (detalhe dede)))
    (is (= 1 (:comparecimentos (por-ver ana))))
    (is (= 1 (:ausencias-justificadas (por-ver bruno))))
    (is (= 1 (:ausencias-com-justificativa-pendente (por-ver carla))))
    (is (= 0 (:ausencias-injustificadas (por-ver carla)))
        "I4: pendente NUNCA conta como injustificada — a Mesa ainda nao decidiu")
    (is (= 1 (:ausencias-injustificadas (por-ver dede))))))

(deftest licenciado-fica-fora-do-denominador-e-aparece-em-sessoes-licenciado
  (let [s1 (random-uuid) v (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        roster {d [(roster-linha v "Vera" {:estado-mandato "licenciado"})]}
        r (logic/apurar-assiduidade sessoes roster {} {})
        agg (first (:por-vereador r))]
    (is (= 0 (:sessoes-computadas agg)))
    (is (= 1 (:sessoes-licenciado agg)))
    (is (nil? (:percentual agg)))))

(deftest denominador-zero-nunca-produz-zero-por-cento-e-sem-assento-conta-so-no-comparecimento
  (let [s1 (random-uuid) orfao (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        presencas {s1 [(presenca orfao "entrada" "plenario")]}
        r (logic/apurar-assiduidade sessoes {} presencas {})
        agg (first (:por-vereador r))
        detalhe (first (:detalhe r))]
    (is (= 0 (:sessoes-computadas agg)) "sem roster nenhum, a linha e' SEM ASSENTO — nao cria cadeira")
    (is (= 1 (:comparecimentos agg)) "mas CONTA no comparecimento — o fato observado")
    (is (nil? (:percentual agg)) "denominador zero -> percentual NIL, nunca 0")
    (is (= :presente-plenario (:estado detalhe)))))

(deftest vereador-empossado-no-meio-do-periodo-tem-denominador-menor-que-a-casa
  (let [s1 (random-uuid) s2 (random-uuid) ana (random-uuid) fabio (random-uuid)
        d1 (LocalDate/of 2026 5 1) d2 (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha ana "Ana")]
                d2 [(roster-linha ana "Ana") (roster-linha fabio "Fabio")]}
        r (logic/apurar-assiduidade sessoes roster {} {})
        por-ver (por-vereador-map r)]
    (is (= 2 (:sessoes-computadas (por-ver ana))) "Ana compunha a Casa nas duas sessoes")
    (is (= 1 (:sessoes-computadas (por-ver fabio)))
        "Fabio (o suplente convocado no meio do periodo) so' compunha a Casa na segunda")
    (is (< (:sessoes-computadas (por-ver fabio)) (:sessoes-computadas (por-ver ana))))))

(deftest sessao-nao-realizada-conta-como-convocada
  (let [s1 (random-uuid) presente (random-uuid) ausente (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d :estado "nao_realizada"})]
        roster {d [(roster-linha presente "Presente") (roster-linha ausente "Ausente")]}
        presencas {s1 [(presenca presente "entrada" "plenario")]}
        r (logic/apurar-assiduidade sessoes roster presencas {})
        por-ver (por-vereador-map r)]
    (is (= 1 (:sessoes-computadas (por-ver presente))))
    (is (= 1 (:comparecimentos (por-ver presente))))
    (is (= 1 (:sessoes-computadas (por-ver ausente))))
    (is (= 1 (:ausencias-injustificadas (por-ver ausente))))
    (is (= 1 (:sessoes-consideradas (:totais r))))))

(deftest sessao-secreta-entra-nos-totais-e-sai-marcada-sigilosa
  (let [s1 (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d :tipo "secreta" :transmite-publica false})]
        r (logic/apurar-assiduidade sessoes {} {} {})]
    (is (true? (:sigilosa (first (:sessoes r)))))
    (is (= 1 (:sessoes-sigilosas (:totais r))))
    (is (= 1 (:sessoes-consideradas (:totais r))) "a sessao secreta ENTRA nos totais, nao some em silencio")))

(deftest identidade-aparece-uma-vez-e-detalhe-nao-repete-nome-nem-partido
  (let [s1 (random-uuid) s2 (random-uuid) ana (random-uuid)
        d1 (LocalDate/of 2026 5 1) d2 (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha ana "Ana" {:nome-parlamentar "Aninha" :partido "PDT"})]
                d2 [(roster-linha ana "Ana" {:nome-parlamentar "Aninha" :partido "PDT"})]}
        r (logic/apurar-assiduidade sessoes roster {} {})]
    (is (= 1 (count (:vereadores r))) "identidade UMA VEZ, apesar de aparecer em DUAS sessoes")
    (is (= {:id ana :nome "Ana" :nome-parlamentar "Aninha" :partido "PDT"} (first (:vereadores r))))
    (is (= #{:sessao-id :vereador-id :estado} (set (keys (first (:detalhe r)))))
        "detalhe carrega SO' ids+estado — nome/partido NAO se repetem por linha (LGPD)")
    (is (= 2 (count (:detalhe r))))))
