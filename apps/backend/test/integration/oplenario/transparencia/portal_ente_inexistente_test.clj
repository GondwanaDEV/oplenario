(ns oplenario.transparencia.portal-ente-inexistente-test
  "Achado B do teste exploratorio contra a homologacao (metodo docs/20, M4 -> M2 pela regra de ouro).

  O DEFEITO era de COERENCIA na borda publica: para o MESMO id de Casa inexistente,
  `GET /portal/casa/:ente` devolvia 404 {:erro \"ente nao encontrado\"} (a rota-pai sabe que a Casa nao
  existe), mas as rotas-FILHAS de colecao devolviam 200 com lista vazia:
      /portal/casa/<inexistente>/materias   -> 200 {:materias [] :materias-total 0}
      /portal/casa/<inexistente>/legislacao -> 200 {:normas [] :normas-total 0}
  Reproduzido ao vivo contra a homologacao. Duas consequencias: (1) um cliente nao consegue distinguir
  \"Casa existe e nao publicou nada\" de \"Casa nao existe\"; (2) foi esse 200 que sustentou a capa do
  portal renderizando o Portal do Cidadao inteiro para um ente inexistente (achado A, conserto irmao).

  DB-free de proposito: no caminho do 404 o handler decide ANTES de tocar o repositorio, entao
  `repo-transparencia` nunca e' chamado e pode ficar nil — o teste prova a BORDA, nao a consulta.
  O caminho feliz (Casa que existe -> 200, inclusive com lista vazia) segue provado em
  e2e/fluxo_portal_test.clj, que monta as mesmas rotas com um `info-ente` que afirma existencia."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(defn- service-fn [info-ente]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade nil
                                   :repo-transparencia nil
                                   :info-ente info-ente})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest colecoes-publicas-404-quando-a-casa-nao-existe
  (let [svc  (service-fn (constantly nil))          ; nenhuma Casa existe
        ente (random-uuid)]
    (doseq [[rotulo caminho] [["materias"   (str "/portal/casa/" ente "/materias")]
                              ["legislacao" (str "/portal/casa/" ente "/legislacao")]]]
      (testing (str "GET /portal/casa/:ente/" rotulo " com Casa inexistente")
        (let [r (pt/response-for svc :get caminho)]
          (is (= 404 (:status r))
              (str rotulo ": colecao de uma Casa que nao existe nao pode responder 200 com lista vazia"))
          (is (= "ente nao encontrado" (:erro (ler-json r)))
              (str rotulo ": mesma mensagem da rota-pai — a borda fala com UMA voz")))))))

(deftest a-mesma-casa-inexistente-404-na-rota-pai-e-nas-filhas
  ;; O CORACAO do achado: nao basta cada rota estar \"certa sozinha\"; o id que nao existe tem que produzir
  ;; o MESMO veredito nas tres. Era exatamente essa divergencia (404 no pai, 200 nas filhas) o defeito.
  (let [svc  (service-fn (constantly nil))
        ente (random-uuid)
        st   (fn [c] (:status (pt/response-for svc :get c)))]
    (is (= [404 404 404]
           [(st (str "/portal/casa/" ente))
            (st (str "/portal/casa/" ente "/materias"))
            (st (str "/portal/casa/" ente "/legislacao"))])
        "pai e filhas coerentes para o mesmo id inexistente")))

(deftest colecoes-publicas-seguem-sem-exigir-auth
  ;; O gate novo e' de EXISTENCIA, nunca de autenticacao: o portal e' publico por lei. Sem Authorization,
  ;; nunca 401 — nem quando a Casa nao existe.
  (let [svc (service-fn (constantly nil))
        r   (pt/response-for svc :get (str "/portal/casa/" (random-uuid) "/materias"))]
    (is (not= 401 (:status r)))))
