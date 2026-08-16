(ns oplenario.kernel.tempo-test
  "Relogio injetado (§22.6 'tempo como coordenada de primeira classe'): producao usa o
  relogio do sistema; teste crava o instante. O motor ja consome 'agora' como valor (runtime),
  o kernel so o PRODUZ de forma injetavel.

  A segunda metade do ns cobre a ARITMETICA PURA de intervalos de data civil (I-5 fatia 2):
  `normalizar-intervalos` e `subtrair-intervalos` sobre `{:inicio LocalDate :fim (maybe LocalDate)}`
  INCLUSIVO nos dois lados, `fim` nil = em aberto. Sem banco, sem relogio: aqui nao ha 'mandato'
  nem 'licenca' — o kernel nao conhece modulo (kernel-sem-modulo), so' intervalos anonimos."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Instant LocalDate ZoneId)))

(defn- d
  "Atalho de legibilidade: \"2026-01-01\" -> LocalDate."
  ^LocalDate [s]
  (LocalDate/parse s))

(defn- iv
  "Intervalo inclusivo; `fim` omitido = em aberto (nil)."
  ([inicio] {:inicio (d inicio) :fim nil})
  ([inicio fim] {:inicio (d inicio) :fim (d fim)}))

(deftest relogio-fixo-devolve-o-instante-cravado
  (let [t (Instant/parse "2026-06-26T12:00:00Z")
        r (tempo/relogio-fixo t)]
    (is (= t (tempo/agora r)) "relogio fixo devolve exatamente o instante cravado")))

(deftest relogio-sistema-devolve-um-instant
  (let [r (tempo/relogio-sistema)]
    (is (instance? Instant (tempo/agora r)) "relogio do sistema devolve um java.time.Instant")))

(deftest hoje-projeta-o-instante-em-localdate-na-zona
  ;; 2026-06-26T02:00:00Z e' 2026-06-25 em America/Fortaleza (UTC-3) — prova que a zona conta.
  (let [r (tempo/relogio-fixo (Instant/parse "2026-06-26T02:00:00Z"))]
    (is (= (LocalDate/parse "2026-06-25")
           (tempo/hoje r (ZoneId/of "America/Fortaleza")))
        "hoje converte o instante na zona dada (nao em UTC)")))

;; ---------------------------------------------------------------------------
;; Aritmetica de intervalos de data civil (I-5 fatia 2)
;; ---------------------------------------------------------------------------

(deftest menor-fim-escolhe-o-encerramento-mais-CEDO-e-nil-e-mais-tarde-que-tudo
  ;; Revisao da fatia 4 (achado MENOR): o host fechava o stint com `(or fim-efetivo vigencia-fim)`, que
  ;; significa "prefira fim-efetivo" e NAO "pegue o menor". `fim_efetivo` e' semanticamente um encerramento
  ;; ANTECIPADO (cassacao/renuncia/falecimento) e `mudar-estado!` o grava com `[:coalesce ...]` sem nenhuma
  ;; checagem contra `vigencia_fim` (nao ha CHECK na mig 0010 nem trigger): uma data digitada errada,
  ;; POSTERIOR ao fim da vigencia, ALARGAVA a janela publicada em vez de encurta-la.
  (is (= (d "2026-05-20") (tempo/menor-fim (d "2026-05-20") (d "2028-12-31")))
      "encerramento antecipado ganha do fim nominal")
  (is (= (d "2028-12-31") (tempo/menor-fim (d "2029-06-30") (d "2028-12-31")))
      "fim POSTERIOR ao nominal nao estende o intervalo — e' o minimo, nao a preferencia")
  (is (= (d "2028-12-31") (tempo/menor-fim nil (d "2028-12-31")))
      "nil = +infinito, entao perde de qualquer data")
  (is (= (d "2026-05-20") (tempo/menor-fim (d "2026-05-20") nil))
      "... dos dois lados (mandato em aberto encerrado antecipadamente fecha na data)")
  (is (nil? (tempo/menor-fim nil nil))
      "dois em aberto continuam em aberto — nunca um fim inventado")
  (is (= (d "2026-05-20") (tempo/menor-fim (d "2026-05-20") (d "2026-05-20")))
      "iguais devolvem a propria data"))

(deftest subtrair-buraco-no-meio-parte-a-janela-em-duas
  (is (= [(iv "2026-01-01" "2026-05-31")
          (iv "2026-07-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-06-01" "2026-06-30")]))
      "buraco interno gera DUAS partes, cada uma recuada/avancada um dia (bordas inclusivas)"))

(deftest subtrair-buraco-que-cobre-a-janela-inteira-devolve-vazio
  (is (= []
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2025-12-01" "2027-01-31")]))
      "buraco que engloba a janela nao deixa resto")
  (is (= []
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-01-01" "2026-12-31")]))
      "buraco com as MESMAS bordas inclusivas tambem zera — nao sobra dia de borda"))

(deftest subtrair-buraco-aberto-a-direita-fecha-a-janela-no-dia-anterior
  (is (= [(iv "2026-01-01" "2026-05-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-06-01")]))
      "buraco com fim nil (em aberto) corta tudo dali para frente; a janela fecha em 05-31"))

(deftest subtrair-buraco-fora-da-janela-nao-altera-nada
  (is (= [(iv "2026-01-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2027-03-01" "2027-04-01")]))
      "buraco depois da janela nao a toca")
  (is (= [(iv "2026-01-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2027-01-01" "2027-01-05")]))
      "buraco ADJACENTE (comeca no dia seguinte ao fim) nao sobrepoe — nada muda")
  (is (= [(iv "2026-01-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2025-11-01" "2025-12-31")]))
      "buraco adjacente ANTES do inicio tambem nao sobrepoe"))

(deftest subtrair-de-janela-aberta-a-direita-preserva-o-fim-nil
  (is (= [(iv "2026-01-01" "2026-02-28")
          (iv "2026-04-01")]
         (tempo/subtrair-intervalos [(iv "2026-01-01")]
                                    [(iv "2026-03-01" "2026-03-31")]))
      "a parte da direita de uma janela em aberto continua em aberto (fim nil), nunca vira data"))

(deftest buraco-que-toca-a-borda-inclusiva-corta-um-dia-so
  (is (= [(iv "2026-01-01" "2026-12-30")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-12-31" "2026-12-31")]))
      "buraco de UM dia sobre o ultimo dia inclusivo tira exatamente esse dia")
  (is (= [(iv "2026-01-02" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-01-01" "2026-01-01")]))
      "idem na borda esquerda"))

(deftest normalizar-funde-intervalos-adjacentes-e-sobrepostos
  (is (= [(iv "2026-01-01" "2026-02-28")
          (iv "2026-05-01" "2026-05-31")
          (iv "2026-09-01")]
         (tempo/normalizar-intervalos [(iv "2026-05-10" "2026-05-31")
                                       (iv "2026-02-01" "2026-02-28")
                                       (iv "2026-09-01")
                                       (iv "2026-01-01" "2026-01-31")
                                       (iv "2026-05-01" "2026-05-20")]))
      "ordena por inicio, funde ADJACENTES (01-31 + 02-01) e SOBREPOSTOS (05-01..05-20 + 05-10..05-31),
       e preserva o vao real entre fevereiro e maio")
  (is (= [(iv "2026-01-01")]
         (tempo/normalizar-intervalos [(iv "2026-01-01")
                                       (iv "2026-03-01" "2026-03-31")]))
      "intervalo em aberto absorve qualquer intervalo posterior"))

(deftest normalizar-funde-fechado-com-aberto-que-chega-depois
  ;; Ramo em que `fim-ultimo` e' NAO-nil e o `fim` que CHEGA e' nil: a fusao TEM de ficar em aberto.
  ;; E' a forma exata do suplente que vira titular (stint fechado + mandato em curso). Sem estes
  ;; asserts, fechar a janela na data do stint anterior passa pela suite inteira (mutante sobrevivia).
  (is (= [(iv "2025-03-01")]
         (tempo/normalizar-intervalos [(iv "2025-03-01" "2026-06-30")
                                       (iv "2026-07-01")]))
      "stints ADJACENTES em que o segundo esta em aberto: a janela fundida fica EM ABERTO, nao fecha em 06-30")
  (is (= [(iv "2026-01-01")]
         (tempo/normalizar-intervalos [(iv "2026-01-01" "2026-06-30")
                                       (iv "2026-03-01")]))
      "stints SOBREPOSTOS em que o segundo esta em aberto: idem — o aberto manda"))

(deftest normalizar-nao-funde-atraves-de-vao-de-um-dia
  ;; Fronteira exata da tolerancia de adjacencia (`fim-ultimo + 1 dia`). O lado que FUNDE ja' esta
  ;; coberto (01-31 + 02-01); este e' o lado NEGATIVO — relaxar a constante (p.ex. `plusDays 2`)
  ;; passava por toda a suite e engolia um dia em que a pessoa NAO era vereadora.
  (is (= [(iv "2026-01-01" "2026-01-31")
          (iv "2026-02-02" "2026-02-28")]
         (tempo/normalizar-intervalos [(iv "2026-01-01" "2026-01-31")
                                       (iv "2026-02-02" "2026-02-28")]))
      "vao de UM dia (02-01) e' real: os dois intervalos continuam separados"))

(deftest normalizar-intervalo-contido-nao-encolhe-o-continente
  ;; Distingue `max(fim, fim-ultimo)` de 'devolver sempre o fim que chegou': so' e' visivel quando o
  ;; intervalo POSTERIOR por :inicio termina ANTES do anterior (esta contido nele).
  (is (= [(iv "2026-01-01" "2026-12-31")]
         (tempo/normalizar-intervalos [(iv "2026-01-01" "2026-12-31")
                                       (iv "2026-03-01" "2026-03-31")]))
      "intervalo contido some dentro do continente; o fim continua sendo o MAIOR dos dois"))

(deftest subtrair-buraco-de-um-dia-no-meio-nao-e-refundido
  ;; `subtrair-intervalos` re-normaliza o resultado: se a tolerancia de adjacencia for relaxada, a
  ;; normalizacao final RE-FUNDE os dois pedacos e desfaz a subtracao sem deixar rastro.
  (is (= [(iv "2026-01-01" "2026-06-14")
          (iv "2026-06-16" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-06-15" "2026-06-15")]))
      "buraco de UM dia no MEIO parte a janela em duas e as duas partes NAO voltam a se fundir"))

(deftest subtrair-com-varias-janelas-e-varios-buracos
  ;; O `reduce` sobre os buracos e o `mapcat` sobre os restos so' aparecem com N > 1 dos DOIS lados —
  ;; que e' exatamente o regime da fatia 4 (N stints x N licencas).
  (is (= [(iv "2024-01-01" "2024-02-29")
          (iv "2024-04-01" "2024-06-30")
          (iv "2026-01-01" "2026-07-31")
          (iv "2026-09-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2024-01-01" "2024-06-30")
                                     (iv "2026-01-01" "2026-12-31")]
                                    [(iv "2024-03-01" "2024-03-31")
                                     (iv "2026-08-01" "2026-08-31")]))
      "duas janelas disjuntas, um buraco em cada: quatro pedacos, nenhuma janela perdida"))

(deftest intervalo-sem-inicio-estoura-em-vez-de-virar-janela-infinita
  ;; A forma canonica exige `:inicio` nao-nil. Sem validacao, `{:inicio nil :fim nil}` sobrevivia ao
  ;; descarte de vazios (curto-circuito em `(some? fim)`), ordenava em PRIMEIRO (nil < tudo) e fundia
  ;; todo o resto dentro de si: uma janela de TODO o tempo, calada. Fail-open no pior lugar possivel.
  (is (thrown? clojure.lang.ExceptionInfo
               (tempo/normalizar-intervalos [{:inicio nil :fim nil}]))
      "intervalo sem :inicio estoura em vez de virar janela infinita")
  (is (thrown? clojure.lang.ExceptionInfo
               (tempo/normalizar-intervalos [(iv "2025-01-01" "2025-12-31") {:inicio nil :fim nil}]))
      "e nao engole em silencio as janelas reais que vieram junto")
  (is (thrown? clojure.lang.ExceptionInfo
               (tempo/normalizar-intervalos [nil]))
      "elemento nil na colecao cai na mesma porta")
  (is (thrown? clojure.lang.ExceptionInfo
               (tempo/subtrair-intervalos [{:inicio nil :fim nil}] []))
      "subtrair-intervalos normaliza os DOIS argumentos, entao herda a mesma guarda nas janelas")
  (is (thrown? clojure.lang.ExceptionInfo
               (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")] [{:inicio nil :fim nil}]))
      "... e nos buracos"))

(deftest normalizar-descarta-intervalo-de-inicio-posterior-ao-fim
  (is (= [(iv "2026-01-01" "2026-01-31")]
         (tempo/normalizar-intervalos [(iv "2026-05-01" "2026-04-30")
                                       (iv "2026-01-01" "2026-01-31")]))
      "intervalo vazio (inicio > fim) e' descartado, nao invertido")
  (is (= [(iv "2026-01-01" "2026-01-01")]
         (tempo/normalizar-intervalos [(iv "2026-01-01" "2026-01-01")]))
      "intervalo de UM dia (inicio = fim) NAO e' vazio — bordas sao inclusivas")
  (is (= [] (tempo/normalizar-intervalos []))
      "lista vazia continua vazia"))

(deftest zona-civil-padrao-e-a-mesma-usada-pelo-seam-de-ficha
  ;; O seam `ficha-vereador-publica` (e `membros-da-casa`) em `rotas.clj` carregava o literal
  ;; (ZoneId/of "America/Fortaleza"); a constante do kernel tem de ser IDENTICA em valor e efeito,
  ;; senao a troca da fatia 2 mudaria a data civil que decide mandato/comissao vigente.
  (is (= (ZoneId/of "America/Fortaleza") tempo/zona-civil-padrao)
      "a constante do kernel e' exatamente a zona que o host usava literal")
  (let [r (tempo/relogio-fixo (Instant/parse "2026-06-26T02:00:00Z"))]
    (is (= (tempo/hoje r (ZoneId/of "America/Fortaleza"))
           (tempo/hoje r tempo/zona-civil-padrao))
        "mesma data civil derivada do mesmo instante — troca sem mudanca de comportamento"))
  ;; Os dois asserts acima provam equivalencia de VALOR da constante; nenhum deles toca `rotas.clj`, e
  ;; portanto nenhum deles quebra se o host voltar a carregar um literal (conflito de merge, copia de
  ;; deploy piloto fora do CE). O guard de FONTE abaixo e' o unico detector desse modo de regressao —
  ;; molde do `estrutura-lint-test`, que ja' varre `src/` por caminho relativo ao cwd da suite.
  (let [fonte (slurp "src/oplenario/rotas.clj")]
    (is (not (re-find #"ZoneId/of" fonte))
        "o host nao pode ter literal de fuso: a zona civil vem de `tempo/zona-civil-padrao` (um lugar so' para mudar)")
    (is (re-find #"tempo/zona-civil-padrao" fonte)
        "e o host de fato usa a constante do kernel (assert com dentes: nao passa vacuo se o uso sumir)")))

(deftest consumidores-de-zona-civil-padrao-estao-DECLARADOS-na-docstring-da-constante
  ;; Revisao da fatia 5 do carry I-5. A docstring da constante se autodescrevia como "unico lugar do fuso em
  ;; `rotas.clj`" e enumerava como carry apenas que ela e' global. Desde a mig 0067 as duas metades ficaram
  ;; falsas: (a) ha' um SEGUNDO consumidor, e ele esta' num MODULO (`transparencia`), nao no host; (b) o valor
  ;; derivado dela deixou de ser efemero — vira `transparencia.sessao_com_chamada.data`, PERSISTIDA, e o repo
  ;; nao tem ferramenta de re-projecao (carry conhecido desde o F6c). Quem for fazer o trabalho ja' decidido
  ;; ("fuso vira atributo do ente antes do primeiro cliente fora do CE") le a docstring, conclui que o raio de
  ;; impacto e' `rotas.clj`, e deixa para tras as linhas ja' projetadas.
  ;; Este deftest e' o detector: se um TERCEIRO consumidor aparecer, ele falha e obriga a atualizar o carry.
  (let [consumidores (->> (file-seq (io/file "src"))
                          (filter #(.isFile ^java.io.File %))
                          (map #(.getPath ^java.io.File %))
                          (filter #(str/ends-with? % ".clj"))
                          (filter #(str/includes? (slurp %) "zona-civil-padrao"))
                          (remove #(str/ends-with? % "kernel/tempo.clj"))
                          set)]
    (is (= #{"src/oplenario/rotas.clj"                                  ; o host (uso real)
             "src/oplenario/transparencia/components/repositorio.clj"   ; o consumer de presenca (uso real)
             "src/oplenario/transparencia/db/parlamentar.clj"           ; so' cita, na docstring do UPSERT
             "src/oplenario/sessoes/controllers.clj"                     ; a CHAMADA, fatia 1b-WIRE (uso real)
             "src/oplenario/sessoes/logic.clj"                           ; o PISO da janela de presenca (uso real)
             "src/oplenario/sessoes/components/serializador_folha.clj"    ; a FOLHA, Etapa 5 fatia 2
             "src/oplenario/sessoes/components/renderizador_pdf.clj"     ; o PDF da FOLHA, Etapa 5 fatia 3 (uso real)
             "src/oplenario/sessoes/db/sessao.clj"}                      ; a APURACAO, Etapa 6 fatia 2 (uso real)
           consumidores)
        "a lista de arquivos de src/ que MENCIONAM o fuso global mudou — atualize a docstring da constante
         (e este conjunto) ANTES de mergear"))
  (let [doc (:doc (meta #'tempo/zona-civil-padrao))]
    (is (re-find #"transparencia" doc)
        "a docstring tem de nomear o segundo consumidor: ela nao e' mais exclusiva do host")
    (is (re-find #"piso-da-janela-de-presenca" doc)
        "e tem de nomear o quarto consumidor: um fuso errado ali RECUSA escrita, nao so' deforma relatorio")
    (is (re-find #"sessao_com_chamada" doc)
        "e tem de dizer que a constante passou a determinar dado PERSISTIDO — trocar o fuso nao reescreve
         linha ja' projetada, e nao ha re-projecao no repo")))
