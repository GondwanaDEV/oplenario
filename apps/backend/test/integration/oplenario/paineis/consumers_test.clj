(ns oplenario.paineis.consumers-test
  "INTEGRACAO (PG real) — drift-guard do modulo `paineis` INTEIRO (review clojure MEDIUM, F7 Slice 2: o
  mesmo guard vivia duplicado, byte-a-byte, em pendencia_test.clj E tramitacao_test.clj — `tipos-consumidos`
  e' MODULO-WIDE, nao por feature, entao o teste pertence aqui, UMA vez so'). Cada tipo em `tipos-consumidos`
  (o que o bus ENTREGA) DEVE ter um branch no `case` de `despachar!` (o que a projecao TRATA) — senao 'No
  matching clause' dentro da tx do relay COMPARTILHADO vira redrive eterno (head-of-line block de TODOS os
  modulos, nao so' paineis). Chama `despachar!` DIRETO (nao `projetar-evento!`, review security HIGH da F7
  Slice 1): `projetar-evento!` tolera QUALQUER excecao (incl. 'No matching clause'), entao testar por ele
  mascararia exatamente o drift que este guard existe p/ pegar — `despachar!` e' a fn SEM tolerancia, o alvo
  certo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as repo]
            [oplenario.paineis.diplomat.consumers :as consumers]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(deftest todo-tipo-consumido-tem-branch-de-projecao
  (let [ente (random-uuid)]
    (doseq [tipo consumers/tipos-consumidos]
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (try
            (repo/despachar! tx ente tipo {})
            (catch Throwable e
              (is (not (re-find #"No matching clause" (str (ex-message e))))
                  (str "tipo consumido sem branch de projecao (drift bus<->case): " tipo)))))))))
