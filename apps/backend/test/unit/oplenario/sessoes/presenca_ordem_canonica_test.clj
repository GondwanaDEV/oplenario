(ns oplenario.sessoes.presenca-ordem-canonica-test
  "UNIT (sem Postgres) — a ORDEM canonica do 'ultimo evento por vereador' e' UMA so.

  Havia DUAS codificacoes concorrentes da mesma regra de desempate dentro de `sessoes`: a de
  `relacoes/presenca` (o caminho que o MOTOR de votacao alcanca por nome, para o quorum da policy) e a de
  `db/presenca/presentes-na-sessao` (o caminho do agregado publicado no dashboard da Mesa), transcrita a mao.
  As duas conferiam campo a campo, mas nada estrutural obrigava a continuarem conferindo — e divergir ali faz
  a TELA anunciar um quorum e a POLICY usar outro na MESMA sessao.

  Estes casos sao pinos de ESTRUTURA (o companheiro de dado real e' o cruzado
  `ordem-do-ultimo-evento-e-a-mesma-no-motor-e-no-dashboard`, em `presenca_db_test`)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic]))

(def ^:private sid (random-uuid))
(def ^:private ente (random-uuid))
(def ^:private t (java.time.Instant/parse "2026-06-20T10:30:00Z"))

(deftest ordem-de-desempate-e-a-historica
  ;; Literal deliberado: se alguem mexer na ordem, o teste tem de acusar aqui e nao seis meses depois num
  ;; quorum errado. `vereador_id` NAO entra na constante — quem particiona ou fixa o vereador o antepoe.
  (is (= [[:ocorrido_em :desc] [:fonte_precedencia :desc] [:id :desc]] logic/ordem-ultimo-evento))
  (is (= [[:vereador_id :asc] [:ocorrido_em :desc] [:fonte_precedencia :desc] [:id :desc]]
         (:order-by (logic/ultimos-eventos-por-vereador-q {:sessao-id sid :instante t})))
      "DISTINCT ON exige o particionador na frente da ordem canonica"))

(deftest ente-id-muda-o-where-nunca-a-ordem
  (let [sem (logic/ultimos-eventos-por-vereador-q {:sessao-id sid :instante t})
        com (logic/ultimos-eventos-por-vereador-q {:sessao-id sid :instante t :ente-id ente})]
    (testing "os DOIS consumidores reais ordenam identico — e' o ponto inteiro da fonte unica"
      (is (= (:order-by sem) (:order-by com))))
    (testing "o `ente-id` opcional so' aperta o WHERE (defense-in-depth do caminho db/, sob a mesma RLS)"
      (is (= [:and [:= :sessao_id sid] [:<= :ocorrido_em t]] (:where sem)))
      (is (= [:and [:= :ente_id ente] [:= :sessao_id sid] [:<= :ocorrido_em t]] (:where com))))))

(deftest projecao-default-serve-o-quorum-e-a-explicita-serve-o-dashboard
  (is (= [[:vereador_id] :vereador_id :tipo :modalidade]
         (:select-distinct-on (logic/ultimos-eventos-por-vereador-q {:sessao-id sid :instante t})))
      "default = o que os agregadores por modalidade precisam")
  (is (= [[:vereador_id] :vereador_id :tipo :ente_id]
         (:select-distinct-on (logic/ultimos-eventos-por-vereador-q
                               {:sessao-id sid :instante t :ente-id ente :projecao [:vereador_id :tipo :ente_id]})))
      "quem filtra a query externa por uma coluna tem de projeta-la aqui, senao ela nao existe la' fora"))
