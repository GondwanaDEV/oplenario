(ns oplenario.busca-db-test
  "INTEGRACAO (PG real): Faixa A / A.5 — os seams reais da busca. A hidratacao le' do core na tx do tenant: a
  proposicao de outra Casa nao volta, trecho de sessao secreta nao volta, transcricao que o core nao registrou para
  aquela sessao nao volta. A IA e' falsa (so' devolve ids); o resto e' o repositorio de verdade."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.busca :as busca]
            [oplenario.config :as config]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.integracao-ia.controllers :as controllers-ia]
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.db.gravacao :as gravacao]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- proposicao! [ente ementa]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (:id (proposicao/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                              :uf "CE" :municipio-nome "Fortaleza" :ementa ementa
                                              :autor-texto "Ver. Ana"})))))

(defn- sessao-transcrita!
  "Sessao do `tipo` com uma gravacao e o ponteiro de transcricao CONCLUIDA registrado pela caixa de entrada."
  [ente tipo]
  (let [[sid seg] (tenancy/com-tenant* *ds* ente
                    (fn [tx]
                      (let [sid (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                          :sessao-legislativa-id (random-uuid)
                                                          :tipo-sessao tipo :modalidade "presencial"}))
                            seg (random-uuid)]
                        (gravacao/registrar-segmento! tx {:id seg :ente-id ente :sessao-id sid
                                                          :iniciou-em (Instant/parse "2026-09-26T18:00:00Z")
                                                          :motivo-inicio "inicio_sessao" :container-bruto-uri "g/x"
                                                          :audio-hash "ab" :fonte-ingestao "gravacao_local_pos_sessao"})
                        [sid seg])))
        tid (random-uuid)]
    (controllers-ia/receber! (repo-ia/map->RepoIntegracaoIAPg {:datasource {:ds *ds*}})
                             {:registrar-transcricao repo-sessoes/registrar-transcricao-em-tx!}
                             {:tipo "TranscricaoConcluida" :versao 1 :chave (str "k-" tid) :ente-id ente
                              :ocorrido-em (Instant/parse "2026-09-26T22:00:00Z")
                              :payload {:sessao-id sid :segmento-id seg :transcricao-id tid :versao 1 :idioma "pt-BR"
                                        :duracao-s 60M :n-trechos 3 :cobertura-atribuida 1M :modelo-asr "fake"
                                        :modelo-diarizacao "fake"}
                              :bruto {}})
    [sid tid]))

(defn- trecho [sid tid]
  {:tipo "transcricao" :ref-id (str (random-uuid)) :parte 0 :texto "fala" :score 0.5
   :meta {:sessao-id (str sid) :transcricao-id (str tid)}})

(defn- ia-que-devolve [resultados]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify plataforma-ia/PlataformaIA
    (buscar [_ _ _] {:modelo "fake" :resultados resultados})))

(defn- seams [resultados]
  (busca/seams {:ia (ia-que-devolve resultados)
                :repo-legislativo (repo-leg/map->RepoLegislativoPg {:datasource {:ds *ds*}})
                :repo-sessoes (repo-sessoes/map->RepoSessoesPg {:datasource {:ds *ds*}})}))

(deftest hidrata-do-core-e-barra-o-que-nao-deve-aparecer
  (let [ente (random-uuid) outra (random-uuid)
        p (proposicao! ente "Dispoe sobre a merenda escolar.")
        p-alheia (proposicao! outra "Dispoe sobre a merenda escolar de outra Casa.")
        [sid tid] (sessao-transcrita! ente "ordinaria")
        [sid-s tid-s] (sessao-transcrita! ente "secreta")
        [sid-o tid-o] (sessao-transcrita! outra "ordinaria")
        r (busca/buscar (seams [{:tipo "proposicao" :ref-id (str p) :texto "t" :score 0.9}
                                {:tipo "proposicao" :ref-id (str p-alheia) :texto "t" :score 0.8}
                                (trecho sid tid)
                                (trecho sid-s tid-s)
                                (trecho sid-o tid-o)
                                (trecho sid (random-uuid))])
                        ente {:consulta "merenda" :tipos ["proposicao" "transcricao"]})]
    (is (= "ia" (:modo r)))
    (is (= [["proposicao" p] ["transcricao" sid]]
           (mapv (juxt :tipo #(or (get-in % [:proposicao :id]) (get-in % [:sessao :id]))) (:resultados r)))
        "outra Casa, sessao secreta e transcricao nao registrada para a sessao ficam de fora")
    (let [[rp rt] (:resultados r)]
      (is (= ["Dispoe sobre a merenda escolar." "Ver. Ana" 2026] ((juxt :ementa :autor-texto :ano) (:proposicao rp))))
      (is (= ["ordinaria" tid] [(get-in rt [:sessao :tipo]) (:transcricao-id rt)]))
      (is (integer? (get-in rt [:sessao :numero]))))))

(deftest sem-ia-busca-a-ementa-no-tenant
  (let [ente (random-uuid) outra (random-uuid) marca (str "zarabatana" (rand-int 100000))]
    (proposicao! ente (str "Institui o dia da " marca "."))
    (proposicao! outra (str "Institui o dia da " marca " em outra Casa."))
    (proposicao! ente "Nada a ver.")
    (let [s (assoc (seams []) :buscar-ia (fn [_ _] (throw (ex-info "fora" {:tipo :ia/indisponivel}))))
          r (busca/buscar s ente {:consulta marca :tipos ["proposicao"]})]
      (is (= "sem-ia" (:modo r)))
      (is (= [(str "Institui o dia da " marca ".")] (map #(get-in % [:proposicao :ementa]) (:resultados r)))))))
