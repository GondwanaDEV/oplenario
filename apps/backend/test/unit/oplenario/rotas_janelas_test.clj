(ns oplenario.rotas-janelas-test
  "UNIT (sem Postgres, sem relogio, sem container) — `rotas/janelas-de-exercicio`, a defn PURA de TOPO do
  host que converte os stints de mandato + as licencas de `cadastros` na JANELA DE EXERCICIO que a fatia 6
  usa para recortar o denominador de presenca (carry I-5).

  Por que UNIT e por que de TOPO: a conversao e' a peca errable da fatia (borda inclusiva, `fim_efetivo`
  vs `vigencia_fim`, vao entre stints, licenca em curso) e nada nela precisa de banco. Fosse closure dentro
  de `montar`, so' seria exercitavel subindo rota — molde declarado de `rotas/resolver-vereador`
  (docstring `rotas.clj`).

  O que este ns NAO cobre: a leitura em si (`ficha-e-mandatos-do-vereador` — fatia 3, tem ns proprio contra
  PG real) e o seam `ficha-e-janelas-publicas` (integracao, `transparencia/janelas_de_exercicio_test`)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc.result-set :as rs]
            [oplenario.kernel.db-tipos]
            [oplenario.rotas :as rotas])
  (:import (java.time LocalDate)))

(defn- d [s] (LocalDate/parse s))

(defn- iv
  "Intervalo na forma canonica do kernel: inclusivo dos dois lados, `fim` nil = em aberto."
  [inicio fim]
  {:inicio (d inicio) :fim (when fim (d fim))})

(defn- mandato
  "Linha de `cadastros.mandato` como `mandatos-do-vereador` a devolve (kebab; colunas `date` ja' chegam
  como `java.time.LocalDate` por `kernel/db_tipos`)."
  [m]
  (merge {:id (random-uuid) :estado "vigente" :natureza "titular" :partido "PDT"
          :vigencia-inicio nil :vigencia-fim nil :fim-efetivo nil}
         m))

(defn- licenca [m]
  (merge {:mandato-id (random-uuid) :inicio nil :fim nil} m))

;; ---------------------------------------------------------------------------
;; a janela em si
;; ---------------------------------------------------------------------------

(deftest janela-de-titular-de-mandato-aberto-tem-fim-nil
  (is (= [(iv "2025-01-01" nil)]
         (rotas/janelas-de-exercicio
          [(mandato {:vigencia-inicio (d "2025-01-01") :vigencia-fim nil})]
          []))
      "mandato sem fim registrado -> janela EM ABERTO (nil), nunca fechada em `hoje`: fechar em hoje
       congelaria o denominador do titular em exercicio a cada requisicao"))

(deftest janela-de-suplente-e-so-o-periodo-da-convocacao
  (let [js (rotas/janelas-de-exercicio
            [(mandato {:natureza "suplente"
                       :vigencia-inicio (d "2026-03-01") :vigencia-fim (d "2026-04-30")})]
            [])]
    (is (= [(iv "2026-03-01" "2026-04-30")] js)
        "a janela e' EXATAMENTE a convocacao — e' o caso que abriu o carry I-5 (o suplente de 3 sessoes
         recebia o denominador da legislatura inteira)")
    (is (some? (:fim (first js)))
        "e ela e' FECHADA: um `fim` nil aqui devolveria a injustica com outra roupa")))

(deftest janela-de-cassado-fecha-em-fim-efetivo-e-nao-em-vigencia-fim
  (let [js (rotas/janelas-de-exercicio
            [(mandato {:estado "cassado"
                       :vigencia-inicio (d "2025-01-01")
                       :vigencia-fim (d "2028-12-31")
                       :fim-efetivo (d "2026-05-20")})]
            [])]
    (is (= [(iv "2025-01-01" "2026-05-20")] js)
        "`mudar-estado!` carimba `fim_efetivo` e NAO fecha `vigencia_fim` — ler so' `vigencia_fim` faria o
         cassado/renunciado/falecido seguir acumulando denominador ate o fim nominal da legislatura")
    (is (not= (d "2028-12-31") (:fim (first js))))))

(deftest janela-de-licenciado-nao-inclui-o-periodo-da-licenca
  (is (= [(iv "2025-01-01" "2025-05-31") (iv "2025-09-01" nil)]
         (rotas/janelas-de-exercicio
          [(mandato {:vigencia-inicio (d "2025-01-01") :vigencia-fim nil})]
          [(licenca {:inicio (d "2025-06-01") :fim (d "2025-08-31")})]))
      "`registrar-licenca!` so' troca `mandato.estado` e NAO fecha a vigencia: sem subtrair o intervalo da
       propria `mandato_licenca`, o licenciado por 3 meses paga por sessoes das quais estava afastado.
       Bordas INCLUSIVAS: a janela morre na vespera (05-31) e renasce no dia seguinte (09-01)"))

(deftest licenca-em-curso-com-fim-nulo-fecha-a-janela-no-inicio-dela
  ;; CARRY ABERTO (revisao da fatia 3, decisao pendente do Daouda): `mandato_licenca.fim` nao tem NENHUM
  ;; caminho de UPDATE no sistema — o unico statement que toca a tabela e' o INSERT de `inserir-licenca!`.
  ;; Logo esta janela fecha PARA SEMPRE, e o vereador que voltar de uma licenca sem data publica 100% de
  ;; presenca tendo faltado a tudo desde o retorno. Esta fatia implementa a semantica ESCRITA na decisao
  ;; (buraco aberto a' direita); a saida (b) — "licenca com `fim` nil NAO subtrai nada" — inverteria este
  ;; deftest e e' decisao do Daouda, nao da execucao.
  (is (= [(iv "2025-01-01" "2026-02-09")]
         (rotas/janelas-de-exercicio
          [(mandato {:vigencia-inicio (d "2025-01-01") :vigencia-fim nil})]
          [(licenca {:inicio (d "2026-02-10") :fim nil})]))
      "buraco em aberto (`fim` nil = licenca em curso) fecha a janela na VESPERA do seu inicio"))

(deftest multiplos-stints-viram-intervalos-disjuntos-sem-o-vao-entre-eles
  (let [js (rotas/janelas-de-exercicio
            [(mandato {:estado "concluido"
                       :vigencia-inicio (d "2021-01-01") :vigencia-fim (d "2024-12-31")})
             (mandato {:vigencia-inicio (d "2029-01-01") :vigencia-fim nil})]
            [])]
    (is (= [(iv "2021-01-01" "2024-12-31") (iv "2029-01-01" nil)] js)
        "DOIS ramos disjuntos, nunca um [primeira linha, ultima linha] unico: o vao 2025-2028 e' o
         periodo em que a pessoa NAO era vereadora e nao pode entrar no denominador")
    (is (= 2 (count js)) "os stints nao se fundem — o vao entre eles nao e' exercicio")))

(deftest vereador-sem-mandato-devolve-lista-de-janelas-vazia
  (testing "listas vazias"
    (is (= [] (rotas/janelas-de-exercicio [] []))))
  (testing "nil (nenhuma leitura fez linha) tambem, sem NPE"
    (is (= [] (rotas/janelas-de-exercicio nil nil))))
  (testing "e a lista vazia NUNCA vira janela em aberto — seria republicar o I-5 com denominador global"
    (is (empty? (rotas/janelas-de-exercicio nil [(licenca {:inicio (d "2025-01-01") :fim nil})])))))

;; ---------------------------------------------------------------------------
;; guards de FONTE (o que nenhum assert comportamental consegue falsificar)
;; ---------------------------------------------------------------------------

(deftest janela-recebe-localdate-porque-kernel-db-tipos-ja-converte-a-coluna-date
  ;; A decisao original mandava a fatia 4 normalizar `java.sql.Date` -> `LocalDate`. E' FALSO que o pgjdbc
  ;; entregue `java.sql.Date` aqui: `kernel/db_tipos` estende `next.jdbc.result-set/ReadableColumn` para
  ;; `java.sql.Date` com `.toLocalDate`, e o ns e' `require`-d pelo componente datasource — vale p/ todo
  ;; `db/` de modulo. Um `.toLocalDate` incondicional em `janelas-de-exercicio` estouraria no PRIMEIRO
  ;; acesso real (500 na rota publica anonima). Este deftest e' o guard de REGRESSAO da ponte, nao a prova
  ;; de um caminho que producao produza.
  (is (= (LocalDate/of 2026 1 1)
         (rs/read-column-by-index (java.sql.Date/valueOf "2026-01-01") nil 1))
      "coluna `date` chega como java.time.LocalDate — se `kernel/db_tipos` perder a extensao, e' AQUI
       que se descobre, nao em producao")
  (is (every? #(and (instance? LocalDate (:inicio %))
                    (or (nil? (:fim %)) (instance? LocalDate (:fim %))))
              (rotas/janelas-de-exercicio
               [(mandato {:vigencia-inicio (d "2025-01-01") :vigencia-fim (d "2025-12-31")})]
               []))
      "a janela sai em LocalDate — o predicado SQL da fatia 6 compara data-com-data")
  (let [fonte (slurp "src/oplenario/rotas.clj")]
    (is (not (re-find #"toLocalDate" fonte))
        "o host NAO converte: a conversao ja' aconteceu em `kernel/db_tipos`, e um cast incondicional aqui
         seria ClassCastException no primeiro acesso real")))

(deftest janelas-de-exercicio-e-defn-de-topo-nao-closure-dentro-de-montar
  ;; O assert comportamental (este ns chamar `rotas/janelas-de-exercicio` sem subir rota) ja' prova que ela
  ;; e' publica; o guard de FONTE abaixo e' o que impede a regressao de VOLTAR a logica para dentro de
  ;; `montar` mantendo um wrapper de topo. Molde do `estrutura-lint-test`.
  (let [fonte (slurp "src/oplenario/rotas.clj")
        pos-janelas (.indexOf fonte "(defn janelas-de-exercicio")
        pos-montar  (.indexOf fonte "(defn montar")]
    (is (pos? pos-janelas) "`janelas-de-exercicio` e' defn de topo em rotas.clj")
    (is (pos? pos-montar))
    (is (< pos-janelas pos-montar)
        "declarada ANTES de `montar` — logo fora do corpo dele (assert com dentes: mover a defn p/ dentro
         do `let` de `montar` fica vermelho aqui)")))
