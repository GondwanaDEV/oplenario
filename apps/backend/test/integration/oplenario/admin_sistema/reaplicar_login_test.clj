(ns oplenario.admin-sistema.reaplicar-login-test
  "INTEGRACAO (PG real, IdP das Casas fake): `reaplicar-login!` (ADR-0025), o passo de producao que liga a configuracao
  de login nos realms que ja' existiam — o botao do console pela linha de comando, Casa a Casa, com o PAR na atuacao
  (ADR-0017, adendo de 05/10/2026)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.controllers :as controllers]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- idp-fake
  "Registra cada realm provisionado; `falhar` = conjunto de ente-ids cujo Keycloak responde erro."
  [chamadas falhar]
  (reify idp/IdentityProvider
    (verificar-token [_ _] nil)
    (provisionar-realm! [_ ente] (swap! chamadas conj [ente nil]) {:realm (str "ente-" ente)})
    (provisionar-realm! [_ ente opcoes]
      (when (contains? falhar ente) (throw (ex-info "keycloak-idp: falha ao configurar o SMTP do realm (infra)\nlinha 2" {})))
      (swap! chamadas conj [ente opcoes])
      {:realm (str "ente-" ente)})
    (criar-usuario! [_ _ _] {})
    (convidar! [_ _ _] true)
    (resetar-mfa! [_ _ _] nil)))

(defn- casa! [nome]
  (let [ente (random-uuid)]
    (repo/registrar-casa! (repo-op) {:ente-id ente :nome nome :nome-curto nome :uf "CE" :municipio-ibge "2302008"
                                     :municipio-nome "Baturité" :primeiro-admin-email "admin@camara.ce.gov.br"}
                          {:operador-id nil})
    ente))

(defn- atuacao [ente] (reverse (repo/atuacao-do-ente (repo-op) ente 20)))

(defn- acoes [ente] (mapv :acao (atuacao ente)))

(deftest reaplica-com-o-nome-da-casa-e-deixa-o-par-na-atuacao
  (let [ente (casa! "Câmara Municipal de Aracati")
        chamadas (atom [])
        [r :as rs] (controllers/reaplicar-login! (repo-op) {:idp-casa (idp-fake chamadas #{})} {:ente-id ente})]
    (is (= 1 (count rs)))
    (is (= {:ente-id ente :nome "Câmara Municipal de Aracati" :resultado :reaplicado} r))
    (is (= [[ente {:nome "Câmara Municipal de Aracati"}]] @chamadas) "o nome da Casa vai para o realm")
    (let [[_ tentativa desfecho] (atuacao ente)]
      (is (= ["casa-provisionada" "realm-reprovisionamento-iniciado" "realm-reprovisionado"] (acoes ente)))
      (is (nil? (:operador-id tentativa)) "linha de comando: sem pessoa")
      (is (= "linha-de-comando" (get-in tentativa [:detalhe :origem])))
      (is (= (str (:id tentativa)) (get-in desfecho [:detalhe :tentativa])) "o desfecho aponta a tentativa")
      (is (= "realm-reprovisionado" (:acao desfecho)) "o desfecho e' o mesmo do botao do console"))))

(deftest a-casa-que-falha-nao-para-as-outras-e-o-desfecho-diz-por-que
  (let [quebrada (casa! "Câmara Municipal de Icó")
        boa (casa! "Câmara Municipal de Iguatu")
        chamadas (atom [])
        deps {:idp-casa (idp-fake chamadas #{quebrada})}
        rs (into {} (map (juxt :ente-id identity)) (controllers/reaplicar-login! (repo-op) deps {}))]
    (testing "sem --ente: todas as Casas do registro"
      (is (= :falhou (:resultado (rs quebrada))))
      (is (= "keycloak-idp: falha ao configurar o SMTP do realm (infra)" (:motivo (rs quebrada)))
          "so' a primeira linha do erro")
      (is (= :reaplicado (:resultado (rs boa)))))
    (is (= ["casa-provisionada" "realm-reprovisionamento-iniciado" "realm-reprovisionamento-falhou"] (acoes quebrada)))
    (let [[_ tentativa desfecho] (atuacao quebrada)]
      (is (= (str (:id tentativa)) (get-in desfecho [:detalhe :tentativa])))
      (is (= "keycloak-idp: falha ao configurar o SMTP do realm (infra)" (get-in desfecho [:detalhe :motivo]))))
    (is (empty? (filter #(= (str (:id (second (atuacao quebrada)))) (str (:id %)))
                        (repo/tentativas-sem-desfecho (repo-op) (java.time.Instant/now))))
        "a tentativa que falhou tem desfecho: a conferencia nao a acusa")))

(deftest casa-encerrada-ou-em-apagamento-fica-de-fora
  ;; o realm dela foi (ou esta' sendo) apagado: provisionar de novo o RECRIARIA
  (let [encerrada (casa! "Câmara Municipal de Crato")
        apagando (casa! "Câmara Municipal de Sobral")
        chamadas (atom [])
        deps {:idp-casa (idp-fake chamadas #{})}]
    (jdbc/execute! *ds* ["UPDATE admin_sistema.ente SET estado = 'encerrado', encerrada_em = now() WHERE ente_id = ?"
                         encerrada])
    (jdbc/execute! *ds* ["UPDATE admin_sistema.ente SET apagamento_iniciado_em = now() WHERE ente_id = ?" apagando])
    (let [rs (into {} (map (juxt :ente-id identity)) (controllers/reaplicar-login! (repo-op) deps {}))]
      (is (= {:resultado :pulada :motivo "encerrada"} (select-keys (rs encerrada) [:resultado :motivo])))
      (is (= {:resultado :pulada :motivo "apagamento iniciado"} (select-keys (rs apagando) [:resultado :motivo]))))
    (is (not-any? #{encerrada apagando} (map first @chamadas)) "o Keycloak nao e' tocado")
    (is (= ["casa-provisionada"] (acoes apagando)) "nada a registrar quando nada foi feito")))

(deftest ente-fora-do-registro-e-404
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Casa nao encontrada"
                        (controllers/reaplicar-login! (repo-op) {:idp-casa (idp-fake (atom []) #{})}
                                                      {:ente-id (random-uuid)}))))
