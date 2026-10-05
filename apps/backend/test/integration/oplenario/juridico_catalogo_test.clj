(ns oplenario.juridico-catalogo-test
  "INTEGRACAO (PG real): ADR-0019 fatia 2 — o agente PROPOE os atos do caminho da materia (pedir parecer juridico,
  encaminhar as comissoes, designar relator) e le o que o juridico ja' opinou; a secretaria confirma em /propostas e a
  MESMA entrada roda como ELA (o pedido nasce com a identidade da pessoa, nunca a do agente). Assinar/substituir o parecer
  nao e' ferramenta: e' ato pessoal do advogado."
  (:require [clojure.set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.components.repositorio-juridico :as juridico]
            [oplenario.migracao :as migracao]
            [oplenario.propostas :as propostas])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(def ^:private relogio (reify tempo/Relogio (agora [_] (Instant/parse "2026-10-01T15:00:00Z"))))

(defn- cenario []
  (let [ente (random-uuid) pessoa (random-uuid) ccj (random-uuid) fin (random-uuid) relator (random-uuid)
        rl (repo-leg/->RepoLegislativoPg *c* (outbox/bus))
        ri (repo-ia/map->RepoIntegracaoIAPg {:datasource *c*})]
    {:ente ente :pessoa pessoa :ccj ccj :fin fin :relator relator :repo rl :repo-ia ri
     :deps {:repo-legislativo rl
            :nome-na-casa (fn [_ id] (when (= id pessoa) "Marta Secretária"))
            ;; os seams de cadastros que o host injeta: so' estas comissoes e este vereador existem nesta Casa
            :resolver-comissoes (fn [_ ids] (into {} (keep #(some->> ({ccj "Comissão de Justiça" fin "Comissão de Finanças"} %)
                                                                     (vector %))) ids))
            :vereador-vinculado? (fn [_ id] (= id relator))
            :nomes-de-vereadores (fn [_ ids] (into {} (keep #(when (= % relator) [% "Ver. Ana Prado"])) ids))
            :colegas-da-casa (fn [_] [{:id relator :nome "Ana Prado" :partido "PSB"}
                                      {:id (random-uuid) :nome "Beto Lima" :partido nil}])
            :comissoes-vigentes (fn [_] [{:id ccj :nome "Comissão de Justiça"} {:id fin :nome "Comissão de Finanças"}])
            :relogio relogio
            :registrar-chamada (catalogo/registrador ri)
            :propor (propostas/propositor ri relogio)
            :marcar-terceiro (propostas/marcador-de-terceiro ri)}}))

(defn- agente [{:keys [ente pessoa]} papel publico]
  {:identidade-id pessoa :ente-id ente :papeis #{papel}
   :via {:agente "assistente-da-casa" :execucao-id (random-uuid) :publico publico :classes #{:leitura :ato}
         :institucional? false}})

(defn- secretaria [c] (agente c "secretario" :secretaria))
(defn- tela [{:keys [ente pessoa]} & [papel]] {:identidade-id pessoa :ente-id ente :papeis #{(or papel "secretario")}})
(defn- deps-tela [{:keys [deps repo-ia]}] {:repo-integracao-ia repo-ia :relogio relogio :deps-catalogo deps})

(defn- materia! [{:keys [repo ente]}]
  (:id (repo-leg/protocolar! repo ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                        :municipio-nome "Fortaleza" :ementa "Dispoe sobre a praça"})))

(defn- contar [ente tabela]
  (:n (jdbc/execute-one! (:ds *c*) [(str "SELECT count(*)::int AS n FROM " tabela " WHERE ente_id = ?") ente]
                         {:builder-fn next.jdbc.result-set/as-unqualified-maps})))

(defn- nomes-do [c] (set (map :nome (catalogo/ferramentas (secretaria c)))))

(defn- tipo-do-erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest o-agente-propoe-o-pedido-de-parecer-e-a-secretaria-confirma
  (let [{:keys [ente pessoa deps repo-ia] :as c} (cenario)
        pid (materia! c)
        ator (secretaria c)
        r (catalogo/executar! deps ator "pedir_parecer_juridico"
                              {"proposicao-id" (str pid) "prazo" "2026-11-10" "em-nome-de" "Presidência"})
        proposta-id (parse-uuid (:proposta-id r))]
    (testing "o agente so' PROPOE: nenhum pedido existe, e o audit registra a proposta"
      (is (= "aguardando_confirmacao" (:estado r)))
      (is (= 0 (contar ente "legislativo.pedido_parecer_juridico")))
      (is (some #{["pedir_parecer_juridico" "ato" "proposta"]}
                (map (juxt :ferramenta :classe :desfecho)
                     (repo-ia/chamadas-da-execucao repo-ia ente (get-in ator [:via :execucao-id]))))
          "o audit da execucao do agente ja' tem a proposta")
      (let [p (repo-ia/proposta repo-ia ente proposta-id)]
        (is (= "confirmar" (:ritual p)))
        (is (re-find #"Pedir parecer jurídico sobre PL 001/2026" (:titulo p)))
        (is (re-find #"Dispoe sobre a praça" (:texto p)))
        (is (re-find #"Prazo: 2026-11-10" (:texto p)))
        (is (re-find #"opinativo" (:texto p)))))
    (testing "a secretaria confirma: o pedido nasce com a identidade DELA, na fila do advogado"
      (let [p (propostas/confirmar! (deps-tela c) (tela c) proposta-id)
            pedido (first (juridico/pedidos-juridicos (:repo c) ente "pendente" 10))]
        (is (= "confirmada" (:estado p)))
        (is (= pid (parse-uuid (get-in p [:resultado :proposicao :id]))))
        (is (= 1 (contar ente "legislativo.pedido_parecer_juridico")))
        (is (= [pessoa "secretaria" "Presidência" "pendente"]
               ((juxt :pedido-por :origem :em-nome-de :estado) pedido))
            "o ator do pedido e' a pessoa que confirmou, nao o agente")))))

(deftest consulta-avulsa-exige-assunto-e-materia-inexistente-nao-vira-proposta
  (let [{:keys [ente deps] :as c} (cenario)]
    (testing "sem materia e sem assunto: invalido ja' na entrada (o agente e' avisado na hora)"
      (is (= :validacao/invalido (tipo-do-erro #(catalogo/executar! deps (secretaria c) "pedir_parecer_juridico" {})))))
    (testing "materia que nao existe nesta Casa: nada a propor"
      (is (nil? (catalogo/executar! deps (secretaria c) "pedir_parecer_juridico"
                                    {"proposicao-id" (str (random-uuid))}))))
    (testing "consulta avulsa com assunto: propoe e, confirmada, cria o pedido sem materia"
      (let [r (catalogo/executar! deps (secretaria c) "pedir_parecer_juridico"
                                  {"assunto" "Prazo regimental da leitura de expediente"})
            p (propostas/confirmar! (deps-tela c) (tela c) (parse-uuid (:proposta-id r)))]
        (is (= "confirmada" (:estado p)))
        (is (nil? (get-in p [:resultado :proposicao])))
        (is (= 1 (contar ente "legislativo.pedido_parecer_juridico")))))))

(deftest o-agente-propoe-o-encaminhamento-as-comissoes-e-a-designacao-do-relator
  (let [{:keys [ente ccj fin relator deps repo] :as c} (cenario)
        pid (materia! c)
        rito (random-uuid)]
    (repo-leg/criar-template! repo ente {:id rito :chave "parecer_comissao" :versao 1 :sujeito "parecer"
                                         :nome "Parecer [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (repo-leg/criar-estado! repo ente {:id (random-uuid) :template-id rito :chave "aguardando_designacao"
                                       :nome "Aguardando" :terminal false})
    (testing "o agente le as comissoes para achar os ids"
      (is (= #{"Comissão de Justiça" "Comissão de Finanças"}
             (set (map :nome (:comissoes (catalogo/executar! deps (secretaria c) "comissoes_da_casa" {})))))))
    (testing "comissao que nao e' desta Casa: invalido ja' na proposta (nunca vira proposta que falharia depois)"
      (is (= :validacao/invalido
             (tipo-do-erro #(catalogo/executar! deps (secretaria c) "encaminhar_as_comissoes"
                                                {"proposicao-id" (str pid)
                                                 "comissoes" [{"comissao-id" (str (random-uuid))}]}))))
      (is (= :validacao/invalido
             (tipo-do-erro #(catalogo/executar! deps (secretaria c) "encaminhar_as_comissoes"
                                                {"proposicao-id" (str pid)
                                                 "comissoes" [{"comissao-id" (str ccj) "relator-id" (str (random-uuid))}]})))
          "relator que nao e' vereador da Casa"))
    (testing "propor nao abre parecer; confirmar abre um por comissao, com o relator"
      (let [r (catalogo/executar! deps (secretaria c) "encaminhar_as_comissoes"
                                  {"proposicao-id" (str pid)
                                   "comissoes" [{"comissao-id" (str ccj) "relator-id" (str relator)}
                                                {"comissao-id" (str fin)}]})
            proposta (repo-ia/proposta (:repo-ia c) ente (parse-uuid (:proposta-id r)))]
        (is (= 0 (contar ente "legislativo.pareceres")))
        (is (re-find #"Comissão de Justiça — relator: Ver\. Ana Prado" (:texto proposta)))
        (is (re-find #"Comissão de Finanças — relator a designar" (:texto proposta)))
        (let [p (propostas/confirmar! (deps-tela c) (tela c) (parse-uuid (:proposta-id r)))]
          (is (= "confirmada" (:estado p)))
          (is (= 2 (count (get-in p [:resultado :pareceres]))))
          (is (= 2 (contar ente "legislativo.pareceres"))))))
    (testing "designar o relator da comissao que ficou sem: propoe e a secretaria confirma"
      (let [pc-fin (first (filter #(= fin (:comissao-id %)) (repo-leg/pareceres-do-objeto repo ente "proposicao" pid)))
            r (catalogo/executar! deps (secretaria c) "designar_relator"
                                  {"parecer-id" (str (:id pc-fin)) "relator-id" (str relator)})]
        (is (nil? (:relator-id (repo-leg/buscar-parecer repo ente (:id pc-fin)))) "propor nao designa")
        (is (re-find #"Ver\. Ana Prado relator do parecer da Comissão de Finanças sobre PL 001/2026"
                     (:texto (repo-ia/proposta (:repo-ia c) ente (parse-uuid (:proposta-id r))))))
        (propostas/confirmar! (deps-tela c) (tela c) (parse-uuid (:proposta-id r)))
        (is (= relator (:relator-id (repo-leg/buscar-parecer repo ente (:id pc-fin)))))))
    (testing "parecer que nao existe: nada a propor"
      (is (nil? (catalogo/executar! deps (secretaria c) "designar_relator"
                                    {"parecer-id" (str (random-uuid)) "relator-id" (str relator)}))))))

(deftest o-agente-acha-o-vereador-para-propor-o-relator
  (let [{:keys [deps relator] :as c} (cenario)]
    (testing "so' id, nome de exibicao e partido; secretaria e vereador leem"
      (doseq [ator [(secretaria c) (agente c "vereador" :vereador)]]
        (let [r (catalogo/executar! deps ator "vereadores_da_casa" {})]
          (is (= ["Ana Prado" "Beto Lima"] (mapv :nome (:vereadores r))))
          (is (= [(str relator) "PSB"] ((juxt #(get-in % [:vereadores 0 :id]) #(get-in % [:vereadores 0 :partido])) r)))
          (is (= #{:id :nome :partido} (set (keys (first (:vereadores r)))))))))
    (testing "sem o seam do host, lista vazia (nunca inventa)"
      (is (= {:vereadores []} (catalogo/executar! (dissoc deps :colegas-da-casa) (secretaria c) "vereadores_da_casa" {}))))
    (testing "o id lido serve para propor o relator (o elo com designar_relator)"
      (is (every? (nomes-do c) ["vereadores_da_casa" "designar_relator"])))
    (testing "o papel e' conferido"
      (is (= :autorizacao/negado
             (tipo-do-erro #(catalogo/executar! deps (agente c "papel_sem_ferramenta" :secretaria) "vereadores_da_casa" {})))))))

(deftest o-agente-le-o-que-o-juridico-ja-opinou-e-nao-assina
  (let [{:keys [ente deps repo] :as c} (cenario)
        pid (materia! c)
        p (juridico/criar-pedido-juridico! repo ente {:proposicao-id pid :assunto "Análise jurídica da matéria"
                                                      :origem "secretaria" :pedido-por (random-uuid)})]
    (juridico/salvar-parecer-juridico! repo ente (:id p) (random-uuid)
                                       {:relatorio "Trata-se de projeto de lei." :fundamentacao "Art. 30, I, da CF."
                                        :conclusao "com_ressalvas"})
    (testing "pedido em aberto: a leitura ve o pedido, nao o rascunho"
      (let [r (catalogo/executar! deps (secretaria c) "pareceres_juridicos_da_materia" {"proposicao-id" (str pid)})]
        (is (empty? (:pareceres r)))
        (is (= 1 (count (:pedidos-abertos r))))))
    (juridico/assinar-parecer-juridico! repo ente (:id p) {:por (random-uuid) :nome "Paulo Bezerra" :oab "CE 12345"
                                                            :qualificacao "efetivo" :assinador (assinador-icp/assinador-stub)})
    (testing "assinado: a leitura traz a conclusao, quem assinou e o hash do carimbo"
      (let [r (catalogo/executar! deps (agente c "vereador" :vereador) "pareceres_juridicos_da_materia"
                                  {"tipo" "projeto_lei" "sequencial" 1 "ano" 2026})
            pj (first (:pareceres r))]
        (is (= "com_ressalvas" (:conclusao pj)))
        (is (= "CE 12345" (get-in pj [:assinatura :oab])))
        (is (re-matches #"sha256:[0-9a-f]{64}" (get-in pj [:assinatura :sha256])))
        (is (= "STUB-ICP-v0" (get-in pj [:assinatura :algoritmo])))))
    (testing "materia de outra Casa nao existe para o agente"
      (is (nil? (catalogo/executar! deps (agente (assoc c :ente (random-uuid)) "secretario" :secretaria)
                                    "pareceres_juridicos_da_materia" {"proposicao-id" (str pid)}))))))

(deftest o-que-o-agente-ve-e-o-que-fica-de-fora
  (let [c (cenario)
        nomes #(set (map :nome (catalogo/ferramentas %)))
        da-secretaria (nomes (secretaria c))]
    (testing "a secretaria tem o caminho da materia; o vereador so' le o parecer"
      (is (every? da-secretaria ["pedir_parecer_juridico" "encaminhar_as_comissoes" "designar_relator"
                                 "comissoes_da_casa" "vereadores_da_casa" "pareceres_juridicos_da_materia"]))
      (is (= #{"pareceres_juridicos_da_materia" "vereadores_da_casa"}
             (clojure.set/intersection (nomes (agente c "vereador" :vereador))
                                       #{"pareceres_juridicos_da_materia" "vereadores_da_casa" "pedir_parecer_juridico"
                                         "encaminhar_as_comissoes" "designar_relator"}))))
    (testing "assinar, salvar e substituir parecer juridico NAO sao ferramentas (ato pessoal do advogado)"
      (is (not-any? #(re-find #"assinar|salvar|substituir" %) (map :nome catalogo/entradas))))
    (testing "execucao so' de leitura nem ve os atos"
      (is (not-any? #{"pedir_parecer_juridico" "encaminhar_as_comissoes" "designar_relator"}
                    (nomes (assoc-in (secretaria c) [:via :classes] #{:leitura})))))
    (testing "o papel e' conferido a cada chamada e na confirmacao"
      (is (= :autorizacao/negado
             (tipo-do-erro #(catalogo/executar! (:deps c) (agente c "vereador" :secretaria) "pedir_parecer_juridico"
                                                {"assunto" "Consulta sobre decoro parlamentar"}))))
      (is (= :validacao/ferramenta-desconhecida
             (tipo-do-erro #(catalogo/executar! (:deps c) (agente c "vereador" :vereador) "pedir_parecer_juridico"
                                                {"assunto" "Consulta sobre decoro parlamentar"})))
          "e a credencial do publico vereador nem oferece a ferramenta")
      (let [r (catalogo/executar! (:deps c) (secretaria c) "pedir_parecer_juridico"
                                  {"assunto" "Consulta sobre decoro parlamentar"})]
        (is (= :autorizacao/negado
               (tipo-do-erro #(propostas/confirmar! (deps-tela c) (tela c "vereador") (parse-uuid (:proposta-id r)))))
            "sem o papel `secretario` agora, a proposta nao executa")
        (is (= 0 (contar (:ente c) "legislativo.pedido_parecer_juridico")))))
    (testing "o agente institucional nunca propoe"
      (is (= :autorizacao/negado
             (tipo-do-erro #(catalogo/executar! (:deps c) (assoc-in (secretaria c) [:via :institucional?] true)
                                                "pedir_parecer_juridico" {"assunto" "Consulta sobre decoro parlamentar"})))))))
