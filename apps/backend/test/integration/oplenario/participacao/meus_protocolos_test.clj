(ns oplenario.participacao.meus-protocolos-test
  "INTEGRACAO (PG real) — a cidada ve o que ela mesma protocolou (formularios do cidadao): pedidos de e-SIC,
  solicitacoes LGPD e manifestacoes de ouvidoria IDENTIFICADAS, cada um com estado e prazo. A anonima nao aparece
  (nao ha' dono persistido — so' o protocolo a acompanha); o que e' de outra pessoa ou de outra Casa, tambem nao."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.adapters.out.meus-protocolos :as out]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers])
  (:import (java.time Instant)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(def ^:private relogio (tempo/relogio-fixo (Instant/parse "2026-07-03T12:00:00Z")))
(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})

(deftest a-cidada-ve-o-que-protocolou-com-estado-e-prazo
  (let [ente (random-uuid) eu (random-uuid) outra (random-uuid)
        p (controllers/protocolar-pedido *repo* relogio (ator ente eu) {:assunto "Contratos de 2025" :descricao "Quero a lista."})
        s (controllers/solicitar-titular! *repo* relogio (ator ente eu) {:tipo "acessar" :detalhe nil})
        m (controllers/protocolar-manifestacao! *repo* relogio (ator ente eu)
                                                {:tipo "reclamacao" :assunto "Buraco" :descricao "Na rua A." :anonima false})
        _anon (controllers/protocolar-manifestacao! *repo* relogio (ator ente eu)
                                                    {:tipo "denuncia" :assunto "Anonima" :descricao "x" :anonima true})
        _de-outra (controllers/protocolar-pedido *repo* relogio (ator ente outra) {:assunto "Outro" :descricao "y"})
        _outra-casa (controllers/protocolar-pedido *repo* relogio (ator (random-uuid) eu) {:assunto "Outra Casa" :descricao "z"})
        r (controllers/meus-protocolos *repo* (ator ente eu) relogio)]
    (is (= [(:protocolo p)] (map :protocolo (:pedidos-esic r))))
    (is (= "Contratos de 2025" (:assunto (first (:pedidos-esic r)))))
    (is (= 20 (:dias-restantes (first (:pedidos-esic r)))) "prazo da LAI contado do relogio")
    (is (= [(:protocolo s)] (map :protocolo (:solicitacoes-lgpd r))))
    (is (= "acessar" (:tipo (first (:solicitacoes-lgpd r)))))
    (is (= [(:protocolo m)] (map :protocolo (:manifestacoes r))) "a anonima nao aparece")
    (is (= (:id m) (:id (first (:manifestacoes r)))) "o id leva ao detalhe (rota ja' existente, so' do dono)")
    (let [wire (out/meus-protocolos->wire r)]
      (is (= #{:pedidos-esic :solicitacoes-lgpd :manifestacoes} (set (keys wire))))
      (is (not-any? #(or (contains? % :solicitante-identidade-id) (contains? % :ente-id)
                         (contains? % :descricao) (contains? % :detalhe))
                    (concat (:pedidos-esic wire) (:solicitacoes-lgpd wire) (:manifestacoes wire)))
          "sem tenant, sem PII e sem o corpo do pedido na lista")
      (is (string? (:id (first (:pedidos-esic wire)))))
      (is (nil? (:resposta (first (:pedidos-esic wire)))) "sem resposta ainda"))))

(deftest a-resposta-da-casa-vem-junto
  (let [ente (random-uuid) eu (random-uuid) servidor {:ente-id ente :identidade-id (random-uuid) :papeis #{"secretario"}}
        p (controllers/protocolar-pedido *repo* relogio (ator ente eu) {:assunto "Diarias" :descricao "De 2025."})
        s (controllers/solicitar-titular! *repo* relogio (ator ente eu) {:tipo "acessar" :detalhe nil})
        m (controllers/protocolar-manifestacao! *repo* relogio (ator ente eu)
                                                {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})]
    (controllers/responder-pedido! *repo* relogio servidor (:id p) {:corpo "Segue a planilha em anexo."})
    (controllers/responder-solicitacao! *repo* relogio servidor (:id s) {:corpo "Seus dados: nome e CPF."})
    (controllers/responder-manifestacao! *repo* relogio servidor (:id m) {:corpo "Ampliamos o atendimento."})
    (let [r (controllers/meus-protocolos *repo* (ator ente eu) relogio)
          wire (out/meus-protocolos->wire r)]
      (is (= "respondido" (:estado (first (:pedidos-esic wire)))))
      (is (= "Segue a planilha em anexo." (get-in wire [:pedidos-esic 0 :resposta :corpo])))
      (is (string? (get-in wire [:pedidos-esic 0 :resposta :respondida-em])))
      (is (= "Seus dados: nome e CPF." (get-in wire [:solicitacoes-lgpd 0 :resposta :corpo])))
      (is (= "Ampliamos o atendimento." (get-in wire [:manifestacoes 0 :resposta :corpo])))
      (is (nil? (get-in wire [:pedidos-esic 0 :recurso])) "sem recurso ainda"))
    (let [rec (controllers/interpor-recurso! *repo* relogio (ator ente eu) (:id p) {:motivo "Faltou o valor."})
          wire (out/meus-protocolos->wire (controllers/meus-protocolos *repo* (ator ente eu) relogio))]
      (is (= {:protocolo (:protocolo rec) :estado "protocolado"}
             (select-keys (get-in wire [:pedidos-esic 0 :recurso]) [:protocolo :estado]))
          "o recurso aparece no item — a tela nao oferece recorrer de novo"))))

(deftest sem-nada-protocolado-listas-vazias
  (is (= {:pedidos-esic [] :solicitacoes-lgpd [] :manifestacoes []}
         (controllers/meus-protocolos *repo* (ator (random-uuid) (random-uuid)) relogio))))
