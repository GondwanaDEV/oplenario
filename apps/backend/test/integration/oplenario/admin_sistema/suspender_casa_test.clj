(ns oplenario.admin-sistema.suspender-casa-test
  "INTEGRACAO (PG real): SUSPENDER, REATIVAR e INICIAR O ENCERRAMENTO de uma Casa pelo console (ADR-0018, fatia 1).
  Two-person rule (quem pede nao aprova — nem pela borda, nem pelo banco); o incidente de seguranca suspende com um so'
  e volta a ativa sem a 2a aprovacao em 24 h; a suspensao aprovada com sessao plenaria em curso fica AGENDADA ate' o
  encerramento (ordem judicial corta na hora); cada transicao sela a atuacao e sai pelo outbox; a cota de IA da Casa
  zera enquanto ela esta' suspensa e volta ao que valia na reativacao."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.integracao-ia.diplomat.consumers :as ia-consumers]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sess])
  (:import (java.time Duration Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))
(defn- repo-sessoes [] (repo-sess/->RepoSessoesPg {:ds *ds*} (outbox/bus)))

(defn- servico
  "O console de verdade (`rotas/montar`), com o relogio dado (o preguicoso do incidente le por ele)."
  ([] (servico (Instant/now)))
  ([^Instant agora]
   (-> (http/servico (config/carregar)
                     (rotas/montar {:idp (idp-dev/idp-dev)
                                    :repo-sessoes (repo-sessoes)
                                    :idp-operacao (idp-admin/idp-operacao-dev)
                                    :repo-admin-sistema (repo-op)
                                    :relogio (tempo/relogio-fixo agora)
                                    :cache-estado-da-casa-ms 0
                                    :operacao {:realm "operacao" :client-id "oplenario-console"
                                               :sessao {:absoluta-h 8 :ociosa-min 15}}})
                     it/globais)
       ph/create-server ::ph/service-fn)))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- operador! [nome]
  (repo/criar-operador! (repo-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev") :nome nome}))

(defn- como [o] {"authorization" (str "Bearer " (json/write-value-as-string {:operador-id (str (:id o))}))
                 "Content-Type" "application/json"})

(defn- casa-ativa!
  "Uma Casa no registro, ja' ativa (o handoff e' do registro_de_casas_test)."
  [o]
  (let [ente (random-uuid)]
    (repo/registrar-casa! (repo-op) {:ente-id ente :nome "Câmara Municipal de Baturité" :uf "CE"
                                     :municipio-ibge "2302008" :municipio-nome "Baturité"}
                          {:operador-id (:id o)})
    (jdbc/with-transaction [tx *ds*] (repo/ativar-casa-em-tx! tx ente {}))
    ente))

(defn- post [svc o caminho corpo]
  (pt/response-for svc :post caminho :headers (como o) :body (json/write-value-as-string corpo)))

(defn- pedir! [svc o ente motivo]
  (post svc o (str "/operacao/casas/" ente "/suspensao")
        {:motivo motivo :justificativa "Motivo registrado no processo administrativo 12/2026."}))

(defn- aprovar! [svc o pedido] (post svc o (str "/operacao/pedidos/" pedido "/aprovacao") {}))
(defn- recusar! [svc o pedido] (post svc o (str "/operacao/pedidos/" pedido "/recusa") {:justificativa "Nao confere."}))
(defn- reativar! [svc o ente]
  (post svc o (str "/operacao/casas/" ente "/reativacao") {:justificativa "Pagamento regularizado em 30/09/2026."}))

(defn- ficha [svc o ente] (ler (pt/response-for svc :get (str "/operacao/casas/" ente) :headers (como o))))
(defn- acoes [svc o ente] (mapv :acao (:atuacao (ficha svc o ente))))

(defn- eventos-da-casa [ente]
  (mapv (juxt :outbox/tipo #(some-> ^org.postgresql.util.PGobject (:outbox/payload %) .getValue (json/read-value json/keyword-keys-object-mapper)))
        (jdbc/execute! *ds* ["SELECT tipo, payload FROM shared.outbox WHERE ente_id = ? AND tipo LIKE 'admin_sistema.%' ORDER BY id"
                             ente])))

(deftest two-person-rule-quem-pede-nao-aprova
  (let [svc (servico) ana (operador! "Ana Operação") beto (operador! "Beto Operação") ente (casa-ativa! ana)
        r (pedir! svc ana ente "inadimplencia")
        pedido (get-in (ler r) [:pedido :id])]
    (is (= 200 (:status r)) (:body r))
    (is (= "aguardando" (get-in (ler r) [:pedido :estado])))
    (is (= "ativo" (get-in (ler r) [:casa :estado])) "o pedido sozinho nao suspende")
    (testing "a fila 'aguardando 2o operador' mostra o pedido, com a Casa e quem pediu"
      (let [lista (ler (pt/response-for svc :get "/operacao/casas" :headers (como beto)))]
        (is (some #(and (= pedido (:id %)) (= "Ana Operação" (:pedido-por %)) (= "Câmara Municipal de Baturité" (:casa-nome %)))
                  (:pendentes lista))))
      (is (= pedido (get-in (ficha svc beto ente) [:pedido-aberto :id]))))
    (testing "a mesma operadora nao aprova o proprio pedido (409, e a Casa segue ativa)"
      (let [r (aprovar! svc ana pedido)]
        (is (= 409 (:status r)))
        (is (re-find #"outro operador" (:erro (ler r)))))
      (is (= "ativo" (get-in (ficha svc ana ente) [:casa :estado]))))
    (testing "nem pelo banco: a constraint recusa a mesma pessoa nas duas pontas"
      (is (thrown-with-msg? Exception #"pedido_restricao_duas_pessoas"
                            (jdbc/execute! *ds* ["UPDATE admin_sistema.pedido_restricao SET estado = 'aprovado',
                                                   decidido_por = pedido_por, decidido_em = now() WHERE id = ?::uuid"
                                                 pedido]))))
    (testing "outro pedido para a mesma Casa espera o primeiro ser decidido"
      (is (= 409 (:status (pedir! svc beto ente "pedido_da_casa")))))
    (testing "o 2o operador aprova: a Casa fica suspensa agora (sem sessao em curso)"
      (let [r (aprovar! svc beto pedido) b (ler r)]
        (is (= 200 (:status r)))
        (is (= "imediato" (:efeito b)))
        (is (= "suspenso" (get-in b [:casa :estado])))
        (is (= "inadimplencia" (get-in b [:casa :restricao :motivo])))
        (is (some? (get-in b [:casa :restricao :desde])))))
    (is (= ["casa-suspensa" "suspensao-aprovada" "suspensao-pedida"] (take 3 (acoes svc ana ente))) "selado, mais recente primeiro")
    (is (= [["admin_sistema.casa.suspensa" "inadimplencia"]] (mapv (fn [[t p]] [t (:motivo p)]) (eventos-da-casa ente))))
    (is (empty? (filter #(= pedido (:id %)) (:pendentes (ler (pt/response-for svc :get "/operacao/casas" :headers (como ana)))))))
    (testing "uma Casa ja' suspensa nao e' suspensa de novo"
      (is (= 409 (:status (pedir! svc ana ente "ordem_judicial")))))
    (testing "reativar: UM operador, com motivo — e o evento sai"
      (let [r (reativar! svc ana ente)]
        (is (= 200 (:status r)))
        (is (= "ativo" (get-in (ler r) [:casa :estado])))
        (is (nil? (get-in (ler r) [:casa :restricao]))))
      (is (= "casa-reativada" (first (acoes svc ana ente))))
      (is (= "admin_sistema.casa.reativada" (first (last (eventos-da-casa ente)))))
      (is (= 409 (:status (reativar! svc ana ente))) "Casa ativa nao se reativa"))
    (is (true? (:integra? (atuacao/verificar-corrente *ds*))) "a corrente da Operacao segue integra")))

(deftest recusar-e-retirar
  (let [svc (servico) ana (operador! "Ana") beto (operador! "Beto") ente (casa-ativa! ana)
        p1 (get-in (ler (pedir! svc ana ente "pedido_da_casa")) [:pedido :id])]
    (let [r (recusar! svc beto p1)]
      (is (= 200 (:status r)))
      (is (= "recusado" (get-in (ler r) [:pedido :estado])))
      (is (= "ativo" (get-in (ler r) [:casa :estado]))))
    (is (= 409 (:status (aprovar! svc beto p1))) "pedido decidido nao se decide de novo")
    (let [p2 (get-in (ler (pedir! svc ana ente "pedido_da_casa")) [:pedido :id])
          r (recusar! svc ana p2)]
      (is (= "retirado" (get-in (ler r) [:pedido :estado])) "quem pediu nao recusa: retira")
      (is (= "pedido-retirado" (first (acoes svc ana ente)))))
    (is (= 404 (:status (aprovar! svc beto (random-uuid)))))
    (is (= 400 (:status (post svc ana (str "/operacao/casas/" ente "/suspensao") {:motivo "outro" :justificativa "qualquer coisa aqui"})))
        "\"outro\" nao e' motivo (Eixo 1a)")
    (is (= 400 (:status (post svc ana (str "/operacao/casas/" ente "/suspensao") {:motivo "inadimplencia" :justificativa "curta"})))
        "a justificativa e' obrigatoria")))

(deftest incidente-suspende-com-um-e-volta-sem-a-segunda-aprovacao-em-24h
  (let [agora (Instant/now) svc (servico agora) ana (operador! "Ana") beto (operador! "Beto")]
    (testing "sem a 2a aprovacao em 24 h, a Casa volta a ativa (verificacao preguicosa ao olhar a Casa)"
      (let [ente (casa-ativa! ana)
            b (ler (pedir! svc ana ente "incidente_de_seguranca"))]
        (is (= "suspenso" (get-in b [:casa :estado])) "o incidente suspende na hora, com um operador")
        (is (= "aguardando" (get-in b [:pedido :estado])))
        (is (some? (get-in b [:pedido :confirmar-ate])))
        (is (= "suspenso" (get-in (ficha (servico (.plus agora (Duration/ofHours 23))) ana ente) [:casa :estado]))
            "23 h depois ainda esta' suspensa")
        (let [depois (servico (.plus agora (Duration/ofHours 25)))
              f (ficha depois ana ente)]
          (is (= "ativo" (get-in f [:casa :estado])))
          (is (nil? (:pedido-aberto f)))
          (is (= "casa-reativada" (:acao (first (:atuacao f)))))
          (is (= "incidente_sem_segunda_aprovacao" (get-in (first (:atuacao f)) [:detalhe :por])))
          (is (nil? (:operador (first (:atuacao f)))) "foi o prazo, nao um operador"))
        (is (= ["admin_sistema.casa.suspensa" "admin_sistema.casa.reativada"] (mapv first (eventos-da-casa ente))))))
    (testing "com a 2a aprovacao dentro do prazo, a suspensao fica"
      (let [ente (casa-ativa! ana)
            pedido (get-in (ler (pedir! svc ana ente "incidente_de_seguranca")) [:pedido :id])
            r (aprovar! svc beto pedido)]
        (is (= "ja-efetivado" (:efeito (ler r))))
        (is (= "suspenso" (get-in (ficha (servico (.plus agora (Duration/ofHours 25))) ana ente) [:casa :estado])))))
    (testing "o 2o operador recusa o incidente: a Casa volta a ativa na hora"
      (let [ente (casa-ativa! ana)
            pedido (get-in (ler (pedir! svc ana ente "incidente_de_seguranca")) [:pedido :id])
            r (recusar! svc beto pedido)]
        (is (= "ativo" (get-in (ler r) [:casa :estado])))
        (is (= "suspensao_recusada" (get-in (first (:atuacao (ficha svc ana ente))) [:detalhe :por])))))))

(defn- sessao-aberta! [ente]
  (let [r (repo-sessoes)
        sid (:id (repo-sess/agendar-sessao! r ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                                     :tipo-sessao "ordinaria" :modalidade "presencial"}))]
    (repo-sess/transicionar-sessao! r ente {:id sid :para "aberta" :updated-by (random-uuid) :lock-version 0})
    sid))

(deftest suspensao-com-sessao-em-curso-fica-agendada-ate-o-encerramento
  (let [svc (servico) ana (operador! "Ana") beto (operador! "Beto") ente (casa-ativa! ana)
        sid (sessao-aberta! ente)
        pedido (get-in (ler (pedir! svc ana ente "inadimplencia")) [:pedido :id])
        b (ler (aprovar! svc beto pedido))]
    (is (= "agendado" (:efeito b)) "nunca derrubar um plenario no meio da votacao")
    (is (= "ativo" (get-in b [:casa :estado])))
    (is (true? (get-in b [:casa :suspensao-agendada])))
    (is (= "suspensao-agendada" (first (acoes svc ana ente))))
    (is (= "ativo" (get-in (ficha svc ana ente) [:casa :estado])) "a sessao segue: a Casa segue ativa")
    (is (empty? (eventos-da-casa ente)))
    (repo-sess/transicionar-sessao! (repo-sessoes) ente {:id sid :para "encerrada" :updated-by (random-uuid) :lock-version 1})
    (let [f (ficha svc ana ente)]
      (is (= "suspenso" (get-in f [:casa :estado])) "a sessao encerrou: a suspensao entra")
      (is (false? (get-in f [:casa :suspensao-agendada])))
      (is (= "apos-a-sessao" (get-in (first (:atuacao f)) [:detalhe :efeito]))))
    (is (= ["admin_sistema.casa.suspensa"] (mapv first (eventos-da-casa ente)))))
  (testing "ordem judicial corta na hora, mesmo com sessao aberta"
    (let [svc (servico) ana (operador! "Ana") beto (operador! "Beto") ente (casa-ativa! ana)
          _ (sessao-aberta! ente)
          pedido (get-in (ler (pedir! svc ana ente "ordem_judicial")) [:pedido :id])
          b (ler (aprovar! svc beto pedido))]
      (is (= "imediato" (:efeito b)))
      (is (= "suspenso" (get-in b [:casa :estado])))))
  (testing "reativar antes da sessao acabar cancela a suspensao agendada"
    (let [svc (servico) ana (operador! "Ana") beto (operador! "Beto") ente (casa-ativa! ana)
          _ (sessao-aberta! ente)
          pedido (get-in (ler (pedir! svc ana ente "pedido_da_casa")) [:pedido :id])]
      (aprovar! svc beto pedido)
      (let [r (reativar! svc ana ente)]
        (is (= 200 (:status r)))
        (is (false? (get-in (ler r) [:casa :suspensao-agendada]))))
      (is (= "suspensao-agendada-cancelada" (first (acoes svc ana ente)))))))

(deftest iniciar-encerramento-com-dois-operadores
  (let [svc (servico) ana (operador! "Ana") beto (operador! "Beto") ente (casa-ativa! ana)
        r (post svc ana (str "/operacao/casas/" ente "/encerramento")
                {:origem "fim_de_contrato" :justificativa "Contrato 7/2025 encerrado sem renovacao."})
        pedido (get-in (ler r) [:pedido :id])]
    (is (= 200 (:status r)))
    (is (= "encerrar" (get-in (ler r) [:pedido :acao])))
    (is (= 409 (:status (aprovar! svc ana pedido))))
    (let [b (ler (aprovar! svc beto pedido))]
      (is (= "suspenso" (get-in b [:casa :estado])))
      (is (= "encerramento_em_curso" (get-in b [:casa :restricao :motivo]))))
    (is (= ["encerramento-aprovado" "encerramento-pedido"] (take 2 (rest (acoes svc ana ente)))))
    (is (= 409 (:status (post svc beto (str "/operacao/casas/" ente "/encerramento")
                              {:origem "pedido_da_casa" :justificativa "Oficio 3/2026 da Mesa Diretora."})))
        "o encerramento ja' esta' em curso")))

(deftest so-o-operador-transiciona
  (let [svc (servico) ana (operador! "Ana") ente (casa-ativa! ana)
        casa {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                                           :identidade-id (str (random-uuid))}))
              "Content-Type" "application/json"}]
    (doseq [[caminho corpo] [[(str "/operacao/casas/" ente "/suspensao") {:motivo "inadimplencia" :justificativa "xxxxxxxxxxxx"}]
                             [(str "/operacao/casas/" ente "/reativacao") {:justificativa "xxxxxxxxxxxx"}]
                             [(str "/operacao/casas/" ente "/encerramento") {:origem "fim_de_contrato" :justificativa "xxxxxxxxxxxx"}]
                             [(str "/operacao/pedidos/" (random-uuid) "/aprovacao") {}]
                             [(str "/operacao/pedidos/" (random-uuid) "/recusa") {}]]]
      (is (= 401 (:status (pt/response-for svc :post caminho :headers casa :body (json/write-value-as-string corpo))))
          (str caminho ": credencial de Casa nao abre o console"))
      (is (= 401 (:status (pt/response-for svc :post caminho :body (json/write-value-as-string corpo))))
          (str caminho ": sem credencial")))))

;; ---------- a cota de IA da Casa suspensa (Eixo 2) ----------

(defn- rp-ia [] (assoc (repo-ia/repositorio) :datasource {:ds *ds*}))

(defn- orcamentos [ente]
  (->> (repo-ia/listar-eventos (rp-ia) 0 100000)
       (filter #(and (= "OrcamentoIADefinido" (:tipo %)) (= ente (:ente-id %))))
       (mapv #(select-keys (:payload %) [:mensal :teto-duro]))))

(deftest a-cota-de-ia-zera-na-suspensao-e-volta-na-reativacao
  (let [svc (servico) ana (operador! "Ana") beto (operador! "Beto") registro (ia-consumers/registrar {})]
    (testing "Casa que tinha orcamento: volta ao que valia"
      (let [ente (casa-ativa! ana)]
        (repo-ia/definir-orcamento! (rp-ia) {:ente-id ente :mensal 100M :teto-duro 120M :moeda "USD" :definido-por "op"})
        (aprovar! svc beto (get-in (ler (pedir! svc ana ente "inadimplencia")) [:pedido :id]))
        (outbox/drenar! *ds* registro)
        (is (= {:mensal 0M :teto-duro 0M} (select-keys (repo-ia/orcamento-atual (rp-ia) ente) [:mensal :teto-duro]))
            "suspensa: cota zero (nem o que a pessoa pede roda)")
        (outbox/drenar! *ds* registro)
        (is (= 2 (count (orcamentos ente))) "o dreno de novo nao duplica")
        (reativar! svc ana ente)
        (outbox/drenar! *ds* registro)
        (is (= {:mensal 100M :teto-duro 120M} (select-keys (repo-ia/orcamento-atual (rp-ia) ente) [:mensal :teto-duro])))
        (is (= [{:mensal "100" :teto-duro "120"} {:mensal "0" :teto-duro "0"} {:mensal "100" :teto-duro "120"}]
               (mapv #(update-vals % (fn [v] (some-> v bigdec .stripTrailingZeros .toPlainString))) (orcamentos ente))))))
    (testing "orcamento definido com a Casa suspensa: a cota segue zero e o valor novo vale na reativacao"
      (let [ente (casa-ativa! ana)]
        (repo-ia/definir-orcamento! (rp-ia) {:ente-id ente :mensal 100M :teto-duro 120M :moeda "USD" :definido-por "op"})
        (aprovar! svc beto (get-in (ler (pedir! svc ana ente "inadimplencia")) [:pedido :id]))
        (outbox/drenar! *ds* registro)
        (let [d (repo-ia/definir-orcamento! (rp-ia) {:ente-id ente :mensal 300M :teto-duro 360M :moeda "USD"
                                                     :definido-por "op"})]
          (is (= {:mensal 300M :teto-duro 360M} (select-keys d [:mensal :teto-duro])) "a definicao pedida fica guardada")
          (is (true? (:pausado-pela-suspensao d)) "e quem definiu sabe que so' vale na reativacao"))
        (is (= {:mensal 0M :teto-duro 0M} (select-keys (repo-ia/orcamento-atual (rp-ia) ente) [:mensal :teto-duro]))
            "a suspensao vence: a cota da Casa suspensa nao reabre")
        (is (= {:mensal "0" :teto-duro "0"}
               (update-vals (last (orcamentos ente)) (fn [v] (some-> v bigdec .stripTrailingZeros .toPlainString))))
            "o satelite segue com a cota zero")
        (reativar! svc ana ente)
        (outbox/drenar! *ds* registro)
        (is (= {:mensal 300M :teto-duro 360M} (select-keys (repo-ia/orcamento-atual (rp-ia) ente) [:mensal :teto-duro]))
            "a reativacao devolve o que foi definido durante a suspensao, nao o de antes dela")))
    (testing "Casa que so' media: volta a so' medir (definicao sem valor)"
      (let [ente (casa-ativa! ana)]
        (aprovar! svc beto (get-in (ler (pedir! svc ana ente "pedido_da_casa")) [:pedido :id]))
        (outbox/drenar! *ds* registro)
        (is (some? (repo-ia/orcamento-atual (rp-ia) ente)))
        (reativar! svc ana ente)
        (outbox/drenar! *ds* registro)
        (is (nil? (repo-ia/orcamento-atual (rp-ia) ente)) "sem orcamento = a Casa so' mede")
        (is (= {:mensal nil :teto-duro nil} (last (orcamentos ente))) "o satelite recebe a definicao sem valor")))))
