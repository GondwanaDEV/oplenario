(ns oplenario.legislativo.proposicao-repositorio-test
  "Onda B Slice 2 — prova a COMPOSICAO numa unica tx: protocolar!+texto, editar-proposicao!+texto,
  buscar-proposicao-detalhe. Repo real (Postgres+bus), sem HTTP — mesmo padrao de
  votacao_eventos_repo_test.clj (`->RepoLegislativoPg` construido direto com o Component de datasource
  JA STARTADO + `outbox/bus`, sem passar pelo `repositorio`/Stuart Sierra `using` — esse fio so' e'
  montado pelo `oplenario.sistema` em producao)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(deftest protocolar-com-texto-cria-e-promove-versao-na-mesma-tx
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :texto "## Art. 1o"})
        {:keys [proposicao texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (= "vigente" (:estado-versao texto)))
    (is (= "## Art. 1o" (:texto-inline texto)))
    (is (= (:id r) (:id proposicao)))))

(deftest protocolar-sem-texto-nao-cria-versao
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X"})
        {:keys [texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (nil? texto))))

(deftest protocolar-com-texto-grande-demais-lanca
  (let [ente (random-uuid) grande (apply str (repeat 40000 "a"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"objeto_store"
                          (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                                          :uf "CE" :municipio-nome "Fortaleza" :ementa "X"
                                                          :texto grande})))))

(deftest editar-proposicao-com-texto-promove-nova-versao-origem-edicao
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :texto "## Art. 1o"})
        ;; protocolar!+texto ja' bombeou lock_version p/ 1 (texto/promover! reaponta o pointer, +1) — le' o
        ;; lock-version ATUAL em vez de assumir 0 (assuncao estaria estala/errada por causa da composicao).
        lock-atual (:lock-version (:proposicao (repo/buscar-proposicao-detalhe *repo* ente (:id r))))]
    (repo/editar-proposicao! *repo* ente {:id (:id r) :lock-version lock-atual :ementa "Y" :texto "## Art. 1o (rev)"
                                          :updated-by (random-uuid)})
    (let [{:keys [proposicao texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
      (is (= "Y" (:ementa proposicao)))
      (is (= "## Art. 1o (rev)" (:texto-inline texto)))
      (is (= "edicao" (:origem-versao texto))))))

(deftest editar-autor-tipo-nao-vereador-zera-autor-id
  ;; Task 1-N1 Peca A (fix do achado N-1): o UPDATE de `editar!` era `some?`-gated — um PATCH
  ;; {:autor-tipo "executivo"} SEM :autor-id passava incolume e deixava a linha incoerente
  ;; (executivo, V). Provamos aqui que o par nunca fica incoerente: autor-tipo != "vereador" zera
  ;; autor_id na MESMA escrita.
  (let [ente (random-uuid)
        vereador (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X"
                                          :autor-tipo "vereador" :autor-id vereador})
        lock-atual (:lock-version (repo/buscar-proposicao *repo* ente (:id r)))]
    (repo/editar-proposicao! *repo* ente {:id (:id r) :lock-version lock-atual :autor-tipo "executivo"
                                          :updated-by (random-uuid)})
    (let [p (repo/buscar-proposicao *repo* ente (:id r))]
      (is (= "executivo" (:autor-tipo p)))
      (is (nil? (:autor-id p)) "o par (autor_tipo, autor_id) nunca fica incoerente na linha"))))

(deftest editar-proposicao-conflito-lock-version-lanca-validacao-invalido
  ;; achado 2 (Task 1-N1 fix2, Menor): a Peca B trocou o guard de conflito de `editar!` de
  ;; `(zero? (:next.jdbc/update-count r 0))` p/ `(nil? r)` por causa do `:returning`. E' o unico ramo
  ;; alterado sem teste (a suite so' prova o ramo de SUCESSO — editar-autor-tipo-nao-vereador-zera-autor-id
  ;; e as demais). Prova, no nivel do Repo-Component (a mesma camada que o controller/HTTP usam), que um
  ;; `lock-version` DESATUALIZADO ainda lanca `:validacao/invalido` — nao passa silenciosamente, nem
  ;; estoura NullPointerException tentando ler `:next.jdbc/update-count` de uma linha de RETURNING.
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X"})
        erro (try
               (repo/editar-proposicao! *repo* ente
                 {:id (:id r) :lock-version 99 :ementa "Y" :updated-by (random-uuid)})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "lock-version desatualizado lanca, nao passa silenciosamente")
    (is (= :validacao/invalido (:tipo (ex-data erro))))))

;; ---------- Fatia 2 (guarda-autografo-votacao): :aprovada vem do ATO, servido NA MESMA tx da leitura ----------

(deftest buscar-proposicao-detalhe-sem-votacao-aprovada-false
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X"})
        {:keys [proposicao]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (false? (:aprovada proposicao))
        "sem NENHUMA votacao encerrada 'aprovada' sobre a materia, o fato e' false")))

(deftest buscar-proposicao-detalhe-com-votacao-aprovada-true
  ;; mesma receita de votacao_eventos_repo_test.clj/encerrar-votacao-emite-encerrada-com-totais: 2 sim > 1
  ;; nao, base-membros 3, maioria_simples -> resultado 'aprovada'.
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X"})
        pid (:id r)
        vid (random-uuid)]
    (repo/abrir-votacao! *repo* ente {:id vid :objeto-tipo "proposicao" :objeto-id pid
                                      :modalidade "nominal" :quorum-tipo "maioria_simples"})
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "sim"})
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "sim"})
    (repo/registrar-voto! *repo* ente {:id (random-uuid) :votacao-id vid :vereador-id (random-uuid) :voto "nao"})
    (repo/encerrar-votacao! *repo* ente {:id vid :base-membros 3 :updated-by nil :lock-version 0})
    (let [{:keys [proposicao]} (repo/buscar-proposicao-detalhe *repo* ente pid)]
      (is (true? (:aprovada proposicao))
          "votacao encerrada com resultado 'aprovada' sobre a MESMA materia -> fato true"))))

(deftest criar-proposicao-via-controller-seta-ente-id
  ;; Bug 1 (review ecc clojure+database, task 12): `controllers/criar-proposicao` mesclava so' {:uf
  ;; :municipio-nome} do resolver de municipio no mapa `m`, NUNCA :ente-id — e' o unico dos 3 gates de
  ;; escrita (protocolar!/editar-proposicao!/mudar-estado-proposicao!) sem o assoc, entao o INSERT real ia
  ;; com ente_id NULL (viola o NOT NULL de legislativo.proposicoes) -> 500 em toda chamada real de POST
  ;; /legislativo/proposicoes. Os testes de HTTP nao pegavam porque usam Repo FAKE; os deste arquivo nao
  ;; pegavam porque chamam `Repo/protocolar!` DIRETO com um mapa ja' contendo :ente-id (bypass do controller
  ;; — exatamente a lacuna). Este teste vai pelo CONTROLLER (a camada onde o bug realmente vive) contra
  ;; Postgres real, provando ente_id persistido + a linha visivel via buscar-proposicao sob a RLS do tenant.
  (let [ente (random-uuid)
        id (random-uuid)
        m {:id id :tipo "projeto_lei" :ano 2026 :ementa "X" :created-by (random-uuid)}
        resolver-municipio (fn [_ente-id] {:uf "CE" :municipio-nome "Fortaleza"})
        ;; sem :autor-id em `m` -> validar-autor! nem chama esta fn (fix da review, achados I-1/M-1); a
        ;; constante so' documenta o contrato, nunca e' de fato invocada aqui.
        vereador-vinculado? (constantly true)]
    (controllers/criar-proposicao *repo* resolver-municipio vereador-vinculado? ente m)
    (let [p (repo/buscar-proposicao *repo* ente id)]
      (is (some? p) "a proposicao deve estar visivel sob a RLS do tenant `ente`")
      (is (= ente (:ente-id p))))))
