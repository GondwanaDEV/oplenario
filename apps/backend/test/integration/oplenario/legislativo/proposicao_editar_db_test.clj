(ns oplenario.legislativo.proposicao-editar-db-test
  "Onda B Slice 2 — db/proposicao.clj/editar!: PATCH parcial (CAS), guard de estado terminal, conflito de
  lock-version, inexistente.

  Fatia 2 / CRITICO 2 — O GUARD DE TERMINAL PERGUNTA AO RITO. Ate' aqui ele comparava o estado contra
  `logic/estados-proposicao-terminais` = #{\"publicada\" \"arquivada\"}: duas palavras de camara cravadas em
  codigo (Inv.4). Os quatro casos abaixo cobrem as quatro respostas possiveis do rito — terminal, vivo,
  fora do rito, e sem rito — e os dois primeiros sao exatamente os dois erros que o literal cometia ao
  mesmo tempo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente]
  (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                        :uf "CE" :municipio-nome "Fortaleza" :ementa "Ementa original"}))

(defn- montar-rito!
  "Rito FIXTURE generico da Casa (sem `:tipo` = vale p/ toda especie), com os estados que o teste declarar.
  `estados` = seq de [chave terminal?]. Chamar ANTES de `protocolar!`: e' `resolver-rito!` quem amarra a
  materia nova a este template (mig 0077), e sem rito ativo no ente a materia nasce sem nenhum."
  [tx ente estados]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_fixture" :versao 1
                              :nome "Rito [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[chave terminal?] estados]
      (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid
                              :chave chave :nome chave :terminal terminal?}))
    tid))

(deftest editar-atualiza-so-os-campos-presentes
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/editar! tx {:id id :ente-id ente :ementa "Ementa corrigida" :updated-by (random-uuid) :lock-version 0})
          (let [atual (prop/buscar tx ente id)]
            (is (= "Ementa corrigida" (:ementa atual)))
            (is (= 1 (:lock-version atual)))))))))

(deftest editar-conflito-de-lock-version-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id]} (protocolar! tx ente)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (prop/editar! tx {:id id :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 99}))))))))

(deftest editar-inexistente-lanca
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? clojure.lang.ExceptionInfo
                     (prop/editar! tx {:id (random-uuid) :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 0})))))))

(deftest editar-recusa-o-estado-que-O-RITO-declara-terminal
  ;; O caso FAIL-OPEN que o literal cometia: Fortaleza cadastra o rito real e nenhum estado dela se chama
  ;; 'publicada' nem 'arquivada' — um projeto de lei JA' SANCIONADO seguia editavel para sempre, porque o
  ;; set em codigo nao conhecia a palavra que a Casa escolheu. Agora quem declara e' o rito.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (montar-rito! tx ente [["protocolada" false] ["sancionada" true]])
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/mudar-estado! tx {:id id :ente-id ente :estado "sancionada" :updated-by (random-uuid) :lock-version 0})
          (is (thrown? clojure.lang.ExceptionInfo
                       (prop/editar! tx {:id id :ente-id ente :ementa "X" :updated-by (random-uuid) :lock-version 1}))
              "o rito diz TERMINAL -> nao edita, mesmo com uma palavra que o vocabulario V1 nao tem"))))))

(deftest editar-aceita-o-estado-que-O-RITO-declara-VIVO
  ;; O caso FAIL-CLOSED-ERRADO, no mesmo literal: a Casa cujo rito permite DESARQUIVAMENTO declara
  ;; 'arquivada' como estado NAO-terminal — e tinha a edicao barrada num estado que ela mesma chamou de
  ;; vivo. A palavra e' a mesma; quem mudou e' a declaracao da Casa, e e' ela que decide.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (montar-rito! tx ente [["protocolada" false] ["arquivada" false]])
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/mudar-estado! tx {:id id :ente-id ente :estado "arquivada" :updated-by (random-uuid) :lock-version 0})
          (prop/editar! tx {:id id :ente-id ente :ementa "Ementa corrigida no desarquivamento"
                            :updated-by (random-uuid) :lock-version 1})
          (is (= "Ementa corrigida no desarquivamento" (:ementa (prop/buscar tx ente id)))))))))

(deftest editar-materia-SEM-rito-EDITA
  ;; A DECISAO desta fatia, declarada tambem na docstring de `editar!`: sem rito nao ha' quem declare
  ;; terminal nenhum, e a materia edita. Ver a justificativa la'.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{:keys [id template-id]} (protocolar! tx ente)]
          (is (nil? template-id) "premissa do teste: sem rito ativo no ente, a materia nasce sem rito")
          (prop/mudar-estado! tx {:id id :ente-id ente :estado "arquivada" :updated-by (random-uuid) :lock-version 0})
          (prop/editar! tx {:id id :ente-id ente :ementa "Corrigida sem rito" :updated-by (random-uuid) :lock-version 1})
          (is (= "Corrigida sem rito" (:ementa (prop/buscar tx ente id)))))))))

(deftest editar-estado-FORA-do-rito-EDITA
  ;; Mesma leitura de `transicionar!` p/ `:estado-fora-do-rito`: `(:terminal nil)` e' nil — estado que o
  ;; rito nem declara NAO e' terminal. Inventar terminalidade aqui divergiria a engine do guard de edicao.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (montar-rito! tx ente [["protocolada" false] ["arquivada" true]])
        (let [{:keys [id]} (protocolar! tx ente)]
          (prop/mudar-estado! tx {:id id :ente-id ente :estado "estado_que_o_rito_nao_conhece"
                                  :updated-by (random-uuid) :lock-version 0})
          (prop/editar! tx {:id id :ente-id ente :ementa "Corrigida fora do rito" :updated-by (random-uuid) :lock-version 1})
          (is (= "Corrigida fora do rito" (:ementa (prop/buscar tx ente id)))))))))
