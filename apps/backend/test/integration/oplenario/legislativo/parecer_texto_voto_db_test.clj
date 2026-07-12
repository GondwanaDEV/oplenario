(ns oplenario.legislativo.parecer-texto-voto-db-test
  "INTEGRACAO (PG real): eixo F (F3.6b) — texto do parecer (parecer_texto_versao, MESMA estrategia do eixo
  B: versionamento append-only no conteudo, hibrido inline/URI, promocao auditada rascunho->vigente que
  reaponta pareceres.texto_vigente_versao_id) + votos divergentes (parecer_voto_divergente, append-only
  PURO — tabela auxiliar, sem UPDATE/DELETE). NAO-particionadas (cardinalidade moderada, como pareceres)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.db.parecer-texto-versao :as ptxt]
            [oplenario.legislativo.db.parecer-voto-divergente :as pvoto]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.models.parecer-texto-versao :as mod-txt]
            [oplenario.legislativo.models.parecer-voto-divergente :as mod-voto]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- novo-parecer! [tx ente]
  (let [tid (random-uuid)]
    ;; chave unica por chamada (a UNIQUE (ente,chave,versao) barra dois templates iguais no mesmo ente)
    (tram/criar-template! tx {:id tid :ente-id ente :chave (str "parecer_ccj_" tid) :versao 1 :sujeito "parecer"
                              :nome "Parecer CCJ [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave "aguardando_designacao"
                            :nome "Aguardando" :terminal false})
    (let [pid (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                         :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"}))]
      (:id (parecer/criar! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id pid
                               :comissao-id (random-uuid) :template-id tid})))))

(defn- nova-versao! [tx ente pcid extra]
  (ptxt/nova-versao! tx (merge {:id (random-uuid) :ente-id ente :parecer-id pcid
                                :origem-versao "redacao" :texto-inline "## Parecer\nVoto do relator: favoravel."} extra)))

;; ---------- texto do parecer (eixo B aplicado ao parecer) ----------

(deftest nova-versao-numera-local-e-conforma
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              v1 (nova-versao! tx ente pcid {})
              v2 (nova-versao! tx ente pcid {:origem-versao "substitutivo" :texto-inline "## Substitutivo"})]
          (is (= 1 (:numero-versao v1)) "primeira versao = 1")
          (is (= 2 (:numero-versao v2)) "segunda versao do MESMO parecer = 2 (ordinal local)")
          (let [r (ptxt/buscar tx ente (:id v1))]
            (is (= "rascunho" (:estado-versao r)) "versao nasce rascunho")
            (is (= pcid (:parecer-id r)))
            (is (m/validate mod-txt/ParecerTextoVersao r) "versao bate o model interno")))))))

(deftest promover-vigente-supersede-e-reaponta-pointer
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              v1 (nova-versao! tx ente pcid {})
              v2 (nova-versao! tx ente pcid {:texto-inline "## v2"})]
          (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v1) :updated-by nil :lock-version 0})
          (is (= (:id v1) (:texto-vigente-versao-id (parecer/buscar tx ente pcid))) "pointer aponta v1")
          (is (= "vigente" (:estado-versao (ptxt/buscar tx ente (:id v1)))))
          ;; promover v2: v1 superada, v2 vigente, pointer reaponta
          (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v2) :updated-by nil :lock-version 0})
          (is (= "superada" (:estado-versao (ptxt/buscar tx ente (:id v1)))) "v1 superada")
          (is (= "vigente" (:estado-versao (ptxt/buscar tx ente (:id v2)))) "v2 vigente")
          (is (= (:id v2) (:texto-vigente-versao-id (parecer/buscar tx ente pcid))) "pointer reaponta v2"))))))

(deftest promover-com-assinatura-grava-os-4-campos
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              v1   (nova-versao! tx ente pcid {})]
          (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v1) :updated-by nil :lock-version 0
                              :assinatura-algoritmo "STUB-ICP-v0" :assinatura-b64 "YWJj"
                              :assinado-por (random-uuid)})
          (let [r (ptxt/buscar tx ente (:id v1))]
            (is (= "STUB-ICP-v0" (:assinatura-algoritmo r)))
            (is (= "YWJj" (:assinatura-b64 r)))
            (is (some? (:assinado-por r)))
            (is (some? (:assinado-em r)) "assinado-em preenchido pelo now() do banco")))))))

(deftest promover-sem-assinatura-nao-grava-nada
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              v1   (nova-versao! tx ente pcid {})]
          (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v1) :updated-by nil :lock-version 0})
          (let [r (ptxt/buscar tx ente (:id v1))]
            (is (nil? (:assinatura-algoritmo r)))
            (is (nil? (:assinado-em r)))))))))

(deftest promover-rejeita-versao-de-outro-parecer
  ;; review F3.6b clojure-MENOR (regressão do eixo B MAJOR-1): o parecer_id no WHERE do CAS impede
  ;; promover uma versao de OUTRO parecer via parecer-id diferente.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pc-a (novo-parecer! tx ente)
              pc-b (novo-parecer! tx ente)
              v-a  (nova-versao! tx ente pc-a {})]
          (is (thrown? Exception
                       (ptxt/promover! tx {:ente-id ente :parecer-id pc-b
                                           :versao-id (:id v-a) :updated-by nil :lock-version 0}))
              "versao de pc-a nao se promove via parecer-id de pc-b"))))))

(deftest promover-em-parecer-terminal-falha
  ;; review F3.6b clojure-MAJOR: parecer terminal -> texto congelado; promover! lanca erro inspecionavel
  ;; (ex-info com :estado) ANTES de bater no trigger opaco.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; template com transicao direta p/ um terminal ('aprovado'), p/ levar o parecer ao terminal.
        (let [tid (random-uuid)
              pid (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                             :uf "CE" :municipio-nome "Fortaleza" :ementa "X"}))]
          (tram/criar-template! tx {:id tid :ente-id ente :chave "p2" :versao 1 :sujeito "parecer"
                                    :nome "P [FIXTURE]" :estado-inicial "aguardando_designacao"})
          (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave "aguardando_designacao" :nome "A" :terminal false})
          (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave "aprovado" :nome "Ap" :terminal true})
          (let [pcid (:id (parecer/criar! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id pid
                                              :comissao-id (random-uuid) :template-id tid}))
                v   (nova-versao! tx ente pcid {})]
            ;; leva direto a 'aprovado' (terminal) por UPDATE de estado
            (parecer/mudar-estado! tx {:id pcid :ente-id ente :estado "aprovado" :updated-by nil :lock-version 0})
            (is (thrown-with-msg? Exception #"terminal"
                                  (ptxt/promover! tx {:ente-id ente :parecer-id pcid :versao-id (:id v)
                                                      :updated-by nil :lock-version 0}))
                "promover texto de parecer terminal e' barrado com erro inspecionavel")))))))

(deftest conteudo-append-only
  (let [ente (random-uuid) vid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)]
          (reset! vid (:id (nova-versao! tx ente pcid {}))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.parecer_texto_versao SET texto_inline = 'hack' WHERE id = ?" @vid]))))
        "o CONTEUDO da versao e' append-only (so estado_versao muda)")))

(deftest xor-e-limite-inline
  (let [ente (random-uuid) ctx (atom nil)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (reset! ctx {:pcid (novo-parecer! tx ente)})))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (nova-versao! tx ente (:pcid @ctx) {:texto-inline "a" :conteudo-uri "s3://x"}))))
        "inline E uri juntos viola o XOR")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (nova-versao! tx ente (:pcid @ctx) {:texto-inline (apply str (repeat 32769 "x"))}))))
        "inline acima de 32KB e' barrado pelo CHECK")))

;; ---------- votos divergentes (tabela auxiliar append-only puro) ----------

(deftest voto-divergente-registra-lista-e-conforma
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)
              d1 (pvoto/registrar! tx {:id (random-uuid) :ente-id ente :parecer-id pcid
                                       :vereador-id (random-uuid) :voto "contrario"
                                       :justificativa "Inconstitucionalidade do art. 2o"})
              _  (pvoto/registrar! tx {:id (random-uuid) :ente-id ente :parecer-id pcid
                                       :vereador-id (random-uuid) :voto "favoravel_com_emendas"})
              ds (pvoto/listar-por-parecer tx ente pcid)]
          (is (= 2 (count ds)) "dois votos divergentes registrados")
          (is (m/validate mod-voto/ParecerVotoDivergente (pvoto/buscar tx ente (:id d1))) "voto bate o model"))))))

(deftest voto-divergente-append-only-puro
  (let [ente (random-uuid) did (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pcid (novo-parecer! tx ente)]
          (reset! did (:id (pvoto/registrar! tx {:id (random-uuid) :ente-id ente :parecer-id pcid
                                                 :vereador-id (random-uuid) :voto "contrario"}))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.parecer_voto_divergente SET voto = 'hack' WHERE id = ?" @did]))))
        "voto divergente e' append-only puro (sem UPDATE)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["DELETE FROM legislativo.parecer_voto_divergente WHERE id = ?" @did]))))
        "voto divergente e' append-only puro (sem DELETE)")))
