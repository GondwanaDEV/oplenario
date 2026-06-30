(ns oplenario.sessoes.gravacao-db-test
  "INTEGRACAO (PG real): §22.6 eixo D (F4.4b) — gravacao de sessao. Prova: `gravacao_segmento` como unidade
  TECNICA do arquivo de gravacao (nao regimental) — uma sessao tem 1 segmento tipico mas N possiveis (reinicio
  do OBS, divisao manual); alinhamento com a sessao = por INSTANTE. Vinculacao Opcao A (upload PRIMEIRO, servidor
  vincula depois) = mutacao uma-vez com CAS. Container bruto opaco; audio/video uri nullable (extracao = IA);
  acesso_restrito p/ sessao secreta. Estado de processamento e' EMERGENTE (sem coluna mutavel de status)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.gravacao :as gravacao]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.models.gravacao :as mod]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- agendar! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                            :tipo-sessao "ordinaria" :modalidade "presencial"})))

(def ^:private t0 (java.time.Instant/parse "2026-06-29T14:00:00Z"))
(def ^:private t1 (java.time.Instant/parse "2026-06-29T16:30:00Z"))

(defn- registrar! [tx ente extra]
  (gravacao/registrar-segmento!
   tx (merge {:id (random-uuid) :ente-id ente :iniciou-em t0 :encerrou-em t1
              :motivo-inicio "inicio_sessao" :motivo-fim "fim_sessao"
              :container-bruto-uri "s3://gravacoes/seg.mkv" :audio-hash "abc123"
              :fonte-ingestao "gravacao_local_pos_sessao"} extra)))

;; ---------- vocabularios (puros) ----------

(deftest vocabularios-gravacao
  (is (thrown? Exception (logic/validar-motivo-inicio "ligou_camera")) "motivo_inicio invalido lanca")
  (is (thrown? Exception (logic/validar-fonte-ingestao "pendrive")) "fonte_ingestao invalida lanca")
  (is (thrown? Exception (logic/validar-motivo-fim "acabou")) "motivo_fim invalido lanca")
  (is (nil? (logic/validar-motivo-fim nil)) "motivo_fim nil e' valido (gravacao ainda aberta)")
  (is (nil? (logic/validar-motivo-inicio "reinicio_pos_falha")) "reinicio pos falha e' motivo valido"))

;; ---------- registrar + buscar + model ----------

(deftest registrar-e-buscar
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {gid :id} (registrar! tx ente {:sessao-id sid :video-uri "s3://gravacoes/seg.mp4"})
              r (gravacao/buscar tx ente gid)]
          (is (= sid (:sessao-id r)) "segmento vinculado a sessao")
          (is (= "gravacao_local_pos_sessao" (:fonte-ingestao r)) "fonte de ingestao")
          (is (= "s3://gravacoes/seg.mkv" (:container-bruto-uri r)) "container bruto opaco")
          (is (= t0 (:iniciou-em r)) "instante de inicio (dominio)")
          (is (false? (:acesso-restrito r)) "default acesso_restrito false")
          (is (m/validate mod/GravacaoSegmento r) "bate o model"))))))

;; ---------- vinculacao Opcao A (upload primeiro, vincula depois) ----------

(deftest vincular-pos-upload
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {gid :id} (registrar! tx ente {})]                 ; SEM sessao-id (chegou do CLI/watch folder)
          (is (nil? (:sessao-id (gravacao/buscar tx ente gid))) "nasce sem vinculo (Opcao A)")
          (gravacao/vincular-segmento! tx {:ente-id ente :id gid :sessao-id sid :lock-version 0 :updated-by nil})
          (is (= sid (:sessao-id (gravacao/buscar tx ente gid))) "servidor vinculou a sessao")
          (is (thrown? Exception
                       (gravacao/vincular-segmento! tx {:ente-id ente :id gid :sessao-id (random-uuid)
                                                      :lock-version 1 :updated-by nil}))
              "re-vincular um segmento ja vinculado e' barrado (vinculo uma-vez)"))))))

;; ---------- multiplos segmentos por sessao (reinicio do OBS) ----------

(deftest multiplos-segmentos-por-sessao
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (registrar! tx ente {:sessao-id sid :iniciou-em t0
                               :encerrou-em (java.time.Instant/parse "2026-06-29T15:00:00Z")
                               :motivo-fim "falha_tecnica"})
          (registrar! tx ente {:sessao-id sid :iniciou-em (java.time.Instant/parse "2026-06-29T15:05:00Z")
                               :motivo-inicio "reinicio_pos_falha" :encerrou-em t1})
          (let [segs (gravacao/listar-segmentos-da-sessao tx ente sid)]
            (is (= 2 (count segs)) "uma sessao pode ter N segmentos (OBS reiniciou)")
            (is (= [t0 (java.time.Instant/parse "2026-06-29T15:05:00Z")] (mapv :iniciou-em segs))
                "listados em ordem cronologica de iniciou_em")))))))

;; ---------- coerencia + FK + sigilo ----------

(deftest encerrou-sem-motivo-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)]
          (is (thrown? Exception (registrar! tx ente {:sessao-id sid :encerrou-em t1 :motivo-fim nil}))
              "encerrou_em sem motivo_fim viola gravacao_encerrou_tem_motivo")
          (is (thrown? Exception (registrar! tx ente {:sessao-id sid :iniciou-em t1
                                                      :encerrou-em t0 :motivo-fim "fim_sessao"}))
              "encerrou_em < iniciou_em viola gravacao_marcos_ordem")
          (is (thrown? Exception (registrar! tx ente {:sessao-id sid :encerrou-em nil :motivo-fim "fim_sessao"}))
              "motivo_fim sem encerrou_em viola gravacao_motivo_fim_exige_encerramento (bicondicional)"))))))

(deftest vinculo-a-sessao-inexistente-barra
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? Exception (registrar! tx ente {:sessao-id (random-uuid)}))
            "registrar com sessao_id inexistente viola a FK same-schema")))))

(deftest acesso-restrito-sessao-secreta
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {gid :id} (registrar! tx ente {:sessao-id sid :acesso-restrito true})]
          (is (true? (:acesso-restrito (gravacao/buscar tx ente gid))) "gravacao de sessao secreta = restrita"))))))

;; ---------- re-derivacao do sigilo no vinculo Opcao A (sessao secreta) ----------

(deftest vincular-secreta-forca-acesso-restrito
  ;; sigilo §22.6: segmento INGERIDO sem vinculo com acesso-restrito=false (Opcao A); o RE-vinculo a uma sessao
  ;; SECRETA tem de elevar acesso_restrito=true (`forcar-acesso-restrito` do controlador) — o flag viaja ao
  ;; pipeline de IA respeitar o sigilo. Vinculo a sessao NAO-secreta deixa o flag intacto.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {gid :id} (registrar! tx ente {:acesso-restrito false})] ; SEM vinculo, ingerido aberto (Opcao A)
          (is (false? (:acesso-restrito (gravacao/buscar tx ente gid))) "nasce aberto (ingestao Opcao A)")
          (gravacao/vincular-segmento! tx {:ente-id ente :id gid :sessao-id sid :lock-version 0
                                           :updated-by nil :forcar-acesso-restrito true})
          (is (true? (:acesso-restrito (gravacao/buscar tx ente gid)))
              "vinculo a sessao secreta RE-deriva acesso_restrito=true (sigilo)")
          (is (= sid (:sessao-id (gravacao/buscar tx ente gid))) "vinculo efetivado na mesma operacao"))))))

(deftest vincular-nao-secreta-preserva-flag
  ;; sessao NAO-secreta: o vinculo nao deve tocar acesso_restrito (forcar-acesso-restrito falsy -> coluna intacta).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [sid (agendar! tx ente)
              {gid :id} (registrar! tx ente {:acesso-restrito true})] ; ingerido restrito por outra razao
          (gravacao/vincular-segmento! tx {:ente-id ente :id gid :sessao-id sid :lock-version 0
                                           :updated-by nil :forcar-acesso-restrito false})
          (is (true? (:acesso-restrito (gravacao/buscar tx ente gid)))
              "vinculo a sessao nao-secreta PRESERVA o acesso_restrito ja gravado (nao reabre o sigilo)"))))))
