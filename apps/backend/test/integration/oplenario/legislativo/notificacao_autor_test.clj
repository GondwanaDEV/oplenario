(ns oplenario.legislativo.notificacao-autor-test
  "INTEGRACAO (PG real) — Onda E fatia 1: o PRODUTOR interno. `legislativo` consome o PROPRIO
  `norma.publicada` (2o consumidor, dedup independente), resolve proposicao->autor (same-schema) e
  vereador->identidade pelo RESOLVEDOR INJETADO PELO HOST (legislativo NUNCA importa cadastros, §22.10),
  e emite `notificacao.requisitada` de canal in_app. `paineis` projeta na inbox. Prova o vertical inteiro
  com o relay REAL, alem dos criterios de aceitacao 1 (uma notificacao, idempotente) e 2 (autor sem
  identidade -> nada, e sem erro)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.diplomat.consumers :as legislativo-consumers]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.diplomat.consumers :as paineis-consumers])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *leg* (legislativo-repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; resolvedor FAKE injetado (o host injeta o real, que le' cadastros.vereador): vereador-id -> identidade-id
(defn- resolver-fixo [mapa] (fn [_tx _ente-id vereador-id] (get mapa vereador-id)))

(defn- drenar! [resolver]
  (outbox/drenar! *ds* (-> {}
                           (legislativo-consumers/registrar resolver)
                           (paineis-consumers/registrar))))

(defn- caixa [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT destinatario_identidade_id, categoria, assunto, objeto_id
                                 FROM paineis.notificacao_caixa"]))))

(defn- publicar-norma-de-autor!
  "Protocola uma proposicao com autor vereador, leva ao desfecho promulgavel, promulga e publica.
  Devolve {:proposicao-id :norma-id}."
  [ente vereador-id]
  (let [{pid :id} (legislativo-repo/protocolar! *leg* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "Dispoe sobre as hortas comunitarias."
                     :autor-tipo "vereador" :autor-id vereador-id :autor-texto "Ver. Fulana"})
        {aid :id} (legislativo-repo/gerar-autografo! *leg* ente
                    {:id (random-uuid) :proposicao-id pid :ano 2026
                     :texto-versao-id (random-uuid)
                     :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! *leg* ente
                    {:id (random-uuid) :autografo-id aid})]
    (legislativo-repo/registrar-resposta-executivo! *leg* ente
      {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
    (let [{nid :id} (legislativo-repo/promulgar-norma! *leg* ente
                      {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                       :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                       :data-promulgacao (LocalDate/of 2026 6 28)
                       :ementa "Dispoe sobre as hortas comunitarias." :texto-versao-id (random-uuid)})]
      (legislativo-repo/publicar-norma! *leg* ente
        {:id nid :veiculo-publicacao "Diario Oficial do Municipio" :updated-by nil :lock-version 0})
      {:proposicao-id pid :norma-id nid})))

(deftest norma-publicada-notifica-o-autor-vereador
  (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)
        {:keys [proposicao-id]} (publicar-norma-de-autor! ente vereador)]
    (drenar! (resolver-fixo {vereador identidade}))
    (let [linhas (caixa ente)]
      (is (= 1 (count linhas)) "criterio 1: UMA notificacao na inbox do autor")
      (let [l (first linhas)]
        (is (= identidade (:notificacao_caixa/destinatario_identidade_id l)) "endereçada a identidade dele")
        (is (= "norma_publicada" (:notificacao_caixa/categoria l)))
        (is (= proposicao-id (:notificacao_caixa/objeto_id l)) "o clique leva a' materia")))))

(deftest reprocessar-nao-duplica
  (let [ente (random-uuid) vereador (random-uuid) identidade (random-uuid)
        resolver (resolver-fixo {vereador identidade})]
    (publicar-norma-de-autor! ente vereador)
    (drenar! resolver)
    (drenar! resolver)   ; 2a passada: o dedup do consumer + a UNIQUE da inbox seguram
    (is (= 1 (count (caixa ente))) "criterio 1: reexecutar nao cria uma segunda notificacao")))

(deftest autor-sem-identidade-vinculada-nao-notifica-e-nao-quebra
  (let [ente (random-uuid) vereador (random-uuid)]
    (publicar-norma-de-autor! ente vereador)
    (is (some? (drenar! (resolver-fixo {}))) "criterio 2: o relay segue drenando (nenhuma excecao)")
    (is (empty? (caixa ente)) "sem identidade resolvivel -> silencio honesto, nenhuma notificacao")))

(deftest autor-nao-vereador-nao-notifica
  (let [ente (random-uuid)
        {pid :id} (legislativo-repo/protocolar! *leg* ente
                    {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                     :municipio-nome "Fortaleza" :ementa "De autoria do Executivo."
                     :autor-tipo "executivo" :autor-texto "Prefeitura"})]
    (let [{aid :id} (legislativo-repo/gerar-autografo! *leg* ente
                      {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
                       :destinatario-texto "Prefeito"})
          {tid :id} (legislativo-repo/iniciar-tramitacao-executiva! *leg* ente
                      {:id (random-uuid) :autografo-id aid})]
      (legislativo-repo/registrar-resposta-executivo! *leg* ente
        {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
      (let [{nid :id} (legislativo-repo/promulgar-norma! *leg* ente
                        {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei"
                         :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                         :data-promulgacao (LocalDate/of 2026 6 28) :ementa "De autoria do Executivo."
                         :texto-versao-id (random-uuid)})]
        (legislativo-repo/publicar-norma! *leg* ente
          {:id nid :veiculo-publicacao "DOM" :updated-by nil :lock-version 0})))
    (drenar! (resolver-fixo {}))
    (is (empty? (caixa ente)) "autor_tipo != 'vereador' -> nao ha' dono nominal, nao notifica")))
