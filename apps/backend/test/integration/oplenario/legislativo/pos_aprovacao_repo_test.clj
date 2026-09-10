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
            [oplenario.legislativo.db.texto-versao :as texto]
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

(defn- nova-versao-vigente!
  "Cria versao NOVA e a promove a vigente — o que `PATCH .../proposicoes/:id` com `texto` faz por dentro."
  [ente pid rotulo]
  (repo/transacao *repo* ente
    (fn [tx]
      (let [{vid :id} (texto/nova-versao! tx {:id (random-uuid) :ente-id ente :proposicao-id pid
                                              :origem-versao "edicao" :texto-inline rotulo :created-by nil})
            {:keys [lock-version]} (texto/buscar tx ente vid)]
        (texto/promover! tx {:ente-id ente :proposicao-id pid :versao-id vid
                             :updated-by nil :lock-version lock-version})
        vid))))

(defn- aprovar!
  "A Camara APROVA `pid`: abre votacao nominal, um voto 'sim', encerra (maioria simples, base 1). Separado
  de `protocolar-e-aprovar!` porque o T3-A2 precisa aprovar num INSTANTE ESCOLHIDO — entre a promocao de
  uma versao de texto e a promocao da seguinte. `abrir!` congela a versao vigente NESTE instante (mig 0075)."
  [ente pid]
  (repo/transacao *repo* ente
    (fn [tx]
      (let [{vid :id} (votacao/abrir! tx {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao"
                                          :objeto-id pid :modalidade "nominal"
                                          :quorum-tipo "maioria_simples"})]
        (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid
                                     :vereador-id (random-uuid) :voto "sim"})
        (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0})))))

(defn- protocolar-e-aprovar!
  "T3-A + T3-A2: protocola, DA TEXTO VIGENTE e FAZ A CAMARA APROVAR — as tres pre-condicoes que um autografo
  exige hoje. Antes das guardas, estes mesmos testes provavam que a numeracao gapless funcionava PARA
  MATERIA QUE NINGUEM VOTOU e SEM TEXTO NENHUM. A fixture ficou mais cara porque passou a descrever o rito."
  [ente]
  (let [pid (protocolar! ente)]
    (nova-versao-vigente! ente pid "TEXTO DELIBERADO [FIXTURE]")
    (aprovar! ente pid)
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

;; ========================= T3-A2: o autografo leva o TEXTO QUE FOI VOTADO =========================
;; O achado (revisao adversarial do T3-A): a votacao guardava so' `objeto_id`, nunca a VERSAO do texto, e o
;; autografo resolvia a versao lendo o VIGENTE no momento da geracao. Uma promocao de versao entre a
;; aprovacao e a geracao — `PATCH .../proposicoes/:id` com `texto`, permitido ate' estado terminal — fazia o
;; autografo sair com um texto que o plenario nunca leu.

(deftest autografo-leva-a-versao-VOTADA-nao-a-vigente-na-geracao
  (let [ente (random-uuid) pid (protocolar! ente)
        v-votada (nova-versao-vigente! ente pid "TEXTO QUE O PLENARIO LEU")
        _ (aprovar! ente pid)                              ; vota e encerra com a v-votada vigente
        v-trocada (nova-versao-vigente! ente pid "TEXTO TROCADO DEPOIS DA VOTACAO")]
    (is (not= v-votada v-trocada) "premissa: sao duas versoes distintas")
    (is (= v-trocada (:id (repo/texto-vigente *repo* ente pid)))
        "premissa do ATAQUE: a vigente AGORA e' a trocada, nao a votada")
    (let [{aut-id :autografo-id} (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
                                   {:id (random-uuid) :proposicao-id pid :ano 2026
                                    :destinatario-texto "Prefeito Municipal de Fortaleza"
                                    :created-by (random-uuid)})
          aut (repo/buscar-autografo *repo* ente aut-id)]
      (is (= v-votada (:texto-versao-id aut))
          "o autografo tem de carregar o texto DELIBERADO, nao o que estava vigente na hora de gerar"))))

(deftest autografo-recusa-quando-a-votacao-nao-registrou-versao
  ;; falha FECHADA: materia aprovada em votacao que nao registrou versao (linha legada anterior a migration
  ;; 0075, ou materia que foi a plenario sem texto vigente) -> nao se sabe o que foi aprovado -> sem autografo.
  (let [ente (random-uuid) pid (protocolar! ente)]         ; NUNCA teve texto vigente
    (aprovar! ente pid)
    (is (nil? (:texto-versao-id (repo/aprovacao-vigente *repo* ente pid)))
        "premissa: a votacao aprovou, mas sem versao registrada")
    (is (thrown? Exception
          (repo/gerar-autografo-e-abrir-tramitacao! *repo* ente
            {:id (random-uuid) :proposicao-id pid :ano 2026
             :destinatario-texto "Prefeito Municipal de Fortaleza" :created-by (random-uuid)})))
    (is (nil? (repo/autografo-da-proposicao *repo* ente pid)) "nenhum autografo ficou no banco")))
