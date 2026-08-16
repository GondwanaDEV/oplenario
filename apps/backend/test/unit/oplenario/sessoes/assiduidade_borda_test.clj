(ns oplenario.sessoes.assiduidade-borda-test
  "UNIT (sem Postgres) — Etapa 6 fatia 3: `adapters.in.assiduidade` (query params -> dominio) e
  `adapters.out.assiduidade` (dominio -> JSON validado + CSV puro). A borda e' onde a Fatia 2 apanhou (brief
  §Fatia 3): data malformada NUNCA vira nil silencioso, `formato`/`recorte` desconhecidos NUNCA caem no
  default em silencio — cada `deftest` abaixo prova a FORMA especifica de erro, nao so' 'lanca alguma coisa'."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.adapters.in.assiduidade :as ai]
            [oplenario.sessoes.adapters.out.assiduidade :as ao])
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
   :totais {:sessoes-consideradas 2 :vereadores-considerados 2 :sessoes-sigilosas 1
            :sessoes-sem-data-de-referencia 3
            :criterio-de-inclusao "criterio fixo de inclusao"
            :nota-de-metodologia "nota fixa de metodologia"}})

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

(deftest apuracao->wire-recusa-payload-fora-do-contrato-AssiduidadeOut
  ;; Prova que a validacao e' REAL, nao decorativa: um campo estranho no `:totais` faz `apuracao->wire`
  ;; ESTOURAR (drift = bug de servidor, nunca resposta malformada).
  (let [ruim (assoc-in (apuracao-fixture) [:totais :campo-que-nao-existe] "x")]
    (is (thrown? clojure.lang.ExceptionInfo (ao/apuracao->wire ruim)))))

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

(deftest csv-cabecalho-de-comentario-se-explica-sozinho
  (let [w   (ao/apuracao->wire (apuracao-fixture))
        txt (csv-str (ao/apuracao-wire->csv w {:de (LocalDate/of 2026 1 1) :ate (LocalDate/of 2026 1 31)
                                                 :tipos ["ordinaria" "secreta"] :recorte :resumo}))]
    (doseq [pedaco ["2026-01-01" "2026-01-31" "ordinaria, secreta" "criterio fixo de inclusao"
                    "nota fixa de metodologia" "Sessoes sigilosas no periodo: 1"
                    "Sessoes fechadas sem data de referencia (fora do periodo): 3"]]
      (is (str/includes? txt pedaco) (str "cabecalho tem que conter: " pedaco)))))

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
