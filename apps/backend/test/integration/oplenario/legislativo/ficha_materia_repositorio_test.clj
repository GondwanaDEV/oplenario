(ns oplenario.legislativo.ficha-materia-repositorio-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis, Onda B Slice 3) — a leitura
  agregada da ficha da materia (proposicao + texto vigente + historico de tramitacao + apensadas ativas +
  emendas + pareceres) NUMA UNICA tx (mesma disciplina de `buscar-proposicao-detalhe`/
  `listar-e-contar-proposicoes`: nunca leituras independentes que poderiam desalinhar sob escrita
  concorrente). Repo real, sem HTTP — mesmo padrao de `proposicao_repositorio_test.clj`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"
                                      :texto "## Art. 1o"})))

(deftest ficha-completa-compoe-proposicao-e-texto
  (let [ente (random-uuid)
        pid (protocolar! ente)
        f (repo/ficha-completa-da-proposicao *repo* ente pid)]
    (is (= pid (:id (:proposicao f))))
    (is (= "## Art. 1o" (:texto-inline (:texto f))))
    (is (false? (:aprovada (:proposicao f)))
        "Fatia 2: sem votacao encerrada 'aprovada', o cabecalho da ficha traz o mesmo fato false")))

(deftest ficha-completa-de-proposicao-inexistente-devolve-proposicao-nil
  (let [ente (random-uuid)
        f (repo/ficha-completa-da-proposicao *repo* ente (random-uuid))]
    (is (nil? (:proposicao f)))
    (is (= [] (:tramitacao f)))
    (is (= [] (:apensadas f)))
    (is (= [] (:emendas f)))
    (is (= [] (:pareceres f)))))

(deftest ficha-completa-traz-historico-de-tramitacao
  ;; o rito e' montado ANTES do protocolo (fatia 4 da borda de tramitacao): `transicionar!` confronta o
  ;; `template-id` do argumento com o `template_id` da LINHA, e a materia so' recebe o elo se o rito ja'
  ;; existir quando ela nasce. Na ordem antiga a fixture tramitava uma materia SEM rito, que e' um estado
  ;; que o produto nao produz (a borda recusa com `:conflito/sem-rito`).
  (let [ente (random-uuid)
        tid (random-uuid)
        _ (repo/criar-template! *repo* ente {:id tid :chave "rito_ordinario" :versao 1
                                             :nome "Rito Ordinario [FIXTURE]" :estado-inicial "protocolada"})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "protocolada"
                                           :nome "Protocolada" :terminal false})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "em_comissoes"
                                           :nome "Em comissoes" :terminal false})
        _ (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "protocolada"
                                              :para-estado "em_comissoes" :gatilho "despachar" :ordem 1})
        pid (protocolar! ente)]
    (repo/transicionar! *repo* ente {} {:proposicao-id pid :template-id tid :gatilho "despachar"})
    (let [f (repo/ficha-completa-da-proposicao *repo* ente pid)]
      (is (= 1 (count (:tramitacao f))))
      (is (= "protocolada" (:de-estado (first (:tramitacao f)))))
      (is (= "em_comissoes" (:para-estado (first (:tramitacao f))))))))

(deftest ficha-completa-traz-apensadas-emendas-e-pareceres
  (let [ente (random-uuid)
        pid (protocolar! ente)
        apensada-pid (protocolar! ente)
        tid-parecer (random-uuid)]
    (repo/apensar! *repo* ente {:id (random-uuid) :principal-id pid :apensada-id apensada-pid})
    (repo/criar-emenda! *repo* ente {:id (random-uuid) :proposicao-mae-id pid :tipo-emenda "aditiva"
                                     :momento-apresentacao "no_prazo" :formato "markdown" :texto-inline "Emenda 1"})
    (repo/criar-template! *repo* ente {:id tid-parecer :chave "parecer_ccj" :versao 1 :sujeito "parecer"
                                       :nome "Parecer CCJ [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid-parecer :chave "aguardando_designacao"
                                     :nome "Aguardando" :terminal false})
    (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid
                                        :comissao-id (random-uuid) :template-id tid-parecer})
    (let [f (repo/ficha-completa-da-proposicao *repo* ente pid)]
      (is (= 1 (count (:apensadas f))))
      (is (= apensada-pid (:apensada-id (first (:apensadas f)))))
      (is (= 1 (count (:emendas f))))
      (is (= "aditiva" (:tipo-emenda (first (:emendas f)))))
      (is (= 1 (count (:pareceres f))))
      (is (= "aguardando_designacao" (:estado (first (:pareceres f))))))))
