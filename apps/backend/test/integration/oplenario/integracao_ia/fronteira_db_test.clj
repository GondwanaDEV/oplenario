(ns oplenario.integracao-ia.fronteira-db-test
  "INTEGRACAO (PG real): ADR-0008 — a gravacao vinculada vira evento no feed core -> IA pelo relay; a caixa de
  entrada IA -> core aplica o ponteiro da transcricao uma vez so', na tx do tenant."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.integracao-ia.components.repositorio :as repo]
            [oplenario.integracao-ia.controllers :as controllers]
            [oplenario.integracao-ia.diplomat.consumers :as consumers]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.db.gravacao :as gravacao]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo/map->RepoIntegracaoIAPg {:datasource c})]
        (try (t) (finally (component/stop c)))))))

(defn- vinculado! [ente sid seg restrito?]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx
               (eventos/evento "gravacao.segmento-vinculado" ente
                 {:segmento-id seg :sessao-id sid :acesso-restrito restrito?})))))

(defn- drena! [] (outbox/drenar! *ds* (consumers/registrar {})))

(defn- do-ente [ente]
  (filter #(= ente (:ente-id %)) (repo/listar-eventos *repo* 0 200)))

(deftest gravacao-vinculada-chega-ao-feed-uma-vez-e-restrita-nunca
  (let [ente (random-uuid) sid (random-uuid) seg (random-uuid) seg-r (random-uuid)]
    (vinculado! ente sid seg false)
    (vinculado! ente sid seg-r true)
    (vinculado! ente sid seg false) ; o mesmo vinculo re-emitido (redrive): a chave barra a duplicata
    (drena!)
    (let [[e :as evs] (do-ente ente)]
      (is (= 1 (count evs)) "uma vez so', e a restrita nao saiu")
      (is (= "GravacaoVinculada" (:tipo e)))
      (is (= (str seg) (get-in e [:payload :segmento-id])))
      (is (pos? (:seq e))))))

(deftest feed-pagina-pelo-cursor
  (let [ente (random-uuid) sid (random-uuid)]
    (dotimes [_ 3] (vinculado! ente sid (random-uuid) false))
    (drena!)
    (let [todos (do-ente ente)
          corte (:seq (first todos))
          depois (filter #(= ente (:ente-id %)) (repo/listar-eventos *repo* corte 200))]
      (is (= 3 (count todos)))
      (is (= (rest (map :seq todos)) (map :seq depois)) "depois do cursor, so' os seguintes, em ordem")
      (is (= 1 (count (repo/listar-eventos *repo* 0 1))) "o limite corta"))))

;; ---------- caixa de entrada ----------

(defn- sessao-com-segmento! [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"}))
            seg (random-uuid)]
        (gravacao/registrar-segmento! tx {:id seg :ente-id ente :sessao-id sid :iniciou-em (Instant/parse "2026-09-26T18:00:00Z")
                                          :motivo-inicio "inicio_sessao" :container-bruto-uri "gravacao/x"
                                          :audio-hash "ab" :fonte-ingestao "gravacao_local_pos_sessao"})
        [sid seg]))))

(defn- concluida [ente sid seg chave]
  {:tipo "TranscricaoConcluida" :versao 1 :chave chave :ente-id ente :correlation-id "c-1"
   :ocorrido-em (Instant/parse "2026-09-26T22:00:00Z")
   :payload {:sessao-id sid :segmento-id seg :transcricao-id (random-uuid) :versao 1 :idioma "pt-BR"
             :duracao-s 3600M :n-trechos 412 :cobertura-atribuida 0.83M :modelo-asr "whisper-large-v3-turbo"
             :modelo-diarizacao "pyannote-3.0"}
   :bruto {"segmento-id" (str seg)}})

(defn- ponteiros [ente sid]
  (tenancy/com-tenant* *ds* ente (fn [tx] (oplenario.sessoes.db.transcricao/listar-da-sessao tx ente sid))))

(deftest transcricao-concluida-grava-o-ponteiro-uma-vez-so
  (let [ente (random-uuid) [sid seg] (sessao-com-segmento! ente) ev (concluida ente sid seg (str "k-" (random-uuid)))]
    (is (= {:aplicado true} (controllers/receber! *repo* repo-sessoes/registrar-transcricao-em-tx! ev)))
    (is (= {:aplicado false} (controllers/receber! *repo* repo-sessoes/registrar-transcricao-em-tx! ev))
        "reenvio da mesma chave: 200 sem efeito novo")
    (let [[p :as ps] (ponteiros ente sid)]
      (is (= 1 (count ps)))
      (is (= ["concluida" 412 "whisper-large-v3-turbo"] [(:situacao p) (:n-trechos p) (:modelo-asr p)]))
      (is (= 0.8300M (:cobertura-atribuida p))))))

(deftest transcricao-falhou-grava-a-categoria
  (let [ente (random-uuid) [sid seg] (sessao-com-segmento! ente)]
    (controllers/receber! *repo* repo-sessoes/registrar-transcricao-em-tx!
      {:tipo "TranscricaoFalhou" :versao 1 :chave (str "f-" (random-uuid)) :ente-id ente
       :ocorrido-em (Instant/parse "2026-09-26T22:00:00Z")
       :payload {:sessao-id sid :segmento-id seg :categoria "entrada" :detalhe "audio corrompido" :retentavel false}
       :bruto {}})
    (is (= [["falhou" "entrada" false]] (mapv (juxt :situacao :categoria-erro :retentavel) (ponteiros ente sid))))))

(deftest segmento-de-outra-sessao-e-recusado-e-nada-fica-registrado
  (let [ente (random-uuid) [sid seg] (sessao-com-segmento! ente) [_ seg-alheio] (sessao-com-segmento! ente)
        chave (str "x-" (random-uuid))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao pertence"
          (controllers/receber! *repo* repo-sessoes/registrar-transcricao-em-tx! (concluida ente sid seg-alheio chave))))
    (is (empty? (ponteiros ente sid)))
    (is (= {:aplicado true}
           (controllers/receber! *repo* repo-sessoes/registrar-transcricao-em-tx!
             (concluida ente sid seg chave)))
        "a chave nao ficou queimada: o registro da entrada foi desfeito junto (mesma tx)")))

(deftest caixa-de-entrada-isola-por-tenant
  (let [ente (random-uuid) [sid seg] (sessao-com-segmento! ente)
        intruso (random-uuid)]
    (is (thrown? Exception
          (controllers/receber! *repo* repo-sessoes/registrar-transcricao-em-tx!
            (concluida intruso sid seg (str "i-" (random-uuid)))))
        "evento com ente errado nao enxerga a sessao do outro tenant (RLS) — nao grava nada")
    (is (empty? (ponteiros ente sid)))))
