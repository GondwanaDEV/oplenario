(ns oplenario.legislativo.logic-test
  "UNITARIO: logic puro do legislativo — sem I/O. Gate eixo H (ADR-0002): coordenada URN/LexML
  computada, slug de municipio, numero de exibicao por template, mapa tipo->lexml. A numeracao crua
  (sequencial gapless) e a imutabilidade sao do banco (db-test); aqui so a montagem pura das strings."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.logic :as logic]))

(deftest municipio-slug-normaliza-lexml
  (testing "sem acento, minusculo, .-separado (ADR-0002 §3)"
    (is (= "fortaleza" (logic/municipio-slug "Fortaleza")))
    (is (= "sao.paulo" (logic/municipio-slug "São Paulo")))
    (is (= "mossoro" (logic/municipio-slug "Mossoró")))
    (is (= "santana.do.cariri" (logic/municipio-slug "Santana do Cariri"))))
  (testing "fail-loud: nome vazio ou so-pontuacao nao vira slug vazio (URN torta imutavel)"
    (is (thrown? Exception (logic/municipio-slug "")))
    (is (thrown? Exception (logic/municipio-slug "   ")))
    (is (thrown? Exception (logic/municipio-slug "---")))))

(deftest tipo->lexml-mapeia-vocabulario
  (testing "vocabulario LexML mapeado do tipo da proposicao (ADR-0002 §3)"
    (is (= "projeto.lei" (logic/tipo->lexml "projeto_lei")))
    (is (= "projeto.lei.complementar" (logic/tipo->lexml "projeto_lei_complementar")))
    (is (= "projeto.resolucao" (logic/tipo->lexml "projeto_resolucao")))
    (is (= "projeto.decreto.legislativo" (logic/tipo->lexml "projeto_decreto_legislativo")))
    (is (= "proposta.emenda.lei.organica" (logic/tipo->lexml "proposta_emenda_lom"))))
  (testing "tipo fora do vocabulario LexML lanca (fail-closed: nao monta URN torta)"
    (is (thrown? Exception (logic/tipo->lexml "tipo_inexistente"))))
  (testing "TODA especie de logic/tipos tem mapeamento LexML (rede contra tipo novo sem mapa)"
    (doseq [t logic/tipos]
      (is (string? (logic/tipo->lexml t)) (str "especie sem mapeamento LexML: " t)))))

(deftest urn-lex-monta-coordenada-canonica
  (testing "formato exato do ADR-0002 §3 (o exemplo canonico de Fortaleza)"
    (is (= "urn:lex:br;ce;fortaleza:camara.municipal;projeto.lei:2026;42"
           (logic/urn-lex {:uf "CE" :municipio-nome "Fortaleza"
                           :tipo "projeto_lei" :ano 2026 :sequencial 42}))))
  (testing "uf minuscula + slug do municipio composto"
    (is (= "urn:lex:br;sp;sao.paulo:camara.municipal;projeto.resolucao:2025;7"
           (logic/urn-lex {:uf "SP" :municipio-nome "São Paulo"
                           :tipo "projeto_resolucao" :ano 2025 :sequencial 7})))))

(deftest numero-exibicao-formata-por-sigla
  (testing "formato humano PL 042/2026 (zero-pad 3, template default por sigla)"
    (is (= "PL 042/2026" (logic/numero-exibicao {:tipo "projeto_lei" :ano 2026 :sequencial 42})))
    (is (= "PDL 001/2026" (logic/numero-exibicao {:tipo "projeto_decreto_legislativo" :ano 2026 :sequencial 1})))
    (is (= "REQ 1234/2026" (logic/numero-exibicao {:tipo "requerimento" :ano 2026 :sequencial 1234}))
        "sequencial >= 1000 nao trunca o pad"))

  (testing "espécies sao um vocabulario fechado conhecido"
    (is (contains? logic/tipos "projeto_lei"))
    (is (contains? logic/tipos "proposta_emenda_lom"))
    (is (not (contains? logic/tipos "lei_promulgada")) "norma promulgada nao e' especie de proposicao (F3.8)")))

;; ---- eixo B: versionamento de texto ----
(deftest decidir-armazenamento-por-bytes
  (testing "threshold 32KB em UTF-8 (nao em chars)"
    (is (= :inline (logic/decidir-armazenamento "texto curto")))
    (is (= :inline (logic/decidir-armazenamento (apply str (repeat 32768 \a)))) "32768 bytes ASCII = limite (inline)")
    (is (= :objeto-store (logic/decidir-armazenamento (apply str (repeat 32769 \a)))) "1 byte acima = objeto-store")
    (is (= :objeto-store (logic/decidir-armazenamento (apply str (repeat 20000 \á))))
        "multibyte: 20000 'á' = 40000 bytes UTF-8 > limite (conta BYTES, nao chars)")))

(deftest vocabularios-eixo-b-fechados
  (is (contains? logic/origens-versao "protocolo"))
  (is (contains? logic/origens-versao "aplicacao_emenda"))
  (is (= 4 (count logic/estados-versao)))
  (is (contains? logic/estados-versao "vigente")))

;; ---- Onda B Slice 2: novos vocabularios ----
(deftest autor-tipos-espelha-o-check-da-migration-0013
  (is (= #{"vereador" "mesa" "comissao" "executivo" "cidadao"} logic/autor-tipos)))

(deftest origens-versao-ganha-edicao-onda-b-slice-2
  (is (contains? logic/origens-versao "edicao")))
