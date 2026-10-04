(ns oplenario.auditoria.janela-de-perda-test
  "INTEGRACAO (PG real) — ADR-0017, adendo de 04/10/2026: o ato nao fica mais fora da trilha quando o registro dele
  falha. Atravessa a borda Pedestal com as rotas passadas por `com-tentativa` (como o host faz em `rotas/montar`) e o
  interceptor global da trilha.

  A JANELA: o handler commita o ato e a gravacao do registro lanca (ou o processo cai). Antes, o ato existia e a trilha
  nao tinha linha nenhuma. Agora a TENTATIVA ja' esta' na corrente, commitada antes do handler, e fica sem desfecho: a
  leitura a mostra e a conferencia a conta."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.auditoria.components.repositorio :as repo]
            [oplenario.auditoria.db.registro :as db]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.auditoria.logic :as logic]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.sql Timestamp)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def ^:dynamic *ds* nil)

(declare registro-antigo!)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- rp
  "O repositorio de verdade. `tolerancia-s` 0 = a tentativa sem desfecho e' acusada na hora (o padrao espera 2 min)."
  ([] (rp 0))
  ([tolerancia-s] (repo/map->RepoAuditoriaPg {:datasource {:ds *ds*} :tolerancia-s tolerancia-s})))

(defn- rp-que-falha
  "O repositorio de verdade, menos na gravacao que `falha?` escolher: ali a gravacao LANCA (o banco caiu entre o ato e o
  registro; o processo morreu antes de gravar)."
  [falha?]
  (let [real (rp)]
    (reify repo/RepoAuditoria
      (registrar! [_ r] (if (falha? r) (throw (ex-info "banco fora" {})) (repo/registrar! real r)))
      (trilha [_ e f l] (repo/trilha real e f l))
      (total [_ e] (repo/total real e))
      (verificar [_ e] (repo/verificar real e))
      (sem-desfecho [_ e] (repo/sem-desfecho real e))
      (selos-do-dia [_ e n] (repo/selos-do-dia real e n)))))

(def desfecho? #(not= logic/iniciado (:decisao %)))
(def tentativa? #(= logic/iniciado (:decisao %)))

(def maria (random-uuid))   ; secretaria
(def rui (random-uuid))     ; vereador
(def ana (random-uuid))     ; auditora

(def papeis-de {maria #{"secretario"} rui #{"vereador"} ana #{"auditor"}})

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ iid] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis (papeis-de iid)})))

(def prop "30000000-0000-0000-0000-000000000003")

(defn- servico
  "`atos` = o \"banco do dominio\": o handler acrescenta nele quando o ato acontece. `rp-escrita` = o repositorio que o
  interceptor usa para GRAVAR; a leitura (`/auditoria`) usa sempre o de verdade."
  [{:keys [atos rp-escrita rp-leitura exigir? sem-tentativa?] :or {atos (atom [])}}]
  (let [auth  (it/autenticacao (idp-dev/idp-dev) (fake-identidade))
        leitura (or rp-leitura (rp))
        rotas (into #{;; "um voto": o handler abre a propria transacao de tenant no MESMO pool e trabalha ~5 ms nela
                      ["/votos" :post
                       [auth (fn [req]
                               (tenancy/com-tenant* *ds* (get-in req [:ator :ente-id])
                                 #(jdbc/execute-one! % ["SELECT pg_sleep(0.005)"]))
                               (swap! atos conj :voto)
                               (http/json-resposta 201 {:ok true}))]
                       :route-name :legislativo/registrar-voto]
                      ["/materias/:proposicao-id/despachar" :post
                       [auth (fn [_] (swap! atos conj :despacho)
                               (assoc (http/json-resposta 201 {:ok true}) :auditoria {:rotulo "PL 7/2026"}))]
                       :route-name :legislativo/despachar]
                      ["/materias/:proposicao-id" :delete
                       [auth (fn [_] (throw (ex-info "quebrou no meio" {})))]
                       :route-name :legislativo/retirar]
                      ["/so-secretaria" :post [auth (it/exige-papel "secretario") (fn [_] (http/json-resposta 200 {}))]
                       :route-name :legislativo/so-secretaria]
                      ["/ler" :get [auth (fn [_] (http/json-resposta 200 {}))] :route-name :legislativo/ler]}
                    (auditoria-http/rotas {:auth auth :repo-auditoria leitura
                                           :resolver-ente-publico #(parse-uuid (str %))
                                           :casa-existe? (constantly true)
                                           :seams {:nome-de {maria "Maria Secretária" rui "Rui Vereador" ana "Ana Auditora"}}}))]
    (-> (http/servico (config/carregar)
                      ;; como o host: a tabela inteira passa por `com-tentativa` (rotas/montar)
                      (if sem-tentativa? rotas (auditoria-http/com-tentativa rotas))
                      (it/globais-com [(auditoria-http/interceptor (or rp-escrita (rp)) {:exigir-tentativa? exigir?})]))
        ph/create-server ::ph/service-fn)))

(defn- como [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))
   "content-type" "application/json"})

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- despachar! [svc ente iid]
  (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}" :headers (como ente iid)))

(defn- trilha [svc ente & [q]]
  (ler (pt/response-for svc :get (str "/auditoria" (when q (str "?" q))) :headers (como ente ana))))

(defn- integridade [svc ente]
  (ler (pt/response-for svc :get "/auditoria/integridade" :headers (como ente ana))))

(defn- corrente
  "A corrente crua da Casa, em ordem — o que esta' no banco, sem o filtro da tela."
  [ente]
  (tenancy/com-tenant* *ds* ente
    #(mapv (fn [l] (update l :detalhe comum/jsonb->kw))
           (jdbc/execute! % ["SELECT seq, acao, classe, decisao, status_http, detalhe, rotulo FROM auditoria.registro
                              WHERE ente_id = ? ORDER BY seq" ente]
                          {:builder-fn rs/as-unqualified-lower-maps}))))

(deftest o-ato-cujo-registro-falha-nao-fica-invisivel
  ;; A JANELA FECHADA. A gravacao do DESFECHO lanca depois de o ato ter acontecido.
  (let [ente (random-uuid) atos (atom [])
        svc (servico {:atos atos :rp-escrita (rp-que-falha desfecho?)})
        r (despachar! svc ente maria)]
    (is (= 201 (:status r)) "a resposta nao muda: o ato aconteceu")
    (is (= [:despacho] @atos) "o ato existe")
    (testing "a corrente TEM o rastro do ato: a tentativa, gravada antes do handler"
      (is (= [["legislativo/despachar" "escrita" "iniciado" nil]]
             (mapv (juxt :acao :classe :decisao :status_http) (corrente ente)))
          "sem a tentativa a corrente estaria vazia — o ato sumiria da trilha sem sinal nenhum"))
    (testing "a leitura da trilha a acusa, em palavras do contrato (nunca o enum do banco)"
      (let [t (trilha svc ente)]
        (is (= 1 (:total t)))
        (is (= ["legislativo/despachar" "escrita" "sem_desfecho" "Maria Secretária"]
               ((juxt :acao :classe :decisao (comp :nome :ator)) (first (:registros t)))))
        (is (= prop (get-in (first (:registros t)) [:recurso :id])) "com o recurso do caminho: da' para ir conferir o ato")))
    (testing "a conferencia da cadeia a conta — a corrente esta' integra E falta um desfecho"
      (is (= {:integra true :sem-desfecho 1 :primeiro-sem-desfecho 1}
             (select-keys (integridade svc ente) [:integra :sem-desfecho :primeiro-sem-desfecho]))))
    (testing "o filtro 'sem desfecho' traz so' ela"
      (despachar! (servico {}) ente maria)   ; um ato normal, com desfecho, na mesma Casa
      (is (= [1] (mapv :seq (:registros (trilha svc ente "classe=sem_desfecho")))))
      (is (= 1 (:total (trilha svc ente "classe=sem_desfecho")))))
    (testing "a exportacao a traz em palavras"
      (let [csv (:body (pt/response-for svc :get "/auditoria/exportar.csv" :headers (como ente ana)))]
        (is (str/includes? csv ",sem desfecho registrado,"))
        (is (not (str/includes? csv "iniciado")))))))

(deftest o-caminho-normal-tentativa-e-desfecho-e-uma-linha-na-tela
  (let [ente (random-uuid) svc (servico {})]
    (is (= 201 (:status (despachar! svc ente maria))))
    (let [[t d] (corrente ente)]
      (is (= ["iniciado" nil {:metodo "POST"}] ((juxt :decisao :status_http :detalhe) t)))
      (is (= ["permitido" 201 {:metodo "POST" :tentativa 1} "PL 7/2026"] ((juxt :decisao :status_http :detalhe :rotulo) d))
          "o desfecho aponta a tentativa pelo seq, dentro do detalhe (que entra no selo)"))
    (let [t (trilha svc ente)]
      (is (= 1 (:total t)) "a tentativa com desfecho nao e' linha da tela: um ato, uma linha")
      (is (= [[2 "permitido"]] (mapv (juxt :seq :decisao) (:registros t)))))
    (is (= {:integra true :total 3 :sem-desfecho 0 :primeiro-sem-desfecho nil}
           (select-keys (integridade svc ente) [:integra :total :sem-desfecho :primeiro-sem-desfecho]))
        "a corrente tem os 2 registros do ato + a leitura da trilha pelo auditor; todos conferem")))

(deftest o-handler-que-lanca-tem-desfecho-falhou-apontando-a-tentativa
  (let [ente (random-uuid) svc (servico {})]
    (is (= 500 (:status (pt/response-for svc :delete (str "/materias/" prop) :headers (como ente maria)))))
    (is (= [["iniciado" nil nil] ["falhou" 500 1]]
           (mapv (juxt :decisao :status_http (comp :tentativa :detalhe)) (corrente ente)))
        "a tentativa atravessa o caminho de erro: o desfecho a encontra")
    (is (zero? (:sem-desfecho (integridade svc ente))))))

(deftest a-trilha-fora-nao-para-a-casa
  ;; O PADRAO: a tentativa nao grava (a trilha esta' fora) -> fica no log e o ato ACONTECE. A sessao ao vivo nao pode
  ;; cair porque a auditoria caiu.
  (testing "so' a tentativa falha: o ato segue e o desfecho entra como antes do adendo (sem apontamento)"
    (let [ente (random-uuid) atos (atom [])
          svc (servico {:atos atos :rp-escrita (rp-que-falha tentativa?)})
          r (despachar! svc ente maria)]
      (is (= 201 (:status r)))
      (is (= [:despacho] @atos) "o handler RODOU")
      (is (= {:ok true} (ler r)))
      (is (= [["permitido" 201 nil]] (mapv (juxt :decisao :status_http (comp :tentativa :detalhe)) (corrente ente))))))
  (testing "a trilha inteira fora: o ato segue, nada entra na corrente (so' o log acusa)"
    (let [ente (random-uuid) atos (atom [])
          svc (servico {:atos atos :rp-escrita (rp-que-falha (constantly true))})]
      (is (= 201 (:status (despachar! svc ente maria))))
      (is (= [:despacho] @atos))
      (is (= [] (corrente ente)))))
  (testing "sem a chave nos seams (nil) tambem nao bloqueia: o bloqueio e' opt-in explicito"
    (let [ente (random-uuid) atos (atom [])
          svc (servico {:atos atos :exigir? nil :rp-escrita (rp-que-falha tentativa?)})]
      (is (= 201 (:status (despachar! svc ente maria)))))))

(deftest a-faxina-do-dia-novo-nao-derruba-o-registro-nem-o-pedido
  ;; o primeiro registro do dia (agora, a tentativa) fecha o dia anterior; anular IPs e criar particoes roda depois,
  ;; em tx propria: se falhar, o registro, o selo do dia e o pedido seguem
  (let [ente (random-uuid) atos (atom []) svc (servico {:atos atos})]
    (registro-antigo! ente)
    (with-redefs [db/anular-ips-antigos! (fn [& _] (throw (ex-info "faxina quebrou" {})))]
      (is (= 201 (:status (despachar! svc ente maria)))))
    (is (= [:despacho] @atos))
    (is (= ["permitido" "iniciado" "permitido"] (mapv :decisao (corrente ente))) "a tentativa e o desfecho foram gravados")
    (is (= [1] (mapv :seq (repo/selos-do-dia (rp) ente 30))) "e o selo do dia que fechou tambem")
    (is (:integra (repo/verificar (rp) ente)))))

(defn- rodada!
  "21 escritas SIMULTANEAS da mesma Casa (um voto por vereador, todas soltas no mesmo instante). Devolve os status, o
  tempo total e o maior tempo individual, em ms."
  [svc ente]
  (let [largada (java.util.concurrent.CountDownLatch. 1)
        t0 (atom nil)
        fs (mapv (fn [_]
                   (future
                     (.await largada)
                     (let [i (System/nanoTime)
                           r (pt/response-for svc :post "/votos" :body "{}" :headers (como ente maria))]
                       {:status (:status r) :ms (/ (- (System/nanoTime) i) 1e6)})))
                 (range 21))]
    (Thread/sleep 200)                      ; todas as threads paradas na largada
    (reset! t0 (System/nanoTime))
    (.countDown largada)
    (let [rs (mapv #(deref % 30000 {:status :timeout :ms 30000.0}) fs)]
      {:status (mapv :status rs)
       :total-ms (/ (- (System/nanoTime) @t0) 1e6)
       :maior-ms (apply max (map :ms rs))})))

(deftest vinte-e-uma-escritas-simultaneas-da-mesma-casa
  ;; A CONTENCAO medida: a corrente serializa as gravacoes por Casa (advisory lock), e a tentativa dobrou as gravacoes
  ;; por escrita. 21 votos de uma vez, pool de 10 conexoes, handler com transacao de tenant propria.
  (let [com (servico {}) sem (servico {:sem-tentativa? true})]
    (rodada! com (random-uuid)) (rodada! sem (random-uuid))        ; aquecimento (JIT, pool), fora da medida
    (let [medidas (vec (for [_ (range 3)]
                         (let [e-sem (random-uuid) e-com (random-uuid)
                               s (rodada! sem e-sem) c (rodada! com e-com)]
                           (testing "todas concluem — nenhuma por deadlock ou timeout de lock"
                             (is (= (repeat 21 201) (:status c)))
                             (is (= (repeat 21 201) (:status s))))
                           (testing "com a tentativa: 21 tentativas, cada uma com o SEU desfecho, e a corrente integra"
                             (let [c* (corrente e-com)
                                   tentativas (set (map :seq (filter #(= "iniciado" (:decisao %)) c*)))
                                   apontadas (keep (comp :tentativa :detalhe) c*)]
                               (is (= 42 (count c*)))
                               (is (= 21 (count tentativas)))
                               (is (= tentativas (set apontadas)) "cada desfecho aponta uma tentativa que existe")
                               (is (= 21 (count apontadas) (count (distinct apontadas))) "e nenhuma e' apontada duas vezes")
                               (is (= {:total 0 :primeiro nil} (repo/sem-desfecho (rp) e-com)))
                               (is (= {:integra true :total 42 :quebra-em nil}
                                      (select-keys (repo/verificar (rp) e-com) [:integra :total :quebra-em])))))
                           (testing "sem a tentativa (como era): 21 registros, corrente integra"
                             (is (= {:integra true :total 21 :quebra-em nil}
                                    (select-keys (repo/verificar (rp) e-sem) [:integra :total :quebra-em]))))
                           {:sem (dissoc s :status) :com (dissoc c :status)})))]
      (doseq [[i m] (map-indexed vector medidas)]
        (println (format "CONTENCAO rodada %d | sem tentativa: total %.0f ms, maior %.0f ms | com tentativa: total %.0f ms, maior %.0f ms"
                         (inc i) (:total-ms (:sem m)) (:maior-ms (:sem m)) (:total-ms (:com m)) (:maior-ms (:com m)))))
      (is (every? #(< (:maior-ms (:com %)) 5000) medidas) "nenhum voto espera segundos pela fila da trilha"))))

(deftest com-a-exigencia-ligada-sem-o-rastro-da-tentativa-o-ato-nao-comeca
  ;; AUDITORIA_EXIGIR_TENTATIVA=true: o modo que recusa
  (let [ente (random-uuid) atos (atom [])
        svc (servico {:atos atos :exigir? true :rp-escrita (rp-que-falha tentativa?)})
        r (despachar! svc ente maria)]
    (is (= 503 (:status r)))
    (is (= [] @atos) "o handler NAO rodou")
    (is (= {:erro "o registro de auditoria esta indisponivel; nada foi feito"} (ler r)))
    (is (= [["falhou" 503 nil]] (mapv (juxt :decisao :status_http (comp :tentativa :detalhe)) (corrente ente)))
        "a recusa fica registrada como qualquer falha (quando a trilha volta a gravar)")))

(deftest negacao-leitura-e-leitura-sensivel-seguem-como-eram
  (let [ente (random-uuid) svc (servico {})]
    (is (= 403 (:status (pt/response-for svc :post "/so-secretaria" :body "{}" :headers (como ente rui)))))
    (is (= 200 (:status (pt/response-for svc :get "/ler" :headers (como ente maria)))))
    (is (= 401 (:status (pt/response-for svc :post (str "/materias/" prop "/despachar") :body "{}"))))
    (trilha svc ente)   ; o auditor le a trilha da Casa: leitura sensivel
    (is (= [["legislativo/so-secretaria" "negacao" "negado" 403 {:metodo "POST"}]
            ["auditoria/trilha" "leitura_sensivel" "permitido" 200 {:metodo "GET"}]]
           (mapv (juxt :acao :classe :decisao :status_http :detalhe) (corrente ente)))
        "a negacao antes do handler e a leitura sensivel: um registro so', sem tentativa; leitura comum e anonimo, nada")))

(deftest a-tentativa-em-curso-nao-e-acusada-antes-da-tolerancia
  ;; o pedido ainda pode estar rodando: so' depois da tolerancia a tentativa sem desfecho aparece
  (let [ente (random-uuid)
        svc-grava (servico {:rp-escrita (rp-que-falha desfecho?)})
        svc-paciente (servico {:rp-leitura (rp 3600)})
        svc-na-hora  (servico {:rp-leitura (rp 0)})]
    (despachar! svc-grava ente maria)
    (let [t (trilha svc-paciente ente)]
      (is (zero? (:total t)) "dentro da tolerancia: ainda nao e' acusada")
      (is (= 1 (:total-da-casa t)) "mas a corrente nunca a escondeu: ela conta no total selado"))
    (is (zero? (:sem-desfecho (integridade svc-paciente ente))))
    (is (= 1 (:sem-desfecho (integridade svc-na-hora ente))) "passada a tolerancia: acusada")))

(defn- registro-antigo!
  "Um registro no formato de ANTES do adendo (sem tentativa, sem apontamento), de dois dias atras."
  [ente]
  (let [r {:ente-id ente :seq 1 :id (random-uuid)
           :ocorrido-em (.truncatedTo (.minus (Instant/now) 2 ChronoUnit/DAYS) ChronoUnit/MICROS)
           :ator-tipo "pessoa" :identidade-id maria :papeis ["secretario"] :acao "legislativo/despachar"
           :classe "escrita" :decisao "permitido" :status-http 201 :canal "web" :campos [] :detalhe {:metodo "POST"}}
        selo (logic/selo-de "" r)]
    (tenancy/com-tenant* *ds* ente
      #(jdbc/execute-one! % ["INSERT INTO auditoria.registro (ente_id, seq, id, ocorrido_em, ator_tipo, identidade_id, papeis,
                              acao, classe, decisao, status_http, canal, detalhe, selo_anterior, selo)
                              VALUES (?,?,?,?,?,?,'{secretario}',?,?,?,?,?,?,'',?)"
                             ente 1 (:id r) (Timestamp/from ^Instant (:ocorrido-em r)) "pessoa" maria
                             "legislativo/despachar" "escrita" "permitido" 201 "web" (comum/->jsonb {:metodo "POST"}) selo]))))

(deftest a-conferencia-cobre-registros-antigos-e-novos
  (let [ente (random-uuid) svc (servico {})]
    (registro-antigo! ente)
    (despachar! svc ente maria)
    ;; direto no repositorio: ler pela borda acrescentaria a leitura sensivel do auditor a corrente
    (is (= {:integra true :total 3 :quebra-em nil} (select-keys (repo/verificar (rp) ente) [:integra :total :quebra-em]))
        "o registro antigo (sem tentativa) + a tentativa + o desfecho: a corrente confere inteira")
    (is (= {:total 0 :primeiro nil} (repo/sem-desfecho (rp) ente)))
    (is (= [1] (mapv :seq (repo/selos-do-dia (rp) ente 30)))
        "a tentativa, primeiro registro do dia novo, fechou o dia anterior na cabeca certa")
    (is (= 2 (:total (trilha svc ente))) "o ato antigo e o novo: uma linha cada")))

(deftest a-migration-vale-para-as-particoes-que-existem-e-para-as-futuras
  (let [q (fn [sql & ps] (jdbc/execute! *ds* (into [sql] ps) {:builder-fn rs/as-unqualified-lower-maps}))
        ;; uma particao FUTURA, criada depois da migration pelo mesmo caminho da producao
        _ (q "SELECT auditoria.garantir_particoes(now() + interval '30 months', 0)")
        particoes (mapv :nome (q "SELECT c.relname AS nome FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                                  WHERE i.inhparent = 'auditoria.registro'::regclass"))
        checks (q "SELECT c.relname AS nome, pg_get_constraintdef(k.oid) AS def
                   FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                   JOIN pg_constraint k ON k.conrelid = c.oid AND k.contype = 'c'
                   WHERE i.inhparent = 'auditoria.registro'::regclass AND pg_get_constraintdef(k.oid) LIKE '%decisao%'")
        indices (set (map :nome (q "SELECT tablename AS nome FROM pg_indexes
                                    WHERE schemaname = 'auditoria' AND indexdef LIKE '%tentativa%'")))]
    (is (>= (count particoes) 5) (str "a DEFAULT + os meses + a futura: " (count particoes) " particoes vistas"))
    (is (= (set particoes) (set (map :nome checks))) "toda particao tem UM check de decisao")
    (is (= (count particoes) (count checks)) "e so' um (o antigo saiu)")
    (is (every? #(str/includes? (:def %) "iniciado") checks) "que aceita a tentativa")
    (is (every? indices particoes) "e o indice do apontamento")
    (is (contains? indices "registro"))))
