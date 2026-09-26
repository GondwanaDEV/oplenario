(ns oplenario.legislativo.recebimento-tramitacao-db-test
  "INTEGRACAO (PG real) — fatia 2b do pedido do stakeholder: \"toda movimentacao do documento assinada por quem
  recebe\". O estado do rito diz se exige RECEBIMENTO (`template_estado.exige_recebimento`, dado da Casa —
  Inv. 4); a materia que chega a ele fica em CARGA pendente ate' alguem autorizado receber e assinar; sem o
  recebimento, ela nao sai do estado. O recebimento e' append-only, um por movimentacao, assinado."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.recebimento :as receb]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.relacoes :as rel-legis]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes rel-legis/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))
(def ^:private assinador (assinador-icp/assinador-stub))

(defn- ator [ente & papeis] {:ente-id ente :identidade-id (random-uuid) :papeis (set papeis)})

(defn- montar-rito!
  "protocolada -despachar-> em_comissoes -concluir-> em_pauta. `em_comissoes` exige recebimento, com a regra de
  quem recebe em `recebedor` (nil = so' o gate da rota)."
  [tx ente recebedor]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave (str "rito_2b_" tid) :versao 1 :tipo "projeto_lei"
                              :nome "Rito [FIXTURE 2b]" :estado-inicial "protocolada"})
    (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave "protocolada" :nome "Protocolada"})
    (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave "em_comissoes"
                            :nome "Em Comissões" :exige-recebimento true :recebedor recebedor})
    (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave "em_pauta" :nome "Em Pauta"})
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "protocolada"
                               :para-estado "em_comissoes" :gatilho "despachar" :ordem 1})
    (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "em_comissoes"
                               :para-estado "em_pauta" :gatilho "concluir" :ordem 1})
    tid))

(defn- protocolar! [tx ente tid]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :template-id tid
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- disparar! [tx ente pid tid gatilho a]
  (tram/transicionar! tx {:registro *registro* :ente-id ente :proposicao-id pid :template-id tid
                          :gatilho gatilho :ator a :ator-id (:identidade-id a) :agora data}))

(defn- receber! [tx ente pid transicao-id a]
  (receb/receber! tx {:registro *registro* :ente-id ente :proposicao-id pid :transicao-id transicao-id
                      :ator a :agora data :assinador assinador}))

(deftest chegar-a-estado-que-exige-recebimento-deixa-a-materia-em-carga
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente nil)
              pid (protocolar! tx ente tid)
              sec (ator ente "secretario")]
          (is (nil? (receb/pendente tx ente pid)) "protocolada nao exige recebimento: nada pendente")
          (is (true? (:transicionou? (disparar! tx ente pid tid "despachar" sec))))
          (let [p (receb/pendente tx ente pid)]
            (is (= "em_comissoes" (:estado p)))
            (is (= "Em Comissões" (:estado-nome p)))
            (is (uuid? (:transicao-id p)) "a pendencia aponta a MOVIMENTACAO a receber")
            (is (= [pid] (map :proposicao-id (receb/listar-pendentes tx ente)))
                "a materia aparece na lista de recebimentos pendentes da Casa"))
          (is (= {:transicionou? false :motivo :recebimento-pendente :de "em_comissoes" :gatilho "concluir"}
                 (disparar! tx ente pid tid "concluir" sec))
              "sem o recebimento, a materia nao sai do estado (a carga nao foi aceita)"))))))

(deftest receber-assina-libera-a-materia-e-nao-se-repete
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente nil)
              pid (protocolar! tx ente tid)
              sec (ator ente "secretario")
              _ (disparar! tx ente pid tid "despachar" sec)
              {:keys [transicao-id]} (receb/pendente tx ente pid)
              r (receber! tx ente pid transicao-id sec)]
          (is (= transicao-id (:transicao-id r)))
          (is (= "em_comissoes" (:estado r)))
          (is (= (:identidade-id sec) (:recebido-por r)) "quem recebe e' quem esta logado")
          (is (= assinador-icp/algoritmo-stub (:assinatura-algoritmo r)))
          (is (some? (:recebido-em r)))
          (is (nil? (receb/pendente tx ente pid)) "recebido: nada mais pendente")
          (is (empty? (receb/listar-pendentes tx ente)))
          (is (= {transicao-id {:recebido-por (:identidade-id sec) :recebido-em (:recebido-em r)}}
                 (update-vals (receb/recebimentos-da-proposicao tx ente pid)
                              #(select-keys % [:recebido-por :recebido-em]))))
          (let [e (try (receber! tx ente pid transicao-id sec) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :conflito/sem-recebimento-pendente (:tipo (ex-data e))) "receber de novo e' conflito, nao duplica"))
          (is (true? (:transicionou? (disparar! tx ente pid tid "concluir" sec)))
              "recebida, a materia volta a andar"))))))

(deftest receber-movimentacao-que-nao-e-a-pendente-e-conflito
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente nil)
              pid (protocolar! tx ente tid)
              sec (ator ente "secretario")]
          (let [e (try (receber! tx ente pid (random-uuid) sec) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :conflito/sem-recebimento-pendente (:tipo (ex-data e)))
                "materia em estado que nao exige recebimento"))
          (disparar! tx ente pid tid "despachar" sec)
          (let [e (try (receber! tx ente pid (random-uuid) sec) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :conflito/movimentacao-divergente (:tipo (ex-data e)))
                "a tela mostrava uma movimentacao e a materia ja' e' outra: nao assina o que a pessoa nao viu")))))))

(deftest quem-recebe-e-regra-da-casa-na-mesma-dsl
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente "\"comissao\" in ator.papeis")
              pid (protocolar! tx ente tid)
              _ (disparar! tx ente pid tid "despachar" (ator ente "secretario"))
              {:keys [transicao-id]} (receb/pendente tx ente pid)
              e (try (receber! tx ente pid transicao-id (ator ente "secretario")) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (authz/negado? e) "fora da regra de quem recebe: NEGACAO (403), nao recusa de dominio")
          (is (some? (receb/pendente tx ente pid)) "a negacao nao consome a pendencia")
          (is (= transicao-id (:transicao-id (receber! tx ente pid transicao-id (ator ente "comissao"))))))))))

(deftest regra-de-quem-recebe-malformada-nao-entra-no-rito
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (random-uuid)]
          (tram/criar-template! tx {:id tid :ente-id ente :chave (str "rito_2b_" tid) :versao 1 :tipo "projeto_lei"
                                    :nome "Rito" :estado-inicial "protocolada"})
          (let [e (try (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave "x" :nome "X"
                                               :exige-recebimento true :recebedor "\"a\" in ("})
                       nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :recebedor-invalido (:erro (ex-data e))) "rejeitada no save, nao no meio do expediente")))))))

(deftest o-recebimento-e-imutavel
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (montar-rito! tx ente nil)
              pid (protocolar! tx ente tid)
              sec (ator ente "secretario")
              _ (disparar! tx ente pid tid "despachar" sec)
              r (receber! tx ente pid (:transicao-id (receb/pendente tx ente pid)) sec)]
          (is (thrown? Exception
                       (jdbc/execute! tx ["UPDATE legislativo.recebimento_tramitacao SET recebido_por = ? WHERE id = ?"
                                          (random-uuid) (:id r)]))
              "append-only: o recibo assinado nao muda depois"))))))
