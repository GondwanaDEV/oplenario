(ns oplenario.legislativo.parecer-juridico-fato-test
  "INTEGRACAO (PG real): o FATO `tem_parecer_juridico_assinado(proposicao.id)` (ADR-0019 Eixo 8) — a etapa juridica
  OBRIGATORIA, desligada por padrao. Prova, pelo caminho do `aprovada_em_votacao` (catalogo do motor -> `relacoes` do
  legislativo -> RegistroFatos -> guard da transicao do rito), que: (1) o rito de uma Casa que poe a guarda no estado
  `em_analise_juridica` NAO deixa a materia sair sem parecer ASSINADO; (2) o parecer CONTRARIO assinado destrava (o fato
  pergunta 'assinado', nunca 'favoravel'); (3) o substituto assinado destrava, e o rascunho do substituto nao tira o
  vigente; (4) o parecer de OUTRA materia e o pedido cancelado nao valem; (5) a Casa SEM a guarda (a demo) tramita igual.
  Os estados e gatilhos daqui sao DADO do tenant (Inv.4), so' existem nesta fixture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.components.repositorio-juridico :as juridico]
            [oplenario.legislativo.relacoes :as rel-legis]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes rel-legis/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus)) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private hoje (LocalDate/parse "2026-10-01"))
(def ^:private guarda "tem_parecer_juridico_assinado(proposicao.id)")
(def ^:private texto {:relatorio "Trata-se de projeto de lei." :fundamentacao "Art. 30, I, da CF." :conclusao "favoravel"})
(def ^:private assinante {:por (random-uuid) :nome "Paulo Bezerra" :oab "CE 12345" :qualificacao "efetivo"
                          :assinador (assinador-icp/assinador-stub)})

(defn- rito!
  "protocolada -[pedir_parecer]-> em_analise_juridica -[encaminhar]-> em_comissoes. O `encaminhar` so' tem a guarda se
  `com-guarda?` (e' assim que uma Casa LIGA a etapa: e' dado do rito, nao codigo). `criar-transicao!` roda o gate do
  motor ao salvar — a guarda tem de tipar como Booleano contra o catalogo, senao o rito nem persiste."
  [ente com-guarda?]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "rito_com_juridico" :versao 1 :nome "Rito [FIXTURE]"
                                       :estado-inicial "protocolada"})
    (doseq [[ch nm] [["protocolada" "Protocolada"] ["em_analise_juridica" "Em análise jurídica"]
                     ["em_comissoes" "Em comissões"]]]
      (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave ch :nome nm :terminal false}))
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "protocolada"
                                        :para-estado "em_analise_juridica" :gatilho "pedir_parecer" :ordem 1})
    (repo/criar-transicao! *repo* ente (cond-> {:id (random-uuid) :template-id tid :de-estado "em_analise_juridica"
                                                :para-estado "em_comissoes" :gatilho "encaminhar" :ordem 1}
                                         com-guarda? (assoc :guarda guarda)))
    tid))

(defn- materia-em-analise! [ente tid]
  (let [pid (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                                :municipio-nome "Fortaleza" :ementa "Dispoe sobre X" :template-id tid}))]
    (repo/transicionar! *repo* ente *registro* {:proposicao-id pid :template-id tid :gatilho "pedir_parecer" :agora hoje})
    pid))

(defn- encaminhar [ente tid pid]
  (repo/transicionar! *repo* ente *registro* {:proposicao-id pid :template-id tid :gatilho "encaminhar" :agora hoje}))

(defn- pedir! [ente pid]
  (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid :assunto "Análise jurídica da matéria"
                                                :origem "secretaria" :pedido-por (random-uuid)}))

(defn- assinar! [ente pedido-id conclusao]
  (juridico/salvar-parecer-juridico! *repo* ente pedido-id (random-uuid) (assoc texto :conclusao conclusao))
  (juridico/assinar-parecer-juridico! *repo* ente pedido-id assinante))

(defn- estado [ente pid] (:estado (repo/buscar-proposicao *repo* ente pid)))

(deftest a-guarda-na-etapa-juridica-exige-parecer-assinado
  (let [ente (random-uuid) tid (rito! ente true)]
    (testing "sem pedido nem parecer: a materia NAO sai da analise juridica — quem negou foi o guard, nao o rito"
      (let [pid (materia-em-analise! ente tid)
            r (encaminhar ente tid pid)]
        (is (false? (:transicionou? r)))
        (is (= :guarda-recusou (:motivo r)))
        (is (= "em_analise_juridica" (estado ente pid)))))
    (testing "pedido pendente e rascunho em redacao ainda nao valem: so' o ASSINADO"
      (let [pid (materia-em-analise! ente tid)
            p (pedir! ente pid)]
        (is (false? (:transicionou? (encaminhar ente tid pid))) "so' o pedido")
        (juridico/salvar-parecer-juridico! *repo* ente (:id p) (random-uuid) texto)
        (is (false? (:transicionou? (encaminhar ente tid pid))) "o rascunho nao e' parecer")
        (juridico/assinar-parecer-juridico! *repo* ente (:id p) assinante)
        (let [r (encaminhar ente tid pid)]
          (is (true? (:transicionou? r)) "assinado: a etapa esta cumprida")
          (is (= "em_comissoes" (estado ente pid))))))
    (testing "parecer CONTRARIO assinado tambem destrava — o parecer e' opinativo, o fato pergunta 'assinado'"
      (let [pid (materia-em-analise! ente tid)
            p (pedir! ente pid)]
        (is (= "contrario" (get-in (assinar! ente (:id p) "contrario") [:pedido :parecer :conclusao])))
        (is (true? (:transicionou? (encaminhar ente tid pid))))))
    (testing "o parecer de OUTRA materia nao destrava esta"
      (let [pid (materia-em-analise! ente tid)
            outra (materia-em-analise! ente tid)]
        (assinar! ente (:id (pedir! ente outra)) "favoravel")
        (is (false? (:transicionou? (encaminhar ente tid pid))))))
    (testing "pedido cancelado nao e' parecer"
      (let [pid (materia-em-analise! ente tid)
            p (pedir! ente pid)]
        (juridico/cancelar-pedido-juridico! *repo* ente (:id p) (random-uuid))
        (is (false? (:transicionou? (encaminhar ente tid pid))))))))

(deftest o-substituto-mantem-a-etapa-cumprida
  (let [ente (random-uuid) tid (rito! ente true)]
    (testing "o RASCUNHO do substituto nao tira o vigente: a materia segue livre"
      (let [pid (materia-em-analise! ente tid)
            p (pedir! ente pid)]
        (assinar! ente (:id p) "favoravel")
        (juridico/substituir-parecer-juridico! *repo* ente (:id p) (random-uuid))
        (is (true? (:transicionou? (encaminhar ente tid pid))))))
    (testing "assinado o substituto (contrario), a etapa segue cumprida"
      (let [pid (materia-em-analise! ente tid)
            p (pedir! ente pid)]
        (assinar! ente (:id p) "favoravel")
        (juridico/substituir-parecer-juridico! *repo* ente (:id p) (random-uuid))
        (juridico/salvar-parecer-juridico! *repo* ente (:id p) (random-uuid) (assoc texto :conclusao "contrario"))
        (juridico/assinar-parecer-juridico! *repo* ente (:id p) assinante)
        (is (true? (:transicionou? (encaminhar ente tid pid))))))))

(deftest sem-a-guarda-a-etapa-fica-desligada
  ;; a Casa demo (e a Baturité, que nao preve parecer juridico no Regimento) nao poe a guarda: tramita igual, com ou sem
  ;; pedido de parecer. A etapa so' existe onde a Casa a escreveu no rito.
  (let [ente (random-uuid) tid (rito! ente false)
        pid (materia-em-analise! ente tid)]
    (is (true? (:transicionou? (encaminhar ente tid pid))) "sem a guarda, sem parecer nenhum, a materia segue")
    (is (= "em_comissoes" (estado ente pid)))))

(deftest o-fato-so-enxerga-a-propria-casa
  ;; a RLS isola: o parecer assinado numa Casa nao cumpre a etapa de uma materia de outra Casa
  (let [a (random-uuid) b (random-uuid)
        tid-a (rito! a true) tid-b (rito! b true)
        pa (materia-em-analise! a tid-a) pb (materia-em-analise! b tid-b)]
    (assinar! a (:id (pedir! a pa)) "favoravel")
    (is (true? (:transicionou? (encaminhar a tid-a pa))))
    (is (false? (:transicionou? (encaminhar b tid-b pb))))))
