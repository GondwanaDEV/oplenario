(ns oplenario.cadastros.repositorio-roster-lote-test
  "INTEGRACAO (PG real) — `RepoCadastros/roster-da-casa-em-datas`: o LOTE de `roster-da-casa` para VARIAS
  datas de uma vez (Etapa 6 fatia 1 — o insumo da apuracao de assiduidade).

  A razao de esta leitura existir e' evitar o carry N+1 do leitor AGREGADO: apurar assiduidade de um
  periodo com centenas de sessoes NAO PODE reabrir `roster-da-casa` sessao a sessao. O predicado de 'quem
  tem mandato vigente numa data' e' o MESMO do singular (extraido para `mandato-vigente-lateral`/
  `cargo-mesa-lateral`, compartilhados) — e' esse compartilhamento (I3 do brief) que o `t1-lote-e-identico-
  ao-singular-data-a-data` abaixo prova: se os dois caminhos de codigo puderem divergir, a apuracao conta
  presenca sobre uma Casa diferente da que a chamada mostrou.

  A FIXTURE E' O TESTE. A revisao adversarial provou que a fixture anterior nao alcancava os ramos que
  importam: dava para DESLIGAR a correlacao de data dentro de `cargo-mesa-lateral` (trocar os quatro usos
  de `data-expr` por uma constante) e os oito testes continuavam VERDES, porque o unico cargo de Mesa da
  fixture tinha janela ABERTA cobrindo as tres datas; nenhum vereador tinha dois mandatos (o desempate
  'vigente' > 'licenciado' era codigo morto nos testes); e todos tinham o mesmo partido e `nome-parlamentar`
  nil (um mandato ERRADO escolhido pelo LATERAL nao apareceria em coluna nenhuma). `casa-de-referencia!`
  abaixo existe para fechar exatamente esses tres buracos — cada peca dela tem, em comentario, a mutacao
  que ela e' capaz de reprovar."
  (:require [clojure.set :as cset]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.cadastros.db.vereador :as db-vereador]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private hoje (LocalDate/of 2026 7 1))
(def ^:private d-30 (LocalDate/of 2026 6 1))
(def ^:private d-15 (LocalDate/of 2026 6 16))

(defn- casa! [ente]
  (let [leg (random-uuid)]
    (repo/criar-legislatura! *repo* ente
      {:id leg :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador! [ente nome & [nome-parlamentar]]
  (let [id (random-uuid)]
    (repo/criar-vereador! *repo* ente {:id id :ente-id ente :nome nome
                                       :nome-parlamentar nome-parlamentar :identidade-id nil})
    id))

(defn- mandato!
  "`opts`: :inicio (obrigatorio) :fim :partido :estado. `estado` default 'vigente'."
  [ente leg vereador-id {:keys [inicio fim partido estado]}]
  (let [id (random-uuid)]
    (repo/criar-mandato! *repo* ente {:id id :ente-id ente :vereador-id vereador-id :legislatura-id leg
                                      :estado (or estado "vigente") :partido (or partido "PX")
                                      :vigencia-inicio inicio :vigencia-fim fim})
    id))

(defn- mesa! [ente leg nome inicio fim]
  (let [id (random-uuid)]
    (repo/criar-comissao! *repo* ente {:id id :ente-id ente :nome nome :tipo "mesa"
                                       :legislatura-id leg :vigencia-inicio inicio :vigencia-fim fim})
    id))

(defn- cargo! [ente mesa-id vereador-id cargo inicio fim]
  (repo/criar-cargo! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                  :vereador-id vereador-id :cargo cargo
                                  :vigencia-inicio inicio :vigencia-fim fim}))

(defn- por-vereador [linhas] (into {} (map (juxt :vereador-id identity)) linhas))

;; ---------- A FIXTURE DE REFERENCIA ----------

(defn- casa-de-referencia!
  "Uma Casa cujo estado MUDA entre d-30, d-15 e hoje, em toda dimensao que a query projeta. Devolve os ids.

  Peca a peca, e a mutacao que cada uma reprova:
  - MESA A (2025-01-01..2026-06-20) com Ana de presidente, e MESA B (2026-06-21..aberta) com Bruno de
    1_secretario. O cargo de Ana EXISTE em d-30/d-15 e SOME em hoje; o de Bruno faz o inverso. Trocar
    `data-expr` por uma constante em `cargo-mesa-lateral` (os quatro usos, ou so' os dois do JOIN com
    `mesa2`) congela a Mesa numa das duas e uma das assercoes cai. Duas Mesas NAO se sobrepoem — o EXCLUDE
    `uq_uma_mesa_ativa` (mig 0010) barraria.
  - CARLA com DOIS mandatos que cobrem as TRES datas: um 'licenciado' (PV) e um 'vigente' (REDE). Exercita
    o desempate `CASE estado='vigente' THEN 0` do LATERAL (era codigo morto) e, sobretudo, e' a unica
    linha capaz de reprovar a remocao do `:limit 1` de `mandato-vigente-lateral` — sem o LIMIT ela aparece
    DUAS vezes por data e a Casa de 6 vira 7 na apuracao.
  - PARTIDO e NOME PARLAMENTAR distintos por vereador. Com todos em 'PX' e nome-parlamentar nil, escolher
    o mandato errado no LATERAL nao aparecia em coluna nenhuma.
  - ELZA sai da Casa em 2026-06-15 (presente so' em d-30) e FABIO entra em 2026-06-16 (ausente em d-30):
    a COMPOSICAO da Casa muda de data para data, entao um roster congelado numa data nao passa."
  [ente leg]
  (let [ana   (vereador! ente "Ana Ribeiro"   "Ana do Porto")
        bruno (vereador! ente "Bruno Sales"   "Bruno da Feira")
        carla (vereador! ente "Carla Nunes"   "Carlinha")
        elza  (vereador! ente "Elza Prado"    "Elza da Praia")
        fabio (vereador! ente "Fabio Lira"    "Fabinho")]
    (mandato! ente leg ana   {:inicio (LocalDate/of 2025 1 1) :partido "PDT"})
    (mandato! ente leg bruno {:inicio (LocalDate/of 2025 1 1) :partido "PSB"})
    ;; Carla: licenciado ANTIGO + vigente que o cobre. So' o EXCLUDE de dois VIGENTES sobrepostos existe.
    (mandato! ente leg carla {:inicio (LocalDate/of 2024 1 1) :partido "PV"   :estado "licenciado"})
    (mandato! ente leg carla {:inicio (LocalDate/of 2025 6 1) :partido "REDE" :estado "vigente"})
    (mandato! ente leg elza  {:inicio (LocalDate/of 2025 1 1) :fim (LocalDate/of 2026 6 15) :partido "MDB"})
    (mandato! ente leg fabio {:inicio (LocalDate/of 2026 6 16) :partido "PP"})
    (let [mesa-a (mesa! ente leg "Mesa Diretora 2025-26" (LocalDate/of 2025 1 1) (LocalDate/of 2026 6 20))
          mesa-b (mesa! ente leg "Mesa Diretora 2026-28" (LocalDate/of 2026 6 21) nil)]
      (cargo! ente mesa-a ana   "presidente"   (LocalDate/of 2025 1 1)  (LocalDate/of 2026 6 20))
      (cargo! ente mesa-b bruno "1_secretario" (LocalDate/of 2026 6 21) nil))
    {:ana ana :bruno bruno :carla carla :elza elza :fabio fabio}))

;; ---------- T1: O TESTE-ANCORA — o lote e' IDENTICO ao singular, data a data ----------

(deftest t1-lote-e-identico-ao-singular-data-a-data
  ;; Sem este teste as duas leituras podem divergir em silencio (uma ganha um filtro que a outra nao
  ;; ganhou) e a apuracao de assiduidade passa a contradizer a tela da chamada — o mesmo risco que o T10
  ;; de `repositorio-roster-test` fecha para `roster-da-casa` vs. `membros-da-casa`.
  ;;
  ;; COMPARA SEQUENCIA, NAO CONJUNTO. `set` apagava as DUAS garantias que o teste do singular achou
  ;; necessario testar em separado (`repositorio-roster-test/roster-ordena-por-nome-e-nao-repete-vereador`,
  ;; `.../roster-desempata-homonimos-por-id`): ORDEM e DUPLICATA. Tirado o `:limit 1` do LATERAL, o vereador
  ;; com dois mandatos aparece 2x por data, `set` colapsa as duas linhas identicas, o teste fica VERDE — e a
  ;; Casa de 6 vira 7 na apuracao, que e' a segunda aritmetica que o I1 do brief proibe.
  (let [ente (random-uuid)
        leg  (casa! ente)
        _    (casa-de-referencia! ente leg)
        datas [d-30 d-15 hoje]
        lote (repo/roster-da-casa-em-datas *repo* ente datas)]
    (is (= (set datas) (set (keys lote))) "toda data pedida aparece como chave")
    (doseq [data datas]
      (is (= (repo/roster-da-casa *repo* ente data) (get lote data))
          (str "data " data ": o lote tem de devolver a MESMA SEQUENCIA (mesma ordem, mesmas repeticoes, "
               "mesmos campos) que o singular chamado data a data")))))

;; ---------- T1b: cada peca da fixture, assertada onde ela MUDA ----------

(deftest t1b-cargo-de-mesa-com-fim-no-meio-do-periodo-aparece-e-some
  ;; A assercao que reprova "trocar `data-expr` por uma constante em `cargo-mesa-lateral`": com a correlacao
  ;; desligada, a Mesa fica congelada numa das duas janelas e uma destas seis linhas cai.
  (let [ente (random-uuid)
        leg  (casa! ente)
        {:keys [ana bruno]} (casa-de-referencia! ente leg)
        em (fn [data] (por-vereador (get (repo/roster-da-casa-em-datas *repo* ente [d-30 d-15 hoje]) data)))]
    (is (= "presidente" (:cargo-mesa (get (em d-30) ana)))  "d-30: Mesa A vigente, Ana e' presidente")
    (is (nil?          (:cargo-mesa (get (em d-30) bruno))) "d-30: Mesa B ainda nao existe")
    (is (= "presidente" (:cargo-mesa (get (em d-15) ana)))  "d-15: Mesa A ainda vigente")
    (is (nil?          (:cargo-mesa (get (em d-15) bruno))) "d-15: Mesa B ainda nao existe")
    (is (nil?          (:cargo-mesa (get (em hoje) ana)))
        "hoje: a Mesa A acabou em 2026-06-20 — o cargo de Ana SOME, nao sobrevive por janela aberta")
    (is (= "1_secretario" (:cargo-mesa (get (em hoje) bruno)))
        "hoje: a Mesa B assumiu — o cargo de Bruno APARECE so' agora")))

(deftest t1b-dois-mandatos-cobrindo-a-data-dao-uma-linha-so-e-o-vigente-vence
  ;; Duas garantias num teste porque sao o mesmo defeito visto de dois lados: sem o `:limit 1` sao duas
  ;; linhas; sem o `CASE ... 'vigente' THEN 0` a linha que sobra pode ser a do mandato LICENCIADO, e a
  ;; apuracao publicaria Carla como licenciada (fora do denominador) num periodo em que ela exercia.
  (let [ente (random-uuid)
        leg  (casa! ente)
        {:keys [carla]} (casa-de-referencia! ente leg)
        lote (repo/roster-da-casa-em-datas *repo* ente [d-30 d-15 hoje])]
    (doseq [data [d-30 d-15 hoje]]
      (let [linhas (get lote data)
            dela   (filter #(= carla (:vereador-id %)) linhas)]
        (is (= 1 (count dela))
            (str "data " data ": Carla tem DOIS mandatos cobrindo a data e UMA linha — sem o LIMIT 1 do "
                 "LATERAL ela conta duas vezes e infla a Casa"))
        (is (= "vigente" (:estado-mandato (first dela)))
            (str "data " data ": 'vigente' vence 'licenciado' quando os dois cobrem a data"))
        (is (= "REDE" (:partido (first dela)))
            (str "data " data ": o partido tem de vir do mandato VIGENTE (REDE), nao do licenciado (PV) — "
                 "partidos distintos e' o que torna a escolha errada do LATERAL visivel"))
        (is (= (count linhas) (count (distinct (map :vereador-id linhas))))
            (str "data " data ": nenhum vereador aparece duas vezes na mesma data"))))))

(deftest t1b-composicao-da-casa-muda-entre-as-datas
  ;; Se algum caminho congelasse a data (um `data-expr` virando constante em `mandato-vigente-lateral`),
  ;; a Casa sairia igual nas tres datas e uma destas quatro linhas cairia.
  (let [ente (random-uuid)
        leg  (casa! ente)
        {:keys [elza fabio]} (casa-de-referencia! ente leg)
        lote (repo/roster-da-casa-em-datas *repo* ente [d-30 d-15 hoje])
        tem? (fn [data v] (contains? (set (map :vereador-id (get lote data))) v))]
    (is (tem? d-30 elza)        "Elza exerce em d-30 (mandato ate' 2026-06-15)")
    (is (not (tem? d-15 elza))  "Elza ja' saiu em d-15 (2026-06-16)")
    (is (not (tem? d-30 fabio)) "Fabio ainda nao entrou em d-30")
    (is (tem? d-15 fabio)       "Fabio entra em 2026-06-16 = d-15")
    (is (= ["Ana Ribeiro" "Bruno Sales" "Carla Nunes" "Elza Prado"] (mapv :nome (get lote d-30)))
        "ordem por nome (com o desempate por id) preservada DENTRO de cada data")
    (is (= ["Ana do Porto" "Bruno da Feira" "Carlinha" "Elza da Praia"]
           (mapv :nome-parlamentar (get lote d-30)))
        "nome-parlamentar distinto por vereador — uma linha trocada aparece aqui")))

;; ---------- T2: data sem NENHUM mandato vigente -> chave presente, vetor vazio ----------

(deftest t2-data-sem-mandato-vigente-tem-chave-presente-com-vetor-vazio
  ;; Chave AUSENTE seria lida a jusante como "nao perguntei por essa data"; o contrato e' "perguntei e a
  ;; resposta e' vazia" — a diferenca importa porque o denominador da apuracao soma sobre as CHAVES.
  (let [ente (random-uuid)
        _leg (casa! ente)
        antes-de-existir-a-casa (LocalDate/of 2020 1 1)]
    (let [lote (repo/roster-da-casa-em-datas *repo* ente [antes-de-existir-a-casa])]
      (is (= {antes-de-existir-a-casa []} lote)))))

;; ---------- T3: licenciado aparece, marcado, no lote (mesma revisao do singular) ----------

(deftest t3-licenciado-aparece-marcado-no-lote
  (let [ente (random-uuid)
        leg  (casa! ente)
        v (vereador! ente "Dario Licenciado")]
    (mandato! ente leg v {:inicio (LocalDate/of 2025 1 1)})
    (repo/registrar-licenca! *repo* ente v
      {:id (random-uuid) :ente-id ente :inicio (LocalDate/of 2026 5 1) :fim nil :motivo "Tratamento de saude"}
      (LocalDate/of 2026 5 1))
    (let [lote (repo/roster-da-casa-em-datas *repo* ente [hoje])]
      (is (= "licenciado" (:estado-mandato (first (get lote hoje))))
          "o licenciado aparece no lote MARCADO, nao ausente e nao mascarado de 'vigente'"))))

;; ---------- T4: bordas inclusivas do stint ----------

(deftest t4-mandato-de-d-a-d-mais-10-aparece-nas-bordas-e-so-nelas
  (let [ente (random-uuid)
        leg  (casa! ente)
        v (vereador! ente "Fronteira")
        d      (LocalDate/of 2026 3 1)
        d+5    (.plusDays d 5)
        d+10   (.plusDays d 10)
        d-1    (.minusDays d 1)
        d+11   (.plusDays d 11)]
    (mandato! ente leg v {:inicio d :fim d+10})
    (let [lote (repo/roster-da-casa-em-datas *repo* ente [d-1 d d+5 d+10 d+11])
          tem? (fn [data] (contains? (set (map :vereador-id (get lote data))) v))]
      (is (not (tem? d-1))   "D-1: vespera do inicio, ainda nao exerce")
      (is (tem? d)           "D: primeiro dia do stint, ja exerce")
      (is (tem? d+5)         "D+5: meio do stint")
      (is (tem? d+10)        "D+10: ultimo dia do stint, ainda exerce (fim inclusivo)")
      (is (not (tem? d+11))  "D+11: um dia depois do fim, ja nao exerce"))))

;; ---------- T5: multi-tenant — as DUAS Casas povoadas, conteudo assertado nos dois sentidos ----------

(deftest t5-lote-e-isolado-por-tenant-nos-dois-sentidos
  ;; A versao anterior deixava a Casa B VAZIA e so' assertava `{hoje []}` — verde tambem se o
  ;; `WHERE v.ente_id = ?` fosse REMOVIDO, porque a RLS sozinha ja' bastaria. Nao separava as duas camadas
  ;; (defesa em profundidade) e nao provava que o lote nao MISTURA duas Casas povoadas. Agora as duas tem
  ;; vereadores proprios e a assercao e' de CONTEUDO.
  (let [ente-a (random-uuid)
        ente-b (random-uuid)
        leg-a  (casa! ente-a)
        leg-b  (casa! ente-b)
        a1 (vereador! ente-a "Ana da Casa A")
        a2 (vereador! ente-a "Bento da Casa A")
        b1 (vereador! ente-b "Célia da Casa B")
        b2 (vereador! ente-b "Décio da Casa B")]
    (doseq [[ente leg v] [[ente-a leg-a a1] [ente-a leg-a a2] [ente-b leg-b b1] [ente-b leg-b b2]]]
      (mandato! ente leg v {:inicio (LocalDate/of 2025 1 1)}))
    (let [ids-a (set (map :vereador-id (get (repo/roster-da-casa-em-datas *repo* ente-a [hoje]) hoje)))
          ids-b (set (map :vereador-id (get (repo/roster-da-casa-em-datas *repo* ente-b [hoje]) hoje)))]
      (is (= #{a1 a2} ids-a) "a Casa A ve' exatamente os SEUS dois vereadores")
      (is (= #{b1 b2} ids-b) "a Casa B ve' exatamente os SEUS dois vereadores")
      (is (empty? (cset/intersection ids-a ids-b)) "nenhuma linha atravessa as duas Casas"))))

;; ---------- T6: lista de datas vazia -> {} sem tocar o banco E sem abrir tx de tenant ----------

(deftest t6-lista-de-datas-vazia-nao-emite-statement-nenhum
  ;; A versao anterior observava so' `jdbc/execute!` e por isso NAO PODIA reprovar o defeito que nomeava:
  ;; `kernel/tenancy/com-tenant*` abre a tx com `jdbc/execute-one!` (`SET LOCAL ROLE` + `set_config`), entao
  ;; um curto-circuito colocado DENTRO da tx deixava o teste verde tendo ja' emprestado conexao do pool e
  ;; emitido dois statements. Com o curto-circuito no metodo do protocolo (antes de `transacao`), a
  ;; alegacao forte passa a ser verdadeira — e agora e' OBSERVADA nas duas funcoes.
  (let [ente (random-uuid)
        execute (atom 0)
        execute-one (atom 0)]
    (with-redefs [jdbc/execute!     (fn [& _] (swap! execute inc) [])
                  jdbc/execute-one! (fn [& _] (swap! execute-one inc) nil)]
      (is (= {} (repo/roster-da-casa-em-datas *repo* ente [])))
      (is (= {} (repo/roster-da-casa-em-datas *repo* ente nil)) "nil e' tratado como lista vazia"))
    (is (= 0 @execute)     "nenhuma query de leitura")
    (is (= 0 @execute-one) "nem a tx do tenant abre — o curto-circuito e' antes de `transacao`")))

(deftest t6b-entrada-rejeitada-nao-abre-tx-de-tenant
  ;; Mesma razao do T6, para o outro caminho de rejeicao: um pedido invalido tem de custar ZERO conexao do
  ;; pool (sao 10 slots). Antes, o teto era checado DENTRO da tx.
  (let [ente (random-uuid)
        execute-one (atom 0)
        datas-demais (mapv #(.plusDays (LocalDate/of 2020 1 1) %) (range 400))]
    (with-redefs [jdbc/execute-one! (fn [& _] (swap! execute-one inc) nil)]
      (is (thrown? clojure.lang.ExceptionInfo (repo/roster-da-casa-em-datas *repo* ente datas-demais)))
      (is (thrown? clojure.lang.ExceptionInfo (repo/roster-da-casa-em-datas *repo* ente [nil hoje]))))
    (is (= 0 @execute-one) "teto estourado e tipo invalido rejeitam ANTES de abrir a tx do tenant")))

;; ---------- T7: teto de DATAS — acima de 366 datas distintas, lanca fail-closed ----------

(deftest t7-acima-do-teto-de-datas-lanca-fail-closed
  ;; 366 e nao 400: o periodo maximo do brief e' de 366 dias, entao a borda nao consegue produzir 401 datas
  ;; distintas — um teto de 400 nunca poderia disparar a partir da rota, e guard-rail que nao dispara nao e'
  ;; guard-rail (nao ha teste possivel do caminho de rejeicao vindo da borda real).
  (let [ente (random-uuid)
        datas-demais (mapv #(.plusDays (LocalDate/of 2020 1 1) %) (range 367))]
    (is (= 367 (count (distinct datas-demais))) "sanidade: sao 367 datas DISTINTAS, acima do teto de 366")
    (let [erro (try (repo/roster-da-casa-em-datas *repo* ente datas-demais)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? erro) "estourar o teto lanca, nunca trunca em silencio")
      (is (= :limite/datas-excedido (:tipo (ex-data erro))))
      (is (= 367 (:medido (ex-data erro))) "o numero MEDIDO vai no corpo do erro")
      (is (= 366 (:teto (ex-data erro))))))
  ;; e o limite EXATO passa (o teto e' inclusivo) — sem isto, um off-by-one rejeitaria o periodo maximo
  ;; legitimo de 366 dias.
  (let [ente (random-uuid)
        _leg (casa! ente)
        no-limite (mapv #(.plusDays (LocalDate/of 2020 1 1) %) (range 366))]
    (is (= 366 (count (repo/roster-da-casa-em-datas *repo* ente no-limite)))
        "366 datas distintas — o periodo maximo do brief — passam")))

;; ---------- T7b: teto de LINHAS (o produto datas x vereadores) ----------

(deftest t7b-acima-do-teto-de-linhas-lanca-fail-closed-e-o-driver-para-antes
  ;; O teto de datas sozinho nao limita o RESULTADO: o produto e' datas x vereadores-com-mandato. Com o teto
  ;; rebaixado a 2 e uma Casa de 5, o driver e' instruido a parar em 3 (`:max-rows` = teto+1) — ler 3 e nao 5
  ;; e' a prova de que ele parou, e lancar em vez de devolver as 3 e' o que impede a pagina truncada de
  ;; passar por total (I7).
  (let [ente (random-uuid)
        leg  (casa! ente)]
    (doseq [n (range 5)]
      (mandato! ente leg (vereador! ente (str "Vereador " n)) {:inicio (LocalDate/of 2025 1 1)}))
    (is (= 5 (count (repo/roster-da-casa *repo* ente hoje))) "sanidade: a Casa tem 5 linhas na data")
    (with-redefs [db-vereador/teto-de-linhas-lote 2]
      (let [erro (try (repo/roster-da-casa-em-datas *repo* ente [hoje])
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "resultado acima do teto de linhas lanca, nunca devolve pagina truncada")
        (is (= :limite/linhas-excedido (:tipo (ex-data erro))))
        (is (= 2 (:teto (ex-data erro))))
        (is (= 3 (:medido-ao-menos (ex-data erro)))
            "3 = teto+1: o driver PAROU de materializar ai (a Casa tem 5) — a medicao e' um PISO, e a chave
             se chama `:medido-ao-menos` justamente para nao mentir que e' a contagem exata")))))

;; ---------- T8: UMA query de LEITURA — e o numero honesto de statements ----------

(deftest t8-o-lote-de-n-datas-e-uma-unica-query-de-leitura
  ;; Prova que nao ha loop de seam singular (o carry N+1): sem isto, o teste-ancora (T1) fica verde tanto
  ;; para uma implementacao em UMA query quanto para uma que chama `roster-da-casa` N vezes por dentro — os
  ;; DOIS produzem o mesmo RESULTADO, so' este teste separa os dois CAMINHOS.
  ;;
  ;; O numero e' 1 LEITURA + 2 statements de abertura de tx, nao "1 statement": `com-tenant*` emite
  ;; `SET LOCAL ROLE` e `set_config` via `jdbc/execute-one!`. Assertar so' o `execute!` e chamar isso de
  ;; "uma query so'" era uma alegacao que o proprio teste nao media.
  (let [ente (random-uuid)
        leg  (casa! ente)
        v (vereador! ente "Sozinho")
        leituras (atom 0)
        statements-de-tx (atom 0)
        original-execute jdbc/execute!
        original-execute-one jdbc/execute-one!]
    (mandato! ente leg v {:inicio (LocalDate/of 2025 1 1)})
    (with-redefs [jdbc/execute!     (fn [tx q & args] (swap! leituras inc) (apply original-execute tx q args))
                  jdbc/execute-one! (fn [tx q & args] (swap! statements-de-tx inc)
                                      (apply original-execute-one tx q args))]
      (let [lote (repo/roster-da-casa-em-datas *repo* ente [d-30 d-15 hoje])]
        (is (= 3 (count lote)))
        (is (= 1 @leituras) "N datas, UMA query de leitura — nao um loop de N chamadas")
        (is (= 2 @statements-de-tx)
            "os 2 statements de `com-tenant*` (SET LOCAL ROLE + set_config), e nenhum a mais")))))

;; ---------- T9: TIPO das datas — fail-closed, sem depender da companhia do elemento ----------

(deftest t9-elemento-que-nao-e-localdate-e-rejeitado-fail-closed
  ;; Os tres defeitos MEDIDOS que motivam a validacao (ver `db/vereador/normalizar-datas!`):
  ;;  - `[nil d1]` PASSAVA e devolvia `{nil [] d1 [...]}` — sucesso com uma chave de roster vazio inventada.
  ;;    `[nil]` sozinho estourava no banco (`date <= text`). O comportamento mudava com a COMPANHIA.
  ;;  - `java.sql.Date` rodava a query, mas o driver devolve `LocalDate`, entao NENHUMA chave semeada casava:
  ;;    o mapa saia com 2N chaves e o chamador lia `[]` para TODAS as datas. Apuracao em branco, zero log.
  ;;  - String/keyword viram expressao SQL na tabela VALUES, nao bind param.
  (let [ente (random-uuid)
        _leg (casa! ente)
        rejeita! (fn [datas rotulo]
                   (let [erro (try (repo/roster-da-casa-em-datas *repo* ente datas)
                                   nil
                                   (catch clojure.lang.ExceptionInfo e e))]
                     (is (some? erro) (str rotulo ": tem de lancar, nunca devolver mapa"))
                     (is (= :validacao/invalido (:tipo (ex-data erro))) rotulo)
                     (is (= :datas (:campo (ex-data erro))) rotulo)
                     (is (some? (:classe (ex-data erro)))
                         (str rotulo ": a CLASSE recebida vai na ex-data (e' o que o log precisa)"))))]
    (rejeita! [nil hoje]                          "nil ACOMPANHADO (o caso que passava)")
    (rejeita! [nil]                               "nil sozinho")
    (rejeita! [(java.sql.Date/valueOf hoje) hoje] "java.sql.Date (rodava e devolvia tudo vazio)")
    (rejeita! ["2026-07-01"]                      "string ISO")
    (rejeita! [:hoje]                             "keyword (viraria expressao SQL)")))

;; ---------- T10: data REPETIDA — a Casa com duas sessoes no mesmo dia ----------

(deftest t10-data-repetida-vira-uma-chave-so-com-o-conteudo-do-singular
  ;; Ordinaria de manha + extraordinaria a tarde no MESMO dia e' rotina, e a fatia 2 monta a lista de datas
  ;; UMA POR SESSAO — entao a repeticao chega aqui de verdade, nao e' hipotese. Hoje so' o `distinct` a
  ;; segura, e nao havia teste: perde-lo faria a mesma data virar duas linhas da tabela VALUES, dobrando o
  ;; roster daquele dia e o denominador da apuracao.
  (let [ente (random-uuid)
        leg  (casa! ente)
        _    (casa-de-referencia! ente leg)
        lote (repo/roster-da-casa-em-datas *repo* ente [hoje hoje d-30])]
    (is (= #{hoje d-30} (set (keys lote))) "duas ocorrencias de `hoje` -> UMA chave")
    (is (= 2 (count lote)))
    (is (= (repo/roster-da-casa *repo* ente hoje) (get lote hoje))
        "e o conteudo da data repetida e' o do singular — mesma sequencia, sem duplicar vereador")))
