(ns oplenario.legislativo.gerar-artefato-publicacao-test
  "INTEGRACAO (PG + MinIO real) — F6c Slice 4a: a ORQUESTRACAO do Repo `gerar-artefato-publicacao!` (doc-mestre
  L287): resolve a norma publicada + o texto legal integral -> renderiza (puro) -> serializa (port) -> assina
  (port ICP STUB) -> hash -> grava o binario no objeto_store -> insere `artefato_publicacao` VERSIONADO (MAX+1
  atomico). Ports/objeto_store entram POR CHAMADA (precedente de gerar-remessa!). Layout fisico/ICP = [GAP]."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.legislativo.components.assinador-icp :as assinador]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.components.serializador-publicacao :as ser]
            [oplenario.legislativo.db.autografo :as autografo]
            [oplenario.legislativo.db.norma :as norma]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.tramitacao-executiva :as exec]
            [oplenario.legislativo.models.artefato-publicacao :as mod]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema])
  (:import (java.time LocalDate)))

(def ^:dynamic *sys* nil)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))]
      (migracao/migrar! (:ds (:datasource s)))
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(def ^:private corpo-legal
  "Art. 1o Esta Lei dispoe sobre X.\nArt. 2o Entra em vigor na data de sua publicacao.")

;; leva uma proposicao ate' a norma PROMULGADA com TEXTO LEGAL real. `publicar?` decide se tambem publica.
;; `texto-attrs` (default inline) alimenta texto/nova-versao! — {:texto-inline ...} OU {:conteudo-uri ...}
;; (XOR do texto_versao). Devolve o norma-id. Tudo numa UNICA tx do tenant (via Repo/transacao).
(defn- preparar-norma!
  ([repo ente publicar?] (preparar-norma! repo ente publicar? {:texto-inline corpo-legal}))
  ([repo ente publicar? texto-attrs]
   (repo-leg/transacao repo ente
     (fn [tx]
       (let [pid (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                            :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"}))
             tvid (random-uuid)
             _ (texto/nova-versao! tx (merge {:id tvid :ente-id ente :proposicao-id pid
                                              :origem-versao "protocolo"} texto-attrs))
             {aid :id} (autografo/gerar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :ano 2026
                                             :texto-versao-id tvid :destinatario-texto "Prefeito"})
             {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})
             _ (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancionado" :updated-by nil :lock-version 0})
             {nid :id} (norma/promulgar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid :autografo-id aid
                                             :tipo-norma "lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                                             :data-promulgacao (LocalDate/of 2026 6 28) :ementa "Dispoe sobre X"
                                             :texto-versao-id tvid})]
         (when publicar?
           (norma/publicar! tx {:id nid :ente-id ente :veiculo-publicacao "Diario Oficial do Municipio"
                                :updated-by nil :lock-version 0}))
         nid)))))

(defn- m-base [norma-id]
  {:norma-id norma-id
   :serializador (ser/serializador-fixture)
   :assinador (assinador/assinador-stub)
   :objeto-store (:objeto-store *sys*)})

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! (:ds (:datasource *sys*))
    ["SELECT payload::text AS payload FROM shared.outbox WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

(defn- parse-payload [s]
  (json/read-value s (json/object-mapper {:decode-key-fn keyword})))

(deftest gera-artefato-e-binario-assinado
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente true)
        r (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid))]
    (is (= 1 (:versao r)) "primeira versao")
    (is (= nid (:norma-id r)))
    (is (str/starts-with? (:hash r) "sha256:") "carimba o hash do binario")
    (is (= "STUB-ICP-v0" (:assinatura-algoritmo r)) "assinatura STUB marcada honestamente")
    (is (string? (:assinatura-b64 r)))
    (is (m/validate mod/ArtefatoPublicacao r) "bate o model")
    (let [conteudo (os/obter (:objeto-store *sys*) (:objeto-store-ref r))]
      (is (some? conteudo) "o binario esta no objeto_store sob o ref carimbado")
      (let [txt (String. ^bytes conteudo "UTF-8")]
        (is (str/includes? txt "LEI N. ") "cabecalho do ato")
        (is (str/includes? txt "Art. 1o Esta Lei dispoe sobre X") "o texto legal integral")
        (is (str/includes? txt "Diario Oficial do Municipio") "o veiculo de publicacao")))))

;; ---------- Slice 4b: emissao de `artefato.publicacao.gerado` DENTRO da tx do INSERT (§22.9 E2). Espelha
;;            publicar-norma! -> norma.publicada: o evento so' existe se a tx do INSERT commitou. E' o que
;;            a EXIBICAO (transparencia projeta + rota publica de download) consome. ----------

(deftest emite-artefato-publicacao-gerado-na-tx-do-insert
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente true)
        r (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid))
        evs (eventos-por-tipo ente "artefato.publicacao.gerado")]
    (is (= 1 (count evs)) "1 evento emitido na tx do INSERT do artefato")
    (let [pl (parse-payload (:payload (first evs)))]
      (is (= (str nid) (:norma-id pl)) "carrega a norma-id")
      (is (= (str (:id r)) (:artefato-id pl)) "carrega o artefato-id (a linha inserida)")
      (is (= 1 (:versao pl)) "carrega a versao materializada")
      (is (= (:hash r) (:hash pl)) "carrega o hash do binario")
      (is (= (:objeto-store-ref r) (:objeto-store-ref pl)) "carrega o ponteiro do objeto_store")
      (is (= "STUB-ICP-v0" (:assinatura-algoritmo pl)) "carrega o algoritmo (stub honesto)")
      (is (false? (:assinado? pl)) "assinado? false enquanto assinado_por e' nil (stub, Slice 4b/F1.4-carry)")
      (is (string? (:criado-em pl)) "criado-em como string ISO (sem Instant no jsonb)"))))

(deftest re-geracao-emite-um-evento-por-versao
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente true)]
    (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid))
    (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid))
    (let [versoes (->> (eventos-por-tipo ente "artefato.publicacao.gerado")
                       (map #(:versao (parse-payload (:payload %)))) sort)]
      (is (= [1 2] versoes) "um evento por (re)geracao, com a versao correspondente"))))

(deftest re-geracao-incrementa-versao
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente true)]
    (is (= 1 (:versao (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid)))))
    (is (= 2 (:versao (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid)))) "MAX+1 atomico")
    (is (= 2 (count (repo-leg/artefatos-da-norma repo ente nid))) "historico de 2 versoes")))

(deftest recusa-norma-nao-publicada
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente false)]   ; promulgada, NAO publicada
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"norma 'publicada'"
          (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid)))
        "so' se gera artefato de norma publicada")
    (is (empty? (repo-leg/artefatos-da-norma repo ente nid)) "nenhum artefato materializado (fail-closed antes do insert)")))

(deftest recusa-norma-inexistente
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"norma inexistente"
          (repo-leg/gerar-artefato-publicacao! repo ente (m-base (random-uuid)))))))

(deftest exige-ports-e-objeto-store
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente true)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"objeto-store ausente"
          (repo-leg/gerar-artefato-publicacao! repo ente (dissoc (m-base nid) :objeto-store))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"serializador ausente"
          (repo-leg/gerar-artefato-publicacao! repo ente (dissoc (m-base nid) :serializador))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"assinador ausente"
          (repo-leg/gerar-artefato-publicacao! repo ente (dissoc (m-base nid) :assinador))))))

(deftest s3-falha-pos-insert-deixa-artefato-ancorado
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente true)
        os-quebrado (reify os/ObjetoStore
                      (guardar! [_ _ _ _] (throw (ex-info "S3 indisponivel" {})))
                      (obter [_ _] nil) (remover! [_ _] nil) (guardar-stream! [_ _ _ _] nil))
        m (assoc (m-base nid) :objeto-store os-quebrado)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"S3 indisponivel"
          (repo-leg/gerar-artefato-publicacao! repo ente m)) "a falha do S3 propaga")
    (let [rows (repo-leg/artefatos-da-norma repo ente nid)]
      (is (= 1 (count rows)) "a linha foi inserida ANTES do S3 (ancora, nao orfao)")
      (is (str/starts-with? (:objeto-store-ref (first rows)) "publicacoes/")
          "com objeto_store_ref resolvivel (recuperavel)"))))

;; ---------- corpo EXTERNALIZADO (conteudo_uri): o texto legal (>32KB) mora no objeto_store, nao inline ----------

(deftest gera-com-corpo-externalizado-em-objeto-store
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        uri (str "textos/" (random-uuid) ".md")
        _ (os/guardar! (:objeto-store *sys*) uri (.getBytes corpo-legal "UTF-8") "text/markdown")
        nid (preparar-norma! repo ente true {:conteudo-uri uri})
        r (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid))
        txt (String. ^bytes (os/obter (:objeto-store *sys*) (:objeto-store-ref r)) "UTF-8")]
    (is (str/includes? txt "Art. 1o Esta Lei dispoe sobre X")
        "o texto legal veio do objeto_store (conteudo_uri resolvido), nao inline")))

(deftest corpo-externalizado-ausente-falha-fechado
  (let [repo (:repo-legislativo *sys*) ente (random-uuid)
        nid (preparar-norma! repo ente true {:conteudo-uri (str "textos/" (random-uuid) ".md")})]  ; blob nunca gravado
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"texto legal da norma nao resolvido"
          (repo-leg/gerar-artefato-publicacao! repo ente (m-base nid)))
        "conteudo_uri sem blob no objeto_store -> fail-closed (nao gera artefato com corpo vazio)")
    (is (empty? (repo-leg/artefatos-da-norma repo ente nid)) "nenhum artefato materializado")))
