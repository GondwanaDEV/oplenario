(ns oplenario.legislativo.requerimento-vereador-test
  "INTEGRACAO (Postgres real + bus): fatia 2a — o vereador redige, assina e protocola o proprio requerimento.
  Prova no banco o que a borda HTTP (meu-requerimento-http-in-test, DB-free) so' ve de fora: a assinatura
  gravada na versao de texto no MESMO insert do conteudo (mig 0082), imutavel depois; a autoria vinda do
  resolvedor (nunca do corpo); o texto do modelo da Casa com autor e data do servidor; e que o Expediente nao
  gera documento administrativo a partir de um modelo de requerimento."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.migracao :as migracao])
  (:import (java.security MessageDigest)
           (java.time LocalDate)
           (java.util Base64)))

(def ^:dynamic *repo* nil)
(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus)) *ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private template
  "REQUERIMENTO\n\n{{vereador}} requer a {{destinatario}} informacoes sobre {{assunto}}.\n\nFortaleza, {{data}}.")

(defn- sha256-b64 [^String s]
  (.encodeToString (Base64/getEncoder) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn- modelo! [ente & {:keys [tipo nome chave] :or {tipo "requerimento_proposicao" nome "Requerimento de informação"}}]
  (let [id (random-uuid)]
    (repo/criar-modelo! *repo* ente {:id id :chave (or chave (str "m-" id)) :nome nome :tipo-documento tipo
                                     :corpo-template template :created-by (random-uuid)})
    id))

(defn- assinatura-vigente [ente proposicao-id]
  (repo/transacao *repo* ente #(texto/assinatura-vigente % ente proposicao-id)))

;; ---------- Repo: a assinatura mora na versao de texto ----------

(deftest protocolar-com-assinador-grava-a-assinatura-na-versao
  (let [ente (random-uuid) quem (random-uuid) corpo "## Requerimento\n\nTexto assinado."
        r (repo/protocolar! *repo* ente {:id (random-uuid) :tipo "requerimento" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :tipo-requerimento "Informação"
                                          :texto corpo :created-by quem
                                          :assinador (assinador-icp/assinador-stub) :assinado-por quem})
        a (assinatura-vigente ente (:id r))]
    (is (= "STUB-ICP-v0" (:algoritmo (:assinatura r))) "o recibo do protocolo carrega a assinatura")
    (is (= "STUB-ICP-v0" (:assinatura-algoritmo a)))
    (is (= (sha256-b64 corpo) (:assinatura-b64 a)) "assina os bytes EXATOS do texto protocolado")
    (is (= quem (:assinado-por a)))
    (is (some? (:assinado-em a)))))

(deftest protocolar-sem-assinador-nao-assina
  (let [ente (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :texto "## Art. 1o"})]
    (is (nil? (:assinatura r)))
    (is (nil? (assinatura-vigente ente (:id r))) "o protocolo da Mesa nao ganha assinatura do autor")))

(deftest assinatura-e-imutavel
  (let [ente (random-uuid) quem (random-uuid)
        r (repo/protocolar! *repo* ente {:id (random-uuid) :tipo "requerimento" :ano 2026 :uf "CE"
                                          :municipio-nome "Fortaleza" :ementa "X" :tipo-requerimento "Informação"
                                          :texto "texto" :created-by quem
                                          :assinador (assinador-icp/assinador-stub) :assinado-por quem})]
    (is (thrown-with-msg? Exception #"assinatura da versao de texto e imutavel"
                          (jdbc/execute-one! *ds* ["UPDATE legislativo.proposicao_texto_versao
                                                     SET assinatura_b64 = 'forjada'
                                                   WHERE ente_id = ? AND proposicao_id = ?" ente (:id r)])))
    (is (thrown-with-msg? Exception #"assinatura da versao de texto e imutavel"
                          (jdbc/execute-one! *ds* ["UPDATE legislativo.proposicao_texto_versao
                                                     SET assinatura_algoritmo = NULL
                                                   WHERE ente_id = ? AND proposicao_id = ?" ente (:id r)]))
        "apagar so' um pedaco do selo tambem nao passa (o trigger barra antes do CHECK de completude)")
    (is (= "STUB-ICP-v0" (:assinatura-algoritmo (assinatura-vigente ente (:id r)))) "o selo segue intacto")))

;; ---------- Controller: o fluxo do vereador ----------

(def ^:private hoje (LocalDate/of 2026 9 26))
(defn- resolver-municipio [_] {:uf "CE" :municipio-nome "Fortaleza"})

(deftest modelos-de-requerimento-so-lista-o-tipo-certo-com-os-campos-do-formulario
  (let [ente (random-uuid)
        mid (modelo! ente :nome "Requerimento de informação")
        _ (modelo! ente :tipo "oficio" :nome "Ofício padrão")
        itens (controllers/modelos-de-requerimento *repo* {:ente-id ente})]
    (is (= [{:id mid :nome "Requerimento de informação" :campos ["destinatario" "assunto"]}] itens)
        "so' o modelo de requerimento; campos = placeholders menos vereador/data")))

(deftest protocolar-requerimento-autor-do-login-texto-do-modelo-assinado
  (let [ente (random-uuid) identidade (random-uuid) vereador (random-uuid)
        mid (modelo! ente :nome "Requerimento de informação")
        resolver-autor (fn [e i] (when (and (= e ente) (= i identidade)) {:id vereador :nome "Ana Prado"}))
        r (controllers/meu-protocolar-requerimento
           *repo* resolver-municipio resolver-autor (assinador-icp/assinador-stub)
           {:ente-id ente :identidade-id identidade}
           {:id (random-uuid) :modelo-id mid :ementa "Informações sobre a obra X" :hoje hoje
            :campos {"destinatario" "Secretaria de Obras" "assunto" "a obra X"
                     "vereador" "Nome Forjado" "data" "1º de janeiro de 1900"}})
        {:keys [proposicao texto]} (repo/buscar-proposicao-detalhe *repo* ente (:id r))]
    (is (= 2026 (:ano r)))
    (is (= "requerimento" (:tipo proposicao)))
    (is (= "vereador" (:autor-tipo proposicao)))
    (is (= vereador (:autor-id proposicao)) "autoria = o vereador do login")
    (is (= "Ana Prado" (:autor-texto proposicao)))
    (is (= "Requerimento de informação" (:tipo-requerimento proposicao)) "o tipo vem do nome do modelo")
    (is (= "REQUERIMENTO\n\nAna Prado requer a Secretaria de Obras informacoes sobre a obra X.\n\nFortaleza, 26 de setembro de 2026."
           (:texto-inline texto))
        "o nome e a data forjados no corpo foram ignorados")
    (is (= (sha256-b64 (:texto-inline texto)) (:assinatura-b64 (assinatura-vigente ente (:id r))))
        "a assinatura e' sobre o texto que ficou vigente")))

(deftest previa-e-o-mesmo-texto-que-sera-assinado
  (let [ente (random-uuid) identidade (random-uuid)
        mid (modelo! ente)
        resolver-autor (fn [_ _] {:id (random-uuid) :nome "Ana Prado"})
        campos {"destinatario" "Secretaria de Obras" "assunto" "a obra X"}
        previa (controllers/previa-requerimento *repo* resolver-autor {:ente-id ente :identidade-id identidade}
                                                {:modelo-id mid :campos campos :hoje hoje})
        r (controllers/meu-protocolar-requerimento *repo* resolver-municipio resolver-autor (assinador-icp/assinador-stub)
                                                   {:ente-id ente :identidade-id identidade}
                                                   {:id (random-uuid) :modelo-id mid :ementa "E" :campos campos :hoje hoje})]
    (is (= (:texto previa) (:texto-inline (:texto (repo/buscar-proposicao-detalhe *repo* ente (:id r))))))))

(deftest sem-cadastro-de-vereador-ou-modelo-fora-da-lista-da-nil
  (let [ente (random-uuid) ator {:ente-id ente :identidade-id (random-uuid)}
        mid (modelo! ente)
        oficio (modelo! ente :tipo "oficio")
        com-autor (fn [_ _] {:id (random-uuid) :nome "Ana Prado"})
        m {:id (random-uuid) :modelo-id mid :ementa "E" :campos {"destinatario" "D" "assunto" "A"} :hoje hoje}]
    (is (nil? (controllers/meu-protocolar-requerimento *repo* resolver-municipio (constantly nil)
                                                       (assinador-icp/assinador-stub) ator m))
        "identidade sem cadastro de vereador nesta Casa")
    (is (nil? (controllers/meu-protocolar-requerimento *repo* resolver-municipio com-autor
                                                       (assinador-icp/assinador-stub) ator (assoc m :modelo-id oficio)))
        "modelo de outro tipo")
    (is (nil? (controllers/meu-protocolar-requerimento *repo* resolver-municipio com-autor
                                                       (assinador-icp/assinador-stub) ator (assoc m :modelo-id (random-uuid))))
        "modelo inexistente")))

(deftest campo-faltando-e-400-nao-500
  (let [ente (random-uuid) mid (modelo! ente)]
    (is (= :validacao/invalido
           (:tipo (ex-data (try (controllers/previa-requerimento *repo* (fn [_ _] {:id (random-uuid) :nome "A"})
                                                                 {:ente-id ente :identidade-id (random-uuid)}
                                                                 {:modelo-id mid :campos {"assunto" "x"} :hoje hoje})
                                (catch clojure.lang.ExceptionInfo e e))))))))

(deftest expediente-nao-gera-documento-de-modelo-de-requerimento
  (let [ente (random-uuid) mid (modelo! ente)]
    (is (= :validacao/invalido
           (:tipo (ex-data (try (controllers/gerar-documento *repo* ente {:id (random-uuid) :modelo-id mid :assunto "x"
                                                                          :dados {} :created-by (random-uuid)})
                                (catch clojure.lang.ExceptionInfo e e)))))
        "400, nunca o 500 do CHECK da tabela documento")))
