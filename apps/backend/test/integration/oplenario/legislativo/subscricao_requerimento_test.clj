(ns oplenario.legislativo.subscricao-requerimento-test
  "INTEGRACAO (Postgres real + bus): fatia 2c — o requerimento COLETIVO. O autor redige e convida coautores; cada
  coautor confirma com a propria assinatura (sobre o MESMO texto que sera' protocolado) ou recusa; o autor
  protocola, e quem ainda nao respondeu NAO CONSTA. Desenho: autoria-apoiamento.html."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.db.subscricao :as subscricao]
            [oplenario.migracao :as migracao])
  (:import (java.security MessageDigest)
           (java.util Base64)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(def ^:private assinador (assinador-icp/assinador-stub))
(def ^:private texto "REQUERIMENTO\n\nAna Prado e demais signatarios requerem informacoes sobre a obra X.")

(defn- sha256-b64 [^String s]
  (.encodeToString (Base64/getEncoder) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn- cenario!
  "Uma proposta de Ana (autora) convidando Bia e Caio. Devolve os ids."
  [ente]
  (let [modelo (random-uuid) proposta (random-uuid)
        ana (random-uuid) bia (random-uuid) caio (random-uuid)]
    (repo/criar-modelo! *repo* ente {:id modelo :chave (str "m-" modelo) :nome "Requerimento de informação"
                                     :tipo-documento "requerimento_proposicao" :corpo-template "{{vereador}}"
                                     :created-by (random-uuid)})
    (repo/criar-proposta-requerimento! *repo* ente
      {:id proposta :autor-vereador-id ana :autor-identidade-id (random-uuid) :autor-nome "Ana Prado"
       :modelo-id modelo :tipo-requerimento "Requerimento de informação" :ementa "Informações sobre a obra X"
       :texto texto :coautores [{:id bia :nome "Bia Lima"} {:id caio :nome "Caio Reis"}]})
    {:proposta proposta :ana ana :bia bia :caio caio}))

(defn- responder! [ente proposta vereador acao]
  (repo/responder-subscricao! *repo* ente {:proposta-id proposta :vereador-id vereador :identidade-id (random-uuid)
                                           :acao acao :assinador assinador}))

(defn- protocolar! [ente proposta autor]
  (repo/protocolar-proposta-requerimento! *repo* ente proposta autor
    {:id (random-uuid) :tipo "requerimento" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
     :ementa "Informações sobre a obra X" :tipo-requerimento "Requerimento de informação"
     :autor-tipo "vereador" :autor-id autor :autor-texto "Ana Prado" :texto texto :created-by (random-uuid)
     :assinador assinador :assinado-por (random-uuid)}))

(deftest os-convidados-veem-o-convite-e-o-autor-acompanha
  (let [ente (random-uuid) {:keys [proposta ana bia caio]} (cenario! ente)]
    (is (= [proposta] (map :proposta-id (repo/convites-de-subscricao *repo* ente bia))))
    (is (= "Ana Prado" (:autor-nome (first (repo/convites-de-subscricao *repo* ente caio)))))
    (is (empty? (repo/convites-de-subscricao *repo* ente ana)) "o autor nao se convida")
    (is (= [{:id proposta :confirmadas 0 :pendentes 2 :recusadas 0}]
           (map #(select-keys % [:id :confirmadas :pendentes :recusadas])
                (repo/propostas-abertas-do-autor *repo* ente ana))))
    (testing "so' quem participa le a proposta"
      (is (= 2 (count (:subscricoes (repo/buscar-proposta-requerimento *repo* ente proposta ana)))))
      (is (some? (repo/buscar-proposta-requerimento *repo* ente proposta bia)))
      (is (nil? (repo/buscar-proposta-requerimento *repo* ente proposta (random-uuid)))))))

(deftest confirmar-assina-o-mesmo-texto-e-nao-se-repete
  (let [ente (random-uuid) {:keys [proposta bia]} (cenario! ente)
        r (responder! ente proposta bia :confirmar)]
    (is (= "confirmada" (:estado r)))
    (is (= "STUB-ICP-v0" (:assinatura-algoritmo r)))
    (is (= (sha256-b64 texto)
           (:assinatura_b64 (repo/transacao *repo* ente
                              #(jdbc/execute-one! %
                                 ["SELECT assinatura_b64 FROM legislativo.subscricao_requerimento WHERE proposta_id = ? AND vereador_id = ?"
                                  proposta bia]
                                 {:builder-fn rs/as-unqualified-maps}))))
        "o coautor assina os bytes EXATOS do texto que sera' protocolado")
    (is (empty? (repo/convites-de-subscricao *repo* ente bia)) "respondido, sai da fila")
    (let [e (try (responder! ente proposta bia :confirmar) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :conflito/subscricao-respondida (:tipo (ex-data e))) "o duplo clique nao assina duas vezes"))))

(deftest recusar-nao-assina
  (let [ente (random-uuid) {:keys [proposta caio]} (cenario! ente)
        r (responder! ente proposta caio :recusar)]
    (is (= "recusada" (:estado r)))
    (is (nil? (:assinatura-algoritmo r)))))

(deftest quem-nao-foi-convidado-nao-responde
  (let [ente (random-uuid) {:keys [proposta]} (cenario! ente)]
    (is (nil? (responder! ente proposta (random-uuid) :confirmar)))))

(deftest protocolar-leva-so-quem-confirmou
  (let [ente (random-uuid) {:keys [proposta ana bia caio]} (cenario! ente)
        _ (responder! ente proposta bia :confirmar)
        r (protocolar! ente proposta ana)]
    (is (some? (:sequencial r)) "numerado como qualquer requerimento")
    (is (= [bia] (map :vereador-id (:coautores r))) "Caio nao respondeu: NAO CONSTA")
    (is (= ["Bia Lima"] (map :vereador-nome (repo/transacao *repo* ente
                                               #(subscricao/coautores-da-proposicao % ente (:id r)))))
        "a ficha le os coautores pela proposicao")
    (testing "o convite de Caio fecha como 'nao consta' e sai da fila; a proposta sai das abertas"
      (is (empty? (repo/convites-de-subscricao *repo* ente caio)))
      (is (= "nao_consta" (->> (:subscricoes (repo/buscar-proposta-requerimento *repo* ente proposta ana))
                               (filter #(= caio (:vereador-id %))) first :estado)))
      (is (empty? (repo/propostas-abertas-do-autor *repo* ente ana))))
    (testing "responder depois do protocolo e' conflito"
      (let [e (try (responder! ente proposta caio :confirmar) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :conflito/proposta-protocolada (:tipo (ex-data e))))))
    (testing "protocolar de novo e' conflito, nao numera outra vez"
      (let [e (try (protocolar! ente proposta ana) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :conflito/proposta-protocolada (:tipo (ex-data e))))))))

(deftest so-o-autor-protocola
  (let [ente (random-uuid) {:keys [proposta bia]} (cenario! ente)]
    (is (nil? (protocolar! ente proposta bia)) "coautor nao protocola a proposta de outro (-> 404)")))

(deftest subscricao-respondida-e-imutavel
  (let [ente (random-uuid) {:keys [proposta bia]} (cenario! ente)
        sql! (fn [q] (repo/transacao *repo* ente #(jdbc/execute! % q)))]
    (responder! ente proposta bia :confirmar)
    (is (thrown? Exception
                 (sql! ["UPDATE legislativo.subscricao_requerimento SET estado = 'recusada' WHERE proposta_id = ?" proposta]))
        "quem subscreveu nao 'dessubscreve' em silencio")
    (is (thrown? Exception
                 (sql! ["UPDATE legislativo.requerimento_proposta SET texto = 'outro' WHERE id = ?" proposta]))
        "o texto assinado nao muda")))
