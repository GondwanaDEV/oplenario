(ns oplenario.transparencia.fanout-notificacao-test
  "INTEGRACAO (PG real) — frente 'truncamento-familia', sitio (b): `fan-out-notificacao!`
  (transparencia/components/repositorio) e' um JOB (consumer do bus), nao uma listagem — o teto
  `teto-fanout` NAO vira campo `-total` publicavel (regra da familia: 'onde -total nao e' a resposta').
  A forma honesta aqui e' o sinal OBSERVAVEL: quando o corte de fato acontece, o servidor LOGA o
  residuo medido (via `contar-seguidores-ativos`, MESMO predicado da lista — regra 3), para que a
  ausencia de entrega nao seja invisivel a quem opera a plataforma. `teto-fanout` e' `def` simples (nao
  `^:const`) e testavel via `with-redefs`, mesmo padrao ja' usado noutras fatias desta frente para tetos
  privados sem aridade injetavel."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.components.repositorio :as repo]
            [oplenario.transparencia.db.acompanhamento :as db-acompanhamento]
            [oplenario.transparencia.db.materia :as db-materia]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(use-fixtures :each
  (fn [t]
    (jdbc/execute! *ds* ["TRUNCATE shared.outbox, transparencia.acompanhamento, transparencia.materia CASCADE"])
    (t)))

(defn- criar-materia! [tx ente pid]
  (db-materia/inserir! tx {:ente-id ente :proposicao-id pid :tipo "projeto_lei" :ano 2026
                           :sequencial 1 :urn-lex "urn:lex:br;ce;fortaleza:projeto.lei:2026;1"
                           :ementa "Dispoe sobre X" :estado "protocolada"}))

(defn- seguir! [tx ente pid seguidor]
  (db-acompanhamento/seguir! tx {:id (random-uuid) :ente-id ente :proposicao-id pid
                                 :seguidor-identidade-id seguidor :created-by seguidor}))

(defn- notificacoes-emitidas [ente]
  (:count (jdbc/execute-one! *ds*
            ["SELECT count(*)::int FROM shared.outbox WHERE ente_id = ? AND tipo = 'notificacao.requisitada'" ente])))

(defn- evento-transicao [ente pid]
  {:ente-id ente :payload {:proposicao-id (str pid) :para "aberta" :transicao-id (str (random-uuid))}})

;; ---------- abaixo do teto: sem corte, sem log ----------

(deftest fan-out-abaixo-do-teto-notifica-todos-e-nao-loga
  (with-redefs [repo/teto-fanout 3]
    (let [ente (random-uuid) pid (random-uuid)]
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (criar-materia! tx ente pid)
          (dotimes [_ 2] (seguir! tx ente pid (random-uuid)))))
      (with-log
        (tenancy/com-tenant* *ds* ente #(repo/fan-out-notificacao! % (evento-transicao ente pid)))
        (is (= 2 (notificacoes-emitidas ente)) "os 2 seguidores, todos abaixo do teto, sao notificados")
        (is (not (logged? 'oplenario.transparencia.components.repositorio :warn #"fan-out"))
            "sem corte, nao ha nada a logar")))))

;; ---------- alem do teto: o residuo NUNCA e' notificado, e o corte fica LOGADO ----------

(deftest fan-out-alem-do-teto-corta-e-loga-o-residuo-medido
  (with-redefs [repo/teto-fanout 3]
    (let [ente (random-uuid) pid (random-uuid)]
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (criar-materia! tx ente pid)
          (dotimes [_ 5] (seguir! tx ente pid (random-uuid)))))
      (with-log
        (tenancy/com-tenant* *ds* ente #(repo/fan-out-notificacao! % (evento-transicao ente pid)))
        (is (= 3 (notificacoes-emitidas ente))
            "so' os 3 do teto sao notificados — os outros 2 NUNCA recebem, sem paginacao/cursor")
        (is (logged? 'oplenario.transparencia.components.repositorio :warn #"fan-out")
            "o corte fica OBSERVAVEL: um log estruturado nomeia que o teto foi atingido")))))

;; ---------- a MESMA transicao, chamada de novo: o residuo NAO entra na rodada seguinte (nao ha cursor) ----------

(deftest fan-out-nao-tem-cursor-a-segunda-passada-notifica-os-MESMOS-3-de-sempre
  ;; Prova a natureza do defeito (docstring do briefing): diferente do sweep de prazo (site a), aqui NAO ha
  ;; estado por-seguidor que avance a cada chamada (a query e' sempre 'os N primeiros por uuid, estado=ativo') —
  ;; entao os seguidores 4 e 5 (por ordem de uuid) NUNCA sao alcancados por nenhuma rechamada futura. Isto e'
  ;; o que torna o corte um DEFEITO real (nao um teto honesto de paginacao), nao so' um numero grande.
  (with-redefs [repo/teto-fanout 3]
    (let [ente (random-uuid) pid (random-uuid)
          seguidores (vec (repeatedly 5 random-uuid))]
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (criar-materia! tx ente pid)
          (doseq [s seguidores] (seguir! tx ente pid s))))
      (let [alcancados-1a (tenancy/com-tenant* *ds* ente
                            #(db-acompanhamento/seguidores-ativos % ente pid 3))
            alcancados-2a (tenancy/com-tenant* *ds* ente
                            #(db-acompanhamento/seguidores-ativos % ente pid 3))]
        (is (= alcancados-1a alcancados-2a)
            "a 2a leitura devolve EXATAMENTE os mesmos 3 — nada 'avancou': o residuo fica para sempre de fora")))))

;; ---------- `contar-seguidores-ativos` usa o MESMO predicado de `seguidores-ativos` (regra 3) ----------

(deftest contar-seguidores-ativos-enxerga-o-mesmo-conjunto-que-a-lista-sem-teto
  (let [ente (random-uuid) pid (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (criar-materia! tx ente pid)
        (dotimes [_ 4] (seguir! tx ente pid (random-uuid)))
        ;; um seguidor CANCELADO nao deve entrar em nenhum dos dois lados do par
        (let [cancelado (random-uuid)]
          (seguir! tx ente pid cancelado)
          (db-acompanhamento/deixar-de-seguir! tx {:ente-id ente :proposicao-id pid
                                                   :seguidor-identidade-id cancelado}))))
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (= 4 (count (db-acompanhamento/seguidores-ativos tx ente pid 100))))
        (is (= 4 (db-acompanhamento/contar-seguidores-ativos tx ente pid)))
        (is (= (count (db-acompanhamento/seguidores-ativos tx ente pid 100))
               (db-acompanhamento/contar-seguidores-ativos tx ente pid))
            "lista (sem teto) e contagem enxergam EXATAMENTE o mesmo conjunto — mesmo predicado")))))
