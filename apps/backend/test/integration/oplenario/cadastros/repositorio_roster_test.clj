(ns oplenario.cadastros.repositorio-roster-test
  "INTEGRACAO (PG real) — `RepoCadastros/roster-da-casa`: os vereadores que COMPOEM a Casa numa data, com a
  identidade que a CHAMADA precisa mostrar (nome, nome parlamentar, partido) e o estado do mandato.

  E' leitura NOVA, e nao `listar-vereadores`, por uma razao concreta: `listar-vereadores` devolve TODO
  vereador ja' cadastrado no ente — cassado, renunciado, sem mandato, o suplente que nunca foi convocado —
  porque existe para a tela de CADASTRO, onde isso e' correto. Consumida pela chamada, uma Casa de 21
  cadeiras listaria 40 nomes e o quorum sairia sobre o denominador errado.

  O predicado desta leitura e' o MESMO de `relacoes/cadastro.clj` (mandato `estado='vigente'` cobrindo a
  data) — o mesmo que ja' produz `membros-da-casa`, o denominador que o motor de votacao usa. Nao ha' duas
  definicoes de 'quem compoe a Casa': ha' uma, lida de dois angulos (as linhas e a contagem), e o teste
  cruzado abaixo e' o que impede as duas de se separarem."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
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

(def ^:private hoje    (LocalDate/of 2026 7 1))
(def ^:private d-30    (LocalDate/of 2026 6 1))   ; a data da SESSAO
(def ^:private d-15    (LocalDate/of 2026 6 16))
(def ^:private d-1     (LocalDate/of 2026 6 30))

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

(defn- mandato! [ente leg vereador-id inicio fim & [partido]]
  (let [id (random-uuid)]
    (repo/criar-mandato! *repo* ente {:id id :ente-id ente :vereador-id vereador-id :legislatura-id leg
                                      :estado "vigente" :partido (or partido "PX")
                                      :vigencia-inicio inicio :vigencia-fim fim})
    id))

;; ---------- T10: o teste cruzado — as linhas e a contagem nao podem divergir ----------

(deftest t10-roster-tem-exatamente-as-linhas-que-membros-da-casa-conta
  ;; O MAIS IMPORTANTE DESTA FATIA. `membros-da-casa` e' o DENOMINADOR que o motor de votacao usa para
  ;; decidir se ha' quorum; `roster-da-casa` e' o NUMERADOR em potencial — as linhas que a tela mostra e
  ;; sobre as quais a presenca e' contada. Se as duas codificacoes do predicado se separarem (uma ganha um
  ;; filtro que a outra nao ganhou), a Mesa le' "18 de 21 presentes" no telao enquanto a policy calcula
  ;; sobre 20 e aprova (ou rejeita) uma votacao com outro numero. E' o defeito que so' aparece em ata.
  ;;
  ;; A Casa e' semeada com os quatro casos que separam as duas leituras se alguma se desalinhar.
  (let [ente (random-uuid)
        leg  (casa! ente)
        ;; (1) tres com mandato vigente cobrindo a data -> COMPOEM a Casa
        v1 (vereador! ente "Ana")
        v2 (vereador! ente "Bruno")
        v3 (vereador! ente "Carla")
        ;; (2) um licenciado -> NAO compoe (quem compoe e' o suplente, com mandato proprio)
        v4 (vereador! ente "Dario")
        ;; (3) um sem mandato nenhum (cadastrado, nunca empossado) — so' precisa EXISTIR na Casa: e' o caso
        ;; que `listar-vereadores` devolveria com estado nil e que o roster nao pode devolver.
        _v5 (vereador! ente "Elza")
        ;; (4) um cujo mandato terminou antes da data
        v6 (vereador! ente "Fabio")]
    (mandato! ente leg v1 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg v2 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg v3 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg v4 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg v6 (LocalDate/of 2025 1 1) (LocalDate/of 2025 12 31))
    ;; a licenca e' gravada pelo PRODUTOR real (`registrar-licenca!`), nunca por um `:estado "licenciado"`
    ;; redigitado no teste: e' ele quem decide que string vai para a coluna, e um valor proximo-mas-errado
    ;; aqui deixaria este teste verde sobre um estado que producao nunca emite.
    (repo/registrar-licenca! *repo* ente v4
      {:id (random-uuid) :ente-id ente :inicio (LocalDate/of 2026 5 1) :fim nil :motivo "Tratamento de saude"}
      (LocalDate/of 2026 5 1))
    (is (= "licenciado" (:estado (first (filter #(= v4 (:vereador-id %))
                                                (repo/mandatos-do-vereador *repo* ente v4)))))
        "sanidade do seed: o produtor real gravou 'licenciado' (nao um vocabulario inventado pelo teste)")

    (let [linhas (repo/roster-da-casa *repo* ente hoje)
          n      (repo/membros-da-casa *repo* ente hoje)
          com-cadeira (remove #(= "licenciado" (:estado-mandato %)) linhas)]
      ;; REVISAO Etapa 1: o LICENCIADO passou a APARECER no roster (com `estado-mandato` = 'licenciado').
      ;; Sem isso o estado `:licenciado` e o sinalizador `inconsistencia-cadastro` de
      ;; `sessoes/logic/estado-de-presenca` eram inalcancaveis por dado real — o alarme de "o cadastro diz
      ;; licenciado mas ele esta no plenario" nunca dispararia em producao. Ele nao entra no DENOMINADOR:
      ;; `logic/contar-quorum` ja' o remove, e o cruzado abaixo passa a comparar `membros-da-casa` com as
      ;; linhas MENOS os licenciados — o mesmo predicado, agora com a linha visivel na tela.
      (is (= #{v1 v2 v3 v4} (set (map :vereador-id linhas)))
          "os tres vigentes + o licenciado (visivel); sem-mandato e ex-vereador seguem fora")
      (is (= "licenciado" (:estado-mandato (first (filter #(= v4 (:vereador-id %)) linhas))))
          "e o licenciado chega MARCADO — e' o que a derivacao le' fail-closed")
      (is (= #{v1 v2 v3} (set (map :vereador-id com-cadeira)))
          "quem COMPOE a Casa (denominador) segue sendo so' o mandato vigente")
      (is (= n (count com-cadeira))
          "TESTE CRUZADO: as linhas do roster MENOS os licenciados sao exatamente membros-da-casa")
      (is (= 3 n) "e o numero e' o esperado (nao um zero mudo dos dois lados)"))

    (is (= [] (repo/roster-da-casa *repo* (random-uuid) hoje)) "RLS: outra Casa nao ve' estes vereadores")))

;; ---------- T11: a data de referencia e' a da SESSAO, nunca 'hoje' ----------

(deftest t11-roster-e-da-data-da-sessao-nao-de-hoje
  ;; Uma chamada e' relida meses depois (recurso, prestacao de contas, ata publicada). Se o roster fosse
  ;; resolvido em 'hoje', a chamada de uma sessao de junho apareceria com a composicao de julho: o suplente
  ;; empossado depois da sessao surgiria como ausente numa sessao de que nao podia participar, e o vereador
  ;; que exercia o mandato naquele dia sumiria da propria ata. Os dois erros sao reescrita de historia.
  (let [ente (random-uuid)
        leg  (casa! ente)
        titular   (vereador! ente "Titular")
        encerrado (vereador! ente "Encerrado")   ; mandato terminou em D-15, DEPOIS da sessao de D-30
        suplente  (vereador! ente "Suplente")]   ; empossado em D-1, DEPOIS da sessao de D-30
    (mandato! ente leg titular   (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg encerrado (LocalDate/of 2025 1 1) d-15)
    (mandato! ente leg suplente  d-1 nil)

    (let [na-sessao (set (map :vereador-id (repo/roster-da-casa *repo* ente d-30)))]
      (is (contains? na-sessao encerrado)
          "o vereador cujo mandato so' terminou em D-15 EXERCIA o mandato na sessao de D-30")
      (is (not (contains? na-sessao suplente))
          "o suplente empossado em D-1 NAO participou da sessao de D-30 — nao entra na chamada dela")
      (is (= #{titular encerrado} na-sessao)))

    (let [agora (set (map :vereador-id (repo/roster-da-casa *repo* ente hoje)))]
      (is (= #{titular suplente} agora) "hoje a composicao e' outra — e' por isso que a data e' parametro"))

    ;; o cruzado tambem vale na data historica: nao adianta as linhas serem certas em D-30 se a contagem
    ;; que o motor usa continuar respondendo pela composicao de hoje. (Aqui nao ha licenciado, entao a
    ;; contagem crua serve; o cruzado com licenca esta em T10.)
    (is (= (repo/membros-da-casa *repo* ente d-30) (count (repo/roster-da-casa *repo* ente d-30))))
    (is (= (repo/membros-da-casa *repo* ente hoje) (count (repo/roster-da-casa *repo* ente hoje))))))

;; ---------- projecao + uma linha por vereador ----------

(deftest roster-projeta-a-identidade-que-a-chamada-mostra
  ;; As chaves sao exatamente as que `logic/derivar-linha-chamada` consome (`select-keys` de :vereador-id
  ;; :nome :nome-parlamentar :partido) mais `:estado-mandato`, que a derivacao valida fail-closed. Uma chave
  ;; a menos aqui vira `nil` silencioso na linha da chamada; `:estado-mandato` a menos vira um estado de
  ;; presenca derivado sem saber se o cadastro contradiz o fato observado.
  (let [ente (random-uuid)
        leg  (casa! ente)
        v (vereador! ente "Zulmira Rocha" "Zu do Povo")]
    (mandato! ente leg v (LocalDate/of 2025 1 1) nil "PDT")
    (let [l (first (repo/roster-da-casa *repo* ente hoje))]
      (is (= #{:vereador-id :nome :nome-parlamentar :partido :estado-mandato :cargo-mesa} (set (keys l))))
      (is (= v (:vereador-id l)))
      (is (= "Zulmira Rocha" (:nome l)))
      (is (= "Zu do Povo" (:nome-parlamentar l)))
      (is (= "PDT" (:partido l)))
      (is (= "vigente" (:estado-mandato l)))
      (is (nil? (:cargo-mesa l)) "sem cargo na Mesa cadastrado -> nil, nao erro"))))

;; ---------- cargo na Mesa (fatia 1b-WIRE: LinhaChamadaOut.cargo-mesa) ----------

(deftest roster-traz-cargo-na-mesa-vigente
  ;; A chamada precisa distinguir o presidente/secretario na lista (telao da Mesa de conducao). So' quem tem
  ;; CARGO vigente na comissao tipo='mesa' na `data` aparece com `cargo-mesa`; membro comum da Casa (sem
  ;; cargo) e' nil, nao erro nem string vazia.
  (let [ente (random-uuid)
        leg  (casa! ente)
        presidente (vereador! ente "Presidente da Mesa")
        comum      (vereador! ente "Vereador Comum")
        mesa-id (random-uuid)]
    (mandato! ente leg presidente (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg comum      (LocalDate/of 2025 1 1) nil)
    (repo/criar-comissao! *repo* ente {:id mesa-id :ente-id ente :nome "Mesa Diretora" :tipo "mesa"
                                       :legislatura-id leg :vigencia-inicio (LocalDate/of 2025 1 1)})
    (repo/criar-cargo! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                    :vereador-id presidente :cargo "presidente"
                                    :vigencia-inicio (LocalDate/of 2025 1 1)})
    (let [linhas (into {} (map (juxt :vereador-id identity)) (repo/roster-da-casa *repo* ente hoje))]
      (is (= "presidente" (:cargo-mesa (get linhas presidente))))
      (is (nil? (:cargo-mesa (get linhas comum)))
          "membro da Casa sem cargo na Mesa -> nil (nao um LEFT JOIN vira INNER e some da lista)"))))

(deftest roster-ordena-por-nome-e-nao-repete-vereador
  ;; Ordem por nome: a chamada e' LIDA em voz alta e conferida linha a linha; ordem instavel entre dois
  ;; carregamentos da mesma tela e' erro de conferencia garantido.
  (let [ente (random-uuid)
        leg  (casa! ente)
        c (vereador! ente "Carla")
        a (vereador! ente "Ana")
        b (vereador! ente "Bruno")]
    (mandato! ente leg c (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg a (LocalDate/of 2025 1 1) nil)
    ;; dois stints do MESMO vereador (o anterior ja' encerrado): um LEFT JOIN plano faria fan-out e Bruno
    ;; apareceria duas vezes na chamada. O LATERAL LIMIT 1 garante uma linha por vereador.
    (mandato! ente leg b (LocalDate/of 2021 1 1) (LocalDate/of 2024 12 31))
    (mandato! ente leg b (LocalDate/of 2025 1 1) nil)
    (let [linhas (repo/roster-da-casa *repo* ente hoje)]
      (is (= ["Ana" "Bruno" "Carla"] (mapv :nome linhas)))
      (is (= 3 (count linhas)) "Bruno tem dois mandatos e UMA linha"))))

;; ---------- REVISAO Etapa 1 (MENOR): homonimos exigem desempate deterministico ----------

(deftest roster-desempata-homonimos-por-id-e-nao-troca-de-ordem
  ;; `nome` NAO e' unico em `cadastros.vereador` (homonimia e' comum em camara municipal — o que distingue
  ;; e' o nome parlamentar). Sem desempate, o Postgres nao garante ordem relativa entre linhas de mesma
  ;; chave de sort e o plano pode mudar entre duas execucoes identicas: o secretario que confere a lista
  ;; impressa contra a tela marca o vereador errado, e a ata sai com a presenca do homonimo.
  (let [ente (random-uuid)
        leg  (casa! ente)
        h1 (vereador! ente "Antonio Carlos Silva" "Antonio do Bairro")
        h2 (vereador! ente "Antonio Carlos Silva" "Toninho da Feira")
        z  (vereador! ente "Zeze")]
    (mandato! ente leg h1 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg h2 (LocalDate/of 2025 1 1) nil)
    (mandato! ente leg z  (LocalDate/of 2025 1 1) nil)
    (let [ordens (repeatedly 5 #(mapv :vereador-id (repo/roster-da-casa *repo* ente hoje)))]
      (is (= 1 (count (set ordens))) "cinco leituras identicas -> UMA unica ordem")
      (is (= (sort-by str [h1 h2]) (vec (take 2 (first ordens))))
          "os homonimos saem em ordem de id (desempate explicito), antes de 'Zeze'")
      (is (= z (last (first ordens)))))))
