(ns oplenario.reconciliar-anexos-integracao-test
  "INTEGRACAO (PG real + MinIO real, BUCKET proprio) — `reconciliar-anexos`. Semeia uma Casa com: um anexo integro, um blob
  orfao NOVO, um blob orfao VELHO, uma linha sem blob e uma linha RETIRADA (sem blob, de proposito) em
  `participacao.anexo`, mais um anexo integro e uma linha sem blob em `comunicacao.anexo`; e uma OUTRA Casa com um blob
  orfao velho. Prova: o relatorio exato; que `--ente` isola a Casa; que `--apagar-orfaos` apaga SO' o orfao velho do
  storage e nao toca em nenhuma linha do banco; o codigo de saida.

  O MinIO carimba a data do blob na hora do PUT (nao da' para recuar). O `agora` do teste e' entao o ponto MEDIO entre as
  datas dos dois orfaos + 24 h: o primeiro tem mais de 24 h e o segundo, menos."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.admin-sistema.components.repositorio :as repo-op]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.comunicacao.components.repositorio :as repo-com]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.reconciliar-anexos :as r])
  (:import (java.time Duration Instant)))

(def ^:dynamic *c* nil)
(def ^:dynamic *os* nil)

(use-fixtures :once
  (fn [t]
    (let [cfg (assoc-in (config/carregar) [:objeto-store :bucket] "oplenario-teste-reconc")
          c (component/start (datasource/datasource cfg))
          os (component/start (store/objeto-store cfg))]
      (migracao/migrar! (:ds c))
      (binding [*c* c *os* os] (try (t) (finally (component/stop os) (component/stop c)))))))

(defn- bytes-de [s] (.getBytes ^String s "UTF-8"))
(defn- guardar! [chave] (store/guardar! *os* chave (bytes-de "x") "application/octet-stream"))
(defn- existe? [chave] (some? (store/obter *os* chave)))
(def ^:private sha (apply str (repeat 64 "a")))

(defn- registrar-casa! []
  (let [rp (assoc (repo-op/repositorio) :datasource *c*)
        op (repo-op/criar-operador! rp {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev") :nome "Op"})
        ente (random-uuid)]
    (repo-op/registrar-casa! rp {:ente-id ente :nome "Camara de Teste" :uf "CE" :municipio-ibge "2304400"
                                 :municipio-nome "Fortaleza"}
                             {:operador-id (:id op)})
    (try (referencia/inserir-municipio! (:ds *c*) {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 1})
         (catch Exception _ nil))
    ente))

(defn- sql! [ente sql & params]
  (tenancy/com-tenant* (:ds *c*) ente #(jdbc/execute! % (into [sql] params) {:builder-fn rs/as-unqualified-maps})))

(defn- linha-participacao!
  "INSERT em participacao.anexo (a tabela e' polimorfica, sem FK para o protocolo). Devolve a chave."
  [ente protocolo id]
  (let [chave (str "atendimento/" ente "/" protocolo "/" id)]
    (sql! ente "INSERT INTO participacao.anexo (ente_id, id, objeto_tipo, objeto_id, origem, nome, tipo_midia, bytes, sha256,
                                               chave_objeto, enviado_por)
                VALUES (?::uuid, ?::uuid, 'pedido_esic', ?::uuid, 'casa', 'a.pdf', 'application/pdf', 1, ?, ?, ?::uuid)"
          (str ente) (str id) (str protocolo) sha chave (str (random-uuid)))
    chave))

(defn- comunicado! [ente]
  (let [id (random-uuid)]
    (sql! ente "INSERT INTO comunicacao.comunicado (ente_id, id, ano, numero, protocolo, remetente_identidade_id, remetente_nome,
                                                    assunto, corpo)
                VALUES (?::uuid, ?::uuid, 2026, 1, 'COM-2026-000001', ?::uuid, 'Secretaria', 'Aviso', 'Texto')"
          (str ente) (str id) (str (random-uuid)))
    id))

(defn- linha-comunicacao! [ente comunicado id]
  (let [chave (str "comunicados/" ente "/" comunicado "/" id)]
    (sql! ente "INSERT INTO comunicacao.anexo (ente_id, id, comunicado_id, nome, tipo_midia, bytes, sha256, chave_objeto)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'b.pdf', 'application/pdf', 1, ?, ?)"
          (str ente) (str id) (str comunicado) sha chave)
    chave))

(defn- contar-linhas [ente]
  (mapv (fn [t] (:n (first (sql! ente (str "SELECT count(*) AS n FROM " t)))))
        ["participacao.anexo" "participacao.anexo_retirada" "comunicacao.anexo"]))

(defn- deps [agora]
  (let [rp (assoc (repo-op/repositorio) :datasource *c*)]
    {:repo-admin rp :objeto-store *os* :agora agora
     :fontes (r/fontes {:repo-participacao (assoc (repo-part/repositorio) :datasource *c*)
                        :repo-comunicacao (assoc (repo-com/repositorio) :datasource *c*)})}))

(defn- sem-datas [pasta] (update pasta :blob-sem-linha (fn [l] (mapv #(do (is (instance? Instant (:modificado-em %))) (dissoc % :modificado-em)) l))))

(deftest reconcilia-o-banco-com-o-storage-e-so-apaga-o-orfao-velho
  (let [ente (registrar-casa!) outra (registrar-casa!)
        p1 (random-uuid) p2 (random-uuid)
        ;; participacao: integro, retirado, linha sem blob
        integro (linha-participacao! ente p1 (random-uuid))
        retirado (linha-participacao! ente p1 (random-uuid))
        sem-blob (linha-participacao! ente p2 (random-uuid))
        _ (guardar! integro)
        _ (let [anexo-id (second (re-find #"/([^/]+)$" retirado))]
            (sql! ente "INSERT INTO participacao.anexo_retirada (ente_id, anexo_id, retirado_por, motivo)
                        VALUES (?::uuid, ?::uuid, ?::uuid, 'documento no protocolo errado')"
                  (str ente) anexo-id (str (random-uuid))))
        ;; comunicacao: integro + linha sem blob
        com (comunicado! ente)
        com-integro (linha-comunicacao! ente com (random-uuid))
        com-sem-blob (linha-comunicacao! ente com (random-uuid))
        _ (guardar! com-integro)
        ;; os dois orfaos de participacao, com 1,2 s de distancia entre os PUTs; e o orfao velho da OUTRA Casa
        orfao-velho (str "atendimento/" ente "/" p1 "/orfao-velho")
        orfao-novo (str "atendimento/" ente "/" p1 "/orfao-novo")
        de-outra (str "atendimento/" outra "/" p1 "/orfao-da-outra")
        _ (do (guardar! orfao-velho) (guardar! de-outra) (Thread/sleep 1200) (guardar! orfao-novo))
        datas (into {} (map (juxt :chave :modificado-em)) (store/listar-objetos *os* (str "atendimento/" ente "/" p1 "/orfao-")))
        corte (.plusMillis ^Instant (get datas orfao-velho)
                           (quot (.toMillis (Duration/between (get datas orfao-velho) (get datas orfao-novo))) 2))
        agora (.plus ^Instant corte (Duration/ofHours 24))
        linhas-antes (contar-linhas ente)]
    (try
      (testing "so' relatar (Casa unica): o relatorio exato, o codigo 1 e nada apagado"
        (let [res (r/reconciliar! (deps agora) ente false)
              [casa] (:casas res)
              [atend com-pasta] (map sem-datas (:pastas casa))]
          (is (= 1 (count (:casas res))))
          (is (= {:pasta "atendimento" :linhas 3 :retiradas 1 :blobs 3
                  :blob-sem-linha [{:chave orfao-novo :motivo :sem-linha :apagavel? false}
                                   {:chave orfao-velho :motivo :sem-linha :apagavel? true}]
                  :linha-sem-blob [sem-blob]}
                 atend)
              "3 blobs: o integro e os 2 orfaos; o retirado sem blob nao e' divergencia")
          (is (= {:pasta "comunicados" :linhas 2 :retiradas 0 :blobs 1 :blob-sem-linha [] :linha-sem-blob [com-sem-blob]}
                 com-pasta))
          (is (= 1 (r/codigo-de-saida (:casas res))))
          (is (every? existe? [integro orfao-velho orfao-novo de-outra com-integro]) "nada foi apagado")))
      (testing "o texto do relatorio traz as listas e o resultado"
        (let [{:keys [saida codigo]} (r/executar (deps agora) ["--ente" (str ente)])]
          (is (= 1 codigo))
          (is (str/includes? saida (str "Casa " ente)))
          (is (str/includes? saida "blob sem linha: 2"))
          (is (str/includes? saida "linha sem blob: 1"))
          (is (str/includes? saida orfao-velho))
          (is (not (str/includes? saida de-outra)) "--ente isola: a outra Casa nem aparece")
          (is (str/includes? saida "DIVERGENTE"))))
      (testing "sem --ente, as duas Casas entram, cada uma com o que e' dela"
        (let [res (r/reconciliar! (deps agora) nil false)
              por-ente (into {} (map (juxt :ente-id identity)) (:casas res))]
          (is (contains? por-ente ente))
          (is (= [de-outra] (map :chave (:blob-sem-linha (first (:pastas (por-ente outra)))))))
          (is (not-any? #(str/includes? (:chave %) (str outra))
                        (mapcat :blob-sem-linha (:pastas (por-ente ente)))))))
      (testing "--apagar-orfaos: apaga SO' o orfao velho desta Casa; o recente, o integro, a outra Casa e o banco ficam"
        (let [res (r/reconciliar! (deps agora) ente true)
              [casa] (:casas res)
              {:keys [saida codigo]} (r/executar (deps agora) ["--ente" (str ente) "--apagar-orfaos"])]
          (is (= [orfao-velho] (:apagados casa)))
          (is (not (existe? orfao-velho)) "o orfao velho saiu do storage")
          (is (every? existe? [integro orfao-novo de-outra com-integro]))
          (is (= linhas-antes (contar-linhas ente)) "nenhuma linha do banco mudou")
          (is (= 1 codigo) "sobram o orfao recente e as duas linhas sem blob")
          (is (str/includes? saida "apagados agora: 0") "a 2a execucao nao tem mais o que apagar")))
      (testing "reconciliacao depois de limpa: so' o que sobra"
        (let [[casa] (:casas (r/reconciliar! (deps agora) ente false))
              [atend] (map sem-datas (:pastas casa))]
          (is (= [orfao-novo] (map :chave (:blob-sem-linha atend))))))
      (finally
        (doseq [k [integro orfao-velho orfao-novo de-outra com-integro]] (store/remover! *os* k))))))
