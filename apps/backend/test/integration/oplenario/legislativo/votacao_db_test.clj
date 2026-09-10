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
            [oplenario.legislativo.db.texto-versao :as texto]
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

;; ---------- T3-A: o FATO da aprovacao (guarda-autografo-votacao) ----------
;; `aprovada-em-votacao?` e' a pre-condicao do autografo. Cada `is` abaixo cobre UMA das exclusoes do
;; predicado; se alguma cair, o autografo volta a nascer em materia que a Camara nao aprovou.

(deftest aprovada-em-votacao-so-conta-o-ato-consumado
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; (0) materia recem-protocolada, sem votacao nenhuma
        (let [pid (protocolar! tx ente)]
          (is (false? (votacao/aprovada-em-votacao? tx ente pid))
              "sem votacao nenhuma: e' EXATAMENTE o caso dos 4 autografos fabricados na T3")
          ;; (1) votacao ABERTA (ainda apurando) nao aprova nada
          (let [{vid :id} (abrir! tx ente pid {})]
            (is (false? (votacao/aprovada-em-votacao? tx ente pid))
                "votacao aberta ainda nao e' aprovacao")
            ;; (2) encerrada com resultado REJEITADA nao aprova
            (votar! tx ente vid "nao")
            (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0})
            (is (= "rejeitada" (:resultado (votacao/buscar tx ente vid))) "premissa do caso: rejeitada")
            (is (false? (votacao/aprovada-em-votacao? tx ente pid))
                "encerrada REJEITADA nao pode virar autografo")))))
    ;; (3) encerrada APROVADA: o unico caso que libera
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {vid :id} (abrir! tx ente pid {})]
          (votar! tx ente vid "sim")
          (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0})
          (is (= "aprovada" (:resultado (votacao/buscar tx ente vid))) "premissa do caso: aprovada")
          (is (true? (votacao/aprovada-em-votacao? tx ente pid)) "o ato consumado libera o autografo"))))))

(deftest aprovada-em-votacao-ignora-a-votacao-que-outra-veio-corrigir
  ;; a armadilha da correcao (migration 0021 L8): anular a corrigida e abrir a corretiva sao DOIS atos. Entre
  ;; um e outro a corrigida segue 'encerrada' — e sozinha ela mentiria "aprovada" p/ o autografo.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente) {v0 :id} (abrir! tx ente pid {})]
          (votar! tx ente v0 "sim")
          (votacao/encerrar! tx {:id v0 :ente-id ente :base-membros 1 :updated-by nil :lock-version 0})
          (is (true? (votacao/aprovada-em-votacao? tx ente pid)) "premissa: a 1a votacao aprovou")
          ;; abre a CORRETIVA apontando a anterior — ainda sem encerrar
          (abrir! tx ente pid {:votacao-corrige-id v0})
          (is (false? (votacao/aprovada-em-votacao? tx ente pid))
              "corrigida por outra deixa de contar, mesmo continuando 'encerrada'"))))))

(deftest aprovada-em-votacao-nao-confunde-objeto-nem-tenant
  ;; `objeto_id` e' polimorfico: sem o `objeto_tipo = 'proposicao'` no WHERE, uma EMENDA aprovada de id
  ;; colidente responderia pela materia-mae. E a RLS isola o tenant, mas o ente_id explicito e' defesa
  ;; em profundidade (mesmo padrao das outras queries deste ns).
  (let [ente (random-uuid) outro (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              {vid :id} (abrir! tx ente pid {:objeto-tipo "emenda"})]
          (votar! tx ente vid "sim")
          (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version 0})
          (is (= "aprovada" (:resultado (votacao/buscar tx ente vid))) "premissa: a EMENDA foi aprovada")
          (is (false? (votacao/aprovada-em-votacao? tx ente pid))
              "emenda aprovada NAO aprova a materia-mae")
          (is (false? (votacao/aprovada-em-votacao? tx outro pid))
              "o fato nao atravessa a fronteira de Casa"))))))

;; ---------- T3-A4: a REDACAO FINAL aprovada tambem destrava o autografo (docs/17 §5.1) ----------
;; A pesquisa de rito (docs/17-rito-do-autografo-fortaleza-e-ceara.md) desmentiu a exclusao que estava aqui:
;; no regimento VIGENTE de Fortaleza (Res. 1.670/2020, Art. 180 §1º) e' a aprovacao da REDACAO FINAL em
;; Plenario que manda a materia a' COGEL p/ elaborar o autografo; em Mossoro/RN o gatilho e' a aprovacao do
;; projeto. A regra anterior (so' 'proposicao') acertava em Mossoro e ERRAVA na casa-alvo. Estes testes
;; provam a UNIAO dos dois modelos — e, o mais importante, que o congelamento de texto acompanha, senao o
;; predicado passaria e o autografo seguiria recusando por :conflito/aprovacao-sem-texto (destrave zero).

(defn- versao-vigente!
  "Cria versao com a `origem` dada e a promove a vigente. Devolve o id.

  ATENCAO (achado I-2 da revisao adversarial): p/ `origem` = \"redacao_final\" esta fixture fabrica um estado
  que a stack de PRODUCAO nao alcanca — os unicos produtores de versao sao 'protocolo' (repositorio.clj),
  'edicao' (PATCH) e 'aplicacao_emenda' (db/emenda.clj); NENHUM cria 'redacao_final', e nao ha' rota que
  passe origem arbitraria. O que `abrir!` congela na votacao de redacao final e' a versao VIGENTE, seja
  qual for a origem dela — a promessa da mig 0022 (\"a versao origem_versao='redacao_final' aprovada\")
  segue NAO implementada. Estes testes provam o congelamento, nao a proveniencia."
  [tx ente pid origem rotulo]
  (let [{vid :id} (texto/nova-versao! tx {:id (random-uuid) :ente-id ente :proposicao-id pid
                                          :origem-versao origem :texto-inline rotulo :created-by nil})
        {:keys [lock-version]} (texto/buscar tx ente vid)]
    (texto/promover! tx {:ente-id ente :proposicao-id pid :versao-id vid
                         :updated-by nil :lock-version lock-version})
    vid))

(defn- aprovar!
  "A Casa APROVA o objeto: abre, um voto 'sim', encerra (maioria simples, base 1). Devolve o id da votacao."
  [tx ente objeto-id extra]
  (let [{vid :id lv :lock-version} (abrir! tx ente objeto-id extra)]
    (votar! tx ente vid "sim")
    (votacao/encerrar! tx {:id vid :ente-id ente :base-membros 1 :updated-by nil :lock-version lv})
    vid))

(deftest redacao-final-aprovada-destrava-o-autografo-no-beachhead
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)]
          (versao-vigente! tx ente pid "redacao_final" "TEXTO DA REDACAO FINAL")
          (is (false? (votacao/aprovada-em-votacao? tx ente pid)) "premissa: nada aprovado ainda")
          (aprovar! tx ente pid {:objeto-tipo "redacao_final"})
          (is (true? (votacao/aprovada-em-votacao? tx ente pid))
              "Fortaleza Res. 1.670/2020 Art. 180 §1º: aprovada a Redacao Final, a materia vai ao autografo"))))))

(deftest abrir-congela-o-texto-tambem-na-votacao-de-redacao-final
  ;; sem isto o conserto do predicado nao destrava NADA: `aprovada-em-votacao?` diria true, `aprovacao-vigente`
  ;; devolveria :texto-versao-id nil e `gerar-autografo` recusaria com :conflito/aprovacao-sem-texto (T3-A2).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              vrf (versao-vigente! tx ente pid "redacao_final" "TEXTO DA REDACAO FINAL")]
          (aprovar! tx ente pid {:objeto-tipo "redacao_final"})
          (is (= vrf (:texto-versao-id (votacao/aprovacao-vigente tx ente pid)))
              "a votacao de redacao final congela o texto deliberado, como a de proposicao"))))))

(deftest aprovacao-vigente-prefere-a-redacao-final-a-aprovacao-do-projeto
  ;; as duas aprovacoes COEXISTEM no rito de Fortaleza (o projeto e depois a redacao final), e `:limit 1` sem
  ;; ordem deixaria o TEXTO do autografo ao acaso do plano do Postgres.
  ;;
  ;; I-3 da revisao adversarial: com uuid v4 nos dois lados este teste passaria em ~50% das corridas MESMO
  ;; SEM o `CASE` — sobraria `id DESC` sobre uuid aleatorio, ou seja, moeda. Uma assercao que so' reprova
  ;; metade das vezes nao e' cobertura. Por isso os ids sao CRAVADOS de forma hostil: o do projeto e' o
  ;; MAIOR possivel e o da redacao final o menor, entao a ordem de insercao E `id DESC` E `atualizado_em`
  ;; (mesma tx = mesmo `now()`) TODOS favorecem o projeto. So' o `CASE` pode devolver `v-final` — se ele
  ;; sumir, o teste reprova em 100% das corridas.
  (let [ente (random-uuid)
        id-projeto #uuid "ffffffff-ffff-4fff-8fff-ffffffffffff"
        id-final   #uuid "00000000-0000-4000-8000-000000000000"]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pid (protocolar! tx ente)
              v-projeto (versao-vigente! tx ente pid "protocolo" "TEXTO DO PROJETO")]
          (aprovar! tx ente pid {:id id-projeto})
          (is (= v-projeto (:texto-versao-id (votacao/aprovacao-vigente tx ente pid)))
              "premissa: aprovado o projeto, o texto deliberado e' o do projeto")
          (let [v-final (versao-vigente! tx ente pid "redacao_final" "TEXTO DA REDACAO FINAL")]
            (aprovar! tx ente pid {:id id-final :objeto-tipo "redacao_final"})
            (is (= v-final (:texto-versao-id (votacao/aprovacao-vigente tx ente pid)))
                "com as duas, o autografo leva a REDACAO FINAL — ela so' existe depois do projeto aprovado")))))))

(deftest aprovacao-vigente-entre-DUAS-redacoes-finais-pega-a-mais-RECENTE
  ;; C-1 da revisao adversarial. Nao ha' rota de anulacao nem de votacao corretiva, entao refazer uma
  ;; votacao de redacao final encerrada errada hoje so' e' possivel abrindo OUTRA — e as duas ficam
  ;; encerrada+aprovada+nao-corrigidas. Aqui o `CASE` EMPATA (mesmo tipo) e o desempate real e'
  ;; `atualizado_em DESC`; sem ele sobraria `id DESC` sobre uuid v4 = moeda decidindo qual texto vai ao
  ;; Prefeito. Os ids sao cravados de forma hostil (a 1a votacao com o uuid MAIOR) p/ que `id DESC` sozinho
  ;; escolha a ERRADA — so' o carimbo de tempo pode acertar. Cada encerramento vai em SUA PROPRIA tx: e' o
  ;; que producao faz (uma requisicao por ato), e `now()` so' empata dentro da mesma tx.
  (let [ente (random-uuid)
        id-velha #uuid "ffffffff-ffff-4fff-8fff-fffffffffffe"
        id-nova  #uuid "00000000-0000-4000-8000-000000000001"
        pid (atom nil) v-velha (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (reset! pid (protocolar! tx ente))
        (reset! v-velha (versao-vigente! tx ente @pid "redacao_final" "REDACAO FINAL COM INEXATIDAO"))
        (aprovar! tx ente @pid {:id id-velha :objeto-tipo "redacao_final"})))
    (is (= @v-velha (:texto-versao-id (tenancy/com-tenant* *ds* ente
                                        (fn [tx] (votacao/aprovacao-vigente tx ente @pid)))))
        "premissa: com uma so', e' ela")
    (let [v-nova (atom nil)]
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (reset! v-nova (versao-vigente! tx ente @pid "redacao_final" "REDACAO FINAL CORRIGIDA"))
          (aprovar! tx ente @pid {:id id-nova :objeto-tipo "redacao_final"})))
      (is (= @v-nova (:texto-versao-id (tenancy/com-tenant* *ds* ente
                                         (fn [tx] (votacao/aprovacao-vigente tx ente @pid)))))
          "o autografo leva o texto da votacao MAIS RECENTE, nao o do uuid maior"))))

(deftest o-conjunto-que-carrega-a-materia-e-exatamente-proposicao-e-redacao-final
  ;; M-2: o set e' ^:private e os testes redigitam as strings — sem esta assercao, um terceiro objeto_tipo
  ;; entrando nele (uma `emenda`, digamos) nao reprovaria NADA, e emenda aprovada voltaria a responder pela
  ;; materia-mae. O predicado e' a pre-condicao de um ato juridico: o conjunto tem de ser afirmado, nao
  ;; deduzido do comportamento de um caso feliz.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (doseq [tipo (disj logic/objetos-votacao "proposicao" "redacao_final")]
          (let [pid (protocolar! tx ente)]
            (aprovar! tx ente pid {:objeto-tipo tipo})
            (is (false? (votacao/aprovada-em-votacao? tx ente pid))
                (str "'" tipo "' aprovado NAO aprova a materia-mae"))))
        (doseq [tipo ["proposicao" "redacao_final"]]
          (let [pid (protocolar! tx ente)]
            (versao-vigente! tx ente pid "protocolo" "TEXTO")
            (aprovar! tx ente pid {:objeto-tipo tipo})
            (is (true? (votacao/aprovada-em-votacao? tx ente pid))
                (str "'" tipo "' aprovado APROVA a materia"))))))))
