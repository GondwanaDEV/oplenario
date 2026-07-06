(ns oplenario.legislativo.proposicao-adapters-in-test
  "UNIT (puro, sem DB) — a coercao dos query-params de GET /legislativo/proposicoes (Onda B Slice 1). Prova
  os defaults, o fail-closed em valor invalido e a tolerancia a ausencia (filtro nil, nao 400)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.legislativo.adapters.in.proposicao :as adapters]))

(defn- invalido? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

(deftest sem-query-params-usa-defaults
  (let [f (adapters/listar-proposicoes->dominio {})]
    (is (nil? (:busca f))) (is (nil? (:tipo f))) (is (nil? (:estado f)))
    (is (nil? (:autor-id f))) (is (nil? (:ano f)))
    (is (= 1 (:pagina f))) (is (= 20 (:tamanho f)))
    (is (= "atualizado_em" (:ordenar-por f))) (is (= "desc" (:ordenar-dir f)))))

(deftest busca-trimada-e-blank-vira-nil
  (is (= "hortas" (:busca (adapters/listar-proposicoes->dominio {:busca "  hortas  "}))))
  (is (nil? (:busca (adapters/listar-proposicoes->dominio {:busca "   "})))))

(deftest busca-grande-demais-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:busca (apply str (repeat 201 "a"))}))))

(deftest tipo-e-estado-passam-direto
  (let [f (adapters/listar-proposicoes->dominio {:tipo "projeto_lei" :estado "em_comissoes"})]
    (is (= "projeto_lei" (:tipo f))) (is (= "em_comissoes" (:estado f)))))

(deftest autor-id-uuid-valido-coage
  (let [id (random-uuid)
        f (adapters/listar-proposicoes->dominio {:autor-id (str id)})]
    (is (= id (:autor-id f)))))

(deftest autor-id-invalido-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:autor-id "nao-e-uuid"}))))

(deftest ano-invalido-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:ano "vinte-e-vinte-seis"}))))

(deftest ano-valido-coage-inteiro
  (is (= 2026 (:ano (adapters/listar-proposicoes->dominio {:ano "2026"})))))

(deftest tamanho-fora-da-faixa-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:tamanho "0"})))
  (is (invalido? #(adapters/listar-proposicoes->dominio {:tamanho "101"})))
  (is (invalido? #(adapters/listar-proposicoes->dominio {:tamanho "abc"}))))

(deftest tamanho-no-limite-aceita
  (is (= 1 (:tamanho (adapters/listar-proposicoes->dominio {:tamanho "1"}))))
  (is (= 100 (:tamanho (adapters/listar-proposicoes->dominio {:tamanho "100"})))))

(deftest pagina-invalida-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:pagina "0"})))
  (is (invalido? #(adapters/listar-proposicoes->dominio {:pagina "-1"}))))

(deftest ordenar-por-fora-do-allowlist-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:ordenar-por "senha"}))))

(deftest ordenar-dir-fora-do-allowlist-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:ordenar-dir "lateral"}))))

(deftest parametro-repetido-400
  (is (invalido? #(adapters/listar-proposicoes->dominio {:busca ["a" "b"]}))))

;; ---------- Onda B Slice 2: criar/editar (corpo JSON) ----------

(deftest criar-proposicao->dominio-injeta-id-e-created-by
  (let [ator {:identidade-id (random-uuid)}
        m (adapters/criar-proposicao->dominio ator {"tipo" "projeto_lei" "ano" 2026 "ementa" "X"})]
    (is (some? (:id m)))
    (is (= (:identidade-id ator) (:created-by m)))
    (is (= "projeto_lei" (:tipo m)))))

(deftest criar-proposicao->dominio-corpo-invalido-lanca
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalido"
                        (adapters/criar-proposicao->dominio {:identidade-id (random-uuid)} {"tipo" "lixo"}))))

(deftest editar-proposicao->dominio-usa-id-do-path-e-updated-by-do-ator
  (let [ator {:identidade-id (random-uuid)} id (random-uuid)
        m (adapters/editar-proposicao->dominio ator id {"lock-version" 0 "ementa" "Y"})]
    (is (= id (:id m)))
    (is (= (:identidade-id ator) (:updated-by m)))
    (is (= "Y" (:ementa m)))))
