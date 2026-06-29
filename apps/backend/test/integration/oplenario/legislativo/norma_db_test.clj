(ns oplenario.legislativo.norma-db-test
  "INTEGRACAO (PG real): F3.8b — norma promulgada (§22.4; doc-mestre L247, feature 3.15). Fecha 'da
  proposicao a' publicacao' (§15): de um desfecho PROMULGAVEL da tramitacao executiva -> promulgacao
  (numeracao canonica gapless + URN-de-norma LexML, imutavel) -> publicacao (promulgada -> publicada, UMA
  vez). Imutabilidade parcial nivel (c): o conteudo legal CONGELA na promulgacao; so a publicacao muta."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.autografo :as autografo]
            [oplenario.legislativo.db.norma :as norma]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao-executiva :as exec]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.models.norma :as mod]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente tipo]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

;; leva uma proposicao ate' o desfecho PROMULGAVEL (sancionado) e devolve {:pid :aid}.
(defn- ate-promulgavel! [tx ente tipo]
  (let [pid (protocolar! tx ente tipo)
        {aid :id} (autografo/gerar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :ano 2026
                                        :texto-versao-id (random-uuid)
                                        :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
    (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancionado" :updated-by nil :lock-version 0})
    {:pid pid :aid aid}))

(defn- promulgar! [tx ente {:keys [pid aid]} tipo-norma]
  (norma/promulgar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :autografo-id aid
                        :tipo-norma tipo-norma :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                        :data-promulgacao (LocalDate/of 2026 6 28) :ementa "Dispoe sobre X"
                        :texto-versao-id (random-uuid)}))

;; ---------- logica pura: derivacao de especie + URN-de-norma ----------

(deftest tipo-proposicao-vira-tipo-norma
  (is (= "lei" (logic/tipo-proposicao->tipo-norma "projeto_lei")))
  (is (= "resolucao" (logic/tipo-proposicao->tipo-norma "projeto_resolucao")))
  (is (= "emenda_lom" (logic/tipo-proposicao->tipo-norma "proposta_emenda_lom")))
  ;; indicacao/requerimento/mocao NAO sao ato normativo -> fail-closed
  (is (thrown? Exception (logic/tipo-proposicao->tipo-norma "indicacao")))
  (is (thrown? Exception (logic/tipo-proposicao->tipo-norma "mocao"))))

(deftest urn-de-norma-formato-lexml
  (is (= "urn:lex:br;ce;fortaleza:lei:2026-06-28;42"
         (logic/urn-norma {:uf "CE" :municipio-nome "Fortaleza" :tipo-norma "lei"
                           :data (LocalDate/of 2026 6 28) :numero 42})))
  (is (= "urn:lex:br;ce;fortaleza:lei.complementar:2026-06-28;1"
         (logic/urn-norma {:uf "CE" :municipio-nome "Fortaleza" :tipo-norma "lei_complementar"
                           :data (LocalDate/of 2026 6 28) :numero 1})))
  (is (thrown? Exception (logic/urn-norma {:uf "CE" :municipio-nome "Fortaleza" :tipo-norma "lei"
                                           :data nil :numero 1})) "data obrigatoria"))

;; ---------- promulgacao: numeracao gapless + URN + estado ----------

(deftest promulga-numera-gapless-e-carimba-urn
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [n1 (promulgar! tx ente (ate-promulgavel! tx ente "projeto_lei") "lei")
              n2 (promulgar! tx ente (ate-promulgavel! tx ente "projeto_lei") "lei")]
          (is (= 1 (:numero n1)) "primeira lei do ano = 1")
          (is (= 2 (:numero n2)) "segunda = 2 (gapless por tipo/ano)")
          (is (= "urn:lex:br;ce;fortaleza:lei:2026-06-28;1" (:urn n1)) "URN-de-norma carimbada")
          (let [r (norma/buscar tx ente (:id n1))]
            (is (= "promulgada" (:estado r)) "nasce promulgada")
            (is (some? (:promulgado-em r)))
            (is (m/validate mod/Norma r) "norma bate o model")))))))

(deftest numeracao-por-tipo-e-independente
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; lei e resolucao tem sequencias separadas (escopo 'norma:tipo:ano')
        (let [lei (promulgar! tx ente (ate-promulgavel! tx ente "projeto_lei") "lei")
              res (promulgar! tx ente (ate-promulgavel! tx ente "projeto_resolucao") "resolucao")]
          (is (= 1 (:numero lei)) "Lei nº 1")
          (is (= 1 (:numero res)) "Resolucao nº 1 (sequencia propria, nao 2)"))))))

(deftest uma-norma-por-proposicao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [ctx (ate-promulgavel! tx ente "projeto_lei")]
          (promulgar! tx ente ctx "lei")
          (is (thrown? Exception (promulgar! tx ente ctx "lei"))
              "uma proposicao/autografo gera UMA norma (UNIQUE)"))))))

;; ---------- publicacao: promulgada -> publicada (mutacao parcial unica) ----------

(deftest publica-e-congela
  (let [ente (random-uuid) nid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{id :id} (promulgar! tx ente (ate-promulgavel! tx ente "projeto_lei") "lei")]
          (reset! nid id)
          (norma/publicar! tx {:id id :ente-id ente :veiculo-publicacao "Diario Oficial do Municipio"
                               :updated-by nil :lock-version 0})
          (let [r (norma/buscar tx ente id)]
            (is (= "publicada" (:estado r)))
            (is (some? (:publicado-em r)) "carimba publicado_em")
            (is (= "Diario Oficial do Municipio" (:veiculo-publicacao r)))))))
    ;; publicada e' congelada: nem republica nem muta conteudo (trigger imut_parcial)
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (norma/publicar! tx {:id @nid :ente-id ente :veiculo-publicacao "Outro"
                                                 :updated-by nil :lock-version 1}))))
        "norma publicada nao republica (guard 'promulgada')")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.norma SET numero = 999 WHERE id = ?" @nid]))))
        "conteudo legal congelado (trigger barra UPDATE em campo fixo)")))

(deftest conteudo-legal-imutavel-mesmo-promulgada
  (let [ente (random-uuid) nid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (reset! nid (:id (promulgar! tx ente (ate-promulgavel! tx ente "projeto_lei") "lei")))))
    ;; ainda 'promulgada' (nao publicada): mesmo assim a URN/numero/ementa nao mudam (so a publicacao muta)
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.norma SET urn = 'urn:lex:br;ce;x:lei:2026;9' WHERE id = ?" @nid]))))
        "URN congela na promulgacao (so estado/publicado_em/veiculo mudam)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["DELETE FROM legislativo.norma WHERE id = ?" @nid]))))
        "norma nao se apaga (Inv.10)")))

(deftest publicar-exige-veiculo
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{id :id} (promulgar! tx ente (ate-promulgavel! tx ente "projeto_lei") "lei")]
          (is (thrown? Exception
                       (norma/publicar! tx {:id id :ente-id ente :veiculo-publicacao "  "
                                            :updated-by nil :lock-version 0}))
              "publicar exige veiculo (prova da publicacao)"))))))
