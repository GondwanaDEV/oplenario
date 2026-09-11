(ns oplenario.paineis.tramitacao-test
  "INTEGRACAO (PG real) — F7 Slice 2: a PROJECAO do board de tramitacao (§16.11). `legislativo` EMITE
  `proposicao.protocolada`/`proposicao.transicionou`; o relay DRENA e despacha ao consumer de paineis
  (`paineis.diplomat.consumers`), que PROJETA em `paineis.tramitacao` (mig 0049) — sem import/JOIN
  cross-modulo (§22.10), mesmos eventos que `transparencia.materia` ja consome. Este teste emite os eventos
  DIRETO no outbox (o payload casa o contrato real de legislativo), drena, e le' pela leitura interna (Repo).
  Prova tambem RLS (isolamento cross-tenant), tolerancia (transicao sem materia projetada nunca lanca) e
  idempotencia (ON CONFLICT DO NOTHING no redrive do protocolo)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as repo]
            [oplenario.paineis.db.tramitacao :as db-tramitacao]
            [oplenario.paineis.diplomat.consumers :as consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- drenar! []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- emitir! [ente tipo payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx (eventos/evento tipo ente payload)))))

(defn- board-itens
  "Fatia 'truncamento-familia': `repo/tramitacao-board` deixou de devolver so' o vetor de itens — agora
  devolve {:itens [...] :totais-por-estado [...]} (mesmo racional do par lista/total de
  compliance/painel e paineis/pendencia). Este helper isola os call-sites existentes deste arquivo (que
  so' se importam com os ITENS) da mudanca de forma."
  [ente]
  (:itens (repo/tramitacao-board *repo* ente)))

(defn- payload-protocolada [over]
  (merge {:proposicao-id (str (random-uuid)) :tipo "pl" :ano 2026 :sequencial 1
          :urn-lex "urn:lex:br:camara:pl:2026;1" :ementa "dispoe sobre teste"
          :autor-tipo "vereador" :autor-texto "Fulano" :estado "protocolada"}
         over))

;; ---------- protocolo -> INSERT no board ----------

(deftest proposicao-protocolada-projeta-item-no-board
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "proposicao.protocolada"
             (payload-protocolada {:proposicao-id (str pid) :tipo "plc" :ano 2026 :sequencial 7
                                   :ementa "ementa de teste do board"}))
    (drenar!)
    (let [[i] (board-itens ente)]
      (is (some? i) "o item foi projetado")
      (is (= pid (:proposicao-id i)))
      (is (= "plc" (:tipo i)))
      (is (= 7 (:sequencial i)))
      (is (= "protocolada" (:estado i))))
    (is (empty? (board-itens (random-uuid))) "RLS: outro ente nao ve o item projetado")))

;; ---------- transicao -> UPDATE de estado, some do board se filtrado por FE (aqui board mostra TUDO) ----------

(deftest proposicao-transicionou-atualiza-o-estado
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str pid)}))
    (drenar!)
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_comissao" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2026-07-05T09:00:00Z"})
    (drenar!)
    (let [[i] (board-itens ente)]
      (is (= "em_comissao" (:estado i)) "estado atualizado")
      (is (some? (:transicionou-em i)) "carimbo de transicao presente"))))

(deftest primeira-transicao-aplica-mesmo-com-ocorrido-em-no-passado
  ;; O FIX do review clojure HIGH: `inserir!` semeia `transicionou_em` com Instant/EPOCH (nao mais `now()`
  ;; da coluna) — sob backlog do relay (protocolada+transicionou drenados juntos no catch-up), a 1a
  ;; transicao real carrega um `:ocorrido-em` BEM anterior ao momento da projecao; antes do fix, o gate de
  ;; monotonicidade rejeitava essa atualizacao legitima (congelando o estado). Simula isso emitindo um
  ;; `:ocorrido-em` no passado distante (muito antes de 'agora', mas depois de EPOCH).
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str pid)}))
    (drenar!)
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_comissao" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2020-01-01T00:00:00Z"})
    (drenar!)
    (let [[i] (board-itens ente)]
      (is (= "em_comissao" (:estado i)) "a 1a transicao aplicou mesmo com ocorrido-em no passado distante")
      (is (= (java.time.Instant/parse "2020-01-01T00:00:00Z") (:transicionou-em i))))))

(deftest transicao-carrega-o-instante-real-do-dominio-nao-o-de-projecao
  ;; O FIX do carry (review architect/database MEDIUM da fatia anterior): `transicionou_em` adota
  ;; :ocorrido-em do EVENTO (o instante real da transicao em legislativo), NAO o momento em que este
  ;; consumer projeta — mesmo sob atraso do relay, o carimbo carrega a data real.
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str pid)}))
    (drenar!)
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_comissao" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2099-07-31T00:00:00Z"})
    (drenar!)
    (let [[i] (board-itens ente)]
      (is (= (java.time.Instant/parse "2099-07-31T00:00:00Z") (:transicionou-em i))
          "adotou o instante do EVENTO, nao 'agora' (2099 e' um valor arbitrario distinto de now(), provando que veio do evento)"))))

(deftest transicao-mais-antiga-fora-de-ordem-e-no-op
  ;; GATE DE MONOTONICIDADE (fix do carry): uma transicao MAIS ANTIGA que chega DEPOIS de uma MAIS NOVA ja
  ;; projetada (redrive/backfill fora de ordem) nao pode retroceder o carimbo de estagnacao.
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str pid)}))
    (drenar!)
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_pauta" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2026-07-10T00:00:00Z"})
    (drenar!)
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_comissao" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2026-07-05T00:00:00Z"})
    (drenar!)
    (let [[i] (board-itens ente)]
      (is (= "em_pauta" (:estado i)) "a transicao mais antiga (fora de ordem) NAO sobrescreveu a mais nova")
      (is (= (java.time.Instant/parse "2026-07-10T00:00:00Z") (:transicionou-em i))))))

(deftest transicao-sem-materia-projetada-nao-lanca
  ;; TOLERANCIA (mesmo racional de transparencia/atualizar-estado! e paineis/pendencia): uma transicao cujo
  ;; protocolo ainda nao foi drenado (backlog/reordenacao) NUNCA pode lancar dentro da tx do relay
  ;; COMPARTILHADO.
  (let [ente (random-uuid)]
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str (random-uuid)) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_comissao" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2026-07-05T09:00:00Z"})
    (drenar!)
    (is (empty? (board-itens ente)) "nenhum item fantasma foi criado")))

;; ---------- idempotencia + drift-guard ----------

(deftest protocolo-redrive-e-no-op
  (let [ente (random-uuid) pid (random-uuid)
        payload (payload-protocolada {:proposicao-id (str pid)})]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (some? (repo/projetar-evento! tx {:tipo "proposicao.protocolada" :ente-id ente :payload payload}))
            "1a projecao insere")
        (is (nil? (repo/projetar-evento! tx {:tipo "proposicao.protocolada" :ente-id ente :payload payload}))
            "2a projecao (mesma proposicao-id) = no-op (ON CONFLICT DO NOTHING), NUNCA lanca")))
    (is (= 1 (count (board-itens ente))) "um unico item permanece")))

;; drift-guard (`todo-tipo-consumido-tem-branch-de-projecao`) consolidado em consumers_test.clj (review
;; clojure MEDIUM: vivia duplicado byte-a-byte aqui e em pendencia_test.clj — o guard e' MODULO-WIDE, nao
;; por feature; pertence a UM lugar so').

;; ---------- leitura: board agrupa por estado, mais estagnado primeiro ----------

(deftest board-agrupa-por-estado-e-ordena-por-estagnacao
  (let [ente (random-uuid) p1 (random-uuid) p2 (random-uuid) p3 (random-uuid)]
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str p1) :sequencial 1}))
    (drenar!)
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str p2) :sequencial 2}))
    (drenar!)
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str p3) :sequencial 3}))
    (drenar!)
    ;; p1 e p3 vao para "em_comissao" (p1 primeiro, entao p1 e' a mais estagnada dentro do grupo);
    ;; p2 fica em "protocolada" (grupo alfabeticamente ANTES de "em_comissao").
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str p1) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_comissao" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2026-07-05T09:00:00Z"})
    (drenar!)
    (emitir! ente "proposicao.transicionou"
             {:proposicao-id (str p3) :template-id (str (random-uuid)) :de "protocolada"
              :para "em_comissao" :gatilho "manual" :transicao-id (str (random-uuid))
              :ocorrido-em "2026-07-05T10:00:00Z"})
    (drenar!)
    (let [board (board-itens ente)]
      (is (= 3 (count board)))
      (is (= [p1 p3 p2] (map :proposicao-id board))
          "'em_comissao' < 'protocolada' alfabeticamente -> grupo em_comissao primeiro (p1 antes de p3, por
          transicionou_em ASC dentro do grupo — p1 transicionou primeiro); 'protocolada' (p2) por ultimo"))))

;; ---------- fatia 'truncamento-familia': o board para de chamar 50 (por estado) de "todas" ----------

(defn- inserir-item-direto!
  "Insere um item do board direto na tx (sem passar pelo outbox/evento) — usado so' pelos testes de
  totais-por-estado abaixo, onde o que importa e' popular MUITAS linhas rapido, nao exercitar a projecao
  do evento (ja coberta pelos testes acima)."
  [tx ente estado sequencial]
  (db-tramitacao/inserir! tx {:ente-id ente :proposicao-id (random-uuid) :tipo "pl" :ano 2026
                              :sequencial sequencial :urn-lex (str "urn:lex:teste:" sequencial)
                              :ementa "item de teste do totais-por-estado" :autor-tipo "vereador"
                              :autor-texto "Fulano" :estado estado}))

(deftest totais-por-estado-conta-tudo-mesmo-quando-a-lista-corta-por-estado
  ;; Regra 3 do par lista/total (nao redigitar o WHERE): db-tramitacao/resumo tem que enxergar EXATAMENTE
  ;; as mesmas linhas que db-tramitacao/listar-board — aqui provado com um teto (2) BEM menor que o real
  ;; (50, privado em components/repositorio), pra' nao pagar o custo de inserir 51+ linhas so' pra' expor
  ;; o corte. "em_comissao" recebe 3 linhas (teto corta pra' 2); "protocolada" recebe 1 (nao corta).
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (dotimes [i 3] (inserir-item-direto! tx ente "em_comissao" i))
        (inserir-item-direto! tx ente "protocolada" 0)
        (let [itens (db-tramitacao/listar-board tx ente 2)
              resumo (db-tramitacao/resumo tx ente)
              n-por-estado (into {} (map (juxt :estado :n)) resumo)]
          (is (= 2 (count (filter #(= "em_comissao" (:estado %)) itens)))
              "a LISTA corta em_comissao no teto (2 de 3 linhas reais)")
          (is (= 3 (get n-por-estado "em_comissao"))
              "o TOTAL de em_comissao continua 3 — nao segue o corte da lista")
          (is (= 1 (get n-por-estado "protocolada"))
              "estado sem corte: lista e total concordam"))))))

(deftest tramitacao-board-devolve-o-par-itens-e-totais-por-estado
  ;; A forma canonica no read publico do Repo (mesmo nivel de `o-que-vence`/`o-que-vence-total`): o board
  ;; nao devolve mais so' um vetor de itens, devolve {:itens [...] :totais-por-estado [{:estado :total}]}
  ;; — o total e' POR ESTADO (nao um escalar), porque o corte tambem e' por estado (um `itens-total`
  ;; escalar seria uma mentira nova: nenhum numero real corresponderia a ele).
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "proposicao.protocolada" (payload-protocolada {:proposicao-id (str pid)}))
    (drenar!)
    (let [board (repo/tramitacao-board *repo* ente)]
      (is (= [{:estado "protocolada" :proposicao-id pid}]
             (map #(select-keys % [:estado :proposicao-id]) (:itens board))))
      (is (= [{:estado "protocolada" :n 1}] (:totais-por-estado board))
          "totais-por-estado reusa a MESMA forma de db-tramitacao/resumo (chave :n), sem redigitar"))))

(deftest tramitacao-board-nao-deriva-o-total-da-lista-ja-cortada
  ;; Achado da revisao adversarial (IMPORTANTE): o teste acima (`tramitacao-board-devolve-o-par-...`) usa
  ;; 1 item / total 1 — nao prova nada, porque 1 = 1 mesmo se o total fosse derivado de `(count itens)`.
  ;; O ponto de FIACAO onde o teto privado (`teto-tramitacao-board-por-estado`, 50) encontra a contagem so'
  ;; existe dentro de `repo/tramitacao-board` (o teste de `totais-por-estado-conta-tudo...` acima chama
  ;; `db-tramitacao/listar-board`/`resumo` DIRETO, pulando o Repo). Sem ESTE teste, uma futura "otimizacao"
  ;; que trocasse `(db-tramitacao/resumo tx ente-id)` por `(count-por-grupo itens)` (evitar a 2a query)
  ;; passaria pela suite inteira em silencio — e a Mesa voltaria a ver 50 como "todas".
  ;;
  ;; Le' o teto PRIVADO via `#'` (tecnica padrao p/ testar um Var privado do MESMO processo, sem
  ;; expor/duplicar a constante) — o teste nao QUEBRA se o teto mudar de valor no futuro.
  (let [ente (random-uuid)
        teto @#'repo/teto-tramitacao-board-por-estado
        n-real (+ teto 2)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (dotimes [i n-real] (inserir-item-direto! tx ente "em_comissoes" i))))
    (let [board (repo/tramitacao-board *repo* ente)
          itens-em-comissoes (filter #(= "em_comissoes" (:estado %)) (:itens board))
          n-por-estado (into {} (map (juxt :estado :n)) (:totais-por-estado board))]
      (is (= teto (count itens-em-comissoes))
          "a LISTA devolvida pelo Repo continua cortada no teto real")
      (is (= n-real (get n-por-estado "em_comissoes"))
          "o TOTAL devolvido pelo Repo e' o numero REAL (teto+2) — nao o tamanho da lista ja cortada"))))
