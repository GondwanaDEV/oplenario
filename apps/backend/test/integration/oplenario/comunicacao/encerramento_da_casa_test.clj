(ns oplenario.comunicacao.encerramento-da-casa-test
  "INTEGRACAO (PG real): ADR-0020 x ADR-0018 — os comunicados e os setores entram SOZINHOS na exportacao e no apagamento
  da Casa: o inventario (descoberto do catalogo, nunca uma lista a mao) acha as tabelas novas, todas isoladas por RLS
  (exportadas), na ordem da FK (marca antes da lista, a lista antes do comunicado); e o anexo segue a convencao
  `comunicados/<ente>/...` que o apagamento varre."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.comunicacao.logic :as logic]
            [oplenario.encerramento.arquivos :as arquivos]
            [oplenario.encerramento.inventario :as inventario]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(deftest as-tabelas-novas-estao-no-inventario
  (let [inv (into {} (map (juxt inventario/nome identity)) (inventario/inventario *ds*))
        novas ["comunicacao.comunicado" "comunicacao.destino" "comunicacao.destinatario" "comunicacao.marca"
               "comunicacao.anexo" "cadastros.setor" "cadastros.setor_membro"]]
    (doseq [t novas]
      (is (contains? inv t) t)
      (is (true? (:exporta? (inv t))) (str t " e' isolada por RLS: vai na exportacao")))
    (is (< (:nivel (inv "comunicacao.marca")) (:nivel (inv "comunicacao.destinatario")) (:nivel (inv "comunicacao.comunicado")))
        "apaga a marca antes da lista, a lista antes do comunicado")
    (is (< (:nivel (inv "cadastros.setor_membro")) (:nivel (inv "cadastros.setor"))))))

(deftest o-anexo-segue-a-convencao-do-apagamento
  (let [ente (random-uuid)
        chave (logic/chave-do-anexo ente (random-uuid) (random-uuid))]
    (is (arquivos/da-convencao? ente chave))
    (is (not (arquivos/da-convencao? (random-uuid) chave)) "nunca o blob de outra Casa")))
