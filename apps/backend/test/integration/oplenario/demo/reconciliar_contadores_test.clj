(ns oplenario.demo.reconciliar-contadores-test
  "INTEGRACAO (PG real): `reconciliar-contadores/reconciliar!` — o reparo do par de invariante
  `shared.sequencial` x linhas numeradas (achado da T2 grupo B, `docs/16-ledger-prontidao.md`).

  O teste que importa aqui NAO e' 'a funcao roda'. E' este: **as strings de escopo deste ns tem de ser
  IDENTICAS as que cada `db/` monta quando chama `sequencial/proximo!`.** Elas estao escritas em dois
  lugares (o SQL daqui e o `(str tipo \":\" ano)` de la'), e uma divergencia silenciosa faz a
  reconciliacao 'passar' reparando um escopo que ninguem usa, enquanto o escopo real continua colidindo
  em 500. Por isso o teste faz o ROUND-TRIP contra o contador de verdade: semeia pela borda real (que
  bumpa o contador), le' o escopo que o proprio `proximo!` gravou em `shared.sequencial`, e exige que
  `pisos` devolva EXATAMENTE aquele mesmo escopo. Renomear o escopo de um lado so' reprova.

  `with-sistema` reusada de `oplenario.demo.casa-test`, como os outros testes de `demo/`."
  (:require [acervo]
            [casa]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.kernel.tenancy :as tenancy]
            [participacao :as participacao-demo]
            [reconciliar-contadores :as recon]))

(defn- escopos-gravados-pelo-proximo
  "Os escopos que o proprio `proximo!` criou em `shared.sequencial` para este ente — a FONTE contra a
  qual as strings deste ns sao conferidas."
  [ds ente]
  (into #{} (map :sequencial/escopo)
        (jdbc/execute! ds ["select escopo from shared.sequencial where ente_id = ?" ente])))

(deftest escopos-da-reconciliacao-batem-com-os-que-proximo-gravou
  (with-sistema [s]
    (let [ds (:ds (:datasource s))
          {:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          _ (participacao-demo/semear! s ente)
          reais (escopos-gravados-pelo-proximo ds ente)
          meus  (into #{} (map :escopo) (recon/pisos ds ente))]
      (testing "a semente exercitou numeracao de verdade (senao o teste nao provaria nada)"
        (is (seq reais) "nenhum contador foi criado — a semente nao numerou nada e este teste seria vazio"))
      (testing "todo escopo que o `proximo!` de fato usou e' coberto por uma consulta deste ns"
        (is (empty? (clojure.set/difference reais meus))
            (str "escopo(s) numerado(s) pela borda real que a reconciliacao NAO enxerga — se o contador "
                 "deles se perder, ninguem repara e a escrita fica em 500 permanente. Faltando: "
                 (pr-str (clojure.set/difference reais meus))))))))

(deftest reconciliacao-repara-contador-perdido-e-a-escrita-volta-a-funcionar
  (with-sistema [s]
    (let [ds (:ds (:datasource s))
          {:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          _ (participacao-demo/semear! s ente)
          escopo (first (sort (escopos-gravados-pelo-proximo ds ente)))
          antes  (:sequencial/valor (jdbc/execute-one! ds ["select valor from shared.sequencial where ente_id = ? and escopo = ?" ente escopo]))]
      (testing "premissa: ha um contador com valor > 0 para destruir"
        (is (pos? antes) (str "escopo " escopo " nao tinha contador")))

      ;; destroi SO' o contador deste ente/escopo, deixando as linhas numeradas — a forma exata do
      ;; estrago que o TRUNCATE de fixture causava (hoje barrado por `sequencial-lint-test`).
      (jdbc/execute! ds ["delete from shared.sequencial where ente_id = ? and escopo = ?" ente escopo])
      (is (nil? (jdbc/execute-one! ds ["select valor from shared.sequencial where ente_id = ? and escopo = ?" ente escopo]))
          "o contador foi mesmo destruido")

      (testing "sem reparo, `proximo!` volta a 1 — o valor que JA' esta gravado numa linha (a colisao)"
        (is (= 1 (tenancy/com-tenant* ds ente (fn [tx] (sequencial/proximo! tx escopo))))
            "e' exatamente por isso que a UNIQUE (ente, ano, sequencial) estoura e a tx aborta em 500")
        (jdbc/execute! ds ["delete from shared.sequencial where ente_id = ? and escopo = ?" ente escopo]))

      (testing "apos reconciliar, o proximo numero emitido passa do maior ja' gravado"
        (let [r (recon/reconciliar! ds ente)
              deste (first (filter #(= escopo (:escopo %)) r))]
          (is (some? deste) (str "reconciliar! nao cobriu o escopo " escopo))
          ;; A garantia e' o PISO (o maior numero ja' gravado nas linhas), NAO o valor anterior do
          ;; contador. Os dois podem divergir legitimamente: um numero alocado numa tx que reverteu
          ;; deixa o contador a' frente das linhas (medido na Casa da demo: `autografo:2026` em 7 com
          ;; max(numero)=6). A reconciliacao le' as LINHAS — e' tudo que ela pode saber — entao exigir
          ;; que ela devolva o contador antigo era uma assercao que o teste nao tinha direito de fazer.
          ;; O que importa, e o que esta fn promete, e' que o proximo numero NAO COLIDE.
          (is (>= (:valor-final deste) (:piso deste))
              "o contador nunca fica abaixo do maior numero ja' gravado")
          (is (<= (:valor-final deste) antes)
              "e nunca inventa numero acima do que as linhas justificam")
          (is (= (inc (:valor-final deste))
                 (tenancy/com-tenant* ds ente (fn [tx] (sequencial/proximo! tx escopo))))
              "o proximo numero segue o contador reconciliado — sem colisao com linha existente"))))))

(deftest reconciliacao-e-idempotente-e-nao-abaixa
  (with-sistema [s]
    (let [ds (:ds (:datasource s))
          {:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          primeira (recon/reconciliar! ds ente)
          segunda  (recon/reconciliar! ds ente)]
      (is (= (mapv (juxt :escopo :valor-final) primeira)
             (mapv (juxt :escopo :valor-final) segunda))
          "rodar a reconciliacao 2x nao move contador nenhum — GREATEST, nao incremento")
      (is (every? #(>= (:valor-final %) (:piso %)) primeira)
          "nenhum contador ficou ABAIXO do maior numero ja' gravado"))))
