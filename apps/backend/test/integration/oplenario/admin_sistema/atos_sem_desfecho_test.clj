(ns oplenario.admin-sistema.atos-sem-desfecho-test
  "INTEGRACAO (PG real): o console do operador VE os atos da Operacao iniciados cujo desfecho nao foi registrado
  (ADR-0017, adendo de 05/10/2026; `GET /operacao/atos-sem-desfecho`). So' o operador le (sem sessao: 401; credencial de
  Casa: 401). A tentativa com desfecho — inclusive o desfecho `falhou` — nao e' acusada; sem desfecho e antiga, e'; sem
  desfecho e recente (dentro da tolerancia), ainda pode estar em curso e nao e'. O total sai sempre e a lista que passa
  do teto diz `truncado`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.adapters.out.ente :as out-ente]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.controllers :as controllers]
            [oplenario.admin-sistema.diplomat.http.in :as admin-sistema-http]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao])
  (:import (java.time Duration Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- servico
  "`agora` = o relogio do servico: adiantá-lo faz a tentativa de agora ficar 'antiga' sem dormir no teste."
  [^Instant agora]
  (-> (http/servico (config/carregar)
                    (admin-sistema-http/rotas {:idp-operacao (idp-admin/idp-operacao-dev) :repo-admin-sistema (repo-op)
                                               :relogio (tempo/relogio-fixo agora)
                                               :operacao {:realm "operacao" :client-id "oplenario-console"
                                                          :sessao {:absoluta-h 8 :ociosa-min 15}}
                                               :deps-registro {}})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- operador!
  "Nome unico: o banco do teste e' compartilhado e a lista se filtra pelo nome do operador."
  [nome]
  (repo/criar-operador! (repo-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev")
                                   :nome (str nome " " (subs (str (random-uuid)) 0 8))}))

(defn- como [o] {"authorization" (str "Bearer " (json/write-value-as-string {:operador-id (str (:id o))}))})

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- casa! [nome]
  (let [ente (random-uuid)]
    (repo/registrar-casa! (repo-op) {:ente-id ente :nome nome :uf "CE" :municipio-ibge "2304400" :municipio-nome "Fortaleza"}
                          {:operador-id nil})
    ente))

(defn- gravar! [acao & {:keys [operador ente detalhe]}]
  (repo/registrar-atuacao! (repo-op) {:operador-id (some-> operador :id) :ente-id ente :acao acao :detalhe detalhe}))

(defn- mais-tarde [min] (.plus (Instant/now) (Duration/ofMinutes min)))

(defn- meus
  "Os atos da lista que sao desta Casa ou deste operador (o banco do teste e' compartilhado com outros testes)."
  [corpo {:keys [ente operador]}]
  (filterv #(or (and ente (= (str ente) (:ente-id %)))
                (and operador (= (:nome operador) (:operador %))))
           (:atos corpo)))

(deftest so-o-operador-le
  (let [svc (servico (Instant/now))]
    (is (= 401 (:status (pt/response-for svc :get "/operacao/atos-sem-desfecho"))) "sem sessao do console: 401")
    (is (= 401 (:status (pt/response-for svc :get "/operacao/atos-sem-desfecho"
                                         :headers {"authorization" "Bearer tok-de-casa"})))
        "credencial de Casa nao abre o console")))

(deftest tentativa-sem-desfecho-e-antiga-aparece-com-os-nomes
  (let [o (operador! "Rafaela Operação")
        ente (casa! "Câmara Municipal de Baturité")
        t-ia (gravar! "ia-orcamento-iniciado" :ente ente :detalhe {:origem "linha-de-comando"})
        t-entrada (gravar! "entrada-no-console-iniciada" :operador o)
        svc (servico (mais-tarde 10))
        r (pt/response-for svc :get "/operacao/atos-sem-desfecho" :headers (como o))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= 120 (:tolerancia-segundos b)) "a tolerancia vem na resposta: a tela diz quanto esperou")
    (testing "o ato do comando: Casa pelo NOME, sem operador, origem em palavras do dominio"
      (let [[a :as achados] (meus b {:ente ente})]
        (is (= 1 (count achados)))
        (is (= {:id (str (:id t-ia)) :acao "ia-orcamento-iniciado" :operador nil :origem "linha-de-comando"
                :ente-id (str ente) :casa-nome "Câmara Municipal de Baturité"}
               (dissoc a :em)))
        (is (string? (:em a)))))
    (testing "a entrada no console de quem nem chegou a ter sessao ENTRA: e' o caso que a conferencia existe para acusar"
      (let [[a :as achados] (meus b {:operador o})]
        (is (= 1 (count achados)))
        (is (= {:id (str (:id t-entrada)) :acao "entrada-no-console-iniciada" :operador (:nome o) :origem nil
                :ente-id nil :casa-nome nil}
               (dissoc a :em)))))
    (testing "total e truncado batem com a lista"
      (is (>= (:total b) 2))
      (is (= (:truncado b) (> (:total b) (count (:atos b))))))))

(deftest tentativa-com-desfecho-nao-aparece-nem-quando-o-desfecho-e-falhou
  (let [o (operador! "Rafaela Operação")
        ente (casa! "Câmara Municipal de Sobral")
        t1 (gravar! "ia-orcamento-iniciado" :ente ente)
        _ (gravar! "ia-orcamento-definido" :ente ente :detalhe {:tentativa (str (:id t1))})
        t2 (gravar! "realm-reprovisionamento-iniciado" :ente ente)
        _ (gravar! "realm-reprovisionamento-falhou" :ente ente :detalhe {:tentativa (str (:id t2)) :motivo "keycloak fora"})
        t3 (gravar! "entrada-no-console-iniciada" :operador o)
        _ (gravar! "entrada-no-console-falhou" :operador o :detalhe {:tentativa (str (:id t3))})
        b (ler (pt/response-for (servico (mais-tarde 10)) :get "/operacao/atos-sem-desfecho" :headers (como o)))]
    (is (empty? (meus b {:ente ente :operador o}))
        "par fechado (definido, reprovisionado/falhou, entrou/falhou) nao e' 'sem desfecho'")))

(deftest tentativa-recente-ainda-pode-estar-em-curso
  (let [o (operador! "Rafaela Operação")
        ente (casa! "Câmara Municipal de Crato")
        _ (gravar! "ia-orcamento-iniciado" :ente ente)
        agora (Instant/now)]
    (testing "dentro da tolerancia: nao e' acusada"
      (is (empty? (meus (ler (pt/response-for (servico agora) :get "/operacao/atos-sem-desfecho" :headers (como o)))
                        {:ente ente}))))
    (testing "passada a tolerancia: e' acusada"
      (is (= 1 (count (meus (ler (pt/response-for (servico (.plusSeconds agora 125)) :get "/operacao/atos-sem-desfecho"
                                                  :headers (como o)))
                            {:ente ente})))))))

(deftest o-total-sai-inteiro-e-a-lista-truncada-diz-que-truncou
  (let [ente (casa! "Câmara Municipal de Iguatu")
        _ (dotimes [_ 3] (gravar! "ia-orcamento-iniciado" :ente ente))
        agora (mais-tarde 10)]
    (testing "teto pequeno de proposito: o total conta tudo, a lista traz so' o teto, e as mais recentes"
      (let [r (controllers/atos-sem-desfecho (repo-op) agora 2)
            w (out-ente/sem-desfecho->wire r)]
        (is (= 2 (:limite w) (count (:atos w))))
        (is (>= (:total w) 3) "o total conta todas, nao so' o que a lista traz")
        (is (true? (:truncado w)) "o teto nunca corta em silencio")
        (is (= (str ente) (:ente-id (first (:atos w)))) "a mais recente primeiro")))
    (testing "o teto da rota e' o do dominio, e a resposta o diz"
      (let [b (ler (pt/response-for (servico agora) :get "/operacao/atos-sem-desfecho"
                                    :headers (como (operador! "Rafaela Operação"))))]
        (is (= controllers/limite-sem-desfecho (:limite b)))
        (is (<= (count (:atos b)) controllers/limite-sem-desfecho))))))

(deftest sem-nada-a-conferir-a-lista-vem-vazia-e-nao-truncada
  (let [o (operador! "Rafaela Operação")
        r (pt/response-for (servico (.minus (Instant/now) (Duration/ofDays 3650))) :get "/operacao/atos-sem-desfecho"
                           :headers (como o))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= {:total 0 :truncado false :atos []} (select-keys b [:total :truncado :atos]))
        "nenhuma tentativa e' anterior a 10 anos atras: 0, nao truncado, e a lista e' [] (nao ausente)")))
