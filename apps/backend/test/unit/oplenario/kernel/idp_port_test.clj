(ns oplenario.kernel.idp-port-test
  "O port do IdP e' seam estavel (§22.5): quem consome o protocolo nao sabe se e' Keycloak ou dev."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.idp-dev :as idp-dev]))

(deftest convidar-existe-no-port
  (is (some? (resolve 'oplenario.kernel.components.idp/convidar!))
      "o port expoe convidar! (bootstrap de 1o acesso, §22.5.2 eixo F)"))

(deftest idp-dev-recusa-convidar
  (is (thrown? Exception (idp/convidar! (idp-dev/idp-dev) (random-uuid) (random-uuid)))
      "idp-dev nao provisiona nem envia e-mail — lanca, como as outras 3 ops de provisionamento"))
