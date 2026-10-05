(ns oplenario.integracao-ia.contexto-votacoes-db-test
  "INTEGRACAO (PG real): o contexto que a IA le para redigir a ata traz as VOTACOES ENCERRADAS da sessao (A.6) — o
  seam do host `rotas/contexto-da-sessao-para-ia` com repos REAIS de sessoes e legislativo, e o `wire` que sai pela
  fronteira. As regras que nao podem falhar, uma a uma: so' fato consumado (aberta e anulada nao saem); so' desta
  sessao e desta Casa; o objeto em palavras; o voto de cada vereador NUNCA aparece — nem na nominal; sessao secreta nao
  le votacao nenhuma; o resultado e os totais saem como o sistema os gravou."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.config :as config]
            [oplenario.integracao-ia.adapters.out.feed :as feed]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.components.repositorio-votacao-ia :as repo-vot-ia]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-s])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *leg* nil)
(def ^:dynamic *ses* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *leg* (repo-leg/->RepoLegislativoPg c (outbox/bus))
                *ses* (repo-s/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(def cadastros-fake
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cad/RepoCadastros
    (roster-da-casa [_ _ente _data] [])))

(defn- sessao! [ente tipo]
  (:id (repo-s/agendar-sessao! *ses* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao tipo
                                           :modalidade "presencial" :agendada-para (Instant/parse "2026-09-22T21:00:00Z")})))

(defn- materia!
  "Protocola uma proposicao de verdade (o numero e' gapless por Casa/tipo/ano: a primeira de uma Casa e' a 1).
  Devolve {:id :sequencial :rotulo} — o rotulo e' o que a ata vai ler ('PL 001/2026')."
  [ente tipo]
  (let [r (tenancy/com-tenant* *ds* ente
            (fn [tx] (proposicao/protocolar! tx {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026 :uf "CE"
                                                 :municipio-nome "Fortaleza" :ementa "Hortas comunitárias"
                                                 :autor-texto "Ver. Ana"})))]
    (assoc r :rotulo (str "PL " (format "%03d" (:sequencial r)) "/2026"))))

(defn- abrir! [ente sessao-id objeto-tipo objeto-id modalidade quorum]
  (let [vid (random-uuid)]
    (repo-leg/abrir-votacao! *leg* ente {:id vid :objeto-tipo objeto-tipo :objeto-id objeto-id :modalidade modalidade
                                         :quorum-tipo quorum :sessao-id sessao-id})
    vid))

(defn- encerrar! [ente vid base & [extra]]
  (repo-leg/encerrar-votacao! *leg* ente (merge {:id vid :base-membros base :updated-by nil :lock-version 0} extra)))

(def vereadores (vec (repeatedly 3 random-uuid)))

(defn- nominal! [ente sessao-id materia]
  (let [vid (abrir! ente sessao-id "proposicao" materia "nominal" "maioria_simples")]
    (doseq [[v voto] (map vector vereadores ["sim" "nao" "sim"])]
      (repo-leg/registrar-voto! *leg* ente {:id (random-uuid) :votacao-id vid :vereador-id v :voto voto}))
    (encerrar! ente vid 3)
    vid))

(defn- secreta! [ente sessao-id materia]
  (let [vid (abrir! ente sessao-id "redacao_final" materia "secreta" "maioria_qualificada_2_3")]
    (doseq [voto (concat (repeat 7 "sim") (repeat 5 "nao"))]
      (repo-leg/registrar-voto-secreto! *leg* ente {:id (random-uuid) :votacao-id vid :voto voto}))
    (encerrar! ente vid 13)
    vid))

(defn- simbolica! [ente sessao-id tipo-objeto]
  (let [vid (abrir! ente sessao-id tipo-objeto (random-uuid) "simbolica" "maioria_simples")]
    (encerrar! ente vid 13 {:resultado "aprovada"})
    vid))

(defn- contexto [ente sid]
  (rotas/contexto-da-sessao-para-ia *ses* cadastros-fake *leg* ente sid))

(deftest so-fato-consumado-desta-sessao-e-desta-casa-na-ordem-em-que-encerrou
  (let [ente (random-uuid) outra-casa (random-uuid)
        sid (sessao! ente "ordinaria") outra-sessao (sessao! ente "ordinaria")
        p7 (materia! ente "projeto_lei")
        nominal (nominal! ente sid (:id p7))
        simbolica (simbolica! ente sid "requerimento")
        secreta (secreta! ente sid (:id p7))
        ;; o que NUNCA pode sair
        _aberta (abrir! ente sid "proposicao" (:id p7) "nominal" "maioria_simples")
        _anulada (let [vid (abrir! ente sid "proposicao" (:id p7) "nominal" "maioria_simples")]
                   (repo-leg/anular-votacao! *leg* ente {:id vid :updated-by nil :lock-version 0})
                   vid)
        _de-outra-sessao (nominal! ente outra-sessao (:id p7))
        _sem-sessao (let [vid (abrir! ente nil "proposicao" (:id p7) "nominal" "maioria_simples")] (encerrar! ente vid 3) vid)
        _de-outra-casa (nominal! outra-casa (sessao! outra-casa "ordinaria") (:id (materia! outra-casa "projeto_lei")))
        vs (:votacoes (contexto ente sid))]
    (is (= [(str nominal) (str simbolica) (str secreta)] (map (comp str :id) vs))
        "so' as 3 encerradas desta sessao, na ordem em que encerraram")
    (testing "o objeto em palavras"
      (is (= [(:rotulo p7) "um requerimento" (str "redação final do " (:rotulo p7))] (map :objeto vs))))
    (testing "o resultado e os totais como o sistema gravou"
      (let [[n s x] vs]
        (is (= {:modalidade "nominal" :quorum-tipo "maioria_simples" :votos-necessarios nil :base-membros 3
                :resultado "aprovada" :total-sim 2 :total-nao 1 :total-abstencao 0}
               (select-keys n [:modalidade :quorum-tipo :votos-necessarios :base-membros :resultado
                               :total-sim :total-nao :total-abstencao])))
        (is (= {:modalidade "simbolica" :resultado "aprovada" :total-sim nil :total-nao nil :total-abstencao nil}
               (select-keys s [:modalidade :resultado :total-sim :total-nao :total-abstencao]))
            "a simbolica nao conta voto")
        (is (= {:modalidade "secreta" :quorum-tipo "maioria_qualificada_2_3" :votos-necessarios 9 :base-membros 13
                :resultado "rejeitada" :total-sim 7 :total-nao 5 :total-abstencao 0}
               (select-keys x [:modalidade :quorum-tipo :votos-necessarios :base-membros :resultado
                               :total-sim :total-nao :total-abstencao]))
            "2/3 de 13 = 9 votos sim: 7 nao basta, e o resultado e' o que o core apurou")))
    (testing "a ordem e' a do encerramento, com o instante"
      (is (= (sort (map (comp str :encerrada-em) vs)) (map (comp str :encerrada-em) vs))))))

(deftest o-voto-de-cada-vereador-nunca-chega-ao-contexto-nem-na-votacao-nominal
  (let [ente (random-uuid) sid (sessao! ente "ordinaria") p (materia! ente "projeto_lei")]
    (nominal! ente sid (:id p))
    (secreta! ente sid (:id p))
    (let [c (contexto ente sid)
          wire (feed/contexto->wire ente (update c :segmentos vec) {})
          json-wire (json/write-value-as-string wire)]
      (testing "as chaves sao exatamente a allowlist"
        (doseq [v (:votacoes c)]
          (is (= #{:id :objeto :modalidade :quorum-tipo :votos-necessarios :base-membros :resultado
                   :total-sim :total-nao :total-abstencao :encerrada-em}
                 (set (keys v))))))
      (testing "nenhum id de vereador, nenhuma lista de votos, em nenhuma forma do contexto"
        (doseq [v vereadores]
          (is (not (str/includes? (pr-str c) (str v))))
          (is (not (str/includes? json-wire (str v)))))
        (is (not (re-find #"vereador|votos\"|voto-" json-wire)) json-wire))
      (testing "a votacao chega pelo wire fechado"
        (is (= 2 (count (:votacoes wire))))
        (is (= (:rotulo p) (:objeto (first (:votacoes wire)))))))))

(deftest sessao-secreta-nao-le-votacao-nenhuma
  (let [ente (random-uuid) sid (sessao! ente "secreta") p (materia! ente "projeto_lei")
        _ (nominal! ente sid (:id p))
        consultas (atom 0)
        leg-que-conta #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify repo-vot-ia/RepoVotacaoIA
          (votacoes-da-sessao-para-ia [_ _ _] (swap! consultas inc) [{:id (random-uuid)}]))
        c (rotas/contexto-da-sessao-para-ia *ses* cadastros-fake leg-que-conta ente sid)]
    (is (= "secreta" (get-in c [:sessao :tipo-sessao])))
    (is (= [] (:votacoes c)))
    (is (zero? @consultas) "nem a consulta acontece")))

(deftest sessao-sem-votacoes-e-outra-casa
  (let [ente (random-uuid) sid (sessao! ente "ordinaria")]
    (is (= [] (:votacoes (contexto ente sid))) "sem votacoes: o contexto de antes, com a lista vazia")
    (is (nil? (contexto (random-uuid) sid)) "outra Casa nao enxerga a sessao (RLS)")))

(deftest o-objeto-que-nao-se-resolve-vai-pelo-tipo-e-nao-inventa-titulo
  (let [ente (random-uuid) sid (sessao! ente "ordinaria")]
    (simbolica! ente sid "emenda")
    (simbolica! ente sid "parecer")
    (simbolica! ente sid "proposicao")                      ; objeto_id que nao e' proposicao nenhuma
    (is (= ["uma emenda" "um parecer" "uma proposição"] (map :objeto (:votacoes (contexto ente sid)))))))
