(ns oplenario.demo.acervo-texto-test
  "UNITARIO (funcoes puras, sem I/O — `demo` esta no classpath do alias `:test`, ver deps.edn:36): defeito
  F3/MATA da caminhada pos-fatia — a ficha da materia mostrava `## Lei`/`### Justificativa` CRUS na tela
  (a semente escrevia sintaxe markdown; o visualizador da ficha e' texto puro, nao renderiza). REPROVA
  a implementacao anterior: os helpers de `acervo.clj` que compoem o `:texto` das 24 materias/pareceres
  nao podem devolver nenhuma linha comecando por `#` — se o `## `/`### ` voltar a algum helper, este teste
  falha. Acessa os helpers privados via `#'acervo/fn` (mesmo padrao de teste de fn privada do resto do
  repo — nenhum helper aqui e' publico, e nao ha' razao de produto pra' publica-los so' pro teste)."
  (:require [acervo]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]))

(defn- sem-linha-markdown? [texto]
  (not-any? #(re-find #"^#+\s" %) (string/split-lines texto)))

(deftest helpers-de-texto-nao-emitem-sintaxe-markdown-visivel
  (testing "artigos->texto (lei/lei complementar/etc.) — a origem do defeito: escrevia '## Lei'"
    (let [t (@#'acervo/artigos->texto "Lei" ["Fica instituido X." "Esta Lei entra em vigor."])]
      (is (sem-linha-markdown? t) (str "linha markdown crua em artigos->texto: " (pr-str t)))
      (is (string/starts-with? t "Lei\n\n") "o rotulo continua presente, so' sem o marcador '## '")))
  (testing "texto-indicacao — escrevia '## Indicação' + '### Justificativa'"
    (let [t (@#'acervo/texto-indicacao "pedido X" "justificativa Y")]
      (is (sem-linha-markdown? t) (str "linha markdown crua em texto-indicacao: " (pr-str t)))))
  (testing "texto-requerimento"
    (let [t (@#'acervo/texto-requerimento "pedido X" "justificativa Y")]
      (is (sem-linha-markdown? t) (str "linha markdown crua em texto-requerimento: " (pr-str t)))))
  (testing "texto-requerimento-pesar"
    (let [t (@#'acervo/texto-requerimento-pesar "Fulano" "justificativa Y")]
      (is (sem-linha-markdown? t) (str "linha markdown crua em texto-requerimento-pesar: " (pr-str t)))))
  (testing "texto-mocao — escrevia '## Moção de X' + '### Considerando'"
    (let [t (@#'acervo/texto-mocao "Congratulações" "corpo X" ["motivo 1" "motivo 2"])]
      (is (sem-linha-markdown? t) (str "linha markdown crua em texto-mocao: " (pr-str t)))))
  (testing "texto-parecer — escrevia '## Relatório' + '## Análise'"
    (let [t (@#'acervo/texto-parecer "assunto X")]
      (is (sem-linha-markdown? t) (str "linha markdown crua em texto-parecer: " (pr-str t))))))
