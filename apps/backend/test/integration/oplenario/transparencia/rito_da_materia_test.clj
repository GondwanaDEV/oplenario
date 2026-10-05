(ns oplenario.transparencia.rito-da-materia-test
  "INTEGRACAO (PG real + borda Pedestal) — a faixa 'Onde esta' a materia' da ficha PUBLICA vem do RITO da Casa, nao de
  um mapa fixo por nome de estado. Prova a cadeia inteira: o Repo de `legislativo` calcula a linha do rito (a mesma de
  `GET /legislativo/proposicoes/:id/ficha`) e a leva no evento (`proposicao.protocolada`/`proposicao.transicionou`); o
  relay drena; `transparencia` guarda o ULTIMO rito da materia sem ler schema de `legislativo` (ADR-0001 §6); a rota
  publica o devolve. Garantias: so' chave/rotulo/terminal das etapas (nada de responsavel, comissao ou id), materia
  sem evento novo fica sem rito (sem backfill), rito malformado nunca lanca no relay COMPARTILHADO, e uma transicao
  sem rito nao deixa um rito VELHO apontando a etapa anterior."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas]
            [oplenario.transparencia.adapters.out.materia :as adapter]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.diplomat.consumers :as consumers])
  (:import (java.time LocalDate Instant)))

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
  "Rito de 3 etapas em linha (recebida -> analise -> pronta) cujos NOMES diferem das chaves, mais um desfecho
  terminal. Vocabulario ilustrativo de fixture (nao e' regulacao real)."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (let [tid (random-uuid)]
        (tram/criar-template! tx {:id tid :ente-id ente :chave "rito_do_portal" :versao 1
                                  :nome "Rito [FIXTURE]" :estado-inicial "recebida_na_mesa"})
        (doseq [[ch nome terminal] [["recebida_na_mesa" "Recebida pela Mesa Diretora" false]
                                    ["analise_comissoes" "Em análise nas comissões" false]
                                    ["pronta_plenario" "Pronta para o Plenário" false]
                                    ["arquivada_pela_mesa" "Arquivada pela Mesa" true]]]
          (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch :nome nome :terminal terminal}))
        (doseq [[de para gatilho] [["recebida_na_mesa" "analise_comissoes" "despachar"]
                                   ["analise_comissoes" "pronta_plenario" "parecer"]
                                   ["recebida_na_mesa" "arquivada_pela_mesa" "arquivar"]]]
          (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid :de-estado de :para-estado para
                                     :gatilho gatilho :guarda nil :ordem 1}))
        tid))))

(defn- protocolar! [ente]
  (:id (legislativo-repo/protocolar! *repo-legislativo* ente
         {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
          :ementa "Dispoe sobre a faixa pelo rito" :autor-tipo "vereador" :autor-texto "Fulano de Tal"})))

(defn- transicionar! [ente tid pid gatilho]
  (let [r (legislativo-repo/transicionar! *repo-legislativo* ente *registro-fatos*
            {:proposicao-id pid :template-id tid :gatilho gatilho :agora (LocalDate/of 2026 3 1)
             :ator-id (random-uuid)})]
    (is (true? (:transicionou? r)))
    r))

(defn- materia [ente pid] (transparencia-repo/buscar-materia *repo-transparencia* ente pid))

(defn- projetar!
  "Entrega um evento CRU ao projetor, como o relay faria."
  [ente tipo payload]
  (jdbc/with-transaction [tx *ds*]
    (transparencia-repo/projetar-evento! tx {:tipo tipo :ente-id ente :payload payload
                                             :idempotency-key (str (random-uuid)) :id 1})))

(defn- GET-ficha [ente pid]
  (let [sf (-> (http/servico (config/carregar)
                             (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade nil
                                            :repo-transparencia *repo-transparencia*})
                             it/globais)
               ph/create-server ::ph/service-fn)]
    (pt/response-for sf :get (str "/portal/casa/" ente "/materias/" pid))))

(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(def ^:private etapa-recebida {:chave "recebida_na_mesa" :rotulo "Recebida pela Mesa Diretora" :terminal false})
(def ^:private etapa-analise {:chave "analise_comissoes" :rotulo "Em análise nas comissões" :terminal false})
(def ^:private etapa-pronta {:chave "pronta_plenario" :rotulo "Pronta para o Plenário" :terminal false})
(def ^:private etapa-arquivada {:chave "arquivada_pela_mesa" :rotulo "Arquivada pela Mesa" :terminal true})

(deftest o-rito-chega-ao-portal-no-protocolo-e-acompanha-cada-transicao
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (let [rito (:rito (materia ente pid))]
      (is (true? (:ordem-unica rito)) "o rito da fixture tem uma linha so'")
      (is (= [etapa-recebida etapa-analise etapa-pronta] (:etapas rito))
          "as etapas EM ORDEM, com o nome que a Casa lhes deu; o desfecho terminal nao e' passo da linha")
      (is (= etapa-recebida (:atual rito)) "no protocolo a atual e' o estado inicial do rito")
      (is (= #{etapa-analise etapa-arquivada} (set (:proximas rito)))
          "as saidas que o rito declara a partir da atual (possiveis, nao disponiveis)"))
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (is (= etapa-analise (:atual (:rito (materia ente pid)))) "a transicao leva o rito novo: a atual andou")
    (transicionar! ente tid pid "parecer")
    (drenar!)
    (is (= etapa-pronta (:atual (:rito (materia ente pid)))))
    (is (= "pronta_plenario" (:estado (materia ente pid))) "estado e rito.atual andam juntos")))

(deftest o-rito-que-o-portal-guarda-so-tem-chave-rotulo-e-terminal-das-etapas
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (let [rito (:rito (materia ente pid))
          todas (concat (:etapas rito) (:proximas rito) (:anteriores rito) (some-> (:atual rito) vector))]
      (is (= #{:ordem-unica :etapas :atual :anteriores :proximas} (set (keys rito))))
      (is (seq todas))
      (is (= #{:chave :rotulo :terminal} (set (mapcat keys todas)))
          "nada de responsavel, comissao, ator, gatilho, guarda nem id interno"))))

(deftest a-rota-publica-devolve-o-rito-e-o-wire-e-fechado
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (transicionar! ente tid pid "despachar")
    (drenar!)
    (let [r (GET-ficha ente pid)
          corpo (ler-json r)]
      (is (= 200 (:status r)))
      (is (= "analise_comissoes" (:estado corpo)))
      (is (= {:chave "analise_comissoes" :rotulo "Em análise nas comissões" :terminal false}
             (get-in corpo [:rito :atual])))
      (is (= ["Recebida pela Mesa Diretora" "Em análise nas comissões" "Pronta para o Plenário"]
             (mapv :rotulo (get-in corpo [:rito :etapas])))))
    (testing "o contrato de saida e' fechado: rito com chave estranha reprova no adapter (bug de servidor)"
      (let [m (assoc (materia ente pid) :rito (assoc (:rito (materia ente pid)) :responsavel "Fulano"))]
        (is (thrown? clojure.lang.ExceptionInfo (adapter/ficha->wire m nil)))))))

(deftest materia-sem-evento-novo-fica-sem-rito-e-a-rota-omite-o-campo-ou-o-devolve-nulo
  (let [ente (random-uuid) pid (random-uuid)]
    ;; protocolada ANTERIOR ao campo: nenhum `rito` no payload (sem backfill, a tela cai no comportamento atual)
    (projetar! ente "proposicao.protocolada"
               {:proposicao-id (str pid) :tipo "projeto_lei" :ano 2026 :sequencial 4
                :urn-lex (str "urn:lex:br;ce;fortaleza:projeto.lei:2026;" (random-uuid))
                :ementa "Materia anterior ao rito no evento" :estado "protocolada"
                :protocolada-em "2026-09-01T12:00:00Z"})
    (is (nil? (:rito (materia ente pid))))
    (let [corpo (ler-json (GET-ficha ente pid))]
      (is (nil? (:rito corpo)) "sem rito: a ficha segue valida, o front usa o mapa de hoje"))))

(deftest transicao-sem-rito-nao-deixa-um-rito-velho-apontando-a-etapa-anterior
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (is (some? (:rito (materia ente pid))))
    ;; transicao de um produtor antigo (ou rito malformado descartado): estado muda, rito nao vem
    (projetar! ente "proposicao.transicionou"
               {:proposicao-id (str pid) :template-id (str tid) :de "recebida_na_mesa" :para "analise_comissoes"
                :gatilho "despachar" :transicao-id (str (random-uuid)) :para-nome "Em análise nas comissões"
                :ocorrido-em (str (Instant/now))})
    (is (= "analise_comissoes" (:estado (materia ente pid))))
    (is (nil? (:rito (materia ente pid)))
        "rito velho (atual = recebida) ao lado do estado novo seria afirmar etapa errada: sem rito novo, sem rito")))

(deftest rito-malformado-nunca-lanca-no-relay-compartilhado
  (let [ente (random-uuid) tid (rito! ente) pid (protocolar! ente)]
    (drenar!)
    (with-log
      (doseq [ruim ["texto" 42 [] {} {:ordem-unica "talvez" :etapas "x"}
                    {:ordem-unica true :etapas [{:chave "a"}] :atual nil :anteriores nil :proximas []}
                    ;; chave estranha: o portal e' publico, so' entra o que o contrato diz
                    {:ordem-unica true :etapas [{:chave "a" :rotulo "A" :terminal false :comissao "CCJ"}]
                     :atual nil :anteriores nil :proximas []}]]
        (projetar! ente "proposicao.transicionou"
                   {:proposicao-id (str pid) :template-id (str tid) :de "recebida_na_mesa" :para "analise_comissoes"
                    :gatilho "despachar" :transicao-id (str (random-uuid)) :para-nome "Em análise nas comissões"
                    :ocorrido-em (str (Instant/now)) :rito ruim})
        (is (nil? (:rito (materia ente pid))) (str "rito malformado vira nenhum rito: " (pr-str ruim))))
      (is (logged? 'oplenario.transparencia.components.repositorio :warn #"rito")))
    (is (= "analise_comissoes" (:estado (materia ente pid))) "o resto do evento foi projetado")
    (testing "e o relay segue: a proxima transicao boa (a real, do legislativo) e' projetada com o rito"
      (transicionar! ente tid pid "despachar")
      (drenar!)
      (is (= etapa-analise (:atual (:rito (materia ente pid))))))))

(deftest o-rito-fica-so-na-materia-da-sua-casa
  (let [a (random-uuid) b (random-uuid)
        _ (rito! a) pid-a (protocolar! a)]
    (rito! b)
    (protocolar! b)
    (drenar!)
    (is (some? (:rito (materia a pid-a))))
    (is (nil? (materia b pid-a)) "a Casa B nao ve a materia da Casa A, nem o rito dela")))

(deftest a-listagem-publica-nao-carrega-o-rito
  (let [ente (random-uuid) _ (rito! ente) _ (protocolar! ente)]
    (drenar!)
    (let [lista (transparencia-repo/listar-materias *repo-transparencia* ente #{})]
      (is (seq (:materias lista)))
      (is (every? #(not (contains? % :rito)) (:materias lista))
          "o rito so' vai na ficha: a lista (ate' 200 linhas) nao paga o jsonb"))))
