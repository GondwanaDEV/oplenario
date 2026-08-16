(ns oplenario.sessoes.assiduidade-borda-test
  "UNIT (sem Postgres) — Etapa 6 fatia 3: `adapters.in.assiduidade` (query params -> dominio) e
  `adapters.out.assiduidade` (dominio -> JSON validado + CSV puro). A borda e' onde a Fatia 2 apanhou (brief
  §Fatia 3): data malformada NUNCA vira nil silencioso, `formato`/`recorte` desconhecidos NUNCA caem no
  default em silencio — cada `deftest` abaixo prova a FORMA especifica de erro, nao so' 'lanca alguma coisa'."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.adapters.in.assiduidade :as ai]
            [oplenario.sessoes.adapters.out.assiduidade :as ao]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time LocalDate)))

;; ---------- query->periodo ----------

(deftest de-e-ate-viram-localdate
  (is (= {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 6 30) :tipos nil}
         (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30"}))))

(deftest data-ausente-e-invalido-explicito
  (doseq [qp [{:ate "2026-06-30"} {:de "2026-01-01"} {} {:de "" :ate "2026-06-30"}]]
    (testing (str "qp=" qp)
      (let [erro (try (ai/query->periodo qp) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "data obrigatoria ausente NUNCA vira periodo com nil silencioso")
        (is (= :validacao/invalido (:tipo (ex-data erro))))))))

(deftest data-malformada-e-invalido-explicito
  ;; Cada forma testada isoladamente prova um ramo distinto do regex/parse — nao so' "alguma coisa quebrou".
  (doseq [de ["2026-13-01"    ;; mes invalido — passa o REGEX, estoura no LocalDate/parse
              "31/01/2026"    ;; formato BR — nao casa o regex
              "2026-1-1"      ;; sem zero a esquerda — nao casa o regex (\d{2})
              "abacate"       ;; lixo puro
              "+10000000-01-01"]] ;; ano ESTENDIDO ISO — a guarda que a fatia de cadastros ja pagou
    (testing (str "de=" de)
      (let [erro (try (ai/query->periodo {:de de :ate "2026-06-30"}) nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) (str de " deveria ser rejeitada"))
        (is (= :validacao/invalido (:tipo (ex-data erro))))))))

(deftest tipos-separa-por-virgula-e-trima-sem-validar-vocabulario
  ;; O vocabulario (logic/tipos-sessao) e' checado A JUSANTE (controller + db, disciplina do brief) — esta
  ;; borda so' SEPARA a lista, para nao duplicar `tipos-sessao` e arriscar a mesma divergencia de
  ;; `estados-mandato-cadastro`.
  (is (= ["ordinaria" "extraordinaria"]
         (:tipos (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30" :tipos " ordinaria , extraordinaria "}))))
  (is (nil? (:tipos (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30" :tipos ""})))
      "branco = todos os tipos (nil)")
  (is (nil? (:tipos (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30"})))
      "ausente = todos os tipos (nil)")
  (is (= ["typo-qualquer"]
         (:tipos (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30" :tipos "typo-qualquer"})))
      "um tipo FORA do vocabulario atravessa esta borda intacto — quem rejeita e' o controller/db, nao aqui"))

(deftest param-REPETIDO-e-400-nos-CINCO-params-nunca-500-opaco
  ;; `route/parse-query-string` devolve VETOR quando o param se repete; `str/blank?` sobre um vetor estourava
  ;; ClassCastException -> 500 `{"erro":"erro interno"}` com stack no log. E `?tipos=a&tipos=b` e' a convencao
  ;; PADRAO de multivalor (`URLSearchParams.append`), que a Onda E vai montar.
  (doseq [[campo qp] [[:de      {:de ["2026-01-01" "2026-02-01"] :ate "2026-06-30"}]
                      [:ate     {:de "2026-01-01" :ate ["2026-06-30" "2026-07-30"]}]
                      [:tipos   {:de "2026-01-01" :ate "2026-06-30" :tipos ["ordinaria" "secreta"]}]]]
    (testing (str "periodo, campo=" campo)
      (let [erro (try (ai/query->periodo qp) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) (str campo " repetido tem de ser rejeitado, nunca 'escolhe um' em silencio"))
        (is (= :validacao/invalido (:tipo (ex-data erro))))
        (is (= campo (:campo (ex-data erro)))))))
  (doseq [[campo qp] [[:formato {:formato ["json" "csv"]}]
                      [:recorte {:recorte ["resumo" "detalhe"]}]]]
    (testing (str "apresentacao, campo=" campo)
      (let [erro (try (ai/query->apresentacao qp) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro))
        (is (= :validacao/invalido (:tipo (ex-data erro))))
        (is (= campo (:campo (ex-data erro))))))))

(deftest tipos-degenerado-e-400-NUNCA-alarga-o-recorte-para-todos
  ;; O defeito da Fatia 2 com o SINAL INVERTIDO: filtro malformado -> resultado MAIS AMPLO que o pedido
  ;; (todos os tipos, `secreta` inclusive), HTTP 200, num arquivo que sai da Casa.
  (doseq [s ["," ",,," " , " "  ,  ,  "]]
    (testing (pr-str s)
      (let [erro (try (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30" :tipos s}) nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "string presente que produz lista vazia NUNCA vira nil (= todos os tipos)")
        (is (= :validacao/invalido (:tipo (ex-data erro))))
        (is (= :tipos (:campo (ex-data erro))))))))

(deftest tipos-repetido-na-mesma-string-colapsa-e-tem-teto-de-cardinalidade
  (is (= ["ordinaria"]
         (:tipos (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30"
                                     :tipos "ordinaria,ordinaria,ordinaria"})))
      "o filtro e' um CONJUNTO — o repetido nao muda o recorte e nao polui o `IN` nem o cabecalho do CSV")
  (let [muitos (str/join "," (repeat 800 "ordinaria"))
        erro (try (ai/query->periodo {:de "2026-01-01" :ate "2026-06-30" :tipos muitos}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "800 elementos cabem numa URL — o teto tem de existir e tem de disparar")
    (is (= :limite/tipos-excedido (:tipo (ex-data erro))))
    (is (= 800 (:medido (ex-data erro))))
    (is (= 20 (:teto (ex-data erro))))))

;; ---------- query->apresentacao ----------

(deftest formato-e-recorte-default-e-allowlist
  (is (= {:formato :json :recorte :resumo} (ai/query->apresentacao {})) "default")
  (is (= {:formato :csv :recorte :detalhe} (ai/query->apresentacao {:formato "csv" :recorte "detalhe"}))))

(deftest formato-desconhecido-e-invalido-explicito-nunca-o-default-silencioso
  (let [erro (try (ai/query->apresentacao {:formato "xlsx"}) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro))
    (is (= :validacao/invalido (:tipo (ex-data erro))))
    (is (= :formato (:campo (ex-data erro))))))

(deftest recorte-desconhecido-e-invalido-explicito-nunca-o-default-silencioso
  (let [erro (try (ai/query->apresentacao {:recorte "resumido"}) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro))
    (is (= :validacao/invalido (:tipo (ex-data erro))))
    (is (= :recorte (:campo (ex-data erro))))))

;; =====================================================================================================
;; adapters.out — apuracao->wire (JSON) e apuracao-wire->csv (CSV, PURO)
;; =====================================================================================================

(def ^:private s1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private s2 #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private v-ana #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private v-bruno #uuid "00000000-0000-0000-0000-0000000000b2")

(def ^:private totais-reais
  "Os `:totais` que `logic/apurar-assiduidade` DE FATO produz — chamada com o periodo vazio, que e' a forma
  mais barata de LER DA FONTE as duas constantes de prosa (`:criterio-de-inclusao`,
  `:nota-de-metodologia`, ambas privadas em `logic` e ambas MULTI-LINHA, uma delas com `;`).

  A versao anterior deste arquivo redigitava `\"criterio fixo de inclusao\"`/`\"nota fixa de metodologia\"` —
  strings de UMA linha inventadas para o teste — e por isso o cabecalho de comentario do CSV ficou verde
  enquanto, em PRODUCAO, saia despedacado em ~12 linhas fisicas. E' a armadilha `fixture-vocabulario-
  ficticio` registrada no projeto: fixture que REDIGITA o vocabulario em vez de ler do dono."
  (:totais (logic/apurar-assiduidade [] {} {} {} {:sessoes-sem-data-de-referencia 3})))

(def ^:private quorum-neutro
  {:presentes-plenario 1 :presentes-remoto 0 :presentes-total 1 :membros-da-casa 2 :presencas-fora-do-roster 0})

(defn- apuracao-fixture
  "Duas sessoes (uma PUBLICA, uma SECRETA), dois vereadores — a fixture minima que DISTINGUE os ramos que os
  testes abaixo exercitam: sigilo por linha, escape RFC4180, guarda de formula, percentual nil."
  []
  {:sessoes [{:id s1 :numero 1 :tipo "ordinaria" :estado "encerrada"
              :data-de-referencia (LocalDate/of 2026 1 5) :sigilosa false :quorum quorum-neutro}
             {:id s2 :numero 2 :tipo "secreta" :estado "encerrada"
              :data-de-referencia (LocalDate/of 2026 1 12) :sigilosa true :quorum quorum-neutro}]
   :vereadores [{:id v-ana :nome "=HYPERLINK(\"http://evil\";\"clique\")" :nome-parlamentar nil
                 :partido "PDT" :partido-variou false}
                {:id v-bruno :nome "Bruno \"Bru\" Silva" :nome-parlamentar nil :partido nil
                 :partido-variou true}]
   :por-vereador [{:vereador-id v-ana :sessoes-computadas 2 :comparecimentos 1 :ausencias-justificadas 0
                    :ausencias-com-justificativa-pendente 1 :ausencias-injustificadas 0
                    :sessoes-licenciado 0 :percentual 50}
                  {:vereador-id v-bruno :sessoes-computadas 0 :comparecimentos 0
                   :ausencias-justificadas 0 :ausencias-com-justificativa-pendente 0
                   :ausencias-injustificadas 0 :sessoes-licenciado 2 :percentual nil}]
   :detalhe [{:sessao-id s1 :vereador-id v-ana :estado :presente-plenario :sigilosa false}
             {:sessao-id s1 :vereador-id v-bruno :estado :licenciado :sigilosa false}
             {:sessao-id s2 :vereador-id v-ana :estado :ausente-justificativa-pendente :sigilosa true}
             {:sessao-id s2 :vereador-id v-bruno :estado :licenciado :sigilosa true}]
   :totais (assoc totais-reais :sessoes-consideradas 2 :vereadores-considerados 2 :sessoes-sigilosas 1)})

(defn- csv-str [conteudo] (String. ^bytes conteudo "UTF-8"))
(defn- linhas [txt] (str/split txt #"\r\n"))

(deftest apuracao->wire-converte-uuid-localdate-e-keyword-para-string
  (let [w (ao/apuracao->wire (apuracao-fixture))]
    (is (= (str s1) (:id (first (:sessoes w)))))
    (is (= "2026-01-05" (:data-de-referencia (first (:sessoes w)))))
    (is (= (str v-ana) (:id (first (:vereadores w)))))
    (is (= (str s1) (:sessao-id (first (:detalhe w)))))
    (is (string? (:estado (first (:detalhe w)))) "keyword do dominio virou string no wire")
    (is (= "presente-plenario" (:estado (first (:detalhe w)))))))

(deftest apuracao->wire-recusa-drift-nas-CINCO-projecoes-nao-so-em-totais
  ;; Achado da revisao adversarial da Fatia 3: o teste anterior exercitava SO' `:totais` — um dos dois ramos
  ;; que ja' funcionavam. MEDIDO na versao anterior, `:sessoes`, `:vereadores` e `:detalhe` ENGOLIAM o campo
  ;; desconhecido em silencio (200, o campo sumia), porque as projecoes construiam mapa literal e o campo
  ;; morria ANTES de o Malli ve-lo. Este teste cobre os CINCO caminhos.
  (doseq [caminho [[:sessoes 0 :campo-que-nao-existe]
                   [:vereadores 0 :campo-que-nao-existe]
                   [:por-vereador 0 :campo-que-nao-existe]
                   [:detalhe 0 :campo-que-nao-existe]
                   [:totais :campo-que-nao-existe]]]
    (testing (pr-str caminho)
      (let [ruim (assoc-in (apuracao-fixture) caminho "x")]
        (is (thrown? clojure.lang.ExceptionInfo (ao/apuracao->wire ruim))
            "campo desconhecido tem de CHEGAR ao Malli e virar 500 de servidor, nunca sumir em silencio")))))

(deftest apuracao->wire-sem-detalhe-valida-e-recusa-detalhe-presente
  (let [sem (dissoc (apuracao-fixture) :detalhe)
        w   (ao/apuracao->wire sem false)]
    (is (not (contains? w :detalhe)) "o CSV `recorte=resumo` nao consome `:detalhe` — a chave nem existe")
    (is (= 2 (count (:sessoes w))) "o resto do contrato continua validado")
    (is (thrown? clojure.lang.ExceptionInfo (ao/apuracao->wire (apuracao-fixture) false))
        "modelo COM `:detalhe` no modo sem-detalhe e' bug de chamador — nunca dropar a chave em silencio")
    (is (thrown? clojure.lang.ExceptionInfo
                 (ao/apuracao->wire (assoc-in sem [:sessoes 0 :campo-que-nao-existe] "x") false))
        "o `:closed` continua valendo no caminho sem-detalhe")))

;; ---------- CSV: forma geral (BOM, separador, CRLF, cabecalho) ----------

(deftest csv-tem-bom-separador-ponto-e-virgula-e-CRLF
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        b   (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                       :tipos nil :recorte :resumo})
        txt (csv-str b)]
    (is (= 0xFEFF (int (first txt))) "BOM UTF-8 e' o PRIMEIRO caractere")
    (is (str/includes? txt "\r\n") "CRLF presente")
    (is (not (str/includes? (subs txt 0 (str/index-of txt "vereador-id")) ",tipos"))
        "sanidade: nada de virgula-como-separador escapando para o cabecalho de dado")
    (let [linha-cabecalho (first (filter #(str/starts-with? % "vereador-id") (linhas txt)))]
      (is (= (str "vereador-id;nome;nome-parlamentar;partido;partido-variou;sessoes-computadas;comparecimentos;"
                  "ausencias-justificadas;ausencias-com-justificativa-pendente;ausencias-injustificadas;"
                  "sessoes-licenciado;percentual")
             linha-cabecalho)
          "separador `;`, nunca `,`"))))

(defn- normalizado [s] (str/trim (str/replace (str s) #"\s+" " ")))

(deftest csv-cabecalho-de-comentario-se-explica-sozinho
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos ["ordinaria" "secreta"] :recorte :resumo}))]
    (doseq [pedaco ["2026-01-01" "2026-01-31" "ordinaria, secreta"
                    (normalizado (:criterio-de-inclusao totais-reais))
                    (normalizado (:nota-de-metodologia totais-reais))
                    "Sessoes sigilosas no periodo: 1"
                    "Sessoes fechadas sem data de referencia (fora do periodo): 3"]]
      (is (str/includes? txt pedaco) (str "cabecalho tem que conter: " pedaco)))))

(deftest cabecalho-de-comentario-NAO-se-despedaca-com-os-textos-REAIS-de-logic
  ;; O achado dos DOIS revisores da Fatia 3. A premissa vem primeiro (sem ela o teste nao exercita nada):
  (is (str/includes? (:criterio-de-inclusao totais-reais) "\n")
      "premissa: o criterio REAL de `logic` e' MULTI-LINHA")
  (is (str/includes? (:criterio-de-inclusao totais-reais) ";")
      "premissa: o criterio REAL contem `;`, o separador do arquivo")
  (is (str/includes? (:nota-de-metodologia totais-reais) "\n")
      "premissa: a nota de metodologia REAL tambem e' MULTI-LINHA")
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                :tipos nil :recorte :resumo}))
        ;; SPLIT EM QUALQUER QUEBRA (`\r\n`, `\r` OU `\n`), e nao so' no CRLF do formato: e' assim que o
        ;; Excel/`pandas` leem o arquivo, e um split so' por CRLF deixaria os `\n` internos do texto de
        ;; `logic` INVISIVEIS para o teste — que e' precisamente o defeito sob prova.
        ;; `(subs txt 1)`: o BOM (U+FEFF, um caractere) mora colado no `#` da primeira linha
        ls  (str/split (subs txt 1) #"\r\n|\r|\n")
        antes-da-branca (take-while (complement str/blank?) ls)]
    (is (= 7 (count antes-da-branca))
        "SETE linhas de comentario, uma por metadado — nunca as ~12 fisicas que os `\\n` internos produziam")
    (doseq [l antes-da-branca]
      (is (str/starts-with? l "#")
          (str "TODA linha antes da linha em branco comeca com `#` — esta nao: " (pr-str l))))
    (is (some #(= % (str "# Criterio de inclusao: " (normalizado (:criterio-de-inclusao totais-reais)))) ls)
        "o criterio REAL cabe em UMA linha de comentario, inteiro")
    (is (some #(= % (str "# Nota de metodologia: " (normalizado (:nota-de-metodologia totais-reais)))) ls)
        "a nota de metodologia REAL — o texto que avisa que os numeros sao RECALCULADOS — chega LEGIVEL")
    (is (not-any? #(str/starts-with? % "\"") antes-da-branca)
        "comentario NAO e' registro: sai sem quoting RFC4180")))

(deftest csv-percentual-nil-sai-como-campo-vazio-nunca-zero
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos nil :recorte :resumo}))
        linha-bruno (first (filter #(str/starts-with? % (str v-bruno)) (linhas txt)))]
    (is (str/ends-with? linha-bruno ";2;")
        "sessoes-licenciado=2 seguido de percentual VAZIO (nao '0') — visto que termina em ';2;'")))

;; ---------- CSV: a GUARDA DE INJECAO DE FORMULA (obrigatoria) ----------

(deftest guarda-de-formula-prefixa-apostrofo-e-RFC4180-cuida-do-resto
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos nil :recorte :resumo}))]
    (is (str/includes? txt "\"'=HYPERLINK(") "o nome iniciado em `=` sai com apostrofo-guarda ANTES do `=`")
    (is (not (re-find #"(?<!')=HYPERLINK" txt)) "nenhum `=HYPERLINK` cru (sem a guarda) sobrou no arquivo")))

(deftest guarda-de-formula-cobre-os-5-caracteres-perigosos
  (doseq [[nome esperado] [["=SOMA(A1:A2)" "'=SOMA(A1:A2)"]
                           ["+1+1" "'+1+1"]
                           ["-2+3" "'-2+3"]
                           ["@SUM(1)" "'@SUM(1)"]
                           [(str \tab "x") (str "'" \tab "x")]
                           [(str \return "x") (str "'" \return "x")]]]
    (testing (pr-str nome)
      (let [fx  (assoc-in (apuracao-fixture) [:vereadores 0 :nome] nome)
            w   (ao/apuracao->wire fx)
            txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                     :tipos nil :recorte :resumo}))]
        (is (str/includes? txt esperado))))))

(deftest guarda-de-formula-nao-e-contornavel-com-espaco-a-esquerda
  ;; VETOR MEDIDO no fio HTTP real na revisao adversarial: `=HYPERLINK(...)` saia guardado, mas
  ;; `⎵=1+1+cmd|' /C calc'!A0` (UM espaco antes) saia CRU. O conjunto ja' incluia TAB e CR porque o Excel os
  ;; pula antes de avaliar o que vem depois — se o modelo vale para TAB, vale para ESPACO.
  (doseq [nome [" =1+1+cmd|' /C calc'!A0"
                "   =1+1"
                " =1+1"          ;; NBSP — tambem e' espaco em branco
                " +1+1" " -2+3" " @SUM(1)"]]
    (testing (pr-str nome)
      (let [fx  (assoc-in (apuracao-fixture) [:vereadores 0 :nome] nome)
            w   (ao/apuracao->wire fx)
            txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                    :tipos nil :recorte :resumo}))]
        (is (str/includes? txt (str "'" nome))
            "o apostrofo-guarda vem ANTES do espaco, e o texto original sai intacto atras dele")))))

(deftest virgula-no-campo-dispara-o-quote-RFC4180
  ;; O outro vetor MEDIDO: `Ana,=1+1+cmd|' /C calc'!A0` nao comeca em caractere de formula, entao nao ganha
  ;; apostrofo — e, sem aspas, num Excel en-US / Google Sheets / `pandas.read_csv` (onde a VIRGULA e' o
  ;; separador) o campo vira DUAS celulas, a segunda comecando em `=`.
  (let [nome "Ana,=1+1+cmd|' /C calc'!A0"
        fx  (assoc-in (apuracao-fixture) [:vereadores 0 :nome] nome)
        w   (ao/apuracao->wire fx)
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                :tipos nil :recorte :resumo}))]
    (is (str/includes? txt (str "\"" nome "\""))
        "campo com virgula sai ENTRE ASPAS — um campo so' em qualquer dialeto de CSV")
    (is (not (str/includes? txt (str ";" nome ";")))
        "e nunca cru, que e' o que quebrava a guarda pelo lado do delimitador"))
  (let [fx  (assoc-in (apuracao-fixture) [:vereadores 1 :nome] (str "Bruno" \tab "Silva"))
        w   (ao/apuracao->wire fx)
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                :tipos nil :recorte :resumo}))]
    (is (str/includes? txt (str "\"Bruno" \tab "Silva\"")) "TAB interno tambem dispara o quote")))

(deftest numero-negativo-nao-ganha-apostrofo-de-guarda
  ;; Latente hoje (todo contador desta apuracao e' >= 0), mas a guarda nao pode transformar NUMERO em texto:
  ;; `-5` viraria `'-5` numa coluna que o secretario vai somar.
  (let [fx  (assoc-in (apuracao-fixture) [:por-vereador 0 :percentual] -5)
        w   (ao/apuracao->wire fx)
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                :tipos nil :recorte :resumo}))]
    (is (str/includes? txt ";-5"))
    (is (not (str/includes? txt "'-5")))))

(deftest campo-sem-caractere-perigoso-nao-ganha-apostrofo
  (let [fx  (assoc-in (apuracao-fixture) [:vereadores 0 :nome] "Ana Pereira")
        w   (ao/apuracao->wire fx)
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos nil :recorte :resumo}))]
    (is (str/includes? txt ";Ana Pereira;"))
    (is (not (str/includes? txt "'Ana Pereira")))))

;; ---------- CSV: escape RFC4180 ----------

(deftest campo-com-separador-vira-entre-aspas
  (let [fx  (assoc-in (apuracao-fixture) [:vereadores 1 :nome] "Bruno; Silva")
        w   (ao/apuracao->wire fx)
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos nil :recorte :resumo}))]
    (is (str/includes? txt "\"Bruno; Silva\""))))

(deftest campo-com-aspas-internas-duplica-as-aspas
  (let [txt (csv-str (ao/apuracao-wire->csv (ao/apuracao->wire (apuracao-fixture))
                                             {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                              :tipos nil :recorte :resumo}))]
    (is (str/includes? txt "\"Bruno \"\"Bru\"\" Silva\"")
        "aspas internas DUPLICADAS (RFC4180) na linha do Bruno")))

(deftest campo-com-quebra-de-linha-vira-entre-aspas
  (let [fx  (assoc-in (apuracao-fixture) [:vereadores 1 :nome] "Bruno\nSilva")
        w   (ao/apuracao->wire fx)
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos nil :recorte :resumo}))]
    (is (str/includes? txt "\"Bruno\nSilva\""))))

;; ---------- CSV: o MAPEAMENTO COLUNA<->VALOR (achado da revisao adversarial da Fatia 3) ----------
;; O revisor rodou TRES mutacoes contra as duas suites da fatia (35 testes) e as tres ficaram VERDES:
;; (A) `partido-variou` mentindo sempre "nao"; (B) `sessoes-computadas` e `comparecimentos` trocados no corpo
;; em relacao ao cabecalho; (C) `sessao-numero` e `sessao-tipo` trocados. Nenhuma assercao olhava a linha
;; INTEIRA — e num arquivo que sustenta perda de mandato por falta (DL 201), publicar o DENOMINADOR na coluna
;; do numerador inverte a acusacao. Os dois testes abaixo pinam a linha de dado inteira, como string literal.

(defn- linha-que-comeca-em [txt prefixo]
  (first (filter #(str/starts-with? % prefixo) (linhas txt))))

(deftest csv-resumo-pina-a-LINHA-DE-DADO-INTEIRA-coluna-a-coluna
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                :tipos nil :recorte :resumo}))]
    (is (= (str v-ana ";\"'=HYPERLINK(\"\"http://evil\"\";\"\"clique\"\")\";;PDT;nao;2;1;0;1;0;0;50")
           (linha-que-comeca-em txt (str v-ana)))
        "Ana: sessoes-computadas=2 ANTES de comparecimentos=1, na ordem do cabecalho")
    (is (= (str v-bruno ";\"Bruno \"\"Bru\"\" Silva\";;;sim;0;0;0;0;0;2;")
           (linha-que-comeca-em txt (str v-bruno)))
        "Bruno: partido-variou=sim, denominador 0 e percentual VAZIO")))

(deftest csv-partido-variou-sai-nos-DOIS-valores-e-por-vereador-certo
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                :tipos nil :recorte :resumo}))
        campos (fn [prefixo] (str/split (linha-que-comeca-em txt prefixo) #";"))
        col (.indexOf ["vereador-id" "nome" "nome-parlamentar" "partido" "partido-variou"] "partido-variou")]
    ;; a coluna e' localizada pelo NOME no cabecalho, nao por um indice redigitado
    (is (= "partido-variou" (nth (str/split (linha-que-comeca-em txt "vereador-id") #";") col)))
    (is (= "sim" (nth (campos (str v-bruno)) col))
        "Bruno TROCOU de partido no periodo — `sim`, e a coluna `partido` sai vazia")
    (is (= "" (nth (campos (str v-bruno)) (dec col))))
    ;; Ana tem `;` DENTRO do nome (o split acima quebraria a linha dela), entao a asserção do valor `nao`
    ;; vive na linha INTEIRA pinada no teste acima; aqui so' se prova que os dois valores existem no arquivo.
    (is (str/includes? txt ";PDT;nao;") "Ana NAO trocou de partido — `nao` na mesma posicao")))

(deftest csv-detalhe-pina-as-QUATRO-linhas-inteiras
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                :tipos nil :recorte :detalhe}))
        ls  (set (linhas txt))
        ana "\"'=HYPERLINK(\"\"http://evil\"\";\"\"clique\"\")\""
        bruno "\"Bruno \"\"Bru\"\" Silva\""]
    (is (contains? ls (str s1 ";1;ordinaria;2026-01-05;nao;" v-ana ";" ana ";presente-plenario"))
        "sessao-numero=1 e sessao-tipo=ordinaria em ORDEM — nao trocados entre si")
    (is (contains? ls (str s1 ";1;ordinaria;2026-01-05;nao;" v-bruno ";" bruno ";licenciado")))
    (is (contains? ls (str s2 ";2;secreta;2026-01-12;sim;" v-ana ";" ana ";ausente-justificativa-pendente"))
        "a SECRETA: numero 2, tipo secreta, sigilo sim — e o estado PENDENTE nunca colapsado em ausente")
    (is (contains? ls (str s2 ";2;secreta;2026-01-12;sim;" v-bruno ";" bruno ";licenciado")))))

;; ---------- CSV: a COLUNA DE SIGILO no detalhe (achado da revisao da Fatia 2) ----------

(deftest detalhe-carrega-a-coluna-de-sigilo-por-linha
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos nil :recorte :detalhe}))
        ls  (linhas txt)
        linha-cabecalho (first (filter #(str/starts-with? % "sessao-id") ls))
        linhas-s1 (filter #(str/starts-with? % (str s1)) ls)
        linhas-s2 (filter #(str/starts-with? % (str s2)) ls)]
    (is (str/includes? linha-cabecalho "sessao-sigilosa"))
    (is (every? #(str/includes? % ";nao;") linhas-s1) "sessao PUBLICA -> cada linha 'nao'")
    (is (every? #(str/includes? % ";sim;") linhas-s2) "sessao SECRETA -> CADA linha 'sim', nao so' o total")
    (is (not-any? #(str/includes? % ";sim;") linhas-s1))
    (is (not-any? #(str/includes? % ";nao;") linhas-s2))))

(deftest detalhe-nao-carrega-motivo-de-justificativa
  ;; `AssiduidadeDetalheLinhaOut` nunca teve o campo `:motivo` — este teste e' o guard de FONTE que impede
  ;; a regressao de reintroduzi-lo (dado de saude, LGPD).
  (let [w (ao/apuracao->wire (apuracao-fixture))]
    (is (= #{:sessao-id :vereador-id :estado :sigilosa} (set (keys (first (:detalhe w))))))))

(deftest recorte-resumo-junta-nome-e-partido-nas-linhas-mas-JSON-nao-repete
  (let [w (ao/apuracao->wire (apuracao-fixture))]
    (is (not (contains? (first (:por-vereador w)) :nome))
        "JSON minimiza: :por-vereador nao carrega nome (identidade UMA SO VEZ em :vereadores)"))
  (let [txt (csv-str (ao/apuracao-wire->csv (ao/apuracao->wire (apuracao-fixture))
                                             {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                              :tipos nil :recorte :resumo}))]
    (is (str/includes? txt "PDT") "o CSV plano JUNTA o nome/partido na hora de renderizar")))
