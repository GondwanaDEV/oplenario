(ns oplenario.legislativo.votacao-db-test
  "INTEGRACAO (PG real): eixo G — votacao (§22.4). Prova: votacoes + votos (nominal, atribuido) +
  votos_secretos (anonimo — SEM vereador_id/created_by, sigilo no schema); objeto POLIMORFICO
  (objeto_tipo,objeto_id) p/ proposicao|emenda|parecer|requerimento|redacao_final; quorum como ENUM
  verificado por ARITMETICA EXATA (a armadilha do quorum — inteiro, sem float); votos APPEND-ONLY puro;
  correcao de voto = NOVA votacao inteira (votacao_corrige_id), nunca UPDATE silencioso."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.models.votacao :as mod]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- protocolar! [tx ente]
  (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                             :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(defn- abrir! [tx ente objeto-id extra]
  (votacao/abrir! tx (merge {:id (random-uuid) :ente-id ente :objeto-tipo "proposicao" :objeto-id objeto-id
                             :modalidade "nominal" :quorum-tipo "maioria_simples"} extra)))

(defn- votar! [tx ente vid voto]
  (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid
                               :vereador-id (random-uuid) :voto voto}))

;; ---------- aritmetica exata do quorum (a armadilha; pura, sem DB) ----------

(deftest resultado-quorum-aritmetica-exata
  ;; 2/3 de 9 = 6 (exato); de 10 = ceil(6.667)=7 — float (2/3*10=6.6666 -> floor erraria). Inteiro acerta.
  (is (= "aprovada" (logic/resultado-votacao "maioria_qualificada_2_3" {:sim 6 :nao 3} 9)) "6>=6 de 9")
  (is (= "rejeitada" (logic/resultado-votacao "maioria_qualificada_2_3" {:sim 6 :nao 4} 10)) "6<7 de 10")
  (is (= "aprovada" (logic/resultado-votacao "maioria_qualificada_2_3" {:sim 7 :nao 3} 10)) "7>=7 de 10")
  ;; 3/5 de 10 = 6; absoluta de 10 = 6 (>metade); simples = mais sim que nao
  (is (= "aprovada" (logic/resultado-votacao "maioria_qualificada_3_5" {:sim 6 :nao 4} 10)) "6>=6 de 10 (3/5)")
  (is (= "rejeitada" (logic/resultado-votacao "maioria_absoluta" {:sim 5 :nao 5} 10)) "5<6 (nao e' >metade)")
  (is (= "aprovada" (logic/resultado-votacao "maioria_absoluta" {:sim 6 :nao 4} 10)) "6>=6 (>metade de 10)")
  (is (= "aprovada" (logic/resultado-votacao "maioria_simples" {:sim 2 :nao 1} 9)) "2>1 votos validos")
  (is (= "rejeitada" (logic/resultado-votacao "maioria_simples" {:sim 1 :nao 1} 9)) "empate nao aprova"))

;; ---------- ledger de prontidao Fase 8 achado #2: abrir! devolve o lock-version ----------

(deftest abrir-devolve-lock-version-para-o-recibo-de-abertura
  ;; nao ha' rota GET de detalhe da votacao: o recibo de `abrir!` e' a UNICA fonte do token de CAS que
  ;; `encerrar!` exige no corpo — sem RETURNING lock_version aqui, encerrar fica impossivel de montar
  ;; so' pela API (a versao anterior devolvia so' {:id}).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              recibo (abrir! tx ente pid {})]
          (is (= 0 (:lock-version recibo)) "votacao recem-aberta nasce com lock_version 0")
          (is (= (:lock-version recibo)
                 (:lock-version (votacao/buscar tx ente (:id recibo))))
              "o lock-version do recibo bate com o que esta gravado"))))))

;; ---------- fluxo nominal ----------

(deftest abrir-votar-encerrar-nominal
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              {vid :id} (abrir! tx ente pid {})]
          (votar! tx ente vid "sim") (votar! tx ente vid "sim") (votar! tx ente vid "nao")
          (let [r (votacao/buscar tx ente vid)]
            (is (= "aberta" (:estado r)) "nasce aberta")
            (is (m/validate mod/Votacao r) "votacao bate o model"))
          (is (= 3 (count (votacao/votos-da-votacao tx ente vid))) "3 votos nominais")
          (let [enc (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 3 :updated-by nil :lock-version 0})]
            (is (= "aprovada" (:resultado enc)) "2 sim > 1 nao -> aprovada (maioria simples)"))
          (let [r (votacao/buscar tx ente vid)]
            (is (= "encerrada" (:estado r)))
            (is (= "aprovada" (:resultado r)))
            (is (= 2 (:total-sim r))) (is (= 1 (:total-nao r)))))))))

(deftest encerrar-com-quorum-qualificado
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              {vid :id} (abrir! tx ente pid {:quorum-tipo "maioria_qualificada_2_3"})]
          (dotimes [_ 6] (votar! tx ente vid "sim"))
          (votar! tx ente vid "nao") (votar! tx ente vid "nao") (votar! tx ente vid "nao")
          ;; Casa de 10: 2/3 -> precisa 7; tem 6 sim -> rejeitada
          (let [enc (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 10 :updated-by nil :lock-version 0})]
            (is (= "rejeitada" (:resultado enc)) "6 sim < 7 (2/3 de 10) -> rejeitada")))))))

;; ---------- append-only + unicidade ----------

(deftest votos-append-only
  (let [ente (random-uuid) voto-id (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {vid :id} (abrir! tx ente pid {})]
          (reset! voto-id (:id (votar! tx ente vid "sim"))))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.votos SET voto = 'nao' WHERE id = ?" @voto-id]))))
        "voto e' append-only (sem UPDATE — correcao e' nova votacao)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["DELETE FROM legislativo.votos WHERE id = ?" @voto-id]))))
        "voto e' append-only (sem DELETE)")))

(deftest voto-unico-por-vereador
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {vid :id} (abrir! tx ente pid {})
              ver (random-uuid)]
          (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid :vereador-id ver :voto "sim"})
          (is (thrown? Exception
                       (votacao/registrar-voto! tx {:id (random-uuid) :ente-id ente :votacao-id vid :vereador-id ver :voto "nao"}))
              "o mesmo vereador nao vota duas vezes na MESMA votacao (UNIQUE)"))))))

;; ---------- voto secreto (sigilo no schema) ----------

(deftest voto-secreto-sem-identidade
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              {vid :id} (abrir! tx ente pid {:modalidade "secreta"})]
          (votacao/registrar-voto-secreto! tx {:id (random-uuid) :ente-id ente :votacao-id vid :voto "sim"})
          (votacao/registrar-voto-secreto! tx {:id (random-uuid) :ente-id ente :votacao-id vid :voto "sim"})
          (votacao/registrar-voto-secreto! tx {:id (random-uuid) :ente-id ente :votacao-id vid :voto "nao"})
          (let [enc (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 3 :updated-by nil :lock-version 0})]
            (is (= "aprovada" (:resultado enc)) "2 sim > 1 nao no escrutinio secreto")))))
    ;; sigilo no SCHEMA: votos_secretos NAO tem coluna vereador_id. O SELECT falho aborta a tx -> isolado.
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["SELECT vereador_id FROM legislativo.votos_secretos LIMIT 1"]))))
        "votos_secretos NAO tem coluna vereador_id (sigilo estrutural)")))

;; ---------- votacao-na-sessao: contexto de pauta (F4.4a, §22.6 eixo B) ----------

(deftest votacao-carrega-contexto-de-pauta
  ;; §22.6 eixo B: a votacao aponta a materia via (objeto_tipo,objeto_id); sessao_id + pauta_item_id sao
  ;; CONTEXTO TEMPORAL (forward-ref a sessoes, §22.10 sem FK). Round-trip + model.
  (let [ente (random-uuid) sessao (random-uuid) item (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              {vid :id} (abrir! tx ente pid {:sessao-id sessao :pauta-item-id item})
              r (votacao/buscar tx ente vid)]
          (is (= sessao (:sessao-id r)) "votacao carrega a sessao de contexto")
          (is (= item (:pauta-item-id r)) "votacao carrega o item de pauta de contexto")
          (is (m/validate mod/Votacao r) "votacao com contexto bate o model"))))))

(deftest item-de-pauta-exige-sessao
  ;; coerencia (DB-MENOR): pauta_item_id sem sessao_id e' incoerente (item pertence a sessao) -> DB trava.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (protocolar! tx ente)))  ; garante schema vivo
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx]
                     (let [pid (protocolar! tx ente)]
                       (abrir! tx ente pid {:pauta-item-id (random-uuid)})))))  ; sem :sessao-id
        "votar sobre item de pauta sem sessao viola votacao_pauta_item_requer_sessao")))

(deftest mesma-materia-votada-em-duas-sessoes
  ;; §22.6 eixo B: "materia pode ser votada em duas sessoes (1a e 2a discussao), duas votacoes com
  ;; pauta_item_id diferentes mas mesma proposicao_id". Reusa a votacao do legislativo sem nova mecanica.
  (let [ente (random-uuid) s1 (random-uuid) s2 (random-uuid) i1 (random-uuid) i2 (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              {v1 :id} (abrir! tx ente pid {:sessao-id s1 :pauta-item-id i1})
              {v2 :id} (abrir! tx ente pid {:sessao-id s2 :pauta-item-id i2})]
          (is (not= v1 v2) "duas votacoes distintas sobre a mesma materia")
          ;; 1a discussao: aprovada
          (votar! tx ente v1 "sim") (votar! tx ente v1 "sim") (votar! tx ente v1 "nao")
          (votacao/encerrar! tx {:id v1 :ente-id ente :base-membros 3 :updated-by nil :lock-version 0})
          ;; 2a discussao: rejeitada
          (votar! tx ente v2 "sim") (votar! tx ente v2 "nao") (votar! tx ente v2 "nao")
          (votacao/encerrar! tx {:id v2 :ente-id ente :base-membros 3 :updated-by nil :lock-version 0})
          (let [r1 (votacao/buscar tx ente v1) r2 (votacao/buscar tx ente v2)]
            (is (= (:objeto-id r1) (:objeto-id r2)) "mesma materia (proposicao) nas duas")
            (is (not= (:pauta-item-id r1) (:pauta-item-id r2)) "itens de pauta distintos")
            (is (= "aprovada" (:resultado r1)) "1a discussao aprovada")
            (is (= "rejeitada" (:resultado r2)) "2a discussao rejeitada")))))))

;; ---------- vocabularios + terminal + correcao ----------

(deftest vocabularios-invalidos-barram
  (let [ente (random-uuid) pid (atom nil)]
    (tenancy/com-tenant* *ds* ente (fn [tx] (reset! pid (protocolar! tx ente))))
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente (fn [tx] (abrir! tx ente @pid {:modalidade "grito"}))))
        "modalidade invalida barra (CHECK)")
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente (fn [tx] (abrir! tx ente @pid {:quorum-tipo "tres_quartos"}))))
        "quorum invalido barra (CHECK)")
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente (fn [tx] (abrir! tx ente @pid {:objeto-tipo "lei_organica"}))))
        "objeto_tipo invalido barra (CHECK)")))

(deftest encerrada-trava-e-correcao-e-nova-votacao
  (let [ente (random-uuid) ctx (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {vid :id} (abrir! tx ente pid {})]
          (votar! tx ente vid "sim")
          (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0})
          (reset! ctx {:pid pid :vid vid}))))
    ;; encerrada e' terminal -> nao muda
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (votacao/anular! tx {:id (:vid @ctx) :ente-id ente :updated-by nil :lock-version 1}))))
        "votacao encerrada (terminal) nao se anula sem correcao auditada")
    ;; correcao = anular uma ABERTA + abrir nova apontando a corrigida
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [{v0 :id} (abrir! tx ente (:pid @ctx) {})]
          (votacao/anular! tx {:id v0 :ente-id ente :updated-by nil :lock-version 0})
          (is (= "anulada" (:estado (votacao/buscar tx ente v0))) "original anulada")
          (let [{v1 :id} (abrir! tx ente (:pid @ctx) {:votacao-corrige-id v0})]
            (is (= v0 (:votacao-corrige-id (votacao/buscar tx ente v1))) "nova votacao aponta a corrigida")))))))
