(ns oplenario.kernel.idp-port-test
  "O port do IdP e' seam estavel (§22.5): quem consome o protocolo nao sabe se e' Keycloak ou dev."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.idp-dev :as idp-dev]))

(deftest convidar-existe-no-port
  (is (some? (resolve 'oplenario.kernel.components.idp/convidar!))
      "o port expoe convidar! (bootstrap de 1o acesso, §22.5.2 eixo F)"))

(deftest idp-dev-provisionamento-e-no-op-nao-lanca
  ;; Antes o idp-dev LANCAVA nestas operacoes e `POST /identidade/acessos` (que grava o vinculo ANTES de chamar o IdP)
  ;; devolvia 500 com o acesso ja' concedido. Dev nao tem realm, usuario nem e-mail: nao ha' o que fazer, e isso nao e'
  ;; falha. A falha de um IdP de verdade (KeycloakIdp) segue lancando — ver `conceder_acesso_dev_test`.
  (let [dev (idp-dev/idp-dev) ente (random-uuid) ident (random-uuid)]
    (is (idp/provisionar-realm! dev ente))
    (is (idp/provisionar-realm! dev ente {:nome "Camara de Teste"}))
    (is (= {:existia? false} (idp/criar-usuario! dev ente {:identidade-id ident :nome "Helena" :email "h@c.local"}))
        "a pessoa nunca teve conta no IdP de dev: o handler segue pelo ramo 'novo'")
    (is (idp/convidar! dev ente ident))
    (is (idp/corrigir-email-do-convite! dev ente ident "h@c.local"))))

(deftest apagar-realm-existe-no-port-e-o-dev-nao-tem-realm
  (is (some? (resolve 'oplenario.kernel.components.idp/apagar-realm!))
      "o port expoe o inverso do provisionamento (ADR-0018, apagamento da Casa encerrada)")
  (is (= {:realm nil :existia? false} (idp/apagar-realm! (idp-dev/idp-dev) (random-uuid)))
      "dev nao provisiona realm -> nada a apagar, idempotente"))
