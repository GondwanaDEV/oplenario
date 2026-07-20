(ns oplenario.paineis.notificacao-caixa-test
  "INTEGRACAO (PG real) — Onda E fatia 1: a tabela da INBOX (`paineis.notificacao_caixa`, mig 0062).
  Task 1 prova SO' o contrato da TABELA: isolamento de tenant (FORCE RLS + WITH CHECK) e a UNIQUE
  (ente_id, idempotency_key) que torna o redrive um no-op. As fns de `db/` entram nas Tasks 4/6/7."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as paineis-repo]
            [oplenario.paineis.diplomat.consumers :as paineis-consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *paineis* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*      (:ds c)
                *paineis* (paineis-repo/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- inserir! [ente destinatario chave]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (jdbc/execute-one! tx
        ["INSERT INTO paineis.notificacao_caixa
            (id, ente_id, destinatario_identidade_id, categoria, assunto, corpo,
             objeto_tipo, objeto_id, idempotency_key)
          VALUES (?, ?, ?, 'norma_publicada', 'assunto', 'corpo', 'proposicao', ?, ?)
          ON CONFLICT (ente_id, idempotency_key) DO NOTHING
          RETURNING id"
         (random-uuid) ente destinatario (random-uuid) chave]))))

(defn- contar [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (:c (jdbc/execute-one! tx ["SELECT count(*) AS c FROM paineis.notificacao_caixa"])))))

(deftest linha-nasce-nao-lida
  (let [ente (random-uuid) dest (random-uuid)]
    (is (some? (inserir! ente dest "k1")) "insercao devolve a linha")
    (is (= 1 (contar ente)))
    (is (nil? (tenancy/com-tenant* *ds* ente
                (fn [tx] (:notificacao_caixa/lida_em
                          (jdbc/execute-one! tx ["SELECT lida_em FROM paineis.notificacao_caixa"])))))
        "lida_em nasce NULL = nao lida")))

(deftest unique-por-chave-de-idempotencia
  (let [ente (random-uuid) dest (random-uuid)]
    (inserir! ente dest "mesma-chave")
    (is (nil? (inserir! ente dest "mesma-chave")) "ON CONFLICT DO NOTHING -> 2a insercao e' no-op")
    (is (= 1 (contar ente)) "uma unica linha para a mesma chave logica")))

(deftest isolamento-de-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid) dest (random-uuid)]
    (inserir! ente-a dest "k-a")
    (is (= 1 (contar ente-a)))
    (is (= 0 (contar ente-b)) "a notificacao de uma Casa nunca aparece na outra (FORCE RLS)")))

(deftest with-check-barra-escrita-cross-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid)]
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente-a
            (fn [tx]
              (jdbc/execute-one! tx
                ["INSERT INTO paineis.notificacao_caixa
                    (id, ente_id, destinatario_identidade_id, categoria, assunto, corpo,
                     objeto_tipo, objeto_id, idempotency_key)
                  VALUES (?, ?, ?, 'norma_publicada', 'a', 'c', 'proposicao', ?, 'k-forjada')"
                 (random-uuid) ente-b (random-uuid) (random-uuid)]))))
        "WITH CHECK barra gravar linha de OUTRO ente mesmo com o GUC do proprio")))

;; ---------- Task 4: o PROJETOR da inbox (2o consumidor de `notificacao.requisitada`) ----------

(defn- drenar! []
  (outbox/drenar! *ds* (paineis-consumers/registrar {})))

(defn- emitir! [ente payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx
               (eventos/evento "notificacao.requisitada" ente payload)))))

(defn- caixa [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT destinatario_identidade_id, categoria, assunto, corpo,
                                        objeto_tipo, objeto_id, idempotency_key, lida_em
                                 FROM paineis.notificacao_caixa ORDER BY criado_em"]))))

(defn- payload-in-app [dest chave]
  {:destinatario-identidade-id (str dest) :canal "in_app" :consent-base "vinculo"
   :idempotency-key chave :categoria "norma_publicada"
   :assunto "A sua proposicao virou lei" :corpo "Lei 3/2026 — Dispoe sobre X."
   :objeto-tipo "proposicao" :objeto-id (str (random-uuid))})

(deftest projeta-in-app-na-inbox
  (let [ente (random-uuid) dest (random-uuid)]
    (emitir! ente (payload-in-app dest "k-inbox-1"))
    (drenar!)
    (let [linhas (caixa ente)]
      (is (= 1 (count linhas)))
      (let [l (first linhas)]
        (is (= dest (:notificacao_caixa/destinatario_identidade_id l)))
        (is (= "norma_publicada" (:notificacao_caixa/categoria l)))
        (is (= "A sua proposicao virou lei" (:notificacao_caixa/assunto l)))
        (is (= "proposicao" (:notificacao_caixa/objeto_tipo l)))
        (is (nil? (:notificacao_caixa/lida_em l)) "nasce nao lida")))))

(deftest projecao-e-idempotente-no-redrive
  ;; criterio de aceitacao 1: re-executar o MESMO evento logico nao cria uma segunda notificacao.
  (let [ente (random-uuid) dest (random-uuid) p (payload-in-app dest "k-inbox-repetida")]
    (emitir! ente p) (drenar!)
    (emitir! ente p) (drenar!)  ; envelope NOVO (idempotency-key do envelope e' aleatoria) -> o consumer roda
    (is (= 1 (count (caixa ente))) "a UNIQUE (ente_id, idempotency_key) torna a 2a projecao um no-op")))

(deftest canal-email-nao-entra-na-inbox
  (let [ente (random-uuid)]
    (emitir! ente (assoc (payload-in-app (random-uuid) "k-email") :canal "email"))
    (drenar!)
    (is (empty? (caixa ente)) "cada projetor trata so' o seu canal (spec §4.3)")))

(deftest payload-malformado-nao-envenena-o-relay
  ;; o relay e' COMPARTILHADO: um payload ruim tem de ser tolerado (log + nil), nunca propagado.
  (let [ente (random-uuid)]
    (emitir! ente (assoc (payload-in-app (random-uuid) "k-ruim") :objeto-id "nao-e-uuid"))
    (is (some? (drenar!)) "drenar! nao lanca")
    (is (empty? (caixa ente)) "nada foi gravado")
    ;; e o bus segue drenando o PROXIMO evento normalmente
    (emitir! ente (payload-in-app (random-uuid) "k-depois"))
    (drenar!)
    (is (= 1 (count (caixa ente))) "o evento seguinte projeta — o relay nao travou")))

;; ---------- Correcao (achado 2): prova a escolha de `catch Throwable` (nao `Exception`) ----------
;;
;; O teste acima (`payload-malformado-nao-envenena-o-relay`) so' exercita `:objeto-id "nao-e-uuid"`, que
;; lanca `IllegalArgumentException` — uma `Exception` comum, ANTES de qualquer SQL. Passaria identico com
;; um catch de `Exception`; nao prova nada sobre a escolha de `Throwable` que a docstring de
;; `projetar-inbox!` justifica. Os dois casos abaixo fecham essa lacuna.

(deftest payload-sem-idempotency-key-prova-o-catch-throwable
  ;; (a) a `:pre` de inserir! lanca `AssertionError` — um `Error`, IRMAO de `Exception` sob `Throwable`,
  ;; NAO capturado por `(catch Exception ...)`. Este e' o caso que de fato prova o `Throwable`.
  (let [ente (random-uuid)]
    (emitir! ente (dissoc (payload-in-app (random-uuid) "k-sem-chave") :idempotency-key))
    (is (some? (drenar!)) "drenar! nao lanca mesmo com AssertionError (Error) dentro do handler")
    (is (empty? (caixa ente)) "nada foi gravado — a :pre barrou antes do INSERT")))

(deftest payload-com-not-null-nulo-nao-envenena-o-relay
  ;; (b) Achado 1: `assunto` e' NOT NULL na tabela (mig 0062), mas a `:pre` de `inserir!` nao a checava.
  ;; ANTES da correcao do achado 1, este teste fica VERMELHO: o payload chega ao INSERT, viola NOT NULL
  ;; (SQLSTATE 23502), a tx do relay fica ABORTADA, e o UPDATE seguinte do proprio relay (marcar
  ;; processed_at) lanca "current transaction is aborted" por CIMA do catch Throwable do handler — engolir
  ;; a excecao nao desfaz uma tx ja abortada. DEPOIS da correcao (a `:pre` passa a cobrir toda NOT NULL
  ;; vinda do payload), a falha vira AssertionError ANTES de qualquer SQL: nao ha tx para abortar, e o
  ;; catch Throwable do handler funciona como esperado.
  (let [ente (random-uuid)]
    (emitir! ente (assoc (payload-in-app (random-uuid) "k-assunto-nulo") :assunto nil))
    (is (some? (drenar!)) "drenar! nao lanca — a :pre barra antes do SQL, a tx nunca aborta")
    (is (empty? (caixa ente)) "nada foi gravado")
    ;; e o bus segue drenando o PROXIMO evento normalmente
    (emitir! ente (payload-in-app (random-uuid) "k-depois-assunto-nulo"))
    (drenar!)
    (is (= 1 (count (caixa ente))) "o evento seguinte projeta — o relay nao travou")))

;; ---------- Task 6: leitura "as minhas notificacoes" ----------

(defn- semear! [ente dest n]
  (dotimes [i n] (inserir! ente dest (str "k-leitura-" i))))

(deftest minhas-notificacoes-so-traz-as-do-proprio-destinatario
  (let [ente (random-uuid) eu (random-uuid) outro (random-uuid)]
    (semear! ente eu 2)
    (inserir! ente outro "k-do-outro")
    (let [{:keys [notificacoes nao-lidas]} (paineis-repo/minhas-notificacoes *paineis* ente eu)]
      (is (= 2 (count notificacoes)) "criterio 4: so' as minhas — a do outro ator nunca aparece")
      (is (= 2 nao-lidas) "contagem de nao lidas")
      (is (every? #(= eu (:destinatario-identidade-id %)) notificacoes)))))

(deftest minhas-notificacoes-vazio
  (let [{:keys [notificacoes nao-lidas]} (paineis-repo/minhas-notificacoes *paineis* (random-uuid) (random-uuid))]
    (is (= [] notificacoes) "criterio 3: lista vazia")
    (is (= 0 nao-lidas) "criterio 3: contagem 0")))

(deftest minhas-notificacoes-mais-recentes-primeiro-com-teto-no-sql
  (let [ente (random-uuid) eu (random-uuid)]
    (semear! ente eu 55)
    (let [{:keys [notificacoes nao-lidas]} (paineis-repo/minhas-notificacoes *paineis* ente eu)]
      (is (= 50 (count notificacoes)) "teto 50 aplicado no SQL (LIMIT), nunca em Clojure depois do fetch")
      (is (= 55 nao-lidas) "a contagem NAO e' limitada pelo teto — a UI nunca mente sobre o que existe")
      (is (apply >= (map (comp #(.toEpochMilli ^java.time.Instant %) :criado-em) notificacoes))
          "mais recentes primeiro"))))

(deftest isolamento-de-tenant-na-leitura
  (let [ente-a (random-uuid) ente-b (random-uuid) eu (random-uuid)]
    (inserir! ente-a eu "k-tenant-a")
    (is (= 1 (count (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente-a eu)))))
    (is (= 0 (count (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente-b eu))))
        "criterio 5: a MESMA identidade em outra Casa nao ve' nada")))

;; ---------- Task 7: marcar como lida ----------

(defn- id-da-unica [ente dest]
  (:id (first (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente dest)))))

(deftest marcar-lida-e-idempotente
  (let [ente (random-uuid) eu (random-uuid)]
    (inserir! ente eu "k-lida")
    (let [id (id-da-unica ente eu)
          r1 (paineis-repo/marcar-notificacao-lida! *paineis* ente {:id id :destinatario-identidade-id eu})
          r2 (paineis-repo/marcar-notificacao-lida! *paineis* ente {:id id :destinatario-identidade-id eu})]
      (is (some? (:lida-em r1)) "1a chamada carimba")
      (is (= (:lida-em r1) (:lida-em r2))
          "criterio 6: 2a chamada nao muda lida_em nem devolve erro (COALESCE preserva o 1o carimbo)")
      (is (= 0 (:nao-lidas (paineis-repo/minhas-notificacoes *paineis* ente eu))) "sai da contagem"))))

(deftest marcar-lida-de-outro-destinatario-e-nil
  (let [ente (random-uuid) eu (random-uuid) outro (random-uuid)]
    (inserir! ente outro "k-do-outro-2")
    (let [id (id-da-unica ente outro)]
      (is (nil? (paineis-repo/marcar-notificacao-lida! *paineis* ente {:id id :destinatario-identidade-id eu}))
          "criterio 4: nem com o id em maos — o guard de posse esta' no MESMO WHERE do tenant")
      (is (nil? (:lida-em (first (:notificacoes (paineis-repo/minhas-notificacoes *paineis* ente outro)))))
          "a notificacao do outro continua NAO lida"))))

(deftest marcar-lida-id-inexistente-e-nil
  ;; nao basta random/random contra um banco vazio — qualquer implementacao que devolva nil passaria, e o
  ;; teste nao se distinguiria de "nada foi inserido". Insere a linha em OUTRO ente e usa o id CERTO com o
  ;; ente errado: isso prova o recorte de tenant no WHERE, nao so' a ausencia de dado.
  (let [ente-a (random-uuid) ente-b (random-uuid) dest (random-uuid)]
    (inserir! ente-a dest "k-inexistente-tenant")
    (let [id (id-da-unica ente-a dest)]
      (is (nil? (paineis-repo/marcar-notificacao-lida! *paineis* ente-b
                  {:id id :destinatario-identidade-id dest}))
          "id existe de fato — so' que em OUTRO ente; o WHERE de tenant barra, nao so' a ausencia de dado"))))
