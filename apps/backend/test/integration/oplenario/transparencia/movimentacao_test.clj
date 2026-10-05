(ns oplenario.transparencia.movimentacao-test
  "INTEGRACAO (PG real) — \"Por onde a materia passou\": a linha do tempo PUBLICA da materia no portal. Prova a cadeia
  ponta-a-ponta (o Repo de `legislativo` emite `proposicao.protocolada`/`proposicao.transicionou` com o ROTULO da
  etapa no rito da Casa; o relay drena; `transparencia` projeta em `materia_movimentacao`) e as garantias que a tela
  promete: so' data e etapa em palavras (nunca a chave, nunca quem despachou), idempotencia, tolerancia do relay
  COMPARTILHADO, isolamento por Casa, materia que o portal nao mostra nao ganha historico, historico que comeca no
  meio e' DITO, e o backfill da migration produz EXATAMENTE as linhas que o caminho por evento produz."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.transparencia.adapters.out.movimentacao :as adapter]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.db.movimentacao :as db-mov]
            [oplenario.transparencia.diplomat.consumers :as consumers])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo-legislativo* nil)
(def ^:dynamic *repo-transparencia* nil)
(def ^:dynamic *registro-fatos* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))
          bus (outbox/bus)]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *repo-legislativo* (legislativo-repo/->RepoLegislativoPg c bus)
                *repo-transparencia* (transparencia-repo/->RepoTransparenciaPg c)
                *registro-fatos* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- drenar! [] (outbox/drenar! *ds* (consumers/registrar {})))

(defn- rito!
  "Rito de 3 estados cujo NOME difere da CHAVE — e' isso que prova que a tela mostra o rotulo do rito e nunca a chave
  (vocabulario livre por Casa). Fixture ilustrativa, nao regulacao real."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [tid (random-uuid)]
        (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_fixture" :versao 1
                                  :nome "Rito [FIXTURE]" :estado-inicial "recebida_na_mesa"})
        (doseq [[ch nome] [["recebida_na_mesa" "Recebida pela Mesa Diretora"]
                           ["analise_comissoes" "Em análise nas comissões"]
                           ["pronta_plenario" "Pronta para o Plenário"]]]
          (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nome :terminal false}))
        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "recebida_na_mesa"
                                   :para-estado "analise_comissoes" :gatilho "despachar" :guarda nil :ordem 1})
        (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado "analise_comissoes"
                                   :para-estado "pronta_plenario" :gatilho "parecer" :guarda nil :ordem 1})
        tid))))

(defn- protocolar! [ente]
  (:id (legislativo-repo/protocolar! *repo-legislativo* ente
         {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
          :ementa "Dispoe sobre a linha do tempo" :autor-tipo "vereador" :autor-texto "Fulano de Tal"})))

(defn- transicionar! [ente tid pid gatilho]
  (let [r (legislativo-repo/transicionar! *repo-legislativo* ente *registro-fatos*
            {:proposicao-id pid :template-id tid :gatilho gatilho :agora (LocalDate/of 2026 3 1)})]
    (is (true? (:transicionou? r)))
    r))

(defn- linha-do-tempo [ente pid] (transparencia-repo/movimentacoes-da-materia *repo-transparencia* ente pid))

(defn- projetar!
  "Entrega um evento CRU ao projetor, como o relay faria (`ente-id` do envelope; payload com chaves kebab/strings)."
  [ente tipo payload]
  (jdbc/with-transaction [tx *ds*]
    (transparencia-repo/projetar-evento! tx {:tipo tipo :ente-id ente :payload payload
                                             :idempotency-key (str (random-uuid)) :id 1})))

(defn- total [ente pid] (:total (linha-do-tempo ente pid)))

;; ---------- a cadeia real: protocolo + transicoes -> linha do tempo em palavras ----------

(deftest a-linha-do-tempo-mostra-a-etapa-pelo-rotulo-do-rito-da-mais-recente-para-a-mais-antiga
  (let [ente (random-uuid)
        tid  (rito! ente)
        pid  (protocolar! ente)]
    (drenar!)
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (transicionar! ente tid pid "parecer")
    (drenar!)
    (let [r (linha-do-tempo ente pid)
          etapas (mapv :etapa (:movimentacoes r))]
      (is (= ["Pronta para o Plenário" "Em análise nas comissões" "Recebida pela Mesa Diretora"] etapas)
          "do mais recente para o mais antigo; o ROTULO do rito, nunca a chave (`pronta_plenario`...)")
      (is (= 3 (:total r)))
      (is (true? (:completo? r)) "a abertura (protocolo) esta' no historico")
      (is (= [true] (distinct (map #(not (str/includes? (str (:etapa %)) "_")) (:movimentacoes r))))
          "nenhuma etapa e' chave de cadastro")
      (is (= [false false true] (mapv :inicial (:movimentacoes r))) "so' a ultima (a mais antiga) e' a abertura")
      (is (= #{:ocorrido-em :etapa :inicial} (set (mapcat keys (:movimentacoes r))))
          "so' instante, etapa e abertura: nada de ator, gatilho, contexto")
      (is (apply >= (map #(.toEpochMilli ^Instant (:ocorrido-em %)) (:movimentacoes r)))
          "ordem cronologica decrescente"))
    (let [wire (adapter/movimentacoes->wire (linha-do-tempo ente pid))]
      (is (= #{:movimentacoes :movimentacoes-total :historico-completo :historico-desde} (set (keys wire))))
      (is (= #{:ocorrido-em :etapa :abertura} (set (mapcat keys (:movimentacoes wire))))
          "o contrato de saida e' fechado: nao ha campo para quem despachou"))))

(deftest sem-rito-a-abertura-chama-se-protocolada
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    (let [r (linha-do-tempo ente pid)]
      (is (= ["Protocolada"] (mapv :etapa (:movimentacoes r))) "e' o ato; o rito da Casa nao a nomeou")
      (is (true? (:completo? r))))))

;; ---------- idempotencia ----------

(deftest o-mesmo-fato-projetado-de-novo-nao-duplica
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (is (= 2 (total ente pid)))
    (let [pl {:proposicao-id (str pid) :template-id (str tid) :de "recebida_na_mesa" :para "analise_comissoes"
              :gatilho "despachar" :transicao-id (str (random-uuid)) :para-nome "Em análise nas comissões"}
          instante (str (:ocorrido-em (first (:movimentacoes (linha-do-tempo ente pid)))))]
      (testing "redrive com idempotency-key NOVA (outro evento, o mesmo fato) cai no ON CONFLICT"
        (projetar! ente "proposicao.transicionou" (assoc pl :ocorrido-em instante))
        (projetar! ente "proposicao.transicionou" (assoc pl :ocorrido-em instante))
        (is (= 2 (total ente pid)))))))

;; ---------- tolerancia do relay compartilhado ----------

(deftest evento-ruim-e-descartado-com-log-e-nunca-lanca
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (is (= 1 (total ente pid)))
    (with-log
      (testing "transicao sem instante: nao inventa data"
        (projetar! ente "proposicao.transicionou" {:proposicao-id (str pid) :para "analise_comissoes"
                                                  :para-nome "Em análise nas comissões"})
        (is (logged? 'oplenario.transparencia.components.repositorio :warn #"sem instante valido")))
      (testing "instante malformado"
        (projetar! ente "proposicao.transicionou" {:proposicao-id (str pid) :para "analise_comissoes"
                                                  :para-nome "x" :ocorrido-em "ontem"}))
      (testing "sem etapa"
        (projetar! ente "proposicao.transicionou" {:proposicao-id (str pid) :ocorrido-em (str (Instant/now))})
        (is (logged? 'oplenario.transparencia.components.repositorio :warn #"sem etapa")))
      (testing "id de materia malformado: descartado com log de erro, sem excecao"
        (projetar! ente "proposicao.transicionou" {:proposicao-id "nao-e-uuid" :para "x"
                                                  :ocorrido-em (str (Instant/now))})
        (is (logged? 'oplenario.transparencia.components.repositorio :error IllegalArgumentException #"payload malformado")))
      (testing "evento sem ente-id (supratenant/malformado)"
        (projetar! nil "proposicao.transicionou" {:proposicao-id (str pid) :para "x"
                                                 :ocorrido-em (str (Instant/now))})
        (is (logged? 'oplenario.transparencia.components.repositorio :error clojure.lang.ExceptionInfo #"sem ente-id"))))
    (is (= 1 (total ente pid)) "nenhum dos eventos ruins gravou nada")
    ;; e o relay segue: um evento BOM depois dos ruins e' projetado
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (is (= 2 (total ente pid)))))

(deftest evento-anterior-ao-rotulo-grava-a-movimentacao-sem-rotulo-e-nunca-a-chave
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    (projetar! ente "proposicao.transicionou" {:proposicao-id (str pid) :de "protocolada" :para "analise_comissoes"
                                              :ocorrido-em (str (Instant/now))})
    (let [r (linha-do-tempo ente pid)
          nova (first (:movimentacoes r))]
      (is (= 2 (:total r)))
      (is (nil? (:etapa nova)) "sem rotulo no evento: a etapa fica sem nome — a tela diz isso, nao exibe a chave")
      (is (nil? (:etapa (first (:movimentacoes (adapter/movimentacoes->wire r)))))))))

;; ---------- visibilidade e isolamento ----------

(deftest materia-que-o-portal-nao-mostra-nao-ganha-historico
  (let [ente (random-uuid) fantasma (random-uuid)]
    (projetar! ente "proposicao.transicionou" {:proposicao-id (str fantasma) :para "analise_comissoes"
                                              :para-nome "Em análise" :ocorrido-em (str (Instant/now))})
    (is (nil? (linha-do-tempo ente fantasma)) "materia fora do portal: nil (a rota responde 404, como a ficha)")
    (is (zero? (:count (tenancy/com-tenant* *ds* ente
                         (fn [tx] (jdbc/execute-one! tx ["SELECT count(*)::int AS count FROM transparencia.materia_movimentacao WHERE proposicao_id = ?"
                                                         fantasma])))))
        "nada foi gravado para a materia que nao esta' no portal")))

(deftest isolamento-por-casa
  (let [a (random-uuid) b (random-uuid)
        pid-a (protocolar! a)]
    (protocolar! b)
    (drenar!)
    (is (= 1 (total a pid-a)))
    (is (nil? (linha-do-tempo b pid-a)) "a Casa B nao ve a materia da Casa A nem pelo id")
    (is (zero? (:count (tenancy/com-tenant* *ds* b
                         (fn [tx] (jdbc/execute-one! tx ["SELECT count(*)::int AS count FROM transparencia.materia_movimentacao WHERE proposicao_id = ?"
                                                         pid-a])))))
        "RLS: sob o tenant B as linhas de A nem existem")))

;; ---------- historico que comeca no meio ----------

(deftest historico-que-comeca-no-meio-e-dito-e-nao-fingido
  (let [ente (random-uuid) pid (random-uuid)]
    ;; a materia chegou ao portal (protocolada projetada) SEM a abertura da linha do tempo: evento anterior ao campo
    (projetar! ente "proposicao.protocolada"
               {:proposicao-id (str pid) :tipo "projeto_lei" :ano 2026 :sequencial 9
                :urn-lex (str "urn:lex:br;ce;fortaleza:projeto.lei:2026;" (random-uuid))
                :ementa "Materia anterior" :estado "protocolada"})
    (projetar! ente "proposicao.transicionou" {:proposicao-id (str pid) :para "analise_comissoes"
                                              :para-nome "Em análise nas comissões"
                                              :ocorrido-em "2026-09-01T12:00:00Z"})
    (let [r (linha-do-tempo ente pid)]
      (is (= 1 (:total r)))
      (is (false? (:completo? r)) "sem a abertura, o historico comeca no meio")
      (is (= (Instant/parse "2026-09-01T12:00:00Z") (:desde r)) "e diz desde quando ele existe")
      (is (false? (:historico-completo (adapter/movimentacoes->wire r)))))))

;; ---------- teto sinalizado ----------

(deftest passou-do-teto-devolve-as-mais-recentes-e-o-total-real
  (let [ente (random-uuid) pid (protocolar! ente)]
    (drenar!)
    ;; depois da abertura, que carrega o instante REAL do protocolo
    (let [base (.plusSeconds (Instant/now) 86400)]
      (doseq [i (range (+ db-mov/teto-listagem 5))]
        (projetar! ente "proposicao.transicionou"
                   {:proposicao-id (str pid) :para (str "etapa_" i) :para-nome (str "Etapa " i)
                    :ocorrido-em (str (.plusSeconds base (* 60 (inc i))))})))
    (let [r (linha-do-tempo ente pid)]
      (is (= (+ db-mov/teto-listagem 6) (:total r)) "o total conta tudo, sem teto (a abertura inclusive)")
      (is (= db-mov/teto-listagem (count (:movimentacoes r))) "a lista corta no teto")
      (is (= (str "Etapa " (+ db-mov/teto-listagem 4)) (:etapa (first (:movimentacoes r))))
          "e corta as MAIS ANTIGAS: a mais recente continua la'"))))

;; ---------- o backfill da migration == o caminho por evento ----------

(def ^:private statements-do-backfill
  "Os statements do BACKFILL, lidos do proprio arquivo da migration (o que roda em producao, nao uma copia): os
  NO FORCE/FORCE das tabelas FONTE e os dois INSERT."
  (delay
    (->> (str/split (slurp (clojure.java.io/resource "migrations/20261005000202-transparencia-materia-movimentacao.up.sql"))
                    #"--;;")
         (map #(str/trim (str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %)))))
         (filter #(or (str/starts-with? % "INSERT INTO transparencia.materia_movimentacao")
                      (re-find #"^ALTER TABLE (transparencia\.materia|legislativo\.\w+) (NO )?FORCE ROW LEVEL SECURITY" %))))))

(deftest o-backfill-reconstroi-exatamente-o-que-o-caminho-por-evento-projeta
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)
        so-abertura (protocolar! ente)
        ente-sem-rito (random-uuid) sem-rito (protocolar! ente-sem-rito)]
    (drenar!)
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (transicionar! ente tid pid "parecer")
    (drenar!)
    (is (= 10 (count @statements-do-backfill)) "4 NO FORCE + 2 INSERT + 4 FORCE: o filtro leu o arquivo certo")
    (let [por-evento (fn [tx e p] (mapv #(select-keys % [:ocorrido-em :etapa :inicial]) (db-mov/listar tx e p)))
          tudo (fn [tx] [(por-evento tx ente pid) (por-evento tx ente so-abertura) (por-evento tx ente-sem-rito sem-rito)])]
      (jdbc/with-transaction [tx *ds* {:rollback-only true}]
        ;; as linhas da tabela sao do dono; sem tenant, FORCE RLS esconderia tudo — mesmo par da migration
        (jdbc/execute! tx ["ALTER TABLE transparencia.materia_movimentacao NO FORCE ROW LEVEL SECURITY"])
        (let [[a b c :as antes] (tudo tx)]
          (is (= ["Recebida pela Mesa Diretora" "Em análise nas comissões" "Pronta para o Plenário"]
                 (reverse (mapv :etapa a))))
          (is (= ["Recebida pela Mesa Diretora"] (mapv :etapa b)) "materia sem transicao: so' a abertura, no rotulo do rito")
          (is (= ["Protocolada"] (mapv :etapa c)) "Casa sem rito: a abertura chama-se Protocolada")
          (testing "rodar o backfill POR CIMA do que o evento ja' projetou nao duplica nada"
            (doseq [s @statements-do-backfill] (jdbc/execute! tx [s]))
            (is (= antes (tudo tx))))
          (testing "apagadas as linhas, o backfill as reconstroi IGUAIS (instante, rotulo e abertura)"
            (jdbc/execute! tx ["DELETE FROM transparencia.materia_movimentacao WHERE ente_id IN (?, ?)" ente ente-sem-rito])
            (is (every? empty? (tudo tx)))
            (doseq [s @statements-do-backfill] (jdbc/execute! tx [s]))
            (is (= antes (tudo tx)))))))))
