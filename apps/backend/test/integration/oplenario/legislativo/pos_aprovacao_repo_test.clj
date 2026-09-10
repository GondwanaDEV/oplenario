(ns oplenario.legislativo.pos-aprovacao-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis) no POS-APROVACAO (Onda B Slice 7,
  F3.8a) — `buscar-pos-aprovacao` (leitura composta, NUMA UNICA tx, sem short-circuit no nil do autografo)
  e `gerar-autografo-e-abrir-tramitacao!` (acao composta: autografo/gerar! + tramitacao-executiva/iniciar!
  NUMA UNICA tx, atomico). Os db/ individuais ja' tem cobertura de dominio completa em
  pos_aprovacao_db_test.clj — aqui cobrimos so' a COMPOSICAO nova do Repo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- protocolar! [ente]
  (:id (repo/transacao *repo* ente
         (fn [tx] (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                        :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))))

(defn- protocolar-e-aprovar!
  "T3-A (guarda-autografo-votacao): protocola E FAZ A CAMARA APROVAR. Desde a guarda, gerar autografo exige
  o ATO — votacao encerrada com resultado 'aprovada' sobre a materia. Todo teste que gera autografo passou
  a precisar disto, e essa exigencia e' o conserto, nao um custo de fixture: antes da guarda, estes mesmos
  testes provavam que a numeracao gapless funcionava PARA MATERIA QUE NINGUEM VOTOU."
  [ente]
  (let [pid (protocolar! ente)]
    (repo/transacao *repo* ente
      (fn [tx]
        (let [{vid :id} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao"
                                            :objeto-id pid :modalidade "nominal"
                                            :quorum-tipo "maioria_simples"})]
          (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid
                                       :vereador-id (random-uuid) :voto "sim"})
          (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0}))))
    pid))

;; ========================= buscar-pos-aprovacao (leitura composta) =========================

(deftest buscar-pos-aprovacao-sem-autografo-ainda
  (let [ente (random-uuid) pid (protocolar! ente)
        r (repo/buscar-pos-aprovacao *repo* ente pid)]
    (is (nil? (:autografo r)) "proposicao existe mas ainda nao gerou autografo")
    (is (nil? (:tramitacao-executiva r)))))

(deftest buscar-pos-aprovacao-proposicao-inexistente-tambem-devolve-mapa-nil
  ;; Repo/buscar-pos-aprovacao NAO checa a proposicao (spec: sem short-circuit no nil do autografo) — a
  ;; decisao 404-vs-corpo-parcial e' do CONTROLLER (buscar-pos-aprovacao pre-checa a proposicao antes).
  (let [r (repo/buscar-pos-aprovacao *repo* (random-uuid) (random-uuid))]
    (is (= {:autografo nil :tramitacao-executiva nil} r))))

(deftest buscar-pos-aprovacao-com-autografo-e-tramitacao
  (let [ente (random-uuid) pid (protocolar-e-aprovar! ente)
        gerado (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
                 {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
                  :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})
        r (repo/buscar-pos-aprovacao *repo* ente pid)]
    (is (some? (:autografo r)))
    (is (= (:autografo-id gerado) (:id (:autografo r))))
    (is (= (:numero gerado) (:numero (:autografo r))))
    (is (some? (:tramitacao-executiva r)))
    (is (= (:tramitacao-executiva-id gerado) (:id (:tramitacao-executiva r))))
    (is (= "aguardando" (:estado (:tramitacao-executiva r))))))

;; ========================= gerar-autografo-e-abrir-tramitacao! (acao composta atomica) =========================

(deftest gerar-autografo-e-abrir-tramitacao-numera-e-abre-atomico
  (let [ente (random-uuid) pid (protocolar-e-aprovar! ente)
        r (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
            {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
             :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})]
    (is (= 1 (:numero r)) "primeiro autografo do ano 2026 neste ente")
    (is (some? (:autografo-id r)))
    (is (some? (:tramitacao-executiva-id r)))
    (let [aut (repo/buscar-autografo *repo* ente (:autografo-id r))
          tram (repo/buscar-tramitacao-executiva *repo* ente (:tramitacao-executiva-id r))]
      (is (= pid (:proposicao-id aut)))
      (is (= (:autografo-id r) (:autografo-id tram)))
      (is (= "aguardando" (:estado tram))))))

(deftest gerar-autografo-e-abrir-tramitacao-numera-gapless-entre-proposicoes
  (let [ente (random-uuid) pid-a (protocolar-e-aprovar! ente) pid-b (protocolar-e-aprovar! ente)
        ra (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
             {:id (random-uuid) :proposicao-id pid-a :ano 2026 :texto-versao-id (random-uuid)
              :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})
        rb (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
             {:id (random-uuid) :proposicao-id pid-b :ano 2026 :texto-versao-id (random-uuid)
              :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})]
    (is (= [1 2] [(:numero ra) (:numero rb)]))))

(deftest gerar-autografo-e-abrir-tramitacao-proposicao-duplicada-lanca-sem-numero-orfao
  ;; a UNIQUE (ente_id, proposicao_id) do autografo barra um segundo autografo p/ a mesma proposicao — a
  ;; tentativa falha NAO deixa buraco no numerador gapless (kernel/sequencial e' transacional; mesmo teste
  ;; de regressao de protocolar-documento-lock-version-desatualizado-lanca-e-rollback-nao-deixa-buraco).
  (let [ente (random-uuid) pid (protocolar-e-aprovar! ente)]
    (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
      {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
       :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})
    (is (thrown? Exception
          (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
            {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
             :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})))
    (let [outro-pid (protocolar-e-aprovar! ente)
          r (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
              {:id (random-uuid) :proposicao-id outro-pid :ano 2026 :texto-versao-id (random-uuid)
               :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})]
      (is (= 2 (:numero r))
          "gapless: a tentativa falha (rollback) NAO consumiu numero — o proximo sucesso e' 2, nao 3"))))

;; ========================= T3-A: a re-verificacao DENTRO da tx =========================

(deftest gerar-autografo-em-materia-nao-aprovada-nao-escreve-nem-queima-numero
  ;; O controller ja' guarda na borda (pos_aprovacao_http_in_test). ESTE teste prova o backstop: mesmo
  ;; chamando o Repo DIRETO — sem passar pela borda — a materia nao aprovada nao vira autografo. Sem esta
  ;; camada haveria janela TOCTOU entre o guard e a escrita, e o guard de duplicidade tem o UNIQUE do banco
  ;; como backstop enquanto a aprovacao (fato noutra tabela) nao tem constraint equivalente.
  (let [ente (random-uuid) pid (protocolar! ente)]      ; protocolada, JAMAIS votada
    (is (thrown? Exception
          (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
            {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
             :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})))
    (is (nil? (repo/autografo-da-proposicao *repo* ente pid)) "nenhum autografo ficou no banco")
    ;; e o numerador gapless nao foi consumido pela tentativa barrada
    (let [aprovada (protocolar-e-aprovar! ente)
          r (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
              {:id (random-uuid) :proposicao-id aprovada :ano 2026 :texto-versao-id (random-uuid)
               :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})]
      (is (= 1 (:numero r)) "a recusa nao queimou numero: o primeiro autografo do ano ainda e' o 1"))))
