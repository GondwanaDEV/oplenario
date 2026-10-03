(ns oplenario.gatilho-compliance-db-test
  "INTEGRACAO (PG real + a cadeia HTTP inteira de `rotas/montar`): ADR-0021 fatia 3 — as duas regras-dado no motor de
  compliance, com gatilho. Sessoes, legislativo, compliance e motor REAIS (o registro de fatos com as relacoes reais dos
  modulos); identidade e cadastros FAKE. Prova:
    - o fato `audiencia_publica_realizada` (sessoes) contra o PG com RLS: so' a audiencia de metas fiscais daquele
      quadrimestre, encerrada E com ata, e so' da propria Casa;
    - ler o painel aciona o gatilho: o catalogo (template vigente + vinculo da Casa) nasce sozinho e as obrigacoes dos
      quadrimestres terminados aparecem — vencidas, porque a Casa nova nao fez audiencia nenhuma; reler nao duplica;
    - a ata publicada da audiencia de metas fiscais cumpre a obrigacao do quadrimestre (cumprimento tardio);
    - registrar a prestacao cria a obrigacao de julgar com o prazo congelado; encerrar a votacao do PDL a cumpre;
    - o gatilho e' idempotente e respeita a Casa que se desligou da regra;
    - a falha do gatilho nao derruba o painel nem o ato."
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

(def ^:dynamic *ds* nil)
(def ^:dynamic *deps* nil)
(def ^:dynamic *svc* nil)
(def ^:dynamic *svc-quebrado* nil)
(def ^:dynamic *ses* nil)

;; "hoje" da Casa: 03/10/2026 (meio-dia em Fortaleza) — o 2o quadrimestre venceu em 30/09
(def agora (Instant/parse "2026-10-03T15:00:00Z"))
(def hoje (LocalDate/parse "2026-10-03"))

(def sec (random-uuid))
(def comissao (random-uuid))
(def roster (vec (repeatedly 9 random-uuid)))       ; 9 membros: 2/3 = 6

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

(defn- motor-quebrado []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-motor/RepoMotor
    (template-vigente [_ _] (throw (ex-info "catalogo do motor fora do ar" {})))))

(defn- servico [deps]
  (-> (http/servico (config/carregar) (rotas/montar deps) it/globais)
      ph/create-server ::ph/service-fn))

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes rel-presenca/relacoes
                                                         rel-aud/relacoes rel-legis/relacoes rel-compliance/relacoes)))]
      (migracao/migrar! (:ds c))
      (let [leg (repo-leg/->RepoLegislativoPg c (outbox/bus))
            ses (repo-s/->RepoSessoesPg c (outbox/bus))
            base {:idp (idp-dev/idp-dev) :repo-identidade (fake-identidade) :repo-cadastros (fake-cadastros)
                  :repo-legislativo leg :repo-sessoes ses
                  :repo-compliance (repo-compliance/->RepoCompliancePg c) :repo-motor (repo-motor/->RepoMotorPg c)
                  :registro-fatos reg :info-ente (constantly {:nome-oficial "Câmara"})
                  :relogio (tempo/relogio-fixo agora)}]
        (binding [*ds* (:ds c)
                  *ses* ses
                  *deps* (assoc (select-keys base [:repo-compliance :repo-motor :registro-fatos :repo-legislativo])
                                :hoje (constantly hoje))
                  *svc* (servico base)
                  *svc-quebrado* (servico (assoc base :repo-motor (motor-quebrado)))]
          (try (t) (finally (component/stop reg) (component/stop c))))))))

;; ---------- utilitarios ----------

(defn- cab [ente]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str sec)}))
   "Content-Type" "application/json"})

(defn- chamar
  ([svc ente metodo url] (chamar svc ente metodo url nil))
  ([svc ente metodo url corpo]
   (let [r (apply pt/response-for svc metodo url :headers (cab ente)
                  (when corpo [:body (json/write-value-as-string corpo)]))]
     {:status (:status r)
      :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))})))

(defn- na-casa [ente f] (tenancy/com-tenant* *ds* ente f))

(defn- contar [ente tabela]
  (na-casa ente #(-> (jdbc/execute-one! % [(str "select count(*) as n from " tabela " where ente_id = ?") ente]) :n long)))

(defn- vincular-desde!
  "A Casa ligada a regra de metas fiscais desde `dia` (o gatilho nao cobra prazo anterior ao vinculo)."
  [ente dia]
  (na-casa ente #(jdbc/execute-one! % [(str "insert into motor.compliance_regra_tenant "
                                            "(id, ente_id, template_chave, ativa, parametros_tenant, criado_em) "
                                            "values (?, ?, 'audiencia_metas_fiscais', true, '{}'::jsonb, ?::timestamptz)")
                                       (random-uuid) ente (str dia "T12:00:00Z")])))

(defn- obrigacao [ente template objeto-tipo objeto-id]
  (->> (repo-compliance/obrigacoes-do-objeto (:repo-compliance *deps*) ente objeto-tipo objeto-id)
       (filter #(= template (:template-chave %)))
       first))

(defn- metas [ente ano mes]
  (obrigacao ente gatilho/chave-metas-fiscais "competencia" (gatilho/objeto-da-competencia ente {:ano ano :mes mes})))

(defn- agendar! [ente tipo bloco agendada-para]
  (let [r (chamar *svc* ente :post "/sessoes" (cond-> {:sessao-legislativa-id (str (random-uuid)) :tipo-sessao tipo
                                                        :agendada-para agendada-para}
                                                 bloco (assoc :audiencia bloco)))]
    (is (= 201 (:status r)) (pr-str r))
    (parse-uuid (get-in r [:corpo :id]))))

(defn- transicionar! [ente sid & paras]
  (doseq [[lv para] (map-indexed vector paras)]
    (repo-s/transicionar-sessao! *ses* ente
                                 {:id sid :para para :lock-version lv :updated-by sec})))

(defn- audiencia-de-metas! [ente referencia]
  (agendar! ente "audiencia_publica" {:comissao-id (str comissao) :tema "Metas fiscais do quadrimestre"
                                      :finalidade "metas_fiscais" :referencia referencia}
            "2026-10-05T13:00:00Z"))

(defn- publicar-ata! [ente sid]
  (chamar *svc* ente :post (str "/sessoes/" sid "/ata") {:texto "Ata da audiência pública de metas fiscais."}))

(defn- realizado? [ente finalidade ano mes]
  (na-casa ente #(rel-aud/audiencia-publica-realizada? % finalidade {:ano ano :mes mes})))

;; ---------- o fato ----------

(deftest fato-audiencia-publica-realizada-contra-o-pg
  (let [ente (random-uuid) outra (random-uuid)
        sid (audiencia-de-metas! ente "2026-Q2")]
    (testing "agendada: nao realizada"
      (is (false? (realizado? ente "metas_fiscais" 2026 8))))
    (transicionar! ente sid "aberta" "encerrada")
    (testing "encerrada SEM ata: ainda nao prova nada"
      (is (false? (realizado? ente "metas_fiscais" 2026 8))))
    (is (= 201 (:status (publicar-ata! ente sid))))
    (testing "encerrada COM ata: realizada — so' no quadrimestre dela, so' com a finalidade dela"
      (is (true? (realizado? ente "metas_fiscais" 2026 8)))
      (is (false? (realizado? ente "metas_fiscais" 2026 4)) "outro quadrimestre")
      (is (false? (realizado? ente "metas_fiscais" 2026 7)) "julho nao fecha quadrimestre")
      (is (false? (realizado? ente "ldo" 2026 8)) "outra finalidade"))
    (testing "RLS: a audiencia de uma Casa nao prova nada para outra"
      (is (false? (realizado? outra "metas_fiscais" 2026 8))))))

(deftest fatos-das-contas-contra-o-pg
  (let [ente (random-uuid)
        p (:corpo (chamar *svc* ente :post "/contas"
                          {:tipo "governo_prefeito" :exercicio 2024 :responsavel "Francisco das Chagas"
                           :recebida-em "2026-09-01" :parecer-previo "favoravel" :comissao-autora-id (str comissao)}))
        pid (parse-uuid (:id p))]
    (is (false? (na-casa ente #(rel-legis/contas-julgadas? % pid))))
    (is (= (LocalDate/parse "2026-10-31") (na-casa ente #(rel-legis/prazo-julgamento-contas % pid))) "60 dias, congelado")
    (is (= (LocalDate/parse "2026-09-01") (na-casa ente #(rel-legis/data-recebimento-contas % pid))))
    (testing "prestacao de outra Casa (ou inexistente): fail-closed, nunca 'nao julgada'"
      (is (thrown? clojure.lang.ExceptionInfo (na-casa (random-uuid) #(rel-legis/contas-julgadas? % pid)))))))

;; ---------- o gatilho: metas fiscais ----------

(deftest casa-nova-nao-nasce-com-vencidas
  (testing "a Casa ligada a regra HOJE nao ve como vencidas as audiencias de antes de usar o sistema"
    (let [ente (random-uuid)
          {:keys [status corpo]} (chamar *svc* ente :get "/compliance/painel")]
      (is (= 200 status))
      (is (empty? (filter #(= "audiencia_metas_fiscais" (:template-chave %)) (:em-aberto corpo))))
      (is (true? (:ativa (repo-motor/binding-do-ente (:repo-motor *deps*) ente "audiencia_metas_fiscais")))
          "o vinculo nasce na primeira leitura; a proxima audiencia (3o quadrimestre, fevereiro) ja' sera' cobrada"))))

(deftest ler-o-painel-aciona-o-gatilho-e-a-ata-cumpre
  (let [ente (random-uuid)]
    (vincular-desde! ente "2025-01-01")
    (testing "a primeira leitura do painel materializa os tres quadrimestres terminados — vencidos"
      (let [{:keys [status corpo]} (chamar *svc* ente :get "/compliance/painel")
            metas-abertas (filter #(= "audiencia_metas_fiscais" (:template-chave %)) (:em-aberto corpo))]
        (is (= 200 status))
        (is (= #{"2026-02-28" "2026-05-31" "2026-09-30"} (set (map :vence-em metas-abertas)))
            "o 3o quadrimestre de 2025 vence no fim de FEVEREIRO (LRF art. 9 §4)")
        (is (every? #(= "vencida" (:estado %)) metas-abertas))
        (is (= 3 (get-in corpo [:resumo :vencida])))))
    (testing "o catalogo nasceu: os dois templates vigentes e o vinculo ativo da Casa"
      (let [m (:repo-motor *deps*)]
        (is (= "federal" (:dominio (repo-motor/template-vigente m "audiencia_metas_fiscais"))))
        (is (= "regimento_tenant" (:dominio (repo-motor/template-vigente m "julgamento_contas_prefeito"))))
        (is (true? (:ativa (repo-motor/binding-do-ente m ente "audiencia_metas_fiscais"))))
        (is (true? (:ativa (repo-motor/binding-do-ente m ente "julgamento_contas_prefeito"))))))
    (testing "reler o painel nao duplica obrigacao nem engorda a prova (a leitura recente nao reavalia)"
      (let [obr (contar ente "compliance.prazo_dominio_ativo")
            aval (contar ente "compliance.compliance_avaliacao")]
        (is (= 200 (:status (chamar *svc* ente :get "/compliance/painel"))))
        (is (= obr (contar ente "compliance.prazo_dominio_ativo")))
        (is (= aval (contar ente "compliance.compliance_avaliacao")))))
    (testing "a ata publicada da audiencia de metas fiscais do 2o quadrimestre cumpre a obrigacao dele (tardia)"
      (let [sid (audiencia-de-metas! ente "2026-Q2")]
        (transicionar! ente sid "aberta" "encerrada")
        (is (= 201 (:status (publicar-ata! ente sid))))
        (is (= "cumprida" (:estado (metas ente 2026 8))))
        (is (= "vencida" (:estado (metas ente 2026 4))) "o 1o quadrimestre segue vencido")
        (is (= 2 (get-in (chamar *svc* ente :get "/compliance/painel") [:corpo :resumo :vencida])))))
    (testing "a ata de uma sessao que nao e' audiencia nao aciona nada"
      (let [aval (contar ente "compliance.compliance_avaliacao")
            sid (agendar! ente "ordinaria" nil "2026-10-05T13:00:00Z")]
        (transicionar! ente sid "aberta" "encerrada")
        (is (= 201 (:status (publicar-ata! ente sid))))
        (is (= aval (contar ente "compliance.compliance_avaliacao")))))))

(deftest gatilho-e-idempotente-e-respeita-a-casa-desligada
  (let [ente (random-uuid)
        _ (vincular-desde! ente "2025-01-01")
        r1 (gatilho/disparar! *deps* ente {:origem "evento"})
        obr (contar ente "compliance.prazo_dominio_ativo")
        r2 (gatilho/disparar! *deps* ente {:origem "evento"})]
    (is (= 3 (get-in r1 [:metas-fiscais :avaliadas])))
    (is (= 3 (get-in r2 [:metas-fiscais :avaliadas])) "o evento reavalia as abertas...")
    (is (= obr (contar ente "compliance.prazo_dominio_ativo")) "...sem materializar de novo")
    (is (zero? (:vencidas r2)) "ja' estavam vencidas: o sweep nao tem o que mover")
    (testing "a Casa que se desligou da regra (opt-out com motivo) nao e' religada nem avaliada"
      (let [outra (random-uuid)]
        (repo-motor/criar-binding! (:repo-motor *deps*) outra
                                   {:id (random-uuid) :ente-id outra :template-chave "audiencia_metas_fiscais"
                                    :ativa false :motivo-desativacao "LOM propria, conferida pelo juridico"})
        (is (nil? (:metas-fiscais (gatilho/disparar! *deps* outra {:origem "evento"}))))
        (is (false? (:ativa (repo-motor/binding-do-ente (:repo-motor *deps*) outra "audiencia_metas_fiscais"))))
        (is (zero? (contar outra "compliance.prazo_dominio_ativo")))))
    (testing "uma obrigacao pendente vence pelo sweep quando o dia passa do prazo"
      (let [casa (random-uuid)
            pid (parse-uuid (get-in (chamar *svc* casa :post "/contas"
                                            {:tipo "governo_prefeito" :exercicio 2023 :responsavel "Fulano"
                                             :recebida-em "2026-09-01" :parecer-previo "favoravel"
                                             :comissao-autora-id (str comissao)})
                                    [:corpo :id]))]
        (is (= "pendente" (:estado (obrigacao casa "julgamento_contas_prefeito" "prestacao_contas" pid))))
        (gatilho/disparar! (assoc *deps* :hoje (constantly (LocalDate/parse "2026-11-01"))) casa {:origem "evento"
                                                                                                 :partes #{:contas}})
        (is (= "vencida" (:estado (obrigacao casa "julgamento_contas_prefeito" "prestacao_contas" pid))))))))

;; ---------- o gatilho: contas ----------

(deftest registrar-cria-a-obrigacao-e-julgar-cumpre
  (let [ente (random-uuid)
        {:keys [status corpo]} (chamar *svc* ente :post "/contas"
                                       {:tipo "governo_prefeito" :exercicio 2024 :responsavel "Francisco das Chagas"
                                        :recebida-em "2026-09-01" :parecer-previo "favoravel_com_ressalvas"
                                        :comissao-autora-id (str comissao)})
        pid (parse-uuid (:id corpo))
        pdl (get-in corpo [:proposicao :id])]
    (is (= 201 status))
    (testing "o registro cria a obrigacao de julgar, com o prazo congelado da prestacao"
      (let [o (obrigacao ente "julgamento_contas_prefeito" "prestacao_contas" pid)]
        (is (= "pendente" (:estado o)))
        (is (= (LocalDate/parse "2026-10-31") (:vence-em o)))))
    (testing "a prestacao da Mesa nao tem obrigacao de julgamento"
      (let [m (parse-uuid (get-in (chamar *svc* ente :post "/contas"
                                          {:tipo "gestao_camara" :exercicio 2024 :responsavel "Mesa Diretora"
                                           :recebida-em "2026-09-01"})
                                  [:corpo :id]))]
        (is (nil? (obrigacao ente "julgamento_contas_prefeito" "prestacao_contas" m)))))
    (testing "encerrar a votacao do PDL julga as contas e cumpre a obrigacao"
      (let [sessao (agendar! ente "ordinaria" nil "2026-10-03T13:00:00Z")
            _ (transicionar! ente sessao "aberta")
            v (chamar *svc* ente :post (str "/sessoes/" sessao "/votacoes")
                      {:objeto-tipo "proposicao" :objeto-id pdl :modalidade "nominal"
                       :quorum-tipo "maioria_qualificada_2_3"})
            vid (get-in v [:corpo :id])]
        (is (= 201 (:status v)) (pr-str v))
        (doseq [[ver voto] (map vector roster (concat (repeat 6 "sim") (repeat 3 "nao")))]
          (is (= 201 (:status (chamar *svc* ente :post (str "/sessoes/" sessao "/votacoes/" vid "/votos")
                                      {:vereador-id (str ver) :voto voto})))))
        (is (= 200 (:status (chamar *svc* ente :post (str "/sessoes/" sessao "/votacoes/" vid "/encerramento")
                                    {:lock-version (get-in v [:corpo :lock-version])}))))
        (is (= "cumprida" (:estado (obrigacao ente "julgamento_contas_prefeito" "prestacao_contas" pid))))))))

;; ---------- a falha nunca derruba ----------

(deftest falha-do-gatilho-nao-quebra-o-painel-nem-o-ato
  (let [ente (random-uuid)]
    (is (= 200 (:status (chamar *svc-quebrado* ente :get "/compliance/painel"))) "o painel le o que ha'")
    (is (= 201 (:status (chamar *svc-quebrado* ente :post "/contas"
                                {:tipo "governo_prefeito" :exercicio 2022 :responsavel "Beltrano"
                                 :recebida-em "2026-09-01" :parecer-previo "favoravel"
                                 :comissao-autora-id (str comissao)})))
        "o registro da prestacao responde 201 mesmo com o gatilho quebrado")
    (is (zero? (contar ente "compliance.prazo_dominio_ativo")) "e nada foi materializado")
    (is (nil? (gatilho/disparar-sem-falhar! (assoc *deps* :repo-motor (motor-quebrado)) ente {}))
        "a versao do host nunca lanca")))
