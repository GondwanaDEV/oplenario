(ns oplenario.demo.ao-vivo-test
  "INTEGRACAO (PG real): `ao-vivo/semear!` — a sessao em curso da demo para ver as telas ao vivo (apps/backend/demo/
  ao_vivo.clj, `demo/semear-ao-vivo.sh`). Prova que, depois da semente, existem:
    (a) uma materia APROVADA em plenario e SEM autografo (o que destrava o formulario do prazo de sancao/veto);
    (b) uma votacao NOMINAL ABERTA na sessao em curso, com votos ja' dados e as personas ainda por votar;
    (c) uma votacao NOMINAL ENCERRADA nessa mesma sessao;
  e que rodar de novo NAO duplica nada — e NAO mexe no que `sessoes` semeou (a sessao aberta da jornada J4 segue com
  a votacao aberta e zero voto).

  BANCO IRMAO, de proposito: a semente acrescenta 2 materias ao Livro do Protocolo e 1 sessao aberta a Casa demo, e os
  vizinhos (`acervo_test`: 'as 24 proposicoes' no Livro, numeracao 1..24) contam exatamente o que o acervo semeia —
  rodar isto no banco de teste compartilhado, em qualquer ordem, quebraria um deles. Mesmo recurso de
  `encerramento_test` (banco descartavel ao lado do de teste, recriado a cada execucao e apagado no fim); aqui a Casa
  demo inteira (casa -> acervo -> sessoes -> ao vivo) nasce nele, o que e' tambem a pre-condicao real do script."
  (:require [acervo]
            [ao-vivo]
            [casa]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema]
            [sessoes]))

;; ---------- o banco irmao descartavel ----------

(defn- nome-do-banco [url] (second (re-find #"/([^/?]+)(\?.*)?$" url)))

(defn- url-irma [url sufixo]
  (str/replace url #"/([^/?]+)(\?.*)?$" (str "/$1" sufixo "$2")))

(defn- com-banco-irmao
  "Cria `<banco>_ao_vivo` (recriado), sobe um sistema completo nele, migra e chama `(f sistema)`; derruba e apaga no
  fim. Falha ALTO se o usuario do teste nao pode criar banco — um teste que se pula em silencio nao prova nada."
  [f]
  (let [cfg (config/carregar)
        url (get-in cfg [:db :jdbc-url])
        nome (str (nome-do-banco url) "_ao_vivo")
        admin (component/start (datasource/datasource cfg))]
    (try
      (jdbc/execute! (:ds admin) [(str "DROP DATABASE IF EXISTS " nome " WITH (FORCE)")])
      (jdbc/execute! (:ds admin) [(str "CREATE DATABASE " nome)])
      (let [s (component/start (sistema/novo-sistema (assoc-in cfg [:db :jdbc-url] (url-irma url "_ao_vivo"))))]
        (try
          (migracao/migrar! (:ds (:datasource s)))
          (f s)
          (finally (component/stop s))))
      (finally
        (jdbc/execute! (:ds admin) [(str "DROP DATABASE IF EXISTS " nome " WITH (FORCE)")])
        (component/stop admin)))))

;; ---------- leituras cruas (conferencia independente da semente: nao usa nada de `ao-vivo`) ----------

(defn- consultar [s ente consulta]
  (tenancy/com-tenant* (get-in s [:datasource :ds]) ente
    (fn [tx] (comum/linhas->kebab (jdbc/execute! tx (sql/format consulta))))))

(defn- votacoes-da-sessao [s ente sessao-id]
  (consultar s ente {:select [:id :objeto_id :objeto_tipo :modalidade :estado :resultado :sessao_id :texto_versao_id]
                     :from [:legislativo.votacoes]
                     :where [:and [:= :ente_id ente] [:= :sessao_id sessao-id]]
                     :order-by [[:criado_em :asc]]}))

(defn- votos-da-votacao [s ente votacao-id]
  (consultar s ente {:select [:vereador_id :voto] :from [:legislativo.votos]
                     :where [:and [:= :ente_id ente] [:= :votacao_id votacao-id]]}))

(defn- contar [s ente tabela]
  (:n (first (consultar s ente {:select [[[:count :*] :n]] :from [tabela] :where [:= :ente_id ente]}))))

(defn- fotografia
  "O que a semente poderia duplicar — contado de fora. Igual antes e depois de rodar de novo."
  [s ente]
  (into {} (map (fn [t] [t (contar s ente t)]))
        [:legislativo.proposicoes :legislativo.votacoes :legislativo.votos :sessoes.sessao :sessoes.pauta_item
         :sessoes.presenca_evento :legislativo.protocolo_geral]))

(deftest sessao-ao-vivo-tem-as-tres-telas-e-nao-duplica
  (com-banco-irmao
   (fn [s]
     (let [{:keys [ente identidades vereadores]} (casa/semear! s)
           secretaria (:secretaria identidades)
           _ (acervo/semear! s ente (:vereador identidades) secretaria)
           {:keys [aberta encerrada]} (sessoes/semear! s ente secretaria)
           id-roster (set (map :id vereadores))
           repo-l (:repo-legislativo s)
           repo-cad (:repo-cadastros s)
           antes (fotografia s ente)
           ;; as votacoes que `sessoes` semeou — a ao-vivo NAO pode mexer nelas
           votos-de-sessoes (fn [] (into {} (map (fn [sessao-id]
                                                   [sessao-id (mapv (fn [v] [(:id v) (:estado v) (count (votos-da-votacao s ente (:id v)))])
                                                                    (votacoes-da-sessao s ente sessao-id))]))
                                         [encerrada aberta]))
           vizinhas-antes (votos-de-sessoes)
           vivo (ao-vivo/semear! s ente identidades)
           {:keys [sessao aprovada em-votacao]} vivo
           vs (votacoes-da-sessao s ente sessao)
           v-aprovada (first (filter #(= (:votacao aprovada) (:id %)) vs))
           v-aberta (first (filter #(= (:votacao em-votacao) (:id %)) vs))
           persona (:id (repo-cadastros/vereador-por-identidade repo-cad ente (:vereador identidades)))]
       (testing "a sessao nova esta' em curso (aberta) e tem as duas materias na pauta"
         (is (= "aberta" (:estado (sessoes/buscar s ente sessao))))
         (is (= 2 (sessoes/itens-de-pauta s ente sessao))))
       (testing "(a) materia APROVADA em plenario e SEM autografo — o que o formulario do prazo de sancao/veto exige"
         (is (true? (repo-leg/proposicao-aprovada-em-votacao? repo-l ente (:proposicao aprovada)))
             "sem votacao encerrada com resultado 'aprovada' o formulario nem aparece (proposicao.aprovada)")
         (is (nil? (repo-leg/autografo-da-proposicao repo-l ente (:proposicao aprovada)))
             "a materia ja' tem autografo — a tela mostraria o pipeline, nao o formulario")
         (is (some? (:texto-versao-id (repo-leg/aprovacao-vigente repo-l ente (:proposicao aprovada))))
             "a votacao nao registrou o texto deliberado — gerar o autografo daria 409 (aprovacao sem texto)")
         (is (= "aprovada" (:estado (repo-leg/buscar-proposicao repo-l ente (:proposicao aprovada))))
             "o rito levou a materia a 'aprovada' depois da votacao"))
       (testing "(c) votacao nominal ENCERRADA na mesma sessao em curso"
         (is (= {:modalidade "nominal" :estado "encerrada" :resultado "aprovada" :objeto-tipo "proposicao" :sessao-id sessao}
                (select-keys v-aprovada [:modalidade :estado :resultado :objeto-tipo :sessao-id])))
         (is (= (:proposicao aprovada) (:objeto-id v-aprovada)))
         (is (= 11 (count (votos-da-votacao s ente (:id v-aprovada)))))
         (is (= {"sim" 8 "nao" 2 "abstencao" 1}
                (frequencies (map :voto (votos-da-votacao s ente (:id v-aprovada)))))))
       (testing "(b) votacao nominal ABERTA, com votos ja' dados e as personas ainda por votar"
         (is (= {:modalidade "nominal" :estado "aberta" :resultado nil :objeto-tipo "proposicao" :sessao-id sessao}
                (select-keys v-aberta [:modalidade :estado :resultado :objeto-tipo :sessao-id])))
         (is (= (:proposicao em-votacao) (:objeto-id v-aberta)))
         (is (= 1 (count (filter #(= "aberta" (:estado %)) vs))) "uma votacao por vez no plenario")
         (let [votos (votos-da-votacao s ente (:id v-aberta))
               quem-votou (set (map :vereador-id votos))]
           (is (= 6 (count votos)))
           (is (not (contains? quem-votou persona)) "o vereador da jornada J4 ja' teria votado — nao teria o que votar")))
       (testing "quem esta' presente e quem votou sao do roster real — nunca uma pessoa fora do cadastro"
         (let [presentes (set (map :vereador-id (sessoes/presencas s ente sessao)))
               votantes (set (mapcat #(map :vereador-id (votos-da-votacao s ente (:id %))) vs))]
           (is (seq presentes))
           (is (empty? (set/difference presentes id-roster)) "presenca fantasma: o telao mostraria UUID sem nome")
           (is (empty? (set/difference votantes id-roster)) "voto fantasma: o placar nominal mostraria UUID sem nome")
           (is (contains? presentes persona) "o vereador precisa estar presente para votar ao vivo (meu-voto)")
           (is (>= (sessoes/quorum s ente sessao) 9) "sem quorum o telao mostra a sessao sem condicoes de votar")))
       (testing "a emenda a Lei Organica da demo vota com 2/3 (CF art. 29) e nao passa por aprovada com um turno so'"
         (let [pelom (:id (first (consultar s ente {:select [:id] :from [:legislativo.proposicoes]
                                                    :where [:and [:= :ente_id ente] [:= :tipo "proposta_emenda_lom"]]})))
               vs-pelom (consultar s ente {:select [:quorum_tipo :estado :resultado] :from [:legislativo.votacoes]
                                           :where [:and [:= :ente_id ente] [:= :objeto_id pelom]]})]
           (is (some? pelom) "o acervo tem a PELOM")
           (is (seq vs-pelom) "a PELOM vai a votos numa das sessoes da demo")
           (is (every? #(= "maioria_qualificada_2_3" (:quorum-tipo %)) vs-pelom))
           (is (every? #(not= "rejeitada" (:resultado %)) vs-pelom) "a demo nao semeia rejeicao da emenda")
           (is (false? (repo-leg/proposicao-aprovada-em-votacao? repo-l ente pelom))
               "um turno so': a semente nao finge o 2o turno 10 dias depois")))
       (testing "o que `sessoes` semeou nao mudou: a aberta da J4 segue com a votacao aberta e zero voto"
         (is (= vizinhas-antes (votos-de-sessoes)))
         (is (some? (sessoes/votacao-aberta s ente aberta)))
         (is (= [0] (mapv #(nth % 2) (get (votos-de-sessoes) aberta)))))
       (testing "rodar de novo RELE: mesmos ids, nada duplicado"
         (let [depois (fotografia s ente)
               de-novo (ao-vivo/semear! s ente identidades)]
           (is (false? (:ja-semeado vivo)))
           (is (true? (:ja-semeado de-novo)))
           (is (= (dissoc vivo :ja-semeado) (dissoc de-novo :ja-semeado)))
           (is (= depois (fotografia s ente)) "a segunda corrida acrescentou linha")
           (is (= 2 (- (:legislativo.proposicoes depois) (:legislativo.proposicoes antes))) "so' as 2 materias novas")
           (is (= 2 (- (:legislativo.votacoes depois) (:legislativo.votacoes antes))))
           (is (= 1 (- (:sessoes.sessao depois) (:sessoes.sessao antes))))))
       (testing "as URLs das tres telas saem com os ids certos"
         (let [u (ao-vivo/urls "http://localhost:3000/" ente vivo)]
           (is (= (str "http://localhost:3000/pos-aprovacao/" (:proposicao aprovada)) (:pos-aprovacao u)))
           (is (= (str "http://localhost:3000/sessoes/" sessao "/plenario") (:telao u)))
           (is (= (str "http://localhost:3000/sessoes/" sessao "/tv") (:tv u)))))
       (testing "sem o semear-tudo a semente falha alto, em vez de semear a Casa por tras"
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"semear-tudo"
                               (ao-vivo/verificar-pre-condicao! s (random-uuid)))))))))
