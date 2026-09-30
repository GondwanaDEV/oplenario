(ns oplenario.legislativo.parecer-juridico-repo-test
  "INTEGRACAO (PG real): o parecer juridico da Casa e o caminho da comissao (ADR-0019 fatia 1) pelo Repo-Component.
  Prova o pedido (materia ou consulta avulsa), o rascunho, a assinatura (numero/ano sequenciais, snapshot), a
  imutabilidade do assinado (trigger), a substituicao, a RLS entre Casas, a publicacao so' depois da deliberacao e a
  distribuicao as comissoes (abrir parecer por comissao, relator)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.components.repositorio-juridico :as juridico]
            [oplenario.legislativo.db.parecer-juridico :as parecer-juridico]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*       (:ds c)
                *repo*     (repo/->RepoLegislativoPg c (outbox/bus))
                *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private texto {:relatorio "Trata-se de projeto de lei." :fundamentacao "Art. 30, I, da CF." :conclusao "favoravel"})
(def ^:private assinante {:por (random-uuid) :nome "Paulo Bezerra" :oab "CE 12345" :qualificacao "efetivo"
                          :assinador (assinador-icp/assinador-stub)})

(defn- protocolar!
  ([ente] (protocolar! ente nil))
  ([ente template-id]
   (:id (repo/protocolar! *repo* ente (cond-> {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                               :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"}
                                        template-id (assoc :template-id template-id))))))

(defn- pedir! [ente m]
  (juridico/criar-pedido-juridico! *repo* ente (merge {:assunto "Análise jurídica da matéria" :origem "secretaria"
                                                       :pedido-por (random-uuid)} m)))

(defn- rito-com-terminal!
  "Rito de proposicao: protocolada -[aprovar]-> aprovada (terminal). Devolve o template-id."
  [ente]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "rito_juridico" :versao 1 :nome "Rito [FIXTURE]"
                                       :estado-inicial "protocolada"})
    (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "protocolada" :nome "Protocolada" :terminal false})
    (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "aprovada" :nome "Aprovada" :terminal true})
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "protocolada"
                                        :para-estado "aprovada" :gatilho "aprovar" :ordem 1})
    tid))

(deftest pedido-sobre-materia-e-consulta-avulsa
  (let [ente (random-uuid) pid (protocolar! ente)]
    (testing "sobre uma materia: traz a referencia e a ementa"
      (let [p (pedir! ente {:proposicao-id pid :prazo (LocalDate/parse "2026-12-01") :em-nome-de "Presidência"})]
        (is (= ["pendente" "secretaria" "Presidência" (LocalDate/parse "2026-12-01")]
               ((juxt :estado :origem :em-nome-de :prazo) p)))
        (is (= ["projeto_lei" "Dispoe sobre X"] ((juxt :materia-tipo :materia-ementa) p)))))
    (testing "consulta avulsa: sem materia"
      (let [p (pedir! ente {:assunto "Prazo regimental da leitura de expediente"})]
        (is (nil? (:proposicao-id p)))
        (is (nil? (:materia-tipo p)))))
    (testing "materia que nao existe nesta Casa -> nil (nunca cria pedido orfao)"
      (is (nil? (pedir! ente {:proposicao-id (random-uuid)})))
      (is (nil? (pedir! (random-uuid) {:proposicao-id pid})) "a materia e' de outra Casa"))
    (testing "a fila: pendentes, mais antigos primeiro"
      (is (= 2 (count (juridico/pedidos-juridicos *repo* ente "pendente" 100))))
      (is (= 2 (count (juridico/pedidos-juridicos *repo* ente nil 100))))
      (is (empty? (juridico/pedidos-juridicos *repo* ente "atendido" 100))))))

(deftest cancelar-so-o-pendente
  (let [ente (random-uuid) por (random-uuid)
        p (pedir! ente {})]
    (let [c (juridico/cancelar-pedido-juridico! *repo* ente (:id p) por)]
      (is (= "cancelado" (:estado c))))
    (is (nil? (juridico/cancelar-pedido-juridico! *repo* ente (:id p) por)) "ja' cancelado")
    (is (= {:erro :pedido-cancelado} (juridico/salvar-parecer-juridico! *repo* ente (:id p) por texto)))))

(deftest rascunho-assinatura-imutabilidade-e-substituicao
  (let [ente (random-uuid) autor (random-uuid)
        pid (protocolar! ente)
        p (pedir! ente {:proposicao-id pid})
        id (:id p)]
    (testing "o rascunho aceita texto incompleto e e' atualizado no lugar (um por pedido)"
      (let [r (juridico/salvar-parecer-juridico! *repo* ente id autor {:relatorio "Rascunho" :fundamentacao "" :conclusao nil})]
        (is (= "rascunho" (get-in r [:pedido :parecer :estado]))))
      (juridico/salvar-parecer-juridico! *repo* ente id autor {:relatorio "Rascunho 2" :fundamentacao "" :conclusao nil})
      (is (= 1 (:n (jdbc/execute-one! *ds* ["SELECT count(*) AS n FROM legislativo.parecer_juridico WHERE pedido_id = ?" id])))))
    (testing "assinar exige relatorio, fundamentacao e conclusao"
      (is (= {:erro :incompleto} (juridico/assinar-parecer-juridico! *repo* ente id assinante))))
    (testing "assinar: numero/ano da Casa, snapshot de quem assinou, pedido atendido"
      (juridico/salvar-parecer-juridico! *repo* ente id autor texto)
      (let [{:keys [pedido]} (juridico/assinar-parecer-juridico! *repo* ente id assinante)
            pj (:parecer pedido)]
        (is (= "atendido" (:estado pedido)))
        (is (= ["assinado" 1 "Paulo Bezerra" "CE 12345" "efetivo" "favoravel"]
               ((juxt :estado :numero :assinatura-nome :assinatura-oab :assinatura-qualificacao :conclusao) pj)))
        (is (integer? (:ano pj)))
        (is (false? (:substituido pj)))))
    (testing "assinado nao se edita, nem se reassina"
      (is (= {:erro :ja-assinado} (juridico/salvar-parecer-juridico! *repo* ente id autor texto)))
      (is (= {:erro :ja-assinado} (juridico/assinar-parecer-juridico! *repo* ente id assinante))))
    (testing "o BANCO tambem barra: assinado e' imutavel (UPDATE e DELETE)"
      (is (thrown-with-msg? Exception #"imutabilidade"
            (jdbc/execute! *ds* ["UPDATE legislativo.parecer_juridico SET relatorio = 'x' WHERE pedido_id = ?" id])))
      (is (thrown-with-msg? Exception #"imutabilidade"
            (jdbc/execute! *ds* ["DELETE FROM legislativo.parecer_juridico WHERE pedido_id = ?" id]))))
    (testing "substituir: novo rascunho com o texto do assinado; o pedido volta a pendente"
      (let [{:keys [pedido]} (juridico/substituir-parecer-juridico! *repo* ente id autor)
            pj (:parecer pedido)]
        (is (= "pendente" (:estado pedido)))
        (is (= ["rascunho" (:relatorio texto)] ((juxt :estado :relatorio) pj)))
        (is (some? (:substitui-id pj))))
      (is (= {:erro :sem-assinado} (juridico/substituir-parecer-juridico! *repo* ente id autor))
          "ja' ha' um substituto em curso"))
    (testing "assinar o substituto: numero seguinte; o primeiro passa a `substituido`"
      (juridico/salvar-parecer-juridico! *repo* ente id autor (assoc texto :conclusao "com_ressalvas"))
      (let [{:keys [pedido]} (juridico/assinar-parecer-juridico! *repo* ente id assinante)
            todos (:pareceres (juridico/pareceres-juridicos-da-materia *repo* ente pid))]
        (is (= 2 (get-in pedido [:parecer :numero])))
        (is (= [["com_ressalvas" false] ["favoravel" true]]
               (mapv (juxt :conclusao :substituido) todos))
            "o mais novo primeiro; o antigo segue na ficha, marcado substituido")))))

(deftest carimbo-do-parecer-assinado
  ;; ADR-0019 fatia 4 (C): ao assinar, o AssinadorICP assina os bytes canonicos do texto (nº/ano + signatario + conclusao +
  ;; relatorio + fundamentacao) e o SHA-256 fica gravado, exposto na ficha e no portal. Imutavel como o resto.
  (let [ente (random-uuid) autor (random-uuid)
        tid (rito-com-terminal! ente)
        pid (protocolar! ente tid)
        id (:id (pedir! ente {:proposicao-id pid}))]
    (juridico/salvar-parecer-juridico! *repo* ente id autor texto)
    (let [pj (get-in (juridico/assinar-parecer-juridico! *repo* ente id assinante) [:pedido :parecer])
          canonico (parecer-juridico/conteudo-canonico
                     {:numero (:numero pj) :ano (:ano pj) :nome "Paulo Bezerra" :oab "CE 12345" :qualificacao "efetivo"
                      :conclusao "favoravel" :relatorio (:relatorio texto) :fundamentacao (:fundamentacao texto)})
          linha (jdbc/execute-one! *ds* ["SELECT assinatura_algoritmo, assinatura_b64, conteudo_sha256
                                          FROM legislativo.parecer_juridico WHERE id = ?" (:id pj)])]
      (testing "o hash e' o SHA-256 dos bytes canonicos e a ficha o traz, com o algoritmo (o stub se declara)"
        (is (re-matches #"sha256:[0-9a-f]{64}" (:conteudo-sha256 pj)))
        (is (= (parecer-juridico/sha256-do-conteudo canonico) (:conteudo-sha256 pj)))
        (is (= "STUB-ICP-v0" (:assinatura-algoritmo pj))))
      (testing "a assinatura destacada e o carimbo ficam no banco; a ficha da materia tambem os traz"
        (is (= ["STUB-ICP-v0" (:conteudo-sha256 pj)]
               [(:parecer_juridico/assinatura_algoritmo linha) (:parecer_juridico/conteudo_sha256 linha)]))
        (is (seq (:parecer_juridico/assinatura_b64 linha)))
        (is (= (:conteudo-sha256 pj)
               (:conteudo-sha256 (first (:pareceres (juridico/pareceres-juridicos-da-materia *repo* ente pid)))))))
      (testing "o texto mexido muda o hash (a forma canonica e' sensivel a cada campo)"
        (is (not= (:conteudo-sha256 pj)
                  (parecer-juridico/sha256-do-conteudo (parecer-juridico/conteudo-canonico
                                                         {:numero (:numero pj) :ano (:ano pj) :nome "Paulo Bezerra"
                                                          :oab "CE 12345" :qualificacao "efetivo" :conclusao "contrario"
                                                          :relatorio (:relatorio texto)
                                                          :fundamentacao (:fundamentacao texto)})))))
      (testing "o banco recusa assinado SEM carimbo, e o carimbo tambem e' imutavel"
        (is (thrown-with-msg? Exception #"imutabilidade"
              (jdbc/execute! *ds* ["UPDATE legislativo.parecer_juridico SET conteudo_sha256 = ? WHERE id = ?"
                                   (str "sha256:" (apply str (repeat 64 "0"))) (:id pj)])))))
    (testing "sem assinador, assinar falha alto (nunca assina sem carimbo)"
      (let [id2 (:id (pedir! ente {}))]
        (juridico/salvar-parecer-juridico! *repo* ente id2 autor texto)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"assinador ausente"
              (juridico/assinar-parecer-juridico! *repo* ente id2 (dissoc assinante :assinador))))))))

(deftest numeracao-sem-buraco-sob-concorrencia
  (let [ente (random-uuid) autor (random-uuid)
        ids (mapv (fn [_] (:id (pedir! ente {}))) (range 6))]
    (doseq [id ids] (juridico/salvar-parecer-juridico! *repo* ente id autor texto))
    (let [numeros (->> ids
                       (mapv #(future (get-in (juridico/assinar-parecer-juridico! *repo* ente % assinante)
                                              [:pedido :parecer :numero])))
                       (mapv deref))]
      (is (= [1 2 3 4 5 6] (sort numeros)) "sem repeticao e sem buraco"))))

(deftest a-fila-traz-o-parecer-sem-texto
  (let [ente (random-uuid) autor (random-uuid)
        p (pedir! ente {})]
    (juridico/salvar-parecer-juridico! *repo* ente (:id p) autor texto)
    (let [[item] (juridico/pedidos-juridicos *repo* ente "pendente" 100)]
      (is (= "rascunho" (get-in item [:parecer :estado])))
      (is (not (contains? (:parecer item) :relatorio)) "a fila nao carrega o texto"))))

(deftest rls-um-ente-nao-ve-o-pedido-do-outro
  (let [ente (random-uuid) outro (random-uuid)
        p (pedir! ente {})]
    (is (some? (juridico/pedido-juridico *repo* ente (:id p))))
    (is (nil? (juridico/pedido-juridico *repo* outro (:id p))))
    (is (= {:erro :nao-encontrado} (juridico/salvar-parecer-juridico! *repo* outro (:id p) (random-uuid) texto)))
    (is (nil? (juridico/cancelar-pedido-juridico! *repo* outro (:id p) (random-uuid))))))

(deftest portal-so-depois-da-deliberacao-e-so-o-vigente
  (let [ente (random-uuid) autor (random-uuid)
        rito (rito-com-terminal! ente)
        pid (protocolar! ente rito)
        p (pedir! ente {:proposicao-id pid})]
    (juridico/salvar-parecer-juridico! *repo* ente (:id p) autor texto)
    (juridico/assinar-parecer-juridico! *repo* ente (:id p) assinante)
    (testing "materia ainda em curso: nada no portal (LAI art. 7 §3)"
      (is (empty? (juridico/pareceres-juridicos-publicos *repo* ente pid)))
      (is (= 1 (count (:pareceres (juridico/pareceres-juridicos-da-materia *repo* ente pid))))
          "a ficha interna ja' mostra"))
    (testing "deliberada: o vigente vai ao portal; o substituido nao"
      (let [r (repo/transicionar! *repo* ente *registro* {:proposicao-id pid :template-id rito :gatilho "aprovar"})]
        (is (true? (:transicionou? r))))
      (is (= 1 (count (juridico/pareceres-juridicos-publicos *repo* ente pid))))
      (juridico/substituir-parecer-juridico! *repo* ente (:id p) autor)
      (juridico/salvar-parecer-juridico! *repo* ente (:id p) autor (assoc texto :conclusao "contrario"))
      (juridico/assinar-parecer-juridico! *repo* ente (:id p) assinante)
      (is (= ["contrario"] (mapv :conclusao (juridico/pareceres-juridicos-publicos *repo* ente pid)))))
    (testing "consulta avulsa nunca vai ao portal (sem materia)"
      (let [av (pedir! ente {})]
        (juridico/salvar-parecer-juridico! *repo* ente (:id av) autor texto)
        (juridico/assinar-parecer-juridico! *repo* ente (:id av) assinante)
        (is (= 1 (count (juridico/pareceres-juridicos-publicos *repo* ente pid))))))))

(defn- rito-de-parecer! [ente]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "parecer_comissao" :versao 1 :sujeito "parecer"
                                       :nome "Parecer [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (doseq [[ch term] [["aguardando_designacao" false] ["aprovado" true]]]
      (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave ch :nome ch :terminal term}))
    tid))

(deftest encaminhar-as-comissoes-abre-um-parecer-por-comissao
  (let [ente (random-uuid) pid (protocolar! ente) ccj (random-uuid) fin (random-uuid) relator (random-uuid) por (random-uuid)]
    (testing "sem o rito de parecer configurado, a Casa e' avisada (nao 500 opaco)"
      (let [e (try (juridico/abrir-pareceres-de-comissao! *repo* ente {:proposicao-id pid :comissoes [{:comissao-id ccj}]
                                                                        :created-by por})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :sem-rito-de-parecer (:erro (ex-data e))))))
    (rito-de-parecer! ente)
    (testing "abre um por comissao, no estado inicial do rito da Casa; relator opcional"
      (let [abertos (juridico/abrir-pareceres-de-comissao! *repo* ente
                      {:proposicao-id pid :created-by por
                       :comissoes [{:comissao-id ccj :relator-id relator} {:comissao-id fin}]})]
        (is (= 2 (count abertos)))
        (is (every? #(= "aguardando_designacao" (:estado %)) abertos))
        (is (every? false? (map :ja-existia abertos)))
        (is (= [relator nil] (mapv :relator-id abertos)))))
    (testing "repetir nao abre outro: devolve o que ja' esta em curso"
      (let [abertos (juridico/abrir-pareceres-de-comissao! *repo* ente
                      {:proposicao-id pid :created-by por :comissoes [{:comissao-id ccj}]})]
        (is (true? (:ja-existia (first abertos))))
        (is (= 2 (count (repo/pareceres-do-objeto *repo* ente "proposicao" pid))))))
    (testing "materia inexistente -> nil"
      (is (nil? (juridico/abrir-pareceres-de-comissao! *repo* ente {:proposicao-id (random-uuid) :created-by por
                                                                     :comissoes [{:comissao-id ccj}]}))))
    (testing "designar o relator de um parecer em curso; troca vale"
      (let [pc (first (filter #(= fin (:comissao-id %)) (repo/pareceres-do-objeto *repo* ente "proposicao" pid)))]
        (is (nil? (:relator-id pc)))
        (is (= {:id (:id pc) :relator-id relator} (juridico/designar-relator-do-parecer! *repo* ente (:id pc) relator por)))
        (is (= relator (:relator-id (repo/buscar-parecer *repo* ente (:id pc)))))
        (let [outro (random-uuid)]
          (juridico/designar-relator-do-parecer! *repo* ente (:id pc) outro por)
          (is (= outro (:relator-id (repo/buscar-parecer *repo* ente (:id pc))))))))
    (testing "parecer inexistente -> nil"
      (is (nil? (juridico/designar-relator-do-parecer! *repo* ente (random-uuid) relator por))))))

(deftest encaminhamentos-simultaneos-nao-duplicam-o-parecer-da-comissao
  (let [ente (random-uuid) pid (protocolar! ente) ccj (random-uuid) por (random-uuid)]
    (rito-de-parecer! ente)
    (let [rs (mapv deref (mapv (fn [_] (future (juridico/abrir-pareceres-de-comissao! *repo* ente
                                                 {:proposicao-id pid :created-by por :comissoes [{:comissao-id ccj}]})))
                               (range 5)))]
      (is (= 1 (count (filter #(false? (:ja-existia (first %))) rs))) "so' um encaminhamento abre; os demais reusam")
      (is (= 1 (count (repo/pareceres-do-objeto *repo* ente "proposicao" pid)))))))

(deftest assinar-e-cancelar-simultaneos-nao-deixam-parecer-assinado-em-pedido-cancelado
  (let [ente (random-uuid) autor (random-uuid)]
    (dotimes [_ 5]
      (let [p (pedir! ente {})]
        (juridico/salvar-parecer-juridico! *repo* ente (:id p) autor texto)
        (let [a (future (juridico/assinar-parecer-juridico! *repo* ente (:id p) assinante))
              c (future (juridico/cancelar-pedido-juridico! *repo* ente (:id p) autor))]
          @a @c
          (let [ped (juridico/pedido-juridico *repo* ente (:id p))
                assinado? (= "assinado" (get-in ped [:parecer :estado]))]
            (is (= assinado? (= "atendido" (:estado ped))) "estado do pedido e do parecer sempre coerentes")
            (is (not (and assinado? (= "cancelado" (:estado ped)))))))))))
