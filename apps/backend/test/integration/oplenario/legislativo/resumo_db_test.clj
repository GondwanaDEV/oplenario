(ns oplenario.legislativo.resumo-db-test
  "INTEGRACAO (PG real): Faixa A / A.8 — o resumo cidadao. A caixa de entrada grava o ponteiro do rascunho da IA na tx
  do tenant (proposicao de outra Casa nao grava nada); a versao do texto diz o que ficou para tras; publicar e'
  versionado, a proveniencia vem do ponteiro; o texto para a IA e' o da ficha; editar SO' o texto muda a chave de
  integracao (a IA redige de novo)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.integracao-ia.controllers :as controllers-ia]
            [oplenario.integracao-ia.diplomat.consumers :as consumers]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.logic :as logic]
            [oplenario.migracao :as migracao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)
(def ^:dynamic *repo-ia* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo/->RepoLegislativoPg c (outbox/bus))
                *repo-ia* (repo-ia/map->RepoIntegracaoIAPg {:datasource c})]
        (try (t) (finally (component/stop c)))))))

(defn- proposicao! [ente texto]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                      :municipio-nome "Fortaleza" :ementa "Institui hortas comunitarias."
                                      :autor-texto "Ver. Ana" :texto texto})))

(def efeitos {:registrar-resumo repo/registrar-resumo-em-tx!})

(defn- pronto [ente pid base & {:keys [rid] :or {rid (random-uuid)}}]
  {:tipo "ResumoCidadaoPronto" :versao 1 :chave (str "ResumoCidadaoPronto:v1:" rid) :ente-id ente
   :ocorrido-em (Instant/parse "2026-09-27T01:00:00Z")
   :payload {:proposicao-id pid :rascunho-id rid :texto-base-sha256 base :modelo-llm-id "fake:fake-1"
             :prompt-versao "resumo-v1" :incerteza "revisar_com_atencao" :n-citacoes 2 :n-citacoes-conferidas 2
             :n-paragrafos-sem-fonte 1}
   :bruto {}})

(deftest texto-para-a-ia-e-o-da-ficha-com-a-versao
  (let [ente (random-uuid) pid (proposicao! ente "Art. 1o Fica instituido o programa.")
        t (repo/texto-para-ia *repo* ente pid)]
    (is (= ["projeto_lei" "Institui hortas comunitarias." "Ver. Ana" "Art. 1o Fica instituido o programa."]
           ((juxt :tipo :ementa :autor-texto :texto) t)))
    (is (= (logic/texto-base-sha256 "Institui hortas comunitarias." "Art. 1o Fica instituido o programa.")
           (:texto-sha256 t)))
    (is (nil? (repo/texto-para-ia *repo* (random-uuid) pid)) "outra Casa nao ve'")))

(deftest rascunho-da-ia-vira-ponteiro-e-fica-para-tras-quando-o-texto-muda
  (let [ente (random-uuid) pid (proposicao! ente "Art. 1o Texto original.")
        base (:texto-sha256 (repo/texto-para-ia *repo* ente pid))
        rid (random-uuid)]
    (is (nil? (:rascunho (controllers/resumo-da-proposicao *repo* ente pid))) "a IA ainda nao redigiu")
    (is (= {:aplicado true} (controllers-ia/receber! *repo-ia* efeitos (pronto ente pid base :rid rid))))
    (is (= {:aplicado false} (controllers-ia/receber! *repo-ia* efeitos (pronto ente pid base :rid rid)))
        "reentrega: nada de novo")
    (let [{:keys [rascunho texto-base-sha256]} (controllers/resumo-da-proposicao *repo* ente pid)]
      (is (= ["pronto" rid "revisar_com_atencao" 1] ((juxt :situacao :rascunho-id :incerteza :n-paragrafos-sem-fonte)
                                                      rascunho)))
      (is (= base texto-base-sha256 (:texto-base-sha256 rascunho))))
    (let [lock (:lock-version (:proposicao (repo/buscar-proposicao-detalhe *repo* ente pid)))]
      (repo/editar-proposicao! *repo* ente {:id pid :lock-version lock :texto "Art. 1o Texto emendado."
                                            :updated-by (random-uuid)}))
    (let [{:keys [rascunho texto-base-sha256]} (controllers/resumo-da-proposicao *repo* ente pid)]
      (is (not= texto-base-sha256 (:texto-base-sha256 rascunho)) "o rascunho descreve o texto de antes"))))

(deftest falha-da-ia-e-proposicao-alheia
  (let [ente (random-uuid) pid (proposicao! ente "Art. 1o X.")]
    (controllers-ia/receber! *repo-ia* efeitos
      {:tipo "ResumoFalhou" :versao 1 :chave (str "rf-" (random-uuid)) :ente-id ente
       :ocorrido-em (Instant/parse "2026-09-27T01:00:00Z")
       :payload {:proposicao-id pid :categoria "modelo" :detalhe "recusa" :retentavel false} :bruto {}})
    (is (= ["falhou" "modelo"] ((juxt :situacao :categoria-erro) (:rascunho (controllers/resumo-da-proposicao *repo* ente pid)))))
    (testing "proposicao de outra Casa: recusa, e a chave nao fica queimada"
      (let [ev (pronto (random-uuid) pid "sha256:x")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao existe nesta Casa"
              (controllers-ia/receber! *repo-ia* efeitos ev)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao existe nesta Casa"
              (controllers-ia/receber! *repo-ia* efeitos ev)) "de novo: nada foi registrado")))))

(deftest publicar-versiona-e-a-proveniencia-vem-do-ponteiro
  (let [ente (random-uuid) pid (proposicao! ente "Art. 1o X.") outra (proposicao! ente "Art. 1o Y.")
        base (:texto-sha256 (repo/texto-para-ia *repo* ente pid))
        rid (random-uuid) rid-alheio (random-uuid) quem (random-uuid)
        ator {:ente-id ente :identidade-id quem}]
    (controllers-ia/receber! *repo-ia* efeitos (pronto ente pid base :rid rid))
    (controllers-ia/receber! *repo-ia* efeitos (pronto ente outra "sha256:y" :rid rid-alheio))
    (let [v1 (controllers/publicar-resumo! *repo* ator pid {:texto "Cria hortas nos terrenos sem uso."
                                                            :rascunho-id rid})]
      (is (= [1 "gerada_automaticamente" "fake:fake-1" "resumo-v1"]
             ((juxt :versao :origem-redacao :modelo-llm-id :prompt-versao) v1)))
      (is (= (logic/sha256-hex "Cria hortas nos terrenos sem uso.") (:conteudo-sha256 v1))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao e' um rascunho pronto desta proposicao"
          (controllers/publicar-resumo! *repo* ator pid {:texto "X" :rascunho-id rid-alheio})))
    (let [v2 (controllers/publicar-resumo! *repo* ator pid {:texto "Texto escrito pela Casa."})]
      (is (= [2 "redigida_pela_casa" nil] ((juxt :versao :origem-redacao :rascunho-id) v2))))
    (let [{:keys [atual versoes]} (controllers/resumo-da-proposicao *repo* ente pid)]
      (is (= [2 "Texto escrito pela Casa." quem] [(:versao atual) (:texto atual) (:publicado-por atual)]))
      (is (= [2 1] (mapv :versao versoes))))
    (is (nil? (controllers/publicar-resumo! *repo* ator (random-uuid) {:texto "X"})) "proposicao inexistente")
    (is (thrown? Exception
          (jdbc/execute! *ds* ["UPDATE legislativo.resumo_cidadao SET texto = 'x' WHERE proposicao_id = ?" pid]))
        "append-only")))

(deftest editar-so-o-texto-muda-a-chave-de-integracao
  (let [ente (random-uuid) pid (proposicao! ente "Art. 1o A.")
        editar! (fn [texto]
                  (let [lock (:lock-version (:proposicao (repo/buscar-proposicao-detalhe *repo* ente pid)))]
                    (repo/editar-proposicao! *repo* ente {:id pid :lock-version lock :texto texto
                                                          :updated-by (random-uuid)})))]
    (editar! "Art. 1o B.")
    (editar! "Art. 1o C.")
    (outbox/drenar! *ds* (consumers/registrar {}))
    (let [atualizadas (loop [cursor 0 acc []]
                        (let [pag (repo-ia/listar-eventos *repo-ia* cursor 500)]
                          (if (empty? pag)
                            (filterv #(and (= ente (:ente-id %)) (= "ProposicaoAtualizada" (:tipo %))) acc)
                            (recur (:seq (peek (vec pag))) (into acc pag)))))]
      (is (= 2 (count atualizadas)) "mesma ementa, textos diferentes: dois eventos para a IA"))))
