(ns oplenario.gatilho-compliance-papel-de-runtime-test
  "INTEGRACAO (PG real), com o role de RUNTIME (`oplenario_pool`, NOBYPASSRLS, nao-dono) — o superuser so' migra e
  semeia. O `gatilho_compliance_db_test` roda como o dono do banco e por isso nunca viu que o gatilho, em producao,
  morria na primeira linha: `permission denied for table template_compliance` (o catalogo do motor e' lido sobre a
  conexao crua do pool, e nenhuma migration tinha dado esse privilegio). Como o gatilho nunca derruba a leitura, a
  falha era muda: o painel respondia 200 e nenhuma obrigacao era avaliada.

  Prova, com a cadeia HTTP inteira de `rotas/montar` sobre o pool de runtime:
    - `disparar!` com origem `sob_demanda` nao lanca e AVALIA (o numero avaliado e' afirmado, nao so' a ausencia de erro);
    - ler o painel materializa as obrigacoes e a ata da audiencia REAVALIA a do quadrimestre (vencida -> cumprida);
    - o privilegio e' o minimo: o runtime le o catalogo e grava versao nova de regra so' fora da sessao da Casa; a
      sessao da Casa (`oplenario_app`) le e nao grava; ninguem altera nem apaga definicao de regra."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.relacoes :as rel-compliance]
            [oplenario.config :as config]
            [oplenario.gatilho-compliance :as gatilho]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.relacoes :as rel-legis]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.motor.components.repositorio :as repo-motor]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-s]
            [oplenario.sessoes.relacoes.audiencia :as rel-aud]
            [oplenario.sessoes.relacoes.presenca :as rel-presenca])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *dono* nil)   ; superuser: migra e semeia
(def ^:dynamic *pool* nil)   ; o role de runtime da aplicacao
(def ^:dynamic *deps* nil)
(def ^:dynamic *svc* nil)
(def ^:dynamic *ses* nil)

;; "hoje" da Casa: 03/10/2026 — o 2o quadrimestre venceu em 30/09
(def agora (Instant/parse "2026-10-03T15:00:00Z"))
(def hoje (LocalDate/parse "2026-10-03"))

(def sec (random-uuid))
(def comissao (random-uuid))
(def roster (vec (repeatedly 9 random-uuid)))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ id] (when (= id sec) {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"secretario"}}))
    (vinculos-de [_ _ _] [{:tipo "servidor"}])
    (nome-por-id [_ _] {:nome "Marina Freire"})))

(defn- fake-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (uf-e-municipio [_ _] {:uf "CE" :municipio-nome "Baturité"})
    (comissoes-vigentes [_ _ _] [{:id comissao :nome "Comissão de Finanças e Orçamento" :tipo "permanente"}])
    (nomes-de-comissoes [_ _ ids] (into {} (keep #(when (= % comissao) [% "Comissão de Finanças e Orçamento"])) ids))
    (membros-da-casa [_ _ _] (count roster))
    (roster-da-casa [_ _ _] (mapv (fn [v] {:vereador-id v :estado-mandato "vigente"}) roster))
    (buscar-ente [_ id] {:id id :nome-oficial "Câmara de Teste"})))

(use-fixtures :once
  (fn [t]
    (let [cfg  (config/carregar)
          dono (component/start (datasource/datasource cfg))
          _    (migracao/migrar! (:ds dono))
          pool (component/start (datasource/datasource (update cfg :db assoc :user "oplenario_pool"
                                                               :password "oplenario_dev_pool")))
          reg  (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes rel-presenca/relacoes
                                                          rel-aud/relacoes rel-legis/relacoes rel-compliance/relacoes)))
          ;; TUDO o que a aplicacao usa em runtime vai sobre o pool: e' o que roda em producao
          leg  (repo-leg/->RepoLegislativoPg pool (outbox/bus))
          ses  (repo-s/->RepoSessoesPg pool (outbox/bus))
          base {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade) :repo-cadastros (fake-cadastros)
                :repo-legislativo leg :repo-sessoes ses
                :repo-compliance (repo-compliance/->RepoCompliancePg pool) :repo-motor (repo-motor/->RepoMotorPg pool)
                :registro-fatos reg :info-ente (constantly {:nome-oficial "Câmara"})
                :relogio (tempo/relogio-fixo agora)}]
      (binding [*dono* (:ds dono)
                *pool* (:ds pool)
                *ses* ses
                *deps* (assoc (select-keys base [:repo-compliance :repo-motor :registro-fatos :repo-legislativo])
                              :hoje (constantly hoje))
                *svc* (-> (http/servico cfg (rotas/montar base) it/globais) ph/create-server ::ph/service-fn)]
        (try (t) (finally (component/stop reg) (component/stop pool) (component/stop dono)))))))

;; ---------- utilitarios ----------

(defn- cab [ente]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str sec)}))
   "Content-Type" "application/json"})

(defn- chamar
  ([ente metodo url] (chamar ente metodo url nil))
  ([ente metodo url corpo]
   (let [r (apply pt/response-for *svc* metodo url :headers (cab ente)
                  (when corpo [:body (json/write-value-as-string corpo)]))]
     {:status (:status r)
      :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))})))

(defn- na-casa [ente f] (tenancy/com-tenant* *dono* ente f))

(defn- contar [ente tabela]
  (na-casa ente #(-> (jdbc/execute-one! % [(str "select count(*) as n from " tabela " where ente_id = ?") ente]) :n long)))

(defn- vincular-desde! [ente dia]
  (na-casa ente #(jdbc/execute-one! % [(str "insert into motor.compliance_regra_tenant "
                                            "(id, ente_id, template_chave, ativa, parametros_tenant, criado_em) "
                                            "values (?, ?, 'audiencia_metas_fiscais', true, '{}'::jsonb, ?::timestamptz)")
                                       (random-uuid) ente (str dia "T12:00:00Z")])))

(defn- metas [ente ano mes]
  (->> (repo-compliance/obrigacoes-do-objeto (:repo-compliance *deps*) ente "competencia"
                                             (gatilho/objeto-da-competencia ente {:ano ano :mes mes}))
       (filter #(= gatilho/chave-metas-fiscais (:template-chave %)))
       first))

(defn- template-de-teste [chave]
  {:id (random-uuid) :chave-template chave :versao 1 :template-pai-id nil :dominio "federal" :chave-dominio nil
   :descricao "regra de teste do papel de runtime" :severidade "aviso" :referencia-normativa "teste"
   :fonte-yaml "template: teste\n" :forma-compilada {} :assinatura-parametros {}
   :registry-versao-ref "teste" :estado-versao "arquivada"})

;; ---------- o gatilho com o papel de producao ----------

(deftest o-papel-de-teste-e-mesmo-o-de-runtime
  (testing "se este teste rodasse como o dono do banco, todo o resto passaria sem provar nada"
    (let [{:keys [quem super]} (jdbc/execute-one! *pool* [(str "select current_user as quem, "
                                                                "(select rolsuper from pg_roles where rolname = current_user) as super")])]
      (is (= "oplenario_pool" quem))
      (is (false? super)))))

(deftest disparar-sob-demanda-nao-lanca-e-avalia
  (let [ente (random-uuid)]
    (vincular-desde! ente "2025-01-01")
    (let [r (gatilho/disparar! *deps* ente {:origem "sob_demanda"})]
      (is (= {:avaliadas 3 :falhas 0} (:metas-fiscais r))
          "os tres quadrimestres terminados foram avaliados; nenhuma avaliacao falhou")
      (is (= ["vencida" "vencida" "vencida"] (mapv #(:estado (metas ente (:ano %) (:mes %)))
                                                   [{:ano 2025 :mes 12} {:ano 2026 :mes 4} {:ano 2026 :mes 8}]))
          "a Casa nao fez audiencia nenhuma: os tres prazos ja' passaram")
      (is (= 3 (contar ente "compliance.prazo_dominio_ativo")))
      (is (= 3 (contar ente "compliance.compliance_avaliacao")) "cada avaliacao deixou a sua prova"))))

(deftest ler-o-painel-avalia-e-a-ata-reavalia
  (let [ente (random-uuid)]
    (vincular-desde! ente "2025-01-01")
    (testing "a leitura do painel (origem sob_demanda) materializa as obrigacoes — vencidas"
      (let [{:keys [status corpo]} (chamar ente :get "/compliance/painel")
            abertas (filter #(= "audiencia_metas_fiscais" (:template-chave %)) (:em-aberto corpo))]
        (is (= 200 status))
        (is (= #{"2026-02-28" "2026-05-31" "2026-09-30"} (set (map :vence-em abertas))))
        (is (every? #(= "vencida" (:estado %)) abertas))))
    (testing "a ata da audiencia de metas fiscais do 2o quadrimestre REAVALIA a obrigacao dele: vencida -> cumprida"
      (let [aval (contar ente "compliance.compliance_avaliacao")
            sid (-> (chamar ente :post "/sessoes"
                            {:sessao-legislativa-id (str (random-uuid)) :tipo-sessao "audiencia_publica"
                             :agendada-para "2026-10-05T13:00:00Z"
                             :audiencia {:comissao-id (str comissao) :tema "Metas fiscais do quadrimestre"
                                         :finalidade "metas_fiscais" :referencia "2026-Q2"}})
                    (get-in [:corpo :id]) parse-uuid)]
        (doseq [[lv para] (map-indexed vector ["aberta" "encerrada"])]
          (repo-s/transicionar-sessao! *ses* ente {:id sid :para para :lock-version lv :updated-by sec}))
        (is (= 201 (:status (chamar ente :post (str "/sessoes/" sid "/ata")
                                    {:texto "Ata da audiência pública de metas fiscais."}))))
        (is (= "cumprida" (:estado (metas ente 2026 8))))
        (is (= "vencida" (:estado (metas ente 2026 4))) "o 1o quadrimestre segue vencido")
        (is (< aval (contar ente "compliance.compliance_avaliacao")) "a reavaliacao deixou prova nova")
        (is (= 2 (get-in (chamar ente :get "/compliance/painel") [:corpo :resumo :vencida])))))))

;; ---------- o privilegio e' o minimo ----------

(deftest o-runtime-le-o-catalogo-do-motor
  (let [m (:repo-motor *deps*)]
    (is (vector? (repo-motor/templates-vigentes m "federal" nil)))
    (is (coll? (repo-motor/feriados m "nacional" nil)) "lido em toda avaliacao (motor/avaliar)")
    (is (nil? (repo-motor/prazo-vigente m "federal" nil "tipo_que_nao_existe" "2026-01")))))

(deftest o-runtime-cataloga-regra-nova-mas-nao-altera-nem-apaga
  (let [chave (str "teste_papel_" (random-uuid))]
    (repo-motor/criar-template! (:repo-motor *deps*) (template-de-teste chave))
    (is (= "arquivada" (:estado-versao (repo-motor/template-por-chave-versao (:repo-motor *deps*) chave 1))))
    (testing "versao de regra e' copia integral: o runtime nao reescreve nem apaga definicao"
      (is (thrown-with-msg? Exception #"permission denied"
                            (jdbc/execute! *pool* ["update motor.template_compliance set estado_versao = 'vigente' where chave_template = ?" chave])))
      (is (thrown-with-msg? Exception #"permission denied"
                            (jdbc/execute! *pool* ["delete from motor.template_compliance where chave_template = ?" chave]))))))

(deftest a-sessao-da-casa-le-mas-nao-grava-regra
  (testing "dentro de com-tenant* (SET LOCAL ROLE oplenario_app) a regra — que vale para TODAS as Casas — nao se grava"
    (let [ente (random-uuid)]
      (is (vector? (tenancy/com-tenant* *pool* ente
                     #(jdbc/execute! % ["select id from motor.template_compliance limit 1"]))))
      (is (thrown-with-msg? Exception #"permission denied"
                            (tenancy/com-tenant* *pool* ente
                              #(jdbc/execute! % [(str "insert into motor.template_compliance (id, chave_template, versao, "
                                                      "dominio, descricao, severidade, referencia_normativa, fonte_yaml, "
                                                      "forma_compilada, assinatura_parametros, registry_versao_ref, estado_versao) "
                                                      "values (?, ?, 1, 'federal', 'x', 'aviso', 'x', 'x', '{}', '{}', 'x', 'vigente')")
                                                 (random-uuid) (str "teste_papel_" (random-uuid))])))))))

(deftest a-versao-do-registry-segue-sem-leitura-em-runtime
  (testing "nada em runtime le `registry_catalogo_versao`: sem uso, sem privilegio"
    (is (thrown-with-msg? Exception #"permission denied"
                          (jdbc/execute! *pool* ["select 1 from motor.registry_catalogo_versao limit 1"])))))
