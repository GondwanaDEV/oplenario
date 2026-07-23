(ns oplenario.kernel.tempo
  "Relogio injetado: o kernel PRODUZ 'agora' de forma injetavel — producao le o relogio do
  sistema, teste crava o instante. Java time (Instant/LocalDate), consistente com o motor
  (que consome 'agora' como valor no runtime). 'tempo como coordenada de primeira classe' (§22.6).

  Alem do relogio, este ns guarda a ARITMETICA PURA de intervalos de DATA CIVIL
  (`normalizar-intervalos`, `subtrair-intervalos`) e a `zona-civil-padrao`. Sao intervalos
  ANONIMOS: o kernel nao conhece 'mandato' nem 'licenca' (kernel-sem-modulo, §22.10) — quem
  da' nome a eles e' a borda (o host, em `rotas.clj`)."
  (:import (java.time Instant LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(defprotocol Relogio
  (agora [r] "Instante atual como java.time.Instant."))

(defn relogio-sistema
  "Relogio de producao: le o relogio do sistema a cada chamada."
  []
  (reify Relogio
    (agora [_] (Instant/now))))

(defn relogio-fixo
  "Relogio de teste: devolve sempre o instante cravado (determinismo)."
  [^Instant t]
  (reify Relogio
    (agora [_] t)))

(defn hoje-de
  "Data civil (LocalDate) de um `instante` JA LIDO, na zona dada — prazos legais correm por fuso, nao em UTC.
  Use esta aridade quando o mesmo ato precisa ANCORAR recibo (Instant) e data (LocalDate) no MESMO instante:
  ler o relogio uma vez (`agora`) e derivar a data daqui evita duas leituras (e o straddle de meia-noite)."
  ^LocalDate [^Instant instante ^ZoneId zona]
  (LocalDate/ofInstant instante zona))

(defn hoje
  "Data civil (LocalDate) do relogio na zona dada — prazos legais correm por fuso, nao em UTC."
  ^LocalDate [r ^ZoneId zona]
  (hoje-de (agora r) zona))

(def zona-civil-padrao
  "Zona civil compartilhada por DOIS consumidores, NAO do sistema inteiro (`legislativo`, `participacao` e
  `cadastros` ainda tem literais proprios). V1 = `America/Fortaleza` (beachhead Fortaleza/NE).

  QUEM DEPENDE DELA (a lista e' pinada por
  `tempo-test/consumidores-de-zona-civil-padrao-estao-DECLARADOS-na-docstring-da-constante`):
  (a) o HOST, `rotas.clj` — data civil de hoje para decidir mandato/comissao VIGENTE (valor efemero, de
      request); (b) o CONSUMER de `presenca.registrada` em `transparencia/components/repositorio.clj` — a
  data civil que vira a coluna `transparencia.sessao_com_chamada.data`.

  O QUE ESTA CONSTANTE RESOLVE: ate aqui o fuso era literal espalhado pelas bordas; o host
  (`rotas.clj`) tinha DOIS. Ter um lugar so' e' o pre-requisito de transformar o fuso em
  atributo do ente.
  O QUE ELA NAO RESOLVE: ela continua GLOBAL. Uma Casa no Acre (UTC-5) ou em Fernando de
  Noronha (UTC-2) tem outra fronteira de dia civil, e isso desloca qual sessao cai em qual
  data — precisa virar coluna do ente antes do primeiro cliente fora do CE (carry escrito).
  E, DESDE A MIG 0067, um lugar so' para mudar JA' NAO BASTA: esta constante deixou de governar so' valor
  efemero e passa a determinar dado PERSISTIDO (`sessao_com_chamada.data`, o predicado da janela de exercicio
  do mandato no numero-card publico de presenca). Promove-la a atributo do ente NAO corrige as linhas ja'
  projetadas — para uma Casa no Acre, toda sessao noturna ja' gravada fica um dia adiante para sempre. O
  trabalho tem, portanto, DUAS metades: trocar a fonte do fuso E re-projetar a tabela; a segunda nao existe
  no repo (nao ha ferramenta de re-projecao, carry conhecido desde o F6c).
  Os fusos literais que sobrevivem em `legislativo`, `participacao` e `cadastros` NAO foram
  migrados nesta fatia: sao bordas de outros modulos, com testes proprios."
  (ZoneId/of "America/Fortaleza"))

;; --- aritmetica de intervalos de data civil -------------------------------------------------
;;
;; Forma canonica de um intervalo: {:inicio LocalDate :fim (maybe LocalDate)}, INCLUSIVO nos
;; dois lados (mesma semantica do `daterange '[]'` que o EXCLUDE de `cadastros` ja' usa);
;; `:fim` nil = em aberto (+infinito). `:inicio` e' OBRIGATORIO e nao-nil: nao existe "desde
;; sempre" neste dominio (as colunas de origem — `mandato.vigencia_inicio`, `mandato_licenca.inicio`
;; — sao NOT NULL). Passar `:inicio` nil e' erro do chamador e estoura aqui, de proposito: a guarda
;; fica na PORTA de `normalizar-intervalos`, por onde `subtrair-intervalos` passa os DOIS argumentos.
;; Sem ela o caso mais provavel do erro (chave lida com o nome errado -> TODOS os :inicio nil) era
;; fail-OPEN: `{:inicio nil :fim nil}` escapava do descarte de vazios (o `and` curto-circuita em
;; `(some? fim)`), ordenava em primeiro (nil < tudo em `compare`) e fundia as janelas reais dentro
;; de si — uma janela de TODO o tempo, sem excecao e sem log.

(defn menor-fim
  "O MENOR de dois fins de intervalo na forma canonica — `nil` e' +infinito e portanto PERDE de qualquer
  data (o `LEAST` de SQL, que trata NULL como desconhecido, faria o contrario).

  Existe porque `or` nao compara datas: quem tem duas candidatas a fim (uma nominal e uma de encerramento
  ANTECIPADO) e escreve `(or antecipada nominal)` esta' dizendo 'prefira a antecipada', e uma antecipada
  digitada POSTERIOR a nominal passa a ESTENDER o intervalo em vez de encurta-lo. Mora aqui, e nao na borda,
  porque e' aritmetica de intervalo pura e assim fica testavel sem banco e sem interop no host."
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (.isBefore ^LocalDate a ^LocalDate b) a
    :else b))

(defn- fim-em-aberto-ou-nao-antes-de?
  "`data` cabe dentro de `fim` (nil = +infinito)? Inclusivo: `data` = `fim` cabe."
  [^LocalDate data fim]
  (or (nil? fim) (not (.isAfter data ^LocalDate fim))))

(defn- fim-antes-de?
  "`a` termina estritamente antes de `b`? Ambos sao fins: nil = +infinito."
  [a b]
  (cond
    (nil? a) false                                          ; +infinito nunca termina antes
    (nil? b) true                                           ; finito sempre termina antes de +infinito
    :else (.isBefore ^LocalDate a ^LocalDate b)))

(defn- intervalo-vazio?
  "Intervalo sem nenhum dia: `inicio` posterior a `fim`. Um dia so' (inicio = fim) NAO e' vazio."
  [intervalo]
  (let [^LocalDate inicio (:inicio intervalo)
        fim (:fim intervalo)]
    (and (some? fim) (.isAfter inicio ^LocalDate fim))))

(defn- sobrepoem?
  "Os dois intervalos compartilham ao menos um dia? Bordas inclusivas: intervalos apenas
  ADJACENTES (um termina na vespera do outro) NAO se sobrepoem."
  [a b]
  (and (fim-em-aberto-ou-nao-antes-de? (:inicio a) (:fim b))
       (fim-em-aberto-ou-nao-antes-de? (:inicio b) (:fim a))))

(defn normalizar-intervalos
  "Forma canonica de uma colecao de intervalos de data civil: descarta os VAZIOS (inicio > fim),
  ordena por `:inicio` e FUNDE os que se sobrepoem ou sao adjacentes (um termina na vespera do
  outro — com bordas inclusivas, 01-31 e 02-01 sao o mesmo periodo continuo). Devolve um vetor de
  mapas com exatamente `:inicio` e `:fim` (chaves extras do chamador sao DESCARTADAS: intervalo
  fundido nao teria como escolher entre as do original). Um intervalo em aberto (`:fim` nil)
  absorve todos os posteriores. Pura: nao le relogio nem banco.

  FAIL-CLOSED na entrada: elemento sem `:inicio` (ou elemento nil) lanca `ExceptionInfo` — nunca vira
  a janela infinita `{:inicio nil :fim nil}`, que engoliria as janelas reais em silencio."
  [intervalos]
  (let [sem-inicio (seq (remove (comp some? :inicio) intervalos))]
    (when sem-inicio
      (throw (ex-info "normalizar-intervalos: :inicio nao pode ser nil"
                      {:intervalo (first sem-inicio)}))))
  (->> intervalos
       (remove intervalo-vazio?)
       (sort-by :inicio)
       (reduce (fn [acc {:keys [inicio fim]}]
                 (let [ultimo (peek acc)
                       fim-ultimo (:fim ultimo)]
                   (if (and ultimo
                            (or (nil? fim-ultimo)
                                (not (.isAfter ^LocalDate inicio
                                               (.plusDays ^LocalDate fim-ultimo 1)))))
                     (conj (pop acc)
                           {:inicio (:inicio ultimo)
                            :fim (when (and (some? fim-ultimo) (some? fim))
                                   (if (.isAfter ^LocalDate fim ^LocalDate fim-ultimo) fim fim-ultimo))})
                     (conj acc {:inicio inicio :fim fim}))))
               [])))

(defn- subtrair-um
  "Remove UM buraco de UM intervalo; devolve 0, 1 ou 2 pedacos."
  [janela buraco]
  (if-not (sobrepoem? janela buraco)
    [janela]
    (let [^LocalDate inicio (:inicio janela)
          fim (:fim janela)
          ^LocalDate b-inicio (:inicio buraco)
          b-fim (:fim buraco)]
      (cond-> []
        (.isAfter b-inicio inicio)
        (conj {:inicio inicio :fim (.minusDays b-inicio 1)})

        ;; `fim-antes-de?` so' e' verdade com `b-fim` nao-nil: buraco em aberto nao deixa resto a direita.
        (fim-antes-de? b-fim fim)
        (conj {:inicio (.plusDays ^LocalDate b-fim 1) :fim fim})))))

(defn subtrair-intervalos
  "`janelas` menos `buracos`, em dias civis INCLUSIVOS dos dois lados. Entrada e saida na forma
  canonica de `normalizar-intervalos` (ambos os argumentos sao normalizados antes; o resultado
  sai ordenado, sem vazios e sem sobreposicao). Um buraco no meio PARTE a janela em duas; um
  buraco em aberto (`:fim` nil) fecha a janela na vespera do seu inicio; a parte a direita de uma
  janela em aberto CONTINUA em aberto. Pura: sem relogio, sem banco, sem fuso — quem converte
  instante em data civil e' `hoje-de`, na borda.

  O kernel nao sabe o que sao estes intervalos. Quem chama (I-5 fatia 4, `rotas.clj`) e' que le
  'janela = mandato' e 'buraco = licenca'."
  [janelas buracos]
  (normalizar-intervalos
   (reduce (fn [restos buraco]
             (into [] (mapcat #(subtrair-um % buraco)) restos))
           (normalizar-intervalos janelas)
           (normalizar-intervalos buracos))))
