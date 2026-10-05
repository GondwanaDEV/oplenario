(ns oplenario.legislativo.regra-votacao-emenda-lom-test
  "INTEGRACAO (PG real, Repo real, sem HTTP): a emenda a Lei Organica so' abre votacao com 2/3 dos membros (CF art. 29,
  regra-dado `emenda_lom` da mig 20261005000250), conferida pelo MESMO caminho das contas do Prefeito
  (`db/regra_votacao/conferir!`, na tx do INSERT). Outra especie segue com o quorum que a Mesa escolher. O 422 da borda
  sai do mesmo `:conflito/regra-de-votacao` que as contas ja' provam em `contas_http_test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]))

(def ^:dynamic *repo* nil)
(def ^:dynamic *ds* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos {}))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus)) *ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- protocolar! [ente tipo]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026 :uf "CE"
                                      :municipio-nome "Baturité" :ementa "Altera a Lei Orgânica" :texto "Art. 1º"})))

(defn- abrir! [ente objeto-id quorum modalidade]
  (repo/abrir-votacao! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id objeto-id
                                    :modalidade modalidade :quorum-tipo quorum :registro *registro*}))

(defn- recusa [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e {:dado (ex-data e) :msg (ex-message e)})))

(deftest a-regra-da-emenda-e-dado
  (let [r (jdbc/execute-one! *ds* ["SELECT guarda, referencia, turnos, intersticio_dias FROM legislativo.regra_votacao_materia WHERE chave = 'emenda_lom'"])]
    (is (= "votacao.quorum_tipo == \"maioria_qualificada_2_3\"" (:regra_votacao_materia/guarda r)))
    (is (= "CF art. 29" (:regra_votacao_materia/referencia r)))
    (is (= 2 (:regra_votacao_materia/turnos r)))
    (is (= 10 (:regra_votacao_materia/intersticio_dias r))))
  (testing "a regra das contas fica com 1 turno e sem intersticio (o que ela ja' significava)"
    (let [r (jdbc/execute-one! *ds* ["SELECT turnos, intersticio_dias FROM legislativo.regra_votacao_materia WHERE chave = 'contas_prefeito'"])]
      (is (= 1 (:regra_votacao_materia/turnos r)))
      (is (nil? (:regra_votacao_materia/intersticio_dias r))))))

(deftest pelom-so-abre-com-dois-tercos
  (let [ente (random-uuid)
        pelom (protocolar! ente "proposta_emenda_lom")]
    (doseq [q ["maioria_simples" "maioria_absoluta" "maioria_qualificada_3_5"]]
      (testing (str q " -> recusa com a regra em palavras")
        (let [{:keys [dado msg]} (recusa #(abrir! ente pelom q "nominal"))]
          (is (= :conflito/regra-de-votacao (:tipo dado)))
          (is (= "emenda_lom" (:regra dado)))
          (is (= "CF art. 29" (:referencia dado)))
          (is (str/includes? (str msg) "2/3 dos membros da Câmara")))))
    (testing "nenhuma votacao ficou gravada pelas recusas"
      (is (zero? (:n (jdbc/execute-one! *ds* ["SELECT count(*) AS n FROM legislativo.votacoes WHERE ente_id = ? AND objeto_id = ?" ente pelom]
                                        {:builder-fn next.jdbc.result-set/as-unqualified-maps})))))
    (testing "2/3 abre, em qualquer modalidade (nominal nao e' exigencia da CF)"
      (is (some? (abrir! ente pelom "maioria_qualificada_2_3" "simbolica"))))))

(deftest outra-especie-segue-com-o-quorum-da-mesa
  (let [ente (random-uuid)
        pl (protocolar! ente "projeto_lei")]
    (is (some? (abrir! ente pl "maioria_simples" "simbolica")))))

(deftest pelom-com-7-de-16-nao-e-aprovada
  (testing "o caso da demo: 7 votos sim sobre 16 membros nao chega a 2/3 (precisa de 11)"
    (let [ente (random-uuid)
          pelom (protocolar! ente "proposta_emenda_lom")
          vid (random-uuid)]
      (repo/abrir-votacao! *repo* ente {:id vid :objeto-tipo "proposicao" :objeto-id pelom :modalidade "nominal"
                                        :quorum-tipo "maioria_qualificada_2_3" :registro *registro*})
      (doseq [voto (concat (repeat 7 "sim") (repeat 2 "nao"))]
        (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto voto}))
      (let [r (repo/encerrar-votacao! *repo* ente {:id vid :base-membros 16 :updated-by nil :lock-version 0})]
        (is (= "rejeitada" (:resultado r)))))))
