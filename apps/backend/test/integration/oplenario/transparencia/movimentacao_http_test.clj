(ns oplenario.transparencia.movimentacao-http-test
  "INTEGRACAO (PG real + borda Pedestal) — a rota PUBLICA GET /portal/casa/:ente/materias/:proposicao_id/movimentacoes
  (\"Por onde a materia passou\"). Sem login. O que se prova na borda: o corpo e' so' data + etapa em palavras (chaves
  fechadas, nenhuma de quem despachou), a materia que o portal nao mostra (ou de outra Casa) e' 404 na mesma voz da
  ficha, id malformado e' 400, e a rota nasce coberta pelo 410 da Casa encerrada (a regra e' por prefixo do host).
  Semeadura pelo caminho real de projecao (Repo de legislativo -> outbox -> relay -> consumer do portal)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.restricao-da-casa :as restricao-casa]
            [oplenario.rotas :as rotas]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.diplomat.consumers :as consumers])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo-legislativo* nil)
(def ^:dynamic *repo-transparencia* nil)
(def ^:dynamic *registro-fatos* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))
          bus (outbox/bus)]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *repo-legislativo* (legislativo-repo/->RepoLegislativoPg c bus)
                *repo-transparencia* (transparencia-repo/->RepoTransparenciaPg c)
                *registro-fatos* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- service-fn []
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade nil :repo-transparencia *repo-transparencia*})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- GET [ente pid] (pt/response-for (service-fn) :get (str "/portal/casa/" ente "/materias/" pid "/movimentacoes")))
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))

(defn- materia-com-duas-etapas! [ente]
  (let [tid (tenancy/com-tenant* *ds* ente
              (fn [tx]
                (let [tid (random-uuid)]
                  (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_http" :versao 1 :nome "Rito [FIXTURE]"
                                            :estado-inicial "recebida"})
                  (doseq [[ch nome] [["recebida" "Recebida pela Mesa"] ["em_comissao" "Em análise na comissão"]]]
                    (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nome
                                            :terminal false}))
                  (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "recebida"
                                             :para-estado "em_comissao" :gatilho "despachar" :guarda nil :ordem 1})
                  tid)))
        pid (:id (legislativo-repo/protocolar! *repo-legislativo* ente
                   {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                    :ementa "Dispoe sobre a rota publica"}))]
    (drenar!)
    ;; QUEM despachou entra no historico do legislativo (ator-id) — e e' exatamente o que NAO pode sair no portal
    (legislativo-repo/transicionar! *repo-legislativo* ente *registro-fatos*
      {:proposicao-id pid :template-id tid :gatilho "despachar" :agora (LocalDate/of 2026 3 1)
       :ator-id (random-uuid)})
    (drenar!)
    pid))

(deftest a-rota-publica-devolve-so-data-e-etapa-em-palavras-sem-login
  (let [ente (random-uuid) pid (materia-com-duas-etapas! ente)
        r (GET ente pid) corpo (ler-json r)]
    (is (= 200 (:status r)) "sem Authorization: nunca 401")
    (is (= #{:movimentacoes :movimentacoes-total :historico-completo :historico-desde} (set (keys corpo))))
    (is (= ["Em análise na comissão" "Recebida pela Mesa"] (mapv :etapa (:movimentacoes corpo)))
        "o rotulo do rito, da mais recente para a mais antiga")
    (is (= 2 (:movimentacoes-total corpo)))
    (is (true? (:historico-completo corpo)))
    (is (= [false true] (mapv :abertura (:movimentacoes corpo))))
    (is (= #{:ocorrido-em :etapa :abertura} (set (mapcat keys (:movimentacoes corpo))))
        "nada de ator, gatilho, contexto ou parecer")
    (is (not (re-find #"(?i)ator|gatilho|despachar|em_comissao" (:body r)))
        "nem a chave de cadastro nem o gatilho vazam no corpo")))

(deftest materia-fora-do-portal-ou-de-outra-casa-e-404-e-id-ruim-e-400
  (let [a (random-uuid) b (random-uuid) pid (materia-com-duas-etapas! a)]
    (is (= 404 (:status (GET a (random-uuid)))) "materia que o portal nao mostra: 404, a voz da ficha")
    (is (= 404 (:status (GET b pid))) "a Casa B nao ve a linha do tempo da materia da Casa A")
    (is (= 400 (:status (GET a "nao-e-uuid"))))
    (is (= 400 (:status (GET "nao-e-uuid" pid))))))

(deftest a-rota-nasce-coberta-pela-casa-encerrada
  ;; a regra e' por prefixo (`com-casa-encerrada` roda sobre as rotas montadas): a pagina nova do portal ja' nasce com o 410
  (is (restricao-casa/rota-publica-da-casa? "/portal/casa/:ente/materias/:proposicao_id/movimentacoes")))
